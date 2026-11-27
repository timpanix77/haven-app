package com.haven.pacing.service

import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.media.*
import android.os.*
import android.speech.tts.*
import androidx.core.app.NotificationCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.haven.pacing.data.ExcursionRecord
import com.haven.pacing.data.HavenDatabase
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

class HavenPacingService : Service(), TextToSpeech.OnInitListener {

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var prefs: SharedPreferences
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var gatt: BluetoothGatt? = null
    private var isConnected = false
    private var isScanning = false

    // Real-Time Autonomic State
    private var currentHr = 0
    private var dailyPeakHr = 0
    private var activeCeiling = DEFAULT_CEILING_BPM
    private var graceMs = DEFAULT_GRACE_BUFFER_MS
    private var budgetCap = BASE_DAILY_BUDGET
    private var postureMultiplier = POSTURE_RECLINED // Safe default 0.20x until set upright/seated
    private var dailyBurn = 0.0
    private var excursionBurn = 0.0
    private var morningHrv = BASELINE_HRV_RMSSD
    private var stability = 75
    private var bleStatus = "BLE: READY"

    // 3-Sample Rolling Window to Reject Optical Wrist Artifacts
    private val hrWindow = ArrayDeque<Int>(3)

    // Excursion & Sensor Watchdog State
    private var inExcursion = false
    private var excursionStartMs = 0L
    private var excursionWallStartMs = 0L
    private var excursionPeakHr = 0
    private var excursionDurationSec = 0L
    private var lastTickMs = 0L
    private var lastAlertMs = 0L

    private var lastPacketElapsedMs = 0L
    private var lastChangedBpm = 0
    private var lastBpmChangeElapsedMs = 0L

    inner class LocalBinder : Binder() {
        fun getService(): HavenPacingService = this@HavenPacingService
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        initTts()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("HAVEN Telemetry Initializing..."))

        evaluateRollover()
        fetchMorningHrv()
        startTelemetryWatchdog()
        startBleAutoConnect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_TTS -> {
                tts?.stop()
                abandonDucking()
                return START_STICKY
            }
            ACTION_SET_POSTURE -> {
                val mult = intent.getDoubleExtra(EXTRA_POSTURE_MULT, POSTURE_RECLINED)
                postureMultiplier = mult.coerceIn(0.20, 1.00)
                prefs.edit().putFloat(KEY_POSTURE_MULT, postureMultiplier.toFloat()).apply()
                updateNotification()
                broadcastHud()
                return START_STICKY
            }
            ACTION_TEST_ALERT -> {
                speakAlert("HAVEN voice alert check. Audio routing active.")
                return START_STICKY
            }
        }
        evaluateRollover()
        broadcastHud()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        isServiceRunning = false
        scope.cancel()
        stopBle()
        abandonDucking()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    // --- 1-Second Watchdog: Catches Silent BLE Dropouts & Frozen HR Sensors ---

    private fun startTelemetryWatchdog() {
        scope.launch {
            while (isActive) {
                delay(1_000L)
                checkSignalFreshness()
            }
        }
    }

    @Synchronized
    private fun checkSignalFreshness() {
        if (currentHr <= 0) return
        val now = SystemClock.elapsedRealtime()
        val packetAgeMs = now - lastPacketElapsedMs
        val frozenAgeMs = now - lastBpmChangeElapsedMs

        val bleTimedOut = lastPacketElapsedMs > 0L && packetAgeMs > BLE_PACKET_TIMEOUT_MS
        val sensorFrozen = lastBpmChangeElapsedMs > 0L && frozenAgeMs > FROZEN_HR_TIMEOUT_MS

        if (bleTimedOut || sensorFrozen) {
            closeActiveExcursion(now, currentHr)
            currentHr = 0
            hrWindow.clear()
            lastTickMs = 0L
            lastChangedBpm = 0
            bleStatus = if (sensorFrozen) {
                "BLE: SENSOR FROZEN (5m TIMEOUT)"
            } else {
                "BLE: SIGNAL LOST (15s TIMEOUT)"
            }
            updateNotification()
            broadcastHud()
            if (bleTimedOut && !isScanning) {
                startBleAutoConnect()
            }
        }
    }

    // --- Bulletproof 3-Way Alert Router (VOX + Alarm-Stream Fallback / VIBRATE / SILENCE) ---

    private fun initTts() {
        ttsReady = false
        tts?.shutdown()
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val langResult = tts?.setLanguage(Locale.UK) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
            }

            // Route through USAGE_ALARM so media-volume mute or Doze never swallows pacing warnings
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { abandonDucking() }
                override fun onError(utteranceId: String?) {
                    abandonDucking()
                    playFallbackTone()
                }
            })
            ttsReady = true
        } else {
            ttsReady = false
        }
    }

    private fun requestDucking() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attr)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { }
                .build()
            audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun abandonDucking() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun triggerAlertVibration() {
        val pattern = longArrayOf(0L, 220L, 120L, 220L, 120L, 260L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        }
    }

    private fun playFallbackTone() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_ALARM, 90)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 450)
            scope.launch {
                delay(600L)
                tg.release()
            }
        } catch (_: Exception) { }
    }

    private fun speakAlert(message: String) {
        val mode = prefs.getString(KEY_ALERT_MODE, ALERT_MODE_VOX) ?: ALERT_MODE_VOX
        when (mode) {
            ALERT_MODE_SILENCE -> {
                tts?.stop()
                abandonDucking()
            }
            ALERT_MODE_VIBRATE -> {
                tts?.stop()
                abandonDucking()
                triggerAlertVibration()
            }
            else -> {
                triggerAlertVibration()
                if (!ttsReady || tts == null) {
                    playFallbackTone()
                    initTts()
                    return
                }
                requestDucking()
                val params = Bundle().apply {
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "HAVEN_ALERT")
                }
                val result = tts?.speak(message, TextToSpeech.QUEUE_FLUSH, params, "HAVEN_ALERT")
                if (result == TextToSpeech.ERROR) {
                    abandonDucking()
                    playFallbackTone()
                    initTts()
                }
            }
        }
    }

    // --- BLE Telemetry Ingress & Auto-Discovery ---

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
        try { scanner?.stopScan(scanCallback) } catch (_: SecurityException) { }
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
                try { g.discoverServices() } catch (_: SecurityException) { }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                closeActiveExcursion(SystemClock.elapsedRealtime(), 0)
                currentHr = 0
                hrWindow.clear()
                lastTickMs = 0L
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

        @Deprecated("Android 12 compatibility")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (c.uuid == UUID_HR_MEASURE) {
                val flags = c.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0) ?: 0
                val contactSupported = (flags and 0x04) != 0
                val sensorInContact = (flags and 0x02) != 0
                val format = if ((flags and 0x01) != 0) {
                    BluetoothGattCharacteristic.FORMAT_UINT16
                } else {
                    BluetoothGattCharacteristic.FORMAT_UINT8
                }
                val hr = c.getIntValue(format, 1) ?: 0
                parseHr(hr, contactSupported && !sensorInContact)
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == UUID_HR_MEASURE && value.size >= 2) {
                val flags = value[0].toInt() and 0xFF
                val contactSupported = (flags and 0x04) != 0
                val sensorInContact = (flags and 0x02) != 0
                val is16 = (flags and 0x01) != 0
                val hr = if (is16 && value.size >= 3) {
                    ((value[2].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)
                } else {
                    value[1].toInt() and 0xFF
                }
                parseHr(hr, contactSupported && !sensorInContact)
            }
        }
    }

    // --- 3-Sample Median Optical Filter ---

    private fun smoothHr(rawHr: Int): Int {
        if (hrWindow.size >= 3) hrWindow.removeFirst()
        hrWindow.addLast(rawHr)
        if (hrWindow.size < 3) return rawHr
        val sorted = hrWindow.sorted()
        return sorted[1]
    }

    // --- Pacing Engine & Math Model ---

    @Synchronized
    private fun parseHr(rawHr: Int, isOffWrist: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        evaluateRollover()

        // Reject off-wrist optical charger noise or invalid/implausible packets
        if (isOffWrist || rawHr !in 35..220) {
            closeActiveExcursion(now, 0)
            currentHr = 0
            hrWindow.clear()
            lastTickMs = 0L
            lastChangedBpm = 0
            bleStatus = if (isOffWrist) "BLE: OFF-WRIST (STANDBY)" else "BLE: WAITING FOR PULSE"
            updateNotification()
            broadcastHud()
            return
        }

        lastPacketElapsedMs = now
        if (rawHr != lastChangedBpm) {
            lastChangedBpm = rawHr
            lastBpmChangeElapsedMs = now
        } else if (lastBpmChangeElapsedMs > 0L && (now - lastBpmChangeElapsedMs) > FROZEN_HR_TIMEOUT_MS) {
            closeActiveExcursion(now, 0)
            currentHr = 0
            hrWindow.clear()
            lastTickMs = 0L
            bleStatus = "BLE: SENSOR FROZEN (5m TIMEOUT)"
            updateNotification()
            broadcastHud()
            return
        }

        val hr = smoothHr(rawHr)
        bleStatus = "BLE: CONNECTED"
        currentHr = hr

        if (hr > dailyPeakHr) {
            dailyPeakHr = hr
            prefs.edit().putInt(KEY_DAILY_PEAK_HR, dailyPeakHr).apply()
        }

        // Never backfill strain across disconnect/charging gaps (> 5 seconds)
        val rawDeltaSec = if (lastTickMs == 0L) 1.0 else (now - lastTickMs) / 1000.0
        val deltaSec = rawDeltaSec.coerceIn(0.0, MAX_PACKET_DELTA_SEC)
        lastTickMs = now

        // Enforce ironclad >= 89 BPM strain floor so sub-ceiling drift cannot pre-arm grace buffer
        val effectiveCeiling = activeCeiling.coerceAtLeast(DEFAULT_CEILING_BPM + 1)

        if (hr >= effectiveCeiling) {
            if (!inExcursion) {
                inExcursion = true
                excursionStartMs = now
                excursionWallStartMs = System.currentTimeMillis()
                excursionPeakHr = hr
                excursionBurn = 0.0
            } else if (hr > excursionPeakHr) {
                excursionPeakHr = hr
            }

            val durMs = now - excursionStartMs
            excursionDurationSec = durMs / 1000L
            prefs.edit().putLong(KEY_LAST_EXCURSION_SEC, excursionDurationSec).apply()

            if (durMs > graceMs) {
                val baseRate = when {
                    hr < 89 -> 0.0
                    hr in 89..95 -> 0.12
                    hr in 96..105 -> 0.45
                    hr in 106..114 -> 1.20
                    else -> 2.40
                }
                val pts = (baseRate * postureMultiplier / 60.0) * deltaSec
                dailyBurn += pts
                excursionBurn += pts
                prefs.edit().putFloat(KEY_DAILY_BURN, dailyBurn.toFloat()).apply()

                if (now - lastAlertMs > 45_000L) {
                    lastAlertMs = now
                    val remaining = (budgetCap - dailyBurn).coerceAtLeast(0.0)
                    val msg = if (remaining <= 0.0) {
                        "Budget exhausted. Recline immediately."
                    } else {
                        "Heart rate $currentHr. Remaining ${String.format(Locale.US, "%.1f", remaining)} points."
                    }
                    speakAlert(msg)
                }
            }
        } else {
            closeActiveExcursion(now, hr)
        }

        updateNotification()
        broadcastHud()
    }

    private fun closeActiveExcursion(nowElapsedMs: Long, settledHr: Int) {
        if (!inExcursion) return
        inExcursion = false
        val finalDurSec = ((nowElapsedMs - excursionStartMs) / 1000L).toInt().coerceAtLeast(1)
        val finalPeak = excursionPeakHr.coerceAtLeast(settledHr)
        val finalBurn = Math.round(excursionBurn * 100.0) / 100.0
        val wallStart = if (excursionWallStartMs > 0L) excursionWallStartMs else System.currentTimeMillis()

        excursionDurationSec = finalDurSec.toLong()
        excursionBurn = 0.0
        excursionPeakHr = 0
        prefs.edit().putLong(KEY_LAST_EXCURSION_SEC, excursionDurationSec).apply()

        if (finalDurSec >= 5 && finalBurn > 0.0) {
            val record = ExcursionRecord(
                timestamp = wallStart,
                durationSeconds = finalDurSec,
                peakHr = finalPeak,
                settledHr = settledHr,
                strainPoints = finalBurn
            )
            scope.launch(Dispatchers.IO) {
                try {
                    HavenDatabase.getDatabase(applicationContext).havenDao().insert(record)
                } catch (_: Exception) { }
            }
        }
    }

    // --- Automated HRV Capacity Governor (With Safe Floor Guardrails) ---

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
        // Ignore corrupt/zero Health Connect readings
        if (rmssd <= 5.0 || rmssd > 250.0) return
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
                activeCeiling = 88
                graceMs = 120_000L
                stability = 65
            }
            else -> {
                // Mild malaise floor = 12.0 PTS; never drop ceiling below 88 BPM or grace below 120s
                budgetCap = 12.0
                activeCeiling = 88
                graceMs = 120_000L
                stability = 45
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
            excursionBurn = 0.0
            inExcursion = false
            // Reset to safe baseline at 04:00 AM rollover before checking Health Connect
            budgetCap = BASE_DAILY_BUDGET
            activeCeiling = DEFAULT_CEILING_BPM
            graceMs = DEFAULT_GRACE_BUFFER_MS
            prefs.edit()
                .putString(KEY_POINTS_DATE, today)
                .putFloat(KEY_DAILY_BURN, 0f)
                .putFloat(KEY_BUDGET_CAP, BASE_DAILY_BUDGET.toFloat())
                .putInt(KEY_ACTIVE_CEILING, DEFAULT_CEILING_BPM)
                .putLong(KEY_GRACE_MS, DEFAULT_GRACE_BUFFER_MS)
                .putInt(KEY_DAILY_PEAK_HR, currentHr)
                .putLong(KEY_LAST_EXCURSION_SEC, 0L)
                .apply()
            fetchMorningHrv()
        } else {
            dailyBurn = prefs.getFloat(KEY_DAILY_BURN, 0f).toDouble()
            budgetCap = prefs.getFloat(KEY_BUDGET_CAP, BASE_DAILY_BUDGET.toFloat()).toDouble()
            activeCeiling = prefs.getInt(KEY_ACTIVE_CEILING, DEFAULT_CEILING_BPM)
            graceMs = prefs.getLong(KEY_GRACE_MS, DEFAULT_GRACE_BUFFER_MS)
            postureMultiplier = prefs.getFloat(KEY_POSTURE_MULT, POSTURE_RECLINED.toFloat()).toDouble()
            morningHrv = prefs.getFloat(KEY_MORNING_HRV, BASELINE_HRV_RMSSD.toFloat()).toDouble()
            stability = prefs.getInt(KEY_STABILITY, 75)
            val savedPeak = prefs.getInt(KEY_DAILY_PEAK_HR, 0)
            if (savedPeak > dailyPeakHr) dailyPeakHr = savedPeak
            if (!inExcursion) {
                excursionDurationSec = prefs.getLong(KEY_LAST_EXCURSION_SEC, 0L)
            }
        }
    }

    // --- Audio, HUD & Notification Sync ---

    private fun broadcastHud() {
        val intent = Intent(ACTION_HUD_UPDATE).apply {
            putExtra(EXTRA_HR, currentHr)
            putExtra(EXTRA_EXCURSION_SEC, excursionDurationSec.toInt())
            putExtra(EXTRA_IN_EXCURSION, inExcursion)
            putExtra(EXTRA_GRACE_SEC, (graceMs / 1000L).toInt())
            putExtra(EXTRA_PEAK_HR, dailyPeakHr)
            putExtra(EXTRA_DAILY_POINTS, dailyBurn)
            putExtra(EXTRA_BUDGET_POINTS, budgetCap)
            putExtra(EXTRA_EXCURSION_POINTS, excursionBurn)
            putExtra(EXTRA_STABILITY_SCORE, stability)
            putExtra(EXTRA_ACTIVE_CEILING, activeCeiling)
            putExtra(EXTRA_POSTURE_MULT, postureMultiplier)
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
        val rem = (budgetCap - dailyBurn).coerceAtLeast(0.0)
        val txt = when {
            currentHr <= 0 -> "$bleStatus | REMAIN: ${String.format(Locale.US, "%.1f", rem)} PTS"
            inExcursion -> "EXCURSION: $currentHr BPM | REMAIN: ${String.format(Locale.US, "%.1f", rem)} PTS"
            else -> "PULSE: $currentHr BPM | PEAK: $dailyPeakHr | REMAIN: ${String.format(Locale.US, "%.1f", rem)} PTS"
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.notify(NOTIFICATION_ID, buildNotification(txt))
    }

    companion object {
        const val CHANNEL_ID = "haven_telemetry"
        const val NOTIFICATION_ID = 1042
        const val PREFS_NAME = "haven_pacing_prefs"

        var isServiceRunning = false

        const val BASE_DAILY_BUDGET = 20.0
        const val DEFAULT_CEILING_BPM = 88
        const val DEFAULT_GRACE_BUFFER_MS = 120_000L
        const val BASELINE_HRV_RMSSD = 50.0

        const val POSTURE_RECLINED = 0.20
        const val POSTURE_SEATED = 0.70
        const val POSTURE_UPRIGHT = 1.00

        const val BLE_PACKET_TIMEOUT_MS = 15_000L
        const val FROZEN_HR_TIMEOUT_MS = 300_000L // 5 minutes
        const val MAX_PACKET_DELTA_SEC = 5.0

        const val ALERT_MODE_VOX = "VOX"
        const val ALERT_MODE_VIBRATE = "VIBRATE"
        const val ALERT_MODE_SILENCE = "SILENCE"
        const val KEY_ALERT_MODE = "alert_mode"
        const val ACTION_STOP_TTS = "com.haven.pacing.ACTION_STOP_TTS"
        const val ACTION_SET_POSTURE = "com.haven.pacing.ACTION_SET_POSTURE"
        const val ACTION_TEST_ALERT = "com.haven.pacing.ACTION_TEST_ALERT"

        const val KEY_DAILY_BURN = "daily_burned"
        const val KEY_BUDGET_CAP = "budget_cap"
        const val KEY_POINTS_DATE = "points_date"
        const val KEY_MORNING_HRV = "morning_hrv"
        const val KEY_ACTIVE_CEILING = "active_ceiling"
        const val KEY_GRACE_MS = "grace_ms"
        const val KEY_STABILITY = "stability"
        const val KEY_POSTURE_MULT = "posture_mult"
        const val KEY_SAVED_DEVICE_ADDR = "saved_device_addr"
        const val KEY_DAILY_PEAK_HR = "daily_peak_hr"
        const val KEY_LAST_EXCURSION_SEC = "last_excursion_sec"

        const val ACTION_HUD_UPDATE = "com.haven.pacing.ACTION_HUD_UPDATE"
        const val EXTRA_HR = "extra_hr"
        const val EXTRA_EXCURSION_SEC = "extra_excursion_sec"
        const val EXTRA_IN_EXCURSION = "extra_in_excursion"
        const val EXTRA_GRACE_SEC = "extra_grace_sec"
        const val EXTRA_PEAK_HR = "extra_peak_hr"
        const val EXTRA_DAILY_POINTS = "extra_daily_points"
        const val EXTRA_BUDGET_POINTS = "extra_budget_points"
        const val EXTRA_EXCURSION_POINTS = "extra_excursion_points"
        const val EXTRA_STABILITY_SCORE = "extra_stability_score"
        const val EXTRA_ACTIVE_CEILING = "extra_active_ceiling"
        const val EXTRA_POSTURE_MULT = "extra_posture_mult"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_BLE_STATUS = "extra_ble_status"

        val UUID_HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val UUID_HR_MEASURE: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val UUID_CLIENT_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
