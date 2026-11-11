package com.haven.pacing.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
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
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Locale
import java.util.UUID

class HavenPacingService : Service(), TextToSpeech.OnInitListener {

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var prefs: SharedPreferences
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var gatt: BluetoothGatt? = null
    private var isConnected = false
    private var isScanning = false

    // Telemetry & Autonomic State
    private var currentHr = 0
    private var dailyPeakHr = 0
    private var activeCeiling = DEFAULT_CEILING_BPM
    private var graceMs = DEFAULT_GRACE_BUFFER_MS
    private var budgetCap = BASE_DAILY_BUDGET
    private var dailyBurn = 0.0
    private var excursionBurn = 0.0
    private var morningHrv = BASELINE_HRV_RMSSD
    private var stability = 75
    private var bleStatus = "BLE: READY"

    // Excursion State Tracking
    private var inExcursion = false
    private var excursionStartMs = 0L
    private var excursionDurationSec = 0L
    private var lastTickMs = 0L
    private var lastAlertMs = 0L

    inner class LocalBinder : Binder() {
        fun getService(): HavenPacingService = this@HavenPacingService
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        tts = TextToSpeech(this, this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("HAVEN Telemetry Initializing..."))

        evaluateRollover()
        fetchMorningHrv()
        startBleAutoConnect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        isServiceRunning = false
        scope.cancel()
        stopBle()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    // --- BLE Ingress & Auto-Discovery ---

    private fun startBleAutoConnect() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        if (!adapter.isEnabled) return

        val savedAddr = prefs.getString(KEY_SAVED_DEVICE_ADDR, null)
        if (!savedAddr.isNullOrEmpty()) {
            connectGatt(savedAddr)
        } else {
            scanBle(adapter)
        }
    }

    private fun scanBle(adapter: BluetoothAdapter) {
        val scanner = adapter.bluetoothLeScanner ?: return
        if (isScanning) return

        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID_HR_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        try {
            isScanning = true
            bleStatus = "BLE: SEARCHING FOR WATCH..."
            broadcastHud()
            scanner.startScan(listOf(filter), settings, scanCallback)
        } catch (_: SecurityException) {
            isScanning = false
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                stopScan()
                connectGatt(device.address)
            }
        }
    }

    private fun stopScan() {
        if (!isScanning) return
        val scanner = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: SecurityException) { }
        isScanning = false
    }

    private fun connectGatt(address: String) {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        try {
            val device = adapter.getRemoteDevice(address)
            gatt = device.connectGatt(this, false, gattCallback)
            prefs.edit().putString(KEY_SAVED_DEVICE_ADDR, address).apply()
            bleStatus = "BLE: CONNECTING..."
            broadcastHud()
        } catch (_: SecurityException) { }
    }

    private fun stopBle() {
        stopScan()
        try {
            gatt?.disconnect()
            gatt?.close()
            gatt = null
        } catch (_: SecurityException) { }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true
                bleStatus = "BLE: CONNECTED"
                broadcastHud()
                try {
                    g.discoverServices()
                } catch (_: SecurityException) { }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                currentHr = 0
                bleStatus = "BLE: DISCONNECTED"
                broadcastHud()
                startBleAutoConnect()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val charac = g.getService(UUID_HR_SERVICE)?.getCharacteristic(UUID_HR_MEASURE) ?: return
                try {
                    g.setCharacteristicNotification(charac, true)
                    val desc = charac.getDescriptor(UUID_CLIENT_CONFIG) ?: return
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(desc)
                    }
                } catch (_: SecurityException) { }
            }
        }

        @Deprecated("Android 12 and below compatibility")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (c.uuid == UUID_HR_MEASURE) {
                val format = if ((c.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0) and 0x01) != 0) {
                    BluetoothGattCharacteristic.FORMAT_UINT16
                } else {
                    BluetoothGattCharacteristic.FORMAT_UINT8
                }
                parseHr(c.getIntValue(format, 1) ?: 0)
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == UUID_HR_MEASURE) {
                val is16 = (value[0].toInt() and 0x01) != 0
                val hr = if (is16) {
                    ((value[2].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)
                } else {
                    value[1].toInt() and 0xFF
                }
                parseHr(hr)
            }
        }
    }

    // --- Pacing Engine & Math Model ---

    @Synchronized
    private fun parseHr(hr: Int) {
        if (hr <= 0) return
        evaluateRollover()

        currentHr = hr
        if (hr > dailyPeakHr) dailyPeakHr = hr

        val now = SystemClock.elapsedRealtime()
        val deltaSec = if (lastTickMs == 0L) 1.0 else (now - lastTickMs) / 1000.0
        lastTickMs = now

        if (hr >= activeCeiling) {
            if (!inExcursion) {
                inExcursion = true
                excursionStartMs = now
                excursionBurn = 0.0
            }
            val durMs = now - excursionStartMs
            excursionDurationSec = durMs / 1000L

            if (durMs > graceMs) {
                val rate = when {
                    hr < 89 -> 0.0
                    hr in 89..95 -> 0.12
                    hr in 96..105 -> 0.45
                    hr in 106..114 -> 1.20
                    else -> 2.40
                }
                val pts = (rate / 60.0) * deltaSec
                dailyBurn += pts
                excursionBurn += pts
                prefs.edit().putFloat(KEY_DAILY_BURN, dailyBurn.toFloat()).apply()

                if (ttsReady && now - lastAlertMs > 45_000L) {
                    lastAlertMs = now
                    val remaining = budgetCap - dailyBurn
                    val msg = if (remaining <= 0.0) {
                        "Budget exhausted. Recline immediately."
                    } else {
                        "Ceiling breached. Heart rate $currentHr."
                    }
                    tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "HAVEN_ALERT")
                }
            }
        } else {
            if (inExcursion) {
                inExcursion = false
                excursionDurationSec = (now - excursionStartMs) / 1000L
                excursionBurn = 0.0
            }
        }

        updateNotification()
        broadcastHud()
        onTelemetryUpdate?.invoke(currentHr, excursionDurationSec.toInt(), (dailyBurn * 60).toInt(), "COCKPIT_LIVE")
    }

    // --- Automated HRV Capacity Governor ---

    private fun fetchMorningHrv() {
        scope.launch {
            try {
                val client = HealthConnectClient.getOrCreate(this@HavenPacingService)
                val now = Instant.now()
                val req = ReadRecordsRequest(
                    recordType = HeartRateVariabilityRmssdRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(now.minus(12, ChronoUnit.HOURS), now)
                )
                val res = client.readRecords(req)
                res.records.maxByOrNull { it.time }?.let { record ->
                    applyHrv(record.heartRateVariabilityMillis)
                }
            } catch (_: Exception) { }
        }
    }

    private fun applyHrv(rmssd: Double) {
        morningHrv = rmssd
        when {
            rmssd >= 50.0 -> {
                budgetCap = 20.0
                activeCeiling = 88
                graceMs = 120_000L
                stability = 85
            }
            rmssd >= 40.0 -> {
                budgetCap = 14.0
                activeCeiling = 84
                graceMs = 60_000L
                stability = 65
            }
            else -> {
                budgetCap = 10.0
                activeCeiling = 84
                graceMs = 60_000L
                stability = 35
            }
        }

        prefs.edit()
            .putFloat(KEY_BUDGET_CAP, budgetCap.toFloat())
            .putInt(KEY_ACTIVE_CEILING, activeCeiling)
            .putLong(KEY_GRACE_MS, graceMs)
            .putFloat(KEY_MORNING_HRV, rmssd.toFloat())
            .putInt(KEY_STABILITY, stability)
            .apply()

        broadcastHud()
        updateNotification()
    }

    private fun evaluateRollover() {
        val cal = Calendar.getInstance()
        if (cal.get(Calendar.HOUR_OF_DAY) < 4) cal.add(Calendar.DAY_OF_YEAR, -1)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        val savedDate = prefs.getString(KEY_POINTS_DATE, "")

        if (savedDate != today) {
            dailyBurn = 0.0
            dailyPeakHr = currentHr
            excursionDurationSec = 0L
            inExcursion = false
            prefs.edit().putString(KEY_POINTS_DATE, today).putFloat(KEY_DAILY_BURN, 0f).apply()
            fetchMorningHrv()
        } else {
            dailyBurn = prefs.getFloat(KEY_DAILY_BURN, 0f).toDouble()
            budgetCap = prefs.getFloat(KEY_BUDGET_CAP, BASE_DAILY_BUDGET.toFloat()).toDouble()
            activeCeiling = prefs.getInt(KEY_ACTIVE_CEILING, DEFAULT_CEILING_BPM)
            graceMs = prefs.getLong(KEY_GRACE_MS, DEFAULT_GRACE_BUFFER_MS)
            morningHrv = prefs.getFloat(KEY_MORNING_HRV, BASELINE_HRV_RMSSD.toFloat()).toDouble()
            stability = prefs.getInt(KEY_STABILITY, 75)
        }
    }

    // --- Audio, Notification & HUD Sync ---

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            ttsReady = true
        }
    }

    private fun broadcastHud() {
        val intent = Intent(ACTION_HUD_UPDATE).apply {
            putExtra(EXTRA_HR, currentHr)
            putExtra(EXTRA_EXCURSION_SEC, if (inExcursion) excursionDurationSec.toInt() else 0)
            putExtra(EXTRA_PEAK_HR, dailyPeakHr)
            putExtra(EXTRA_DAILY_POINTS, dailyBurn)
            putExtra(EXTRA_BUDGET_POINTS, budgetCap)
            putExtra(EXTRA_EXCURSION_POINTS, excursionBurn)
            putExtra(EXTRA_STABILITY_SCORE, stability)
            putExtra(EXTRA_ACTIVE_CEILING, activeCeiling)
            putExtra(EXTRA_MODE, "COCKPIT_LIVE")
            putExtra(EXTRA_BLE_STATUS, bleStatus)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(CHANNEL_ID, "HAVEN Telemetry", NotificationManager.IMPORTANCE_LOW)
            chan.setShowBadge(false)
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.createNotificationChannel(chan)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HAVEN FLIGHT DECK")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val rem = budgetCap - dailyBurn
        val txt = if (inExcursion) {
            "EXCURSION: $currentHr BPM | REMAIN: ${String.format(Locale.US, "%.1f", rem)} PTS"
        } else {
            "PULSE: $currentHr BPM | PEAK: $dailyPeakHr | REMAIN: ${String.format(Locale.US, "%.1f", rem)} PTS"
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.notify(NOTIFICATION_ID, buildNotification(txt))
    }

    companion object {
        const val CHANNEL_ID = "haven_telemetry"
        const val NOTIFICATION_ID = 1042
        const val PREFS_NAME = "haven_pacing_prefs"

        var isServiceRunning = false
        var onTelemetryUpdate: ((hr: Int, excSec: Int, totalSec: Int, mode: String) -> Unit)? = null

        const val BASE_DAILY_BUDGET = 20.0
        const val DEFAULT_CEILING_BPM = 88
        const val DEFAULT_GRACE_BUFFER_MS = 120_000L
        const val BASELINE_HRV_RMSSD = 50.0

        const val KEY_DAILY_BURN = "daily_burned"
        const val KEY_BUDGET_CAP = "budget_cap"
        const val KEY_POINTS_DATE = "points_date"
        const val KEY_MORNING_HRV = "morning_hrv"
        const val KEY_ACTIVE_CEILING = "active_ceiling"
        const val KEY_GRACE_MS = "grace_ms"
        const val KEY_STABILITY = "stability"
        const val KEY_SAVED_DEVICE_ADDR = "saved_device_addr"

        const val ACTION_HUD_UPDATE = "com.haven.pacing.ACTION_HUD_UPDATE"
        const val EXTRA_HR = "extra_hr"
        const val EXTRA_EXCURSION_SEC = "extra_excursion_sec"
        const val EXTRA_PEAK_HR = "extra_peak_hr"
        const val EXTRA_DAILY_POINTS = "extra_daily_points"
        const val EXTRA_BUDGET_POINTS = "extra_budget_points"
        const val EXTRA_EXCURSION_POINTS = "extra_excursion_points"
        const val EXTRA_STABILITY_SCORE = "extra_stability_score"
        const val EXTRA_ACTIVE_CEILING = "extra_active_ceiling"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_BLE_STATUS = "extra_ble_status"

        val UUID_HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val UUID_HR_MEASURE: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val UUID_CLIENT_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
