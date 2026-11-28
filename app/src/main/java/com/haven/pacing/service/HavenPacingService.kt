package com.haven.pacing.service

import android.annotation.SuppressLint
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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.haven.pacing.MainActivity
import com.haven.pacing.data.ExcursionRecord
import com.haven.pacing.data.HavenDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

class HavenPacingService : Service(), TextToSpeech.OnInitListener, SensorEventListener {

    companion object {
        private const val TAG = "HavenPacingService"
        private const val CHANNEL_ID = "haven_pacing_channel"
        private const val NOTIFICATION_ID = 1001

        // SharedPreferences & Constants expected by MainActivity
        const val PREFS_NAME = "haven_pacing_prefs"
        const val KEY_POSTURE_MULT = "posture_mult"
        const val KEY_ALERT_MODE = "alert_mode"
        const val KEY_DAILY_POINTS = "daily_points"
        const val KEY_ACTIVE_DAY = "active_day_key"
        const val KEY_MANUAL_POSTURE_OVERRIDE = "manual_posture_override"

        const val POSTURE_RECLINED = 0.20
        const val POSTURE_SEATED = 0.70
        const val POSTURE_UPRIGHT = 1.00

        const val ALERT_MODE_VOX = "VOX"
        const val ALERT_MODE_VIBRATE = "VIBRATE"
        const val ALERT_MODE_SILENCE = "SILENCE"

        // Service Actions expected by MainActivity
        const val ACTION_SET_POSTURE = "com.haven.pacing.ACTION_SET_POSTURE"
        const val ACTION_STOP_TTS = "com.haven.pacing.ACTION_STOP_TTS"
        const val ACTION_TEST_ALERT = "com.haven.pacing.ACTION_TEST_ALERT"

        // HUD Broadcast & Extras expected by MainActivity
        const val ACTION_HUD_UPDATE = "com.haven.pacing.ACTION_HUD_UPDATE"
        const val EXTRA_HR = "extra_hr"
        const val EXTRA_EXCURSION_SEC = "extra_excursion_sec"
        const val EXTRA_IN_EXCURSION = "extra_in_excursion"
        const val EXTRA_PEAK_HR = "extra_peak_hr"
        const val EXTRA_DAILY_POINTS = "extra_daily_points"
        const val EXTRA_BUDGET_POINTS = "extra_budget_points"
        const val EXTRA_EXCURSION_POINTS = "extra_excursion_points"
        const val EXTRA_STABILITY_SCORE = "extra_stability_score"
        const val EXTRA_ACTIVE_CEILING = "extra_active_ceiling"
        const val EXTRA_GRACE_SEC = "extra_grace_sec"
        const val EXTRA_POSTURE_MULT = "extra_posture_mult"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_BLE_STATUS = "extra_ble_status"

        // Standard Bluetooth Heart Rate Service & Characteristics
        val HR_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT_CHAR_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Pacing & Biometric Thresholds
        const val DEFAULT_CEILING_BPM = 89
        const val RECOVERY_CLEAR_BPM = 82
        const val RECOVERY_SETTLE_SECONDS = 30L
        const val ANAEROBIC_EMERGENCY_BPM = 115
        const val DEFAULT_GRACE_SEC = 120
        const val VOICE_REPEAT_COOLDOWN_MS = 90_000L

        const val BUDGET_FLOOR_PTS = 12.0
        const val BUDGET_DEFAULT_PTS = 16.0
        const val BUDGET_MAX_PTS = 20.0

        @Volatile
        var isServiceRunning: Boolean = false
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Bluetooth LE
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private var reconnectJob: Job? = null
    private var rolloverWatchdogJob: Job? = null
    private var bleStatusText: String = "BLE: SCANNING FOR VENU..."

    // Telemetry & Excursion State
    private val hrWindow = ArrayDeque<Int>(3)
    private var currentFilteredHr: Int = 0
    private var lastHrTickMs: Long = 0L

    private var inExcursion: Boolean = false
    private var excursionStartMs: Long = 0L
    private var excursionElapsedSec: Int = 0
    private var excursionPeakHr: Int = 0
    private var excursionPoints: Double = 0.0
    private var lastVoiceAlertMs: Long = 0L

    // Below-threshold recovery state
    private var awaitingRecoveryClear: Boolean = false
    private var belowThresholdSinceMs: Long = 0L

    // Budget & Governor State
    private var activeDayKey: String = ""
    private var dailyPoints: Double = 0.0
    private var budgetPoints: Double = BUDGET_DEFAULT_PTS
    private var activeCeilingBpm: Int = DEFAULT_CEILING_BPM
    private var stabilityScore: Int = 75
    private var activePostureMult: Double = POSTURE_RECLINED
    private var hasManualPostureOverride: Boolean = false

    // TTS Audio
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // Sensors & Autonomous Posture Inference
    private lateinit var sensorManager: SensorManager
    private var gravitySensor: Sensor? = null
    private var stepDetectorSensor: Sensor? = null

    private var gravX = 0f
    private var gravY = 0f
    private var gravZ = 9.81f
    private var lastGravityMag = 9.81f
    private var motionVarianceEma = 0f
    private var lastStepTimestampMs = 0L
    private var lastScreenInteractiveMs = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT || intent?.action == Intent.ACTION_SCREEN_ON) {
                lastScreenInteractiveMs = System.currentTimeMillis()
                checkDayRollover()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createNotificationChannel()

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter

        tts = TextToSpeech(this, this)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)

        activeDayKey = currentPacingDayKey()
        loadPersistedState()
        startRolloverWatchdog()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        checkDayRollover()

        when (intent?.action) {
            ACTION_SET_POSTURE -> {
                val requested = intent.getDoubleExtra(EXTRA_POSTURE_MULT, POSTURE_RECLINED)
                activePostureMult = requested
                hasManualPostureOverride = true
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putFloat(KEY_POSTURE_MULT, activePostureMult.toFloat())
                    .putBoolean(KEY_MANUAL_POSTURE_OVERRIDE, true)
                    .apply()
                broadcastHudUpdate()
            }
            ACTION_STOP_TTS -> {
                try {
                    tts?.stop()
                } catch (_: Exception) { }
            }
            ACTION_TEST_ALERT -> {
                val sampleHr = if (currentFilteredHr > 0) currentFilteredHr else 79
                dispatchAlert(sampleHr, forceVoice = true)
            }
            else -> {
                registerSensors()
                serviceScope.launch {
                    refreshDailyGovernorFromHealthConnect()
                }
                startBleScan()
                refreshNotificationAndHud()
            }
        }

        return START_STICKY
    }

    private fun ensureForeground() {
        val notification = buildForegroundNotification("HAVEN Cockpit Live • Armed")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // -------------------------------------------------------------------------
    // 1. AUTONOMOUS ZERO-INPUT POSTURE ENGINE
    // -------------------------------------------------------------------------

    private fun registerSensors() {
        gravitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        stepDetectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun unregisterSensors() {
        try {
            sensorManager.unregisterListener(this)
        } catch (_: Exception) { }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                gravX = event.values[0]
                gravY = event.values[1]
                gravZ = event.values[2]
                val mag = sqrt(gravX * gravX + gravY * gravY + gravZ * gravZ)
                val delta = abs(mag - lastGravityMag)
                lastGravityMag = mag
                motionVarianceEma = (0.85f * motionVarianceEma) + (0.15f * delta)
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                lastStepTimestampMs = System.currentTimeMillis()
                if (hasManualPostureOverride) {
                    hasManualPostureOverride = false
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                        .putBoolean(KEY_MANUAL_POSTURE_OVERRIDE, false)
                        .apply()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun inferAutonomousPostureMultiplier(hr: Int): Double {
        if (hasManualPostureOverride) {
            return activePostureMult
        }

        val now = System.currentTimeMillis()
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        val isStationary = motionVarianceEma < 0.03f
        val recentSteps = (now - lastStepTimestampMs) < 20_000L
        val mediaOrScreenActive = audioManager.isMusicActive ||
            powerManager.isInteractive ||
            (now - lastScreenInteractiveMs) < 15 * 60_000L

        if (isStationary) {
            if (hr >= activeCeilingBpm) {
                return POSTURE_UPRIGHT
            }
            if (mediaOrScreenActive) {
                return if (hr >= 96) POSTURE_UPRIGHT else POSTURE_SEATED
            }
            return POSTURE_RECLINED
        }

        val mag = sqrt(gravX * gravX + gravY * gravY + gravZ * gravZ).coerceAtLeast(0.1f)
        val normY = abs(gravY) / mag
        val normZ = abs(gravZ) / mag

        return when {
            recentSteps -> POSTURE_UPRIGHT
            normZ > 0.85f && hr < activeCeilingBpm -> POSTURE_RECLINED
            normY > 0.78f -> POSTURE_SEATED
            hr >= activeCeilingBpm -> POSTURE_UPRIGHT
            else -> POSTURE_SEATED
        }
    }

    // -------------------------------------------------------------------------
    // 2. HR INGESTION, 3-SAMPLE MEDIAN FILTER, STRAIN & RECOVERY CLEAR
    // -------------------------------------------------------------------------

    private fun onRawHeartRateReceived(rawBpm: Int) {
        if (rawBpm !in 30..220) return

        if (hrWindow.size == 3) hrWindow.removeFirst()
        hrWindow.addLast(rawBpm)

        val filteredBpm = when (hrWindow.size) {
            3 -> hrWindow.sorted()[1]
            2 -> hrWindow.average().toInt()
            else -> rawBpm
        }
        currentFilteredHr = filteredBpm

        val now = System.currentTimeMillis()
        val dtSec = if (lastHrTickMs > 0L) {
            ((now - lastHrTickMs) / 1000.0).coerceIn(0.5, 5.0)
        } else {
            1.0
        }
        lastHrTickMs = now

        checkDayRollover()

        val inferredMult = inferAutonomousPostureMultiplier(filteredBpm)
        if (abs(inferredMult - activePostureMult) > 0.01) {
            activePostureMult = inferredMult
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_POSTURE_MULT, activePostureMult.toFloat())
                .apply()
        }

        if (filteredBpm >= activeCeilingBpm) {
            belowThresholdSinceMs = 0L

            if (!inExcursion) {
                inExcursion = true
                excursionStartMs = now
                excursionElapsedSec = 0
                excursionPeakHr = filteredBpm
                excursionPoints = 0.0
                lastVoiceAlertMs = 0L
            }

            excursionElapsedSec = ((now - excursionStartMs) / 1000L).toInt()
            excursionPeakHr = max(excursionPeakHr, filteredBpm)

            val bpmAboveFloor = (filteredBpm - 65).coerceAtLeast(1)
            val rawPtsPerMin = (bpmAboveFloor / 25.0) * 1.25
            val stepPts = rawPtsPerMin * (dtSec / 60.0) * activePostureMult

            excursionPoints += stepPts
            dailyPoints += stepPts
            persistDailyPoints()

            val graceExpired = excursionElapsedSec >= DEFAULT_GRACE_SEC
            val emergencySpike = filteredBpm >= ANAEROBIC_EMERGENCY_BPM
            if ((graceExpired || emergencySpike) && (now - lastVoiceAlertMs >= VOICE_REPEAT_COOLDOWN_MS)) {
                lastVoiceAlertMs = now
                awaitingRecoveryClear = true
                dispatchAlert(filteredBpm)
            }
        } else {
            if (inExcursion) {
                closeAndPersistExcursion(settledHr = filteredBpm)
                inExcursion = false
                excursionElapsedSec = 0
            }

            if (awaitingRecoveryClear) {
                if (belowThresholdSinceMs == 0L) {
                    belowThresholdSinceMs = now
                }
                val settledSeconds = (now - belowThresholdSinceMs) / 1000L
                val settledCleanly = filteredBpm <= RECOVERY_CLEAR_BPM ||
                    settledSeconds >= RECOVERY_SETTLE_SECONDS

                if (settledCleanly) {
                    awaitingRecoveryClear = false
                    belowThresholdSinceMs = 0L
                    dispatchAlert(filteredBpm)
                }
            }
        }

        refreshNotificationAndHud()
    }

    private fun closeAndPersistExcursion(settledHr: Int) {
        if (!inExcursion) return
        val durationSec = ((System.currentTimeMillis() - excursionStartMs) / 1000L).toInt().coerceAtLeast(1)
        val record = ExcursionRecord(
            timestamp = excursionStartMs,
            durationSeconds = durationSec,
            peakHr = excursionPeakHr,
            settledHr = settledHr,
            strainPoints = (excursionPoints * 100.0).roundToInt() / 100.0
        )
        serviceScope.launch(Dispatchers.IO) {
            try {
                HavenDatabase.getDatabase(this@HavenPacingService).havenDao().insert(record)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert ExcursionRecord: ${e.message}")
            }
        }
        excursionStartMs = 0L
        excursionPeakHr = 0
        excursionPoints = 0.0
    }

    // -------------------------------------------------------------------------
    // 3. BRITISH FEMALE TELEMETRY VOICE ("Heartrate. [BPM].") & BT AUTO-HANDOFF
    // -------------------------------------------------------------------------

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            engine.language = Locale.UK
            engine.setSpeechRate(0.95f)

            try {
                val ukVoices = engine.voices?.filter { voice ->
                    voice.locale.language.equals("en", ignoreCase = true) &&
                        voice.locale.country.equals("GB", ignoreCase = true) &&
                        !voice.isNetworkConnectionRequired
                }.orEmpty()

                val preferredFemale = ukVoices.firstOrNull { v ->
                    val n = v.name.lowercase(Locale.US)
                    n.contains("gba") || n.contains("fis") || n.contains("female") || n.contains("sfg")
                } ?: ukVoices.firstOrNull()

                if (preferredFemale != null) {
                    engine.voice = preferredFemale
                }
            } catch (e: Exception) {
                Log.w(TAG, "Using default UK voice: ${e.message}")
            }

            ttsReady = true
        }
    }

    private fun hasExternalHeadset(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputs.any { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    private fun dispatchAlert(bpm: Int, forceVoice: Boolean = false) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getString(KEY_ALERT_MODE, ALERT_MODE_VOX) ?: ALERT_MODE_VOX

        if (!forceVoice && mode == ALERT_MODE_SILENCE) return

        if (!forceVoice && mode == ALERT_MODE_VIBRATE) {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 220, 120, 220), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(400L)
            }
            return
        }

        if (!ttsReady) return

        val useHeadset = hasExternalHeadset()
        val usage = if (useHeadset) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ALARM
        val stream = if (useHeadset) AudioManager.STREAM_MUSIC else AudioManager.STREAM_ALARM

        tts?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, stream)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        tts?.speak("Heartrate. $bpm.", TextToSpeech.QUEUE_FLUSH, params, "HAVEN_HR_TELEMETRY")
    }

    // -------------------------------------------------------------------------
    // 4. HEALTH CONNECT HRV GOVERNOR WITH 72-HOUR & RHR FALLBACK
    // -------------------------------------------------------------------------

    private suspend fun refreshDailyGovernorFromHealthConnect() {
        try {
            val status = HealthConnectClient.getSdkStatus(this)
            if (status != HealthConnectClient.SDK_AVAILABLE) {
                applyBudgetGovernor(null, null)
                return
            }

            val hcClient = HealthConnectClient.getOrCreate(this)
            val now = Instant.now()

            val hrvResponse = hcClient.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateVariabilityRmssdRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(now.minus(72, ChronoUnit.HOURS), now)
                )
            )
            val latestHrv = hrvResponse.records.maxByOrNull { it.time }

            val rhrResponse = hcClient.readRecords(
                ReadRecordsRequest(
                    recordType = RestingHeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(now.minus(48, ChronoUnit.HOURS), now)
                )
            )
            val latestRhr = rhrResponse.records.maxByOrNull { it.time }?.beatsPerMinute

            applyBudgetGovernor(latestHrv, latestRhr)
        } catch (e: Exception) {
            Log.w(TAG, "Health Connect fallback triggered: ${e.message}")
            applyBudgetGovernor(null, null)
        }
    }

    private fun applyBudgetGovernor(
        hrvRecord: HeartRateVariabilityRmssdRecord?,
        restingBpm: Long?
    ) {
        val now = Instant.now()
        val isFreshOvernight = hrvRecord != null &&
            ChronoUnit.HOURS.between(hrvRecord.time, now) <= 16

        if (hrvRecord != null) {
            val rmssd = hrvRecord.heartRateVariabilityMillis
            val rawCap = when {
                rmssd >= 45.0 -> 20.0
                rmssd >= 35.0 -> 18.0
                rmssd >= 26.0 -> 15.0
                else -> 12.0
            }
            val adjusted = if (isFreshOvernight) rawCap else (rawCap - 1.5)
            budgetPoints = adjusted.coerceIn(BUDGET_FLOOR_PTS, BUDGET_MAX_PTS)
            stabilityScore = when {
                rmssd >= 45.0 -> 88
                rmssd >= 35.0 -> 78
                rmssd >= 26.0 -> 64
                else -> 48
            }
        } else if (restingBpm != null) {
            budgetPoints = when {
                restingBpm <= 66 -> 18.0
                restingBpm <= 72 -> 15.0
                else -> 12.0
            }
            stabilityScore = when {
                restingBpm <= 66 -> 78
                restingBpm <= 72 -> 64
                else -> 48
            }
        } else {
            budgetPoints = BUDGET_DEFAULT_PTS
            stabilityScore = 70
        }

        activeCeilingBpm = DEFAULT_CEILING_BPM
        refreshNotificationAndHud()
    }

    // -------------------------------------------------------------------------
    // 5. BLE HEART RATE SCAN & AUTO-RECONNECT
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            bleStatusText = "BLE: BLUETOOTH OFF"
            broadcastHudUpdate()
            return
        }
        if (isScanning) return

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val connectedDevices = btManager.getConnectedDevices(BluetoothProfile.GATT)
        val existingVenu = connectedDevices.firstOrNull {
            it.name?.contains("Venu", ignoreCase = true) == true ||
                it.name?.contains("Garmin", ignoreCase = true) == true
        }
        if (existingVenu != null) {
            connectToDevice(existingVenu)
            return
        }

        val scanner = adapter.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(HR_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        isScanning = true
        bleStatusText = "BLE: SCANNING FOR VENU..."
        broadcastHudUpdate()
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (!isScanning) return
        isScanning = false
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) { }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            stopBleScan()
            connectToDevice(device)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        disconnectGatt()
        val name = device.name ?: "GARMIN VENU"
        bleStatusText = "BLE: CONNECTING ($name)..."
        broadcastHudUpdate()
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt() {
        reconnectJob?.cancel()
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: Exception) { }
        bluetoothGatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                bleStatusText = "BLE: CONNECTED (${gatt.device?.name ?: "VENU"})"
                broadcastHudUpdate()
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (inExcursion) {
                    closeAndPersistExcursion(settledHr = currentFilteredHr)
                    inExcursion = false
                    excursionElapsedSec = 0
                }
                currentFilteredHr = 0
                bleStatusText = "BLE: RECONNECTING..."
                broadcastHudUpdate()
                scheduleReconnect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val hrService = gatt.getService(HR_SERVICE_UUID) ?: return
            val hrChar = hrService.getCharacteristic(HR_MEASUREMENT_CHAR_UUID) ?: return

            gatt.setCharacteristicNotification(hrChar, true)
            val descriptor = hrChar.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == HR_MEASUREMENT_CHAR_UUID) {
                parseHeartRatePacket(characteristic.value)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == HR_MEASUREMENT_CHAR_UUID) {
                parseHeartRatePacket(value)
            }
        }
    }

    private fun parseHeartRatePacket(data: ByteArray?) {
        if (data == null || data.size < 2) return
        val flags = data[0].toInt()
        val isUint16 = (flags and 0x01) != 0
        val bpm = if (isUint16 && data.size >= 3) {
            ((data[2].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        } else {
            data[1].toInt() and 0xFF
        }
        mainHandler.post { onRawHeartRateReceived(bpm) }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            delay(4_000L)
            if (isServiceRunning) {
                startBleScan()
            }
        }
    }

    // -------------------------------------------------------------------------
    // 6. HUD BROADCAST, 04:00 AM DAY ROLLOVER WATCHDOG & PERSISTENCE
    // -------------------------------------------------------------------------

    private fun startRolloverWatchdog() {
        rolloverWatchdogJob?.cancel()
        rolloverWatchdogJob = serviceScope.launch {
            while (isActive) {
                delay(60_000L)
                mainHandler.post { checkDayRollover() }
            }
        }
    }

    private fun refreshNotificationAndHud() {
        val postureTag = when {
            activePostureMult <= 0.25 -> "REC"
            activePostureMult <= 0.75 -> "SEAT"
            else -> "UP"
        }
        val hrDisplay = if (currentFilteredHr > 0) "$currentFilteredHr BPM" else "-- BPM"
        updateNotification(
            String.format(
                Locale.US,
                "HR %s • %s • %.1f/%.0f PTS",
                hrDisplay,
                postureTag,
                dailyPoints,
                budgetPoints
            )
        )
        broadcastHudUpdate()
    }

    private fun broadcastHudUpdate() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val uiMode = if (powerManager.isInteractive) "COCKPIT_LIVE" else "COCKPIT_DARK"

        val intent = Intent(ACTION_HUD_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_HR, currentFilteredHr)
            putExtra(EXTRA_EXCURSION_SEC, excursionElapsedSec)
            putExtra(EXTRA_IN_EXCURSION, inExcursion)
            putExtra(EXTRA_PEAK_HR, excursionPeakHr)
            putExtra(EXTRA_DAILY_POINTS, dailyPoints)
            putExtra(EXTRA_BUDGET_POINTS, budgetPoints)
            putExtra(EXTRA_EXCURSION_POINTS, excursionPoints)
            putExtra(EXTRA_STABILITY_SCORE, stabilityScore)
            putExtra(EXTRA_ACTIVE_CEILING, activeCeilingBpm)
            putExtra(EXTRA_GRACE_SEC, DEFAULT_GRACE_SEC)
            putExtra(EXTRA_POSTURE_MULT, activePostureMult)
            putExtra(EXTRA_MODE, uiMode)
            putExtra(EXTRA_BLE_STATUS, bleStatusText)
        }
        sendBroadcast(intent)
    }

    private fun currentPacingDayKey(): String {
        val now = Instant.now().atZone(ZoneId.systemDefault())
        val shifted = if (now.hour < 4) now.minusDays(1) else now
        return shifted.toLocalDate().toString()
    }

    private fun checkDayRollover() {
        val key = currentPacingDayKey()
        if (key != activeDayKey) {
            Log.i(TAG, "04:00 AM Pacing Day Rollover: $activeDayKey -> $key (resetting points to 0.0)")
            activeDayKey = key
            dailyPoints = 0.0
            excursionPoints = 0.0
            persistDailyPoints()
            refreshNotificationAndHud()
            serviceScope.launch {
                refreshDailyGovernorFromHealthConnect()
            }
        }
    }

    private fun loadPersistedState() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedDay = prefs.getString(KEY_ACTIVE_DAY, null)
        if (savedDay == activeDayKey) {
            dailyPoints = prefs.getFloat(KEY_DAILY_POINTS, 0f).toDouble()
        } else {
            dailyPoints = 0.0
            persistDailyPoints()
        }
        activePostureMult = prefs.getFloat(KEY_POSTURE_MULT, POSTURE_RECLINED.toFloat()).toDouble()
        hasManualPostureOverride = prefs.getBoolean(KEY_MANUAL_POSTURE_OVERRIDE, false)
    }

    private fun persistDailyPoints() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACTIVE_DAY, activeDayKey)
            .putFloat(KEY_DAILY_POINTS, dailyPoints.toFloat())
            .apply()
    }

    // -------------------------------------------------------------------------
    // 7. FOREGROUND NOTIFICATION & CLEANUP
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "HAVEN Cockpit Live Pacing",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Continuous autonomic telemetry and PacePoint governor"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HAVEN Cockpit Live")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildForegroundNotification(contentText))
    }

    override fun onDestroy() {
        isServiceRunning = false
        rolloverWatchdogJob?.cancel()
        if (inExcursion) {
            closeAndPersistExcursion(settledHr = currentFilteredHr)
        }
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) { }
        stopBleScan()
        disconnectGatt()
        unregisterSensors()
        tts?.stop()
        tts?.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }
}
