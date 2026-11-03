package com.haven.pacing.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import com.haven.pacing.MainActivity
import com.haven.pacing.audio.HavenAudioEngine
import com.haven.pacing.ble.HavenBleManager
import com.haven.pacing.data.ExcursionRecord
import com.haven.pacing.data.HavenDatabase
import com.haven.pacing.engine.AutonomicStateMachine
import com.haven.pacing.engine.CockpitMode
import com.haven.pacing.engine.PemPredictiveModel
import com.haven.pacing.engine.TelemetryEvent
import kotlinx.coroutines.*
import java.time.LocalDate

class HavenPacingService : Service() {
    private val NOTIF_CHANNEL_ID = "HAVEN_TELEMETRY_FOREGROUND"
    private val NOTIF_ID = 8801
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var bleManager: HavenBleManager
    private lateinit var audioEngine: HavenAudioEngine
    private lateinit var stateMachine: AutonomicStateMachine
    private lateinit var database: HavenDatabase

    private val tickerHandler = Handler(Looper.getMainLooper())
    private var excursionStartTime: Long = 0L

    override fun onCreate() {
        super.onCreate()
        database = HavenDatabase.getInstance(this)
        audioEngine = HavenAudioEngine(this)

        // Indefinite PARTIAL_WAKE_LOCK to prevent Samsung One UI Doze sleep
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HAVEN::PersistentTelemetry")
        wakeLock?.acquire()

        createNotificationChannel()
        val notif = buildNotification("HAVEN Telemetry Armed", "Awaiting live sensor link")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        stateMachine = AutonomicStateMachine(
            activeCeiling = 88,
            recoveryFloor = 75,
            onEvent = { event -> handleTelemetryEvent(event) }
        )

        bleManager = HavenBleManager(
            context = this,
            onHeartRateReceived = { hr ->
                stateMachine.updateHeartRate(hr)
                updateNotification("HR: $hr BPM", "State: ${stateMachine.mode.name}")
            },
            onConnectionStatusChanged = { connected, status ->
                updateNotification("BLE: $status", if (connected) "Active Monitoring" else "Reconnecting...")
            }
        )

        bleManager.startConnect()
        tickerHandler.post(tickerRunnable)
    }

    private val tickerRunnable = object : Runnable {
        override fun run() {
            stateMachine.onTick()
            tickerHandler.postDelayed(this, 1000)
        }
    }

    private fun handleTelemetryEvent(event: TelemetryEvent) {
        when (event) {
            is TelemetryEvent.Breach -> {
                excursionStartTime = System.currentTimeMillis()
                audioEngine.speak("${event.hr}")
            }
            is TelemetryEvent.CadenceUpdate -> {
                val minutes = event.excursionSec / 60
                val seconds = event.excursionSec % 60
                val timeStr = if (minutes == 0) "$seconds" else "$minutes $seconds"
                if (event.debtSec > 0) {
                    audioEngine.speak("${event.hr}. $timeStr. Debt ${event.debtSec} seconds.")
                } else {
                    audioEngine.speak("${event.hr}. $timeStr.")
                }
            }
            is TelemetryEvent.Settled -> {
                val endTime = System.currentTimeMillis()
                val budgetAlert = if (event.budgetSpent) "Budget spent." else ""
                audioEngine.speak("Settled at ${event.hr}. Excursion ${event.excursionSec} seconds. $budgetAlert")

                // Persist to Room SQLite
                serviceScope.launch {
                    val record = ExcursionRecord(
                        startTime = excursionStartTime,
                        endTime = endTime,
                        durationSeconds = event.excursionSec,
                        peakHr = stateMachine.excursionPeakHr,
                        settledHr = event.hr,
                        exertionPoints = stateMachine.currentExcursionStrain
                    )
                    database.havenDao().insertExcursion(record)
                }
            }
            is TelemetryEvent.ModeChanged -> {
                // Silently change HUD state without startle audio
                updateNotification("Mode: ${event.newMode.name}", "HR: ${stateMachine.currentHr} BPM")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "HAVEN Telemetry Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "24/7 Autonomic Pacing Foreground Engine"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(title, text))
    }

    override fun onDestroy() {
        tickerHandler.removeCallbacksAndMessages(null)
        bleManager.disconnect()
        audioEngine.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
