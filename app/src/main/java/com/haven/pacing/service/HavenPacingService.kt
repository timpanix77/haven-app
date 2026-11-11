package com.haven.autonomic

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
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.preference.PreferenceManager
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
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var prefs: SharedPreferences
    private var tts: TextToSpeech? = null
    private var ttsInitialized = false

    private var bluetoothGatt: BluetoothGatt? = null
    private var isConnected = false

    // Telemetry State
    private var currentHr = 0
    private var dailyPeakHr = 0
    private var activeCeilingBpm = DEFAULT_CEILING_BPM
    private var graceBufferDurationMs = DEFAULT_GRACE_BUFFER_MS
    private var dailyBudgetRemaining = BASE_DAILY_BUDGET
    private var morningHrvRmssd = 0.0

    // Excursion Tracking
    private var isExcursionActive = false
    private var excursionStartElapsedMs = 0L
    private var lastExcursionDurationSeconds = 0L
    private var lastTickTimestampMs = 0L
    private var lastSpokenAlertElapsedMs = 0L

    // Posture multiplier (0.20 = Supine, 0.70 = Seated, 1.00 = Upright)
    private var currentPostureMultiplier = 1.00

    inner class LocalBinder : Binder() {
        fun getService(): HavenPacingService = this@HavenPacingService
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        initTextToSpeech()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("Initializing HAVEN Telemetry..."))

        evaluateDailyRollover()
        attemptAutomatedHrvGovernor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            if (it.hasExtra(EXTRA_POSTURE_MULTIPLIER)) {
                currentPostureMultiplier = it.getDoubleExtra(EXTRA_POSTURE_MULTIPLIER, 1.00)
            }
            if (it.hasExtra(EXTRA_DEVICE_ADDRESS)) {
                val address = it.getStringExtra(EXTRA_DEVICE_ADDRESS)
                if (!address.isNullOrEmpty()) {
                    connectToDevice(address)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        serviceScope.cancel()
        disconnectGatt()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    // --- Bluetooth Low Energy Telemetry Ingress ---

    fun connectToDevice(deviceAddress: String) {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) return

        try {
            val device = adapter.getRemoteDevice(deviceAddress)
            bluetoothGatt = device.connectGatt(this, false, gattCallback)
        } catch (e: SecurityException) {
            // Log or handle missing BLUETOOTH_CONNECT permission
        }
    }

    private fun disconnectGatt() {
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
        } catch (e: SecurityException) {
            // Handled
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true
                try {
                    gatt.discoverServices()
                } catch (e: SecurityException) { }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                currentHr = 0
                broadcastTelemetryUpdate()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                enableHeartRateNotifications(gatt)
            }
        }

        @Deprecated("Used for Android 12 and below compatibility")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == UUID_HEART_RATE_MEASUREMENT) {
                parseHeartRatePacket(characteristic)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == UUID_HEART_RATE_MEASUREMENT) {
                parseHeartRatePacket(characteristic, value)
            }
        }
    }

    private fun enableHeartRateNotifications(gatt: BluetoothGatt) {
        val service = gatt.getService(UUID_HEART_RATE_SERVICE) ?: return
        val characteristic = service.getCharacteristic(UUID_HEART_RATE_MEASUREMENT) ?: return

        try {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG)
            descriptor?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(it)
                }
            }
        } catch (e: SecurityException) { }
    }

    private fun parseHeartRatePacket(characteristic: BluetoothGattCharacteristic, rawValue: ByteArray? = null) {
        val flags = if (rawValue != null) rawValue[0].toInt() else characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)
        val is16Bit = (flags and 0x01) != 0

        val heartRate = if (rawValue != null) {
            if (is16Bit) {
                ((rawValue[2].toInt() and 0xFF) shl 8) or (rawValue[1].toInt() and 0xFF)
            } else {
                rawValue[1].toInt() and 0xFF
            }
        } else {
            val format = if (is16Bit) BluetoothGattCharacteristic.FORMAT_UINT16 else BluetoothGattCharacteristic.FORMAT_UINT8
            characteristic.getIntValue(format, 1) ?: return
        }

        processHeartRate(heartRate)
    }

    // --- Mathematical Pacing Engine ---

    @Synchronized
    private fun processHeartRate(hr: Int) {
        evaluateDailyRollover()

        currentHr = hr
        if (hr > dailyPeakHr) {
            dailyPeakHr = hr
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        val deltaSeconds = if (lastTickTimestampMs == 0L) 1.0 else (nowElapsed - lastTickTimestampMs) / 1000.0
        lastTickTimestampMs = nowElapsed

        // Excursion boundary monitoring
        if (hr >= activeCeilingBpm) {
            if (!isExcursionActive) {
                isExcursionActive = true
                excursionStartElapsedMs = nowElapsed
            }
            val excursionDurationMs = nowElapsed - excursionStartElapsedMs
            lastExcursionDurationSeconds = excursionDurationMs / 1000L

            // Only deduct strain points if the 120s (or 60s) grace buffer has lapsed
            if (excursionDurationMs > graceBufferDurationMs) {
                val strainRatePerMinute = calculatePiecewiseStrainRate(hr)
                val burn = (strainRatePerMinute / 60.0) * currentPostureMultiplier * deltaSeconds
                dailyBudgetRemaining -= burn
                persistActiveBudget()

                checkVoiceWarningTrigger(nowElapsed, excursionDurationMs)
            }
        } else {
            if (isExcursionActive) {
                // Recovered below ceiling
                isExcursionActive = false
                lastExcursionDurationSeconds = (nowElapsed - excursionStartElapsedMs) / 1000L
            }
        }

        updateNotification()
        broadcastTelemetryUpdate()
    }

    private fun calculatePiecewiseStrainRate(hr: Int): Double {
        return when {
            hr < 89 -> 0.00
            hr in 89..95 -> 0.12
            hr in 96..105 -> 0.45
            hr in 106..114 -> 1.20
            else -> 2.40 // >= 115 BPM
        }
    }

    // --- Auditory Warning Engine (Stream & Focus Routing) ---

    private fun initTextToSpeech() {
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US

            // USAGE_ASSISTANCE_NAVIGATION_GUIDANCE bypasses notification channel mutes
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            tts?.setAudioAttributes(audioAttributes)
            ttsInitialized = true
        }
    }

    private fun checkVoiceWarningTrigger(nowElapsed: Long, excursionDurationMs: Long) {
        if (!ttsInitialized) return

        // Throttle auditory alerts to once every 45 seconds during an active breached excursion
        if (nowElapsed - lastSpokenAlertElapsedMs > 45_000L) {
            lastSpokenAlertElapsedMs = nowElapsed
            val secondsOver = excursionDurationMs / 1000L
            val spokenMessage = if (dailyBudgetRemaining <= 0.0) {
                "Pace ceiling breached. Budget fully exhausted. Terminate exertion and recline immediately."
            } else {
                "Ceiling breached. Excursion at $secondsOver seconds. Heart rate $currentHr."
            }
            tts?.speak(spokenMessage, TextToSpeech.QUEUE_FLUSH, null, "HAVEN_EXCURSION_ALERT")
        }
    }

    // --- Automated HRV Capacity Governor ---

    private fun attemptAutomatedHrvGovernor() {
        serviceScope.launch {
            try {
                val healthConnectClient = HealthConnectClient.getOrCreate(this@HavenPacingService)
                val now = Instant.now()
                val twelveHoursAgo = now.minus(12, ChronoUnit.HOURS)

                val request = ReadRecordsRequest(
                    recordType = HeartRateVariabilityRmssdRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(twelveHoursAgo, now)
                )

                val response = healthConnectClient.readRecords(request)
                val latestRecord = response.records.maxByOrNull { it.time }

                latestRecord?.let {
                    val rmssd = it.heartRateVariabilityMillis
                    applyAutomatedHrvGovernor(rmssd)
                }
            } catch (e: Exception) {
                // Health Connect not available, permitted, or no overnight record yet
            }
        }
    }

    private fun applyAutomatedHrvGovernor(rmssd: Double) {
        morningHrvRmssd = rmssd
        val ratio = rmssd / BASELINE_HRV_RMSSD

        val dynamicBudget: Float
        val dynamicCeiling: Int
        val dynamicGraceSec: Long

        when {
            ratio >= 1.0 -> {
                // Optimal recovery / Full tank
                dynamicBudget = 20.0f
                dynamicCeiling = 88
                dynamicGraceSec = 120L
            }
            ratio >= 0.80 -> {
                // Caution / Compromised tank
                dynamicBudget = 14.0f
                dynamicCeiling = 84
                dynamicGraceSec = 60L
            }
            else -> {
                // Suppressed / Malaise Lockdown
                dynamicBudget = 10.0f
                dynamicCeiling = 84
                dynamicGraceSec = 60L
            }
        }

        activeCeilingBpm = dynamicCeiling
        graceBufferDurationMs = dynamicGraceSec * 1000L
        dailyBudgetRemaining = dynamicBudget.toDouble()

        prefs.edit()
            .putFloat(KEY_BUDGET_REMAINING, dynamicBudget)
            .putInt(KEY_ACTIVE_CEILING, dynamicCeiling)
            .putLong(KEY_GRACE_SEC, dynamicGraceSec)
            .putFloat(KEY_MORNING_HRV, rmssd.toFloat())
            .apply()

        broadcastTelemetryUpdate()
        updateNotification()
    }

    // --- State Persistence & 04:00 AM Rollover ---

    private fun evaluateDailyRollover() {
        val currentPacingDate = getCurrentPacingDayStamp()
        val savedPacingDate = prefs.getString(KEY_POINTS_DATE, "")

        if (savedPacingDate != currentPacingDate) {
            // 04:00 AM Boundary crossed: wipe accumulators and restore clean baseline
            dailyBudgetRemaining = BASE_DAILY_BUDGET
            dailyPeakHr = currentHr
            lastExcursionDurationSeconds = 0L
            isExcursionActive = false

            prefs.edit()
                .putString(KEY_POINTS_DATE, currentPacingDate)
                .putFloat(KEY_BUDGET_REMAINING, BASE_DAILY_BUDGET.toFloat())
                .apply()

            // Check for new sleep HRV session
            attemptAutomatedHrvGovernor()
        } else {
            // Same day: load persisted budget float
            dailyBudgetRemaining = prefs.getFloat(KEY_BUDGET_REMAINING, BASE_DAILY_BUDGET.toFloat()).toDouble()
            activeCeilingBpm = prefs.getInt(KEY_ACTIVE_CEILING, DEFAULT_CEILING_BPM)
            graceBufferDurationMs = prefs.getLong(KEY_GRACE_SEC, DEFAULT_GRACE_BUFFER_MS / 1000L) * 1000L
            morningHrvRmssd = prefs.getFloat(KEY_MORNING_HRV, 0.0f).toDouble()
        }
    }

    private fun getCurrentPacingDayStamp(): String {
        val cal = Calendar.getInstance()
        // If before 04:00 AM, this time window belongs to the previous day's budget
        if (cal.get(Calendar.HOUR_OF_DAY) < 4) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return sdf.format(cal.time)
    }

    private fun persistActiveBudget() {
        prefs.edit()
            .putFloat(KEY_BUDGET_REMAINING, dailyBudgetRemaining.toFloat())
            .apply()
    }

    // --- UI Synchronization & Foreground Service Notification ---

    private fun broadcastTelemetryUpdate() {
        val intent = Intent(ACTION_TELEMETRY_UPDATE).apply {
            putExtra(EXTRA_CURRENT_HR, currentHr)
            putExtra(EXTRA_PEAK_HR, dailyPeakHr)
            putExtra(EXTRA_BUDGET_REMAINING, dailyBudgetRemaining)
            putExtra(EXTRA_CEILING_BPM, activeCeilingBpm)
            putExtra(EXTRA_LAST_EXCURSION_SEC, lastExcursionDurationSeconds)
            putExtra(EXTRA_IS_EXCURSION_ACTIVE, isExcursionActive)
            putExtra(EXTRA_MORNING_HRV, morningHrvRmssd)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HAVEN Flight Deck Live Telemetry",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors autonomic strain, excursion buffers, and real-time pacing budgets."
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HAVEN AUTONOMIC FLIGHT DECK")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification() {
        val statusText = if (isExcursionActive) {
            "EXCURSION: $currentHr BPM (Ceil: $activeCeilingBpm) | REMAIN: ${String.format(Locale.US, "%.1f", dailyBudgetRemaining)} PTS"
        } else {
            "PULSE: $currentHr BPM | PEAK: $dailyPeakHr | REMAIN: ${String.format(Locale.US, "%.1f", dailyBudgetRemaining)} PTS"
        }
        val notification = buildForegroundNotification(statusText)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "haven_telemetry_channel"
        const val NOTIFICATION_ID = 1042

        // Standard Defaults
        const val BASE_DAILY_BUDGET = 20.0
        const val DEFAULT_CEILING_BPM = 88
        const val DEFAULT_GRACE_BUFFER_MS = 120_000L
        const val BASELINE_HRV_RMSSD = 50.0

        // Storage Keys
        const val KEY_BUDGET_REMAINING = "budget_remaining"
        const val KEY_POINTS_DATE = "points_date_stamp"
        const val KEY_MORNING_HRV = "morning_hrv_rmssd"
        const val KEY_ACTIVE_CEILING = "active_ceiling_bpm"
        const val KEY_GRACE_SEC = "grace_buffer_sec"

        // Broadcast Action & Extras
        const val ACTION_TELEMETRY_UPDATE = "com.haven.autonomic.ACTION_TELEMETRY_UPDATE"
        const val EXTRA_CURRENT_HR = "extra_current_hr"
        const val EXTRA_PEAK_HR = "extra_peak_hr"
        const val EXTRA_BUDGET_REMAINING = "extra_budget_remaining"
        const val EXTRA_CEILING_BPM = "extra_ceiling_bpm"
        const val EXTRA_LAST_EXCURSION_SEC = "extra_last_excursion_sec"
        const val EXTRA_IS_EXCURSION_ACTIVE = "extra_is_excursion_active"
        const val EXTRA_MORNING_HRV = "extra_morning_hrv"

        // Service Inbound Extras
        const val EXTRA_DEVICE_ADDRESS = "extra_device_address"
        const val EXTRA_POSTURE_MULTIPLIER = "extra_posture_multiplier"

        // Bluetooth Standard UUIDs
        val UUID_HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val UUID_HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val UUID_CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
