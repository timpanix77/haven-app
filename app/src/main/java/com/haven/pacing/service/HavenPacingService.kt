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
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.haven.pacing.MainActivity
import com.haven.pacing.R
import com.haven.pacing.data.ExcursionRecord
import com.haven.pacing.data.HavenDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import java.util.UUID

class HavenPacingService : Service(), TextToSpeech.OnInitListener {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tickerRunnable: Runnable? = null

    // Pacing Thresholds & State
    private var currentHr: Int = 0
    private var floorHr: Int = 75
    private var activeCeiling: Int = 88
    private var stabilityScore: Int = 75
    private var serviceLatchedPeakHr: Int = 0

    // Full Weighted PacePoint Engine (Visible / Workwell Model)
    private var dailyBudgetPoints: Double = 20.0
    private var dailyTotalPoints: Double = 0.0
    private var currentExcursionPoints: Double = 0.0
    private var excursionDurationSec: Int = 0
    private var isExcursionActive: Boolean = false
    private var excursionPeakHr: Int = 0

    private var bleStatusMessage: String = "BLE: INITIALIZING"
    private var lastRolloverDay: Int = -1

    companion object {
        const val CHANNEL_ID = "haven_pacing_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_HUD_UPDATE = "com.haven.pacing.ACTION_HUD_UPDATE"
        const val EXTRA_HR = "extra_hr"
        const val EXTRA_EXCURSION_SEC = "extra_excursion_sec"
        const val EXTRA_PEAK_HR = "extra_peak_hr"
        const val EXTRA_DAILY_POINTS = "extra_daily_points"
        const val EXTRA_DAILY_TOTAL_SEC = "extra_daily_total_sec"
        const val EXTRA_BUDGET_POINTS = "extra_budget_points"
        const val EXTRA_BUDGET_SEC = "extra_budget_sec"
        const val EXTRA_EXCURSION_POINTS = "extra_excursion_points"
        const val EXTRA_STABILITY_SCORE = "extra_stability_score"
        const val EXTRA_ACTIVE_CEILING = "extra_active_ceiling"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_BLE_STATUS = "extra_ble_status"

        var isServiceRunning: Boolean = false
        var onTelemetryUpdate: ((Int, Int, Int, String) -> Unit)? = null

        val UUID_HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val UUID_HR_CHARACTERISTIC: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val UUID_CLIENT_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        tts = TextToSpeech(this, this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Engine Active - Scanning for Garmin..."))

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        lastRolloverDay = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)

        startBleScan()
        startEngineTicker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            ttsReady = true
        }
    }

    private fun calculatePacePointsPerSecond(hr: Int, ceiling: Int): Double {
        if (hr <= ceiling) return 0.0
        return when {
            hr <= 95 -> 0.12 / 60.0   // 0.12 pts/min: Light Orthostatic
            hr <= 105 -> 0.45 / 60.0  // 0.45 pts/min: Moderate Active
            hr <= 114 -> 1.20 / 60.0  // 1.20 pts/min: Anaerobic Exertion
            else -> 2.40 / 60.0       // 2.40 pts/min: Severe Surge (115+ BPM)
        }
    }

    private fun startEngineTicker() {
        tickerRunnable = object : Runnable {
            override fun run() {
                check0400Rollover()

                if (currentHr > 0) {
                    val pointsRate = calculatePacePointsPerSecond(currentHr, activeCeiling)
                    dailyTotalPoints += pointsRate

                    if (currentHr > serviceLatchedPeakHr) {
                        serviceLatchedPeakHr = currentHr
                    }

                    if (currentHr >= activeCeiling) {
                        if (!isExcursionActive) {
                            isExcursionActive = true
                            excursionDurationSec = 0
                            currentExcursionPoints = 0.0
                            excursionPeakHr = currentHr
                        }
                        excursionDurationSec++
                        currentExcursionPoints += pointsRate
                        if (currentHr > excursionPeakHr) {
                            excursionPeakHr = currentHr
                        }
                    } else if (currentHr <= floorHr && isExcursionActive) {
                        finalizeExcursion(excursionDurationSec, excursionPeakHr, currentHr, currentExcursionPoints)
                        isExcursionActive = false
                        excursionDurationSec = 0
                        currentExcursionPoints = 0.0
                        excursionPeakHr = 0
                    } else if (isExcursionActive) {
                        excursionDurationSec++
                        currentExcursionPoints += pointsRate
                    }
                }

                broadcastTelemetry()
                updateNotification()
                mainHandler.postDelayed(this, 1000)
            }
        }
        mainHandler.post(tickerRunnable!!)
    }

    private fun finalizeExcursion(duration: Int, peak: Int, settled: Int, points: Double) {
        serviceScope.launch {
            try {
                val db = HavenDatabase.getDatabase(this@HavenPacingService)
                val record = ExcursionRecord(
                    timestamp = System.currentTimeMillis(),
                    durationSeconds = duration,
                    peakHr = peak,
                    settledHr = settled,
                    strainPoints = points
                )
                db.havenDao().insert(record)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun check0400Rollover() {
        val now = Calendar.getInstance()
        val currentDay = now.get(Calendar.DAY_OF_YEAR)
        val hour = now.get(Calendar.HOUR_OF_DAY)

        if (hour >= 4 && currentDay != lastRolloverDay) {
            lastRolloverDay = currentDay
            dailyTotalPoints = 0.0
            serviceLatchedPeakHr = 0
        }
    }

    private fun broadcastTelemetry() {
        val intent = Intent(ACTION_HUD_UPDATE).apply {
            putExtra(EXTRA_HR, currentHr)
            putExtra(EXTRA_EXCURSION_SEC, excursionDurationSec)
            putExtra(EXTRA_PEAK_HR, serviceLatchedPeakHr)
            putExtra(EXTRA_DAILY_POINTS, dailyTotalPoints)
            putExtra(EXTRA_DAILY_TOTAL_SEC, (dailyTotalPoints * 60).toInt())
            putExtra(EXTRA_BUDGET_POINTS, dailyBudgetPoints)
            putExtra(EXTRA_BUDGET_SEC, (dailyBudgetPoints * 60).toInt())
            putExtra(EXTRA_EXCURSION_POINTS, currentExcursionPoints)
            putExtra(EXTRA_STABILITY_SCORE, stabilityScore)
            putExtra(EXTRA_ACTIVE_CEILING, activeCeiling)
            putExtra(EXTRA_MODE, "COCKPIT_LIVE")
            putExtra(EXTRA_BLE_STATUS, bleStatusMessage)
        }
        sendBroadcast(intent)
        onTelemetryUpdate?.invoke(currentHr, excursionDurationSec, (dailyTotalPoints * 60).toInt(), "COCKPIT_LIVE")
    }

    private fun startBleScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            bleStatusMessage = "BLE: BLUETOOTH OFF"
            return
        }
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            bleStatusMessage = "BLE: SCANNER UNAVAILABLE"
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(UUID_HR_SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleStatusMessage = "BLE: SEARCHING FOR GARMIN..."
        isScanning = true
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
        } catch (e: SecurityException) {
            bleStatusMessage = "BLE: PERMISSION MISSING"
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                if (isScanning) {
                    try {
                        bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
                    } catch (e: SecurityException) {}
                    isScanning = false
                }
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            bleStatusMessage = "BLE: SCAN FAILED ($errorCode)"
            isScanning = false
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        bleStatusMessage = "BLE: CONNECTING..."
        try {
            bluetoothGatt = device.connectGatt(this, true, gattCallback)
        } catch (e: SecurityException) {
            bleStatusMessage = "BLE: SECURITY ERROR"
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                bleStatusMessage = "BLE: CONNECTED - DISCOVERING"
                try {
                    gatt?.discoverServices()
                } catch (e: SecurityException) {}
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                bleStatusMessage = "BLE: SEARCHING..."
                currentHr = 0
                gatt?.close()
                bluetoothGatt = null
                startBleScan()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt?.getService(UUID_HR_SERVICE)
                val characteristic = service?.getCharacteristic(UUID_HR_CHARACTERISTIC)
                if (characteristic != null) {
                    try {
                        gatt.setCharacteristicNotification(characteristic, true)
                        val descriptor = characteristic.getDescriptor(UUID_CLIENT_CONFIG)
                        if (descriptor != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            } else {
                                @Suppress("DEPRECATION")
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                gatt.writeDescriptor(descriptor)
                            }
                            bleStatusMessage = "BLE: LOCKED"
                        }
                    } catch (e: SecurityException) {}
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            parseHeartRate(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            @Suppress("DEPRECATION")
            characteristic?.value?.let { parseHeartRate(it) }
        }
    }

    private fun parseHeartRate(data: ByteArray) {
        if (data.isEmpty()) return
        val flag = data[0].toInt()
        val format16 = (flag and 0x01) != 0
        val hr = if (format16 && data.size >= 3) {
            (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        } else if (data.size >= 2) {
            data[1].toInt() and 0xFF
        } else {
            0
        }
        if (hr > 0) {
            currentHr = hr
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HAVEN Pacing Telemetry",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live Autonomic Pacing Engine Status"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HAVEN Flight Deck Active")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        val remaining = dailyBudgetPoints - dailyTotalPoints
        val statusText = if (currentHr > 0) {
            String.format("HR: %d BPM | Budget: %.1f / %.0f PTS", currentHr, dailyTotalPoints, dailyBudgetPoints)
        } else {
            bleStatusMessage
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    override fun onDestroy() {
        isServiceRunning = false
        tickerRunnable?.let { mainHandler.removeCallbacks(it) }
        serviceScope.cancel()
        try {
            bluetoothGatt?.close()
            bluetoothGatt = null
        } catch (e: SecurityException) {}
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
