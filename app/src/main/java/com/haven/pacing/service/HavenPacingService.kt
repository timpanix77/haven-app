package com.haven.pacing.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.haven.pacing.MainActivity
import com.haven.pacing.R
import com.haven.pacing.audio.AudioEngine
import com.haven.pacing.data.ExcursionRecord
import com.haven.pacing.data.HavenDatabase
import com.haven.pacing.engine.AutonomicStateMachine
import com.haven.pacing.engine.CockpitMode
import com.haven.pacing.engine.TelemetryEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class HavenPacingService : Service() {

    companion object {
        const val CHANNEL_ID = "haven_autonomic_channel"
        const val NOTIFICATION_ID = 8801

        const val ACTION_HUD_UPDATE = "com.haven.pacing.ACTION_HUD_UPDATE"
        const val EXTRA_HR = "extra_hr"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_EXCURSION_SEC = "extra_excursion_sec"
        const val EXTRA_PEAK_HR = "extra_peak_hr"
        const val EXTRA_DAILY_TOTAL_SEC = "extra_daily_total_sec"
        const val EXTRA_BUDGET_SEC = "extra_budget_sec"
        const val EXTRA_STABILITY_SCORE = "extra_stability_score"
        const val EXTRA_ACTIVE_CEILING = "extra_active_ceiling"
        const val EXTRA_RECOVERY_FLOOR = "extra_recovery_floor"

        val HEART_RATE_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT_CHAR_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var audioEngine: AudioEngine
    private lateinit var vaultDatabase: HavenDatabase
    private lateinit var stateMachine: AutonomicStateMachine

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null

    private val tickerHandler = Handler(Looper.getMainLooper())
    private val tickerRunnable = object : Runnable {
        override fun run() {
            stateMachine.onTick()
            broadcastHudUpdate()
            updateForegroundNotification()
            tickerHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting telemetry..."))

        audioEngine = AudioEngine(this)
        vaultDatabase = HavenDatabase.getDatabase(this)

        stateMachine = AutonomicStateMachine(
            activeCeiling = 88,
            recoveryFloor = 75,
            budgetSec = 1200
        ) { event ->
            handleTelemetryEvent(event)
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        tickerHandler.post(tickerRunnable)
        connectGarminBle()
        evaluateMorningStability()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleTelemetryEvent(event: TelemetryEvent) {
        when (event) {
            is TelemetryEvent.Breach -> {
                audioEngine.speak("${event.hr}")
                broadcastHudUpdate()
            }
            is TelemetryEvent.CadenceUpdate -> {
                val minutes = event.excursionSec / 60
                audioEngine.speak("${event.hr}. $minutes minutes.")
                broadcastHudUpdate()
            }
            is TelemetryEvent.Settled -> {
                // 1. Decoupled Persistence: Log every excursion to SQLite silently
                serviceScope.launch(Dispatchers.IO) {
                    val record = ExcursionRecord(
                        timestamp = System.currentTimeMillis(),
                        durationSeconds = event.excursionSec,
                        peakHr = event.peakHr,
                        settledHr = event.hr,
                        strainPoints = event.strain
                    )
                    vaultDatabase.havenDao().insert(record)
                }

                // 2. Settlement Voice: Speak ONLY if the 2-minute buffer was breached
                if (event.voiceAlerted) {
                    audioEngine.speak("Settled at ${event.hr}. Excursion ${event.excursionSec} seconds.")
                }
                broadcastHudUpdate()
            }
            is TelemetryEvent.ModeChanged -> {
                broadcastHudUpdate()
            }
            is TelemetryEvent.DayRollover -> {
                evaluateMorningStability()
            }
        }
    }

    private fun evaluateMorningStability() {
        serviceScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val threeDaysAgo = now - (72 * 3600 * 1000)
            val recentExcursions = vaultDatabase.havenDao().getExcursionsSince(threeDaysAgo)

            var totalWeightedStrain = 0.0
            for (rec in recentExcursions) {
                val ageHours = (now - rec.timestamp) / (3600 * 1000.0)
                val weight = when {
                    ageHours <= 24 -> 1.0
                    ageHours <= 48 -> 1.6
                    else -> 2.2
                }
                totalWeightedStrain += (rec.strainPoints * weight)
            }

            // Calculate 0-100 Autonomic Stability Index
            val calculatedScore = (100 - (totalWeightedStrain * 0.05).toInt()).coerceIn(10, 100)

            val (targetCeiling, targetBudgetMin) = when {
                calculatedScore >= 85 -> Pair(90, 25)
                calculatedScore >= 70 -> Pair(88, 20)
                calculatedScore >= 50 -> Pair(84, 15)
                calculatedScore >= 30 -> Pair(80, 10)
                else -> Pair(76, 5)
            }

            stateMachine.applyDynamicCalibration(calculatedScore, targetCeiling, targetBudgetMin)

            withContext(Dispatchers.Main) {
                audioEngine.speak("Stability $calculatedScore of 100. Ceiling $targetCeiling. Budget $targetBudgetMin minutes.")
                broadcastHudUpdate()
                updateForegroundNotification()
            }
        }
    }

    private fun connectGarminBle() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return

        val pairedDevices: Set<BluetoothDevice> = adapter.bondedDevices
        val venuDevice = pairedDevices.firstOrNull { 
            it.name?.contains("Venu", ignoreCase = true) == true || 
            it.name?.contains("Garmin", ignoreCase = true) == true 
        } ?: return

        bluetoothGatt = venuDevice.connectGatt(this, true, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                refreshGattCache(gatt)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gatt.close()
                bluetoothGatt = null
                tickerHandler.postDelayed({ connectGarminBle() }, 5000)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(HEART_RATE_SERVICE_UUID)
                val characteristic = service?.getCharacteristic(HEART_RATE_MEASUREMENT_CHAR_UUID)
                if (characteristic != null) {
                    gatt.setCharacteristicNotification(characteristic, true)
                    val descriptor = characteristic.getDescriptor(CCCD_UUID)
                    if (descriptor != null) {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == HEART_RATE_MEASUREMENT_CHAR_UUID) {
                val data = characteristic.value ?: return
                val flag = data[0].toInt()
                val hr = if ((flag and 0x01) != 0) {
                    ((data[2].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                } else {
                    data[1].toInt() and 0xFF
                }
                stateMachine.updateHeartRate(hr)
            }
        }
    }

    private fun refreshGattCache(gatt: BluetoothGatt) {
        try {
            val refreshMethod = gatt.javaClass.getMethod("refresh")
            refreshMethod.invoke(gatt)
        } catch (_: Exception) {}
    }

    private fun broadcastHudUpdate() {
        val intent = Intent(ACTION_HUD_UPDATE).apply {
            putExtra(EXTRA_HR, stateMachine.currentHr)
            putExtra(EXTRA_MODE, stateMachine.mode.name)
            putExtra(EXTRA_EXCURSION_SEC, stateMachine.excursionSeconds)
            putExtra(EXTRA_PEAK_HR, stateMachine.excursionPeakHr)
            putExtra(EXTRA_DAILY_TOTAL_SEC, stateMachine.dailyTotalSeconds)
            putExtra(EXTRA_BUDGET_SEC, stateMachine.budgetSec)
            putExtra(EXTRA_STABILITY_SCORE, stateMachine.stabilityScore)
            putExtra(EXTRA_ACTIVE_CEILING, stateMachine.activeCeiling)
            putExtra(EXTRA_RECOVERY_FLOOR, stateMachine.recoveryFloor)
        }
        sendBroadcast(intent)
    }

    private fun updateForegroundNotification() {
        val remainingSec = maxOf(0, stateMachine.budgetSec - stateMachine.dailyTotalSeconds)
        val remainingMin = remainingSec / 60
        val remainingSecPart = remainingSec % 60
        val text = "HR: ${stateMachine.currentHr} BPM | Remaining: %02d:%02d".format(remainingMin, remainingSecPart)

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HAVEN Autonomic Engine")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HAVEN Pacing Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tickerHandler.removeCallbacks(tickerRunnable)
        serviceJob.cancel()
        bluetoothGatt?.close()
        bluetoothGatt = null
        audioEngine.shutdown()
    }
}
