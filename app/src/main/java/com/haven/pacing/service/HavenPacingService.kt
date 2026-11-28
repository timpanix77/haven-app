package com.haven.pacing

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
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class HavenPacingService : Service(), TextToSpeech.OnInitListener, SensorEventListener {

    companion object {
        private const val TAG = "HavenPacingService"
        private const val CHANNEL_ID = "haven_pacing_channel"
        private const val NOTIFICATION_ID = 1001

        // Standard Bluetooth Heart Rate Service & Characteristics
        val HR_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT_CHAR_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Biometric & Pacing Constants
        const val CEILING_BPM = 89
        const val RECOVERY_CLEAR_BPM = 82
        const val RECOVERY_SETTLE_SECONDS = 30L
        const val ANAEROBIC_EMERGENCY_BPM = 115
        const val GRACE_SECONDS = 120L
        const val VOICE_REPEAT_COOLDOWN_MS = 90_000L

        const val BUDGET_FLOOR_PTS = 12.0
        const val BUDGET_DEFAULT_PTS = 16.0
        const val BUDGET_MAX_PTS = 20.0

        // Posture Multipliers
        const val MULT_RECLINED = 0.20
        const val MULT_SEATED = 0.70
        const val MULT_UPRIGHT = 1.00

        // Broadcast Actions
        const val ACTION_START_ARM = "com.haven.pacing.ACTION_START_ARM"
        const val ACTION_STOP_DISARM = "com.haven.pacing.ACTION_STOP_DISARM"
        const val ACTION_TEST_VOICE = "com.haven.pacing.ACTION_TEST_VOICE"
        const val ACTION_CYCLE_POSTURE_OVERRIDE = "com.haven.pacing.ACTION_CYCLE_POSTURE_OVERRIDE"
    }

    enum class PostureState(val label: String, val multiplier: Double) {
        RECLINED("RECLINED", MULT_RECLINED),
        SEATED("SEATED", MULT_SEATED),
        UPRIGHT("UPRIGHT", MULT_UPRIGHT)
    }

    data class TelemetryUiState(
        val isArmed: Boolean = false,
        val connectionStatus: String = "DISARMED",
        val deviceName: String = "--",
        val currentBpm: Int = 0,
        val posture: PostureState = PostureState.RECLINED,
        val isPostureManualOverride: Boolean = false,
        val dailyCapPts: Double = BUDGET_DEFAULT_PTS,
        val spentPts: Double = 0.0,
        val morningHrvRmssd: Double? = null,
        val hrvSourceNote: String = "Baseline Guard",
        val inExcursion: Boolean = false,
        val excursionElapsedSec: Long = 0L,
        val isHeadsetConnected: Boolean = false,
        val awaitingRecoveryClear: Boolean = false
    )

    inner class LocalBinder : Binder() {
        fun getService(): HavenPacingService = this@HavenPacingService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _uiState = MutableStateFlow(TelemetryUiState())
    val uiState: StateFlow<TelemetryUiState> = _uiState.asStateFlow()

    // Bluetooth LE
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private var reconnectJob: Job? = null

    // HR Median Filter & Excursion / Recovery Tracking
    private val hrWindow = ArrayDeque<Int>(3)
    private var lastHrTickMs: Long = 0L
    private var excursionStartMs: Long = 0L
    private var excursionPeakBpm: Int = 0
    private var excursionBpmSum: Long = 0L
    private var excursionSamples: Int = 0
    private var lastVoiceAlertMs: Long = 0L
    private var awaitingRecoveryClear: Boolean = false
    private var belowThresholdSinceMs: Long = 0L
    private var activeDayKey: String = ""

    // TTS Audio
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // Sensors & Posture Inference
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
    private var manualPostureOverride: PostureState? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT || intent?.action == Intent.ACTION_SCREEN_ON) {
                lastScreenInteractiveMs = System.currentTimeMillis()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
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
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_DISARM -> {
                disarmService()
                return START_NOT_STICKY
            }
            ACTION_TEST_VOICE -> {
                val testBpm = _uiState.value.currentBpm.takeIf { it > 0 } ?: 79
                speakTelemetry(testBpm)
                return START_STICKY
            }
            ACTION_CYCLE_POSTURE_OVERRIDE -> {
                cycleManualPostureOverride()
                return START_STICKY
            }
            else -> {
                armService()
            }
        }
        return START_STICKY
    }

    fun armService() {
        if (_uiState.value.isArmed) return

        val notification = buildForegroundNotification("Scanning for Garmin Venu...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        registerSensors()
        _uiState.value = _uiState.value.copy(
            isArmed = true,
            connectionStatus = "SCANNING",
            isHeadsetConnected = hasExternalHeadset()
        )

        serviceScope.launch {
            refreshDailyGovernorFromHealthConnect()
        }

        startBleScan()
    }

    fun disarmService() {
        closeActiveExcursionIfNeeded()
        awaitingRecoveryClear = false
        belowThresholdSinceMs = 0L
        stopBleScan()
        disconnectGatt()
        unregisterSensors()

        _uiState.value = _uiState.value.copy(
            isArmed = false,
            connectionStatus = "DISARMED",
            currentBpm = 0,
            inExcursion = false,
            excursionElapsedSec = 0L,
            awaitingRecoveryClear = false
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun cycleManualPostureOverride() {
        manualPostureOverride = when (manualPostureOverride) {
            null -> PostureState.RECLINED
            PostureState.RECLINED -> PostureState.SEATED
            PostureState.SEATED -> PostureState.UPRIGHT
            PostureState.UPRIGHT -> null
        }
        val activePosture = manualPostureOverride ?: inferAutonomousPosture(_uiState.value.currentBpm)
        _uiState.value = _uiState.value.copy(
            posture = activePosture,
            isPostureManualOverride = manualPostureOverride != null
        )
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
        sensorManager.unregisterListener(this)
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
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun inferAutonomousPosture(currentBpm: Int): PostureState {
        manualPostureOverride?.let { return it }

        val now = System.currentTimeMillis()
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        val isStationary = motionVarianceEma < 0.03f
        val recentSteps = (now - lastStepTimestampMs) < 20_000L
        val mediaOrScreenActive = audioManager.isMusicActive ||
            powerManager.isInteractive ||
            (now - lastScreenInteractiveMs) < 15 * 60_000L

        if (isStationary) {
            if (currentBpm >= CEILING_BPM) {
                return PostureState.UPRIGHT
            }
            if (mediaOrScreenActive) {
                return if (currentBpm >= 96) PostureState.UPRIGHT else PostureState.SEATED
            }
            return PostureState.RECLINED
        }

        val mag = sqrt(gravX * gravX + gravY * gravY + gravZ * gravZ).coerceAtLeast(0.1f)
        val normY = abs(gravY) / mag
        val normZ = abs(gravZ) / mag

        return when {
            recentSteps -> PostureState.UPRIGHT
            normZ > 0.85f && currentBpm < CEILING_BPM -> PostureState.RECLINED
            normY > 0.78f -> PostureState.SEATED
            currentBpm >= CEILING_BPM -> PostureState.UPRIGHT
            else -> PostureState.SEATED
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

        val now = System.currentTimeMillis()
        val dtSec = if (lastHrTickMs > 0L) {
            ((now - lastHrTickMs) / 1000.0).coerceIn(0.5, 5.0)
        } else {
            1.0
        }
        lastHrTickMs = now

        checkDayRollover()

        val inferredPosture = inferAutonomousPosture(filteredBpm)
        val headsetConnected = hasExternalHeadset()

        var spent = _uiState.value.spentPts
        var inExc = _uiState.value.inExcursion
        var elapsedSec = 0L

        if (filteredBpm >= CEILING_BPM) {
            belowThresholdSinceMs = 0L

            if (!inExc) {
                inExc = true
                excursionStartMs = now
                excursionPeakBpm = filteredBpm
                excursionBpmSum = 0L
                excursionSamples = 0
                lastVoiceAlertMs = 0L
            }

            elapsedSec = (now - excursionStartMs) / 1000L
            excursionPeakBpm = max(excursionPeakBpm, filteredBpm)
            excursionBpmSum += filteredBpm
            excursionSamples++

            val bpmAboveFloor = (filteredBpm - 65).coerceAtLeast(1)
            val rawPtsPerMin = (bpmAboveFloor / 25.0) * 1.25
            val stepPts = rawPtsPerMin * (dtSec / 60.0) * inferredPosture.multiplier
            spent += stepPts

            val graceExpired = elapsedSec >= GRACE_SECONDS
            val emergencySpike = filteredBpm >= ANAEROBIC_EMERGENCY_BPM
            if ((graceExpired || emergencySpike) && (now - lastVoiceAlertMs >= VOICE_REPEAT_COOLDOWN_MS)) {
                lastVoiceAlertMs = now
                awaitingRecoveryClear = true
                speakTelemetry(filteredBpm)
            }
        } else {
            if (inExc) {
                closeActiveExcursionIfNeeded()
                inExc = false
            }
            elapsedSec = 0L

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
                    speakTelemetry(filteredBpm)
                }
            }
        }

        persistSpentPoints(spent)

        _uiState.value = _uiState.value.copy(
            currentBpm = filteredBpm,
            posture = inferredPosture,
            isPostureManualOverride = manualPostureOverride != null,
            spentPts = spent,
            inExcursion = inExc,
            excursionElapsedSec = elapsedSec,
            isHeadsetConnected = headsetConnected,
            awaitingRecoveryClear = awaitingRecoveryClear
        )

        updateNotification(
            "HR $filteredBpm BPM • ${inferredPosture.label} • " +
                String.format(Locale.US, "%.1f/%.1f PTS", spent, _uiState.value.dailyCapPts)
        )
    }

    private fun closeActiveExcursionIfNeeded() {
        if (!_uiState.value.inExcursion || excursionSamples == 0) return
        val durationSec = ((System.currentTimeMillis() - excursionStartMs) / 1000L).coerceAtLeast(1L)
        val avgBpm = (excursionBpmSum / excursionSamples).toInt()
        val postureLabel = _uiState.value.posture.label
        Log.i(
            TAG,
            "Excursion closed: duration=${durationSec}s, avg=$avgBpm, peak=$excursionPeakBpm, posture=$postureLabel"
        )
        excursionStartMs = 0L
        excursionPeakBpm = 0
        excursionBpmSum = 0L
        excursionSamples = 0
    }

    // -------------------------------------------------------------------------
    // 3. BLUETOOTH-AWARE TELEMETRY VOICE ("Heartrate. [BPM].")
    // -------------------------------------------------------------------------

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setSpeechRate(0.95f)
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

    private fun speakTelemetry(bpm: Int) {
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

        tts?.speak("Heartrate. $bpm.", TextToSpeech.QUEUE_FLUSH, params, "HAVEN_HR_ALERT")
    }

    // -------------------------------------------------------------------------
    // 4. HEALTH CONNECT HRV GOVERNOR WITH 72-HOUR & RHR FALLBACK
    // -------------------------------------------------------------------------

    suspend fun refreshDailyGovernorFromHealthConnect() {
        try {
            val status = HealthConnectClient.getSdkStatus(this)
            if (status != HealthConnectClient.SDK_AVAILABLE) {
                applyBudgetGovernor(null, null)
                return
            }

            val hcClient = HealthConnectClient.getOrCreate(this)
            val now = Instant.now()

            val hrvResponse72h = hcClient.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateVariabilityRmssdRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(now.minus(72, ChronoUnit.HOURS), now)
                )
            )
            val latestHrv = hrvResponse72h.records.maxByOrNull { it.time }

            val rhrResponse = hcClient.readRecords(
                ReadRecordsRequest(
                    recordType = RestingHeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(now.minus(48, ChronoUnit.HOURS), now)
                )
            )
            val latestRhr = rhrResponse.records.maxByOrNull { it.time }?.beatsPerMinute

            applyBudgetGovernor(latestHrv, latestRhr)
        } catch (e: Exception) {
            Log.w(TAG, "Health Connect read fallback triggered: ${e.message}")
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

        val computedCap: Double
        val sourceNote: String

        if (hrvRecord != null) {
            val rmssd = hrvRecord.heartRateVariabilityMillis
            val rawCap = when {
                rmssd >= 45.0 -> 20.0
                rmssd >= 35.0 -> 18.0
                rmssd >= 26.0 -> 15.0
                else -> 12.0
            }
            val adjusted = if (isFreshOvernight) rawCap else (rawCap - 1.5)
            computedCap = adjusted.coerceIn(BUDGET_FLOOR_PTS, BUDGET_MAX_PTS)
            sourceNote = if (isFreshOvernight) {
                "Overnight RMSSD ${rmssd.toInt()}ms"
            } else {
                "72h Fallback RMSSD ${rmssd.toInt()}ms"
            }
        } else if (restingBpm != null) {
            computedCap = when {
                restingBpm <= 66 -> 18.0
                restingBpm <= 72 -> 15.0
                else -> 12.0
            }
            sourceNote = "RHR Floor Fallback (${restingBpm} BPM)"
        } else {
            computedCap = BUDGET_DEFAULT_PTS
            sourceNote = "Fragmented Sleep Safe Default"
        }

        _uiState.value = _uiState.value.copy(
            dailyCapPts = computedCap,
            morningHrvRmssd = hrvRecord?.heartRateVariabilityMillis,
            hrvSourceNote = sourceNote
        )
    }

    // -------------------------------------------------------------------------
    // 5. BLE HEART RATE SCAN & AUTO-RECONNECT
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled || isScanning) return

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
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (!isScanning) return
        isScanning = false
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan ignored: ${e.message}")
        }
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
        val name = device.name ?: "Garmin Venu"
        _uiState.value = _uiState.value.copy(
            connectionStatus = "CONNECTING",
            deviceName = name
        )
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt() {
        reconnectJob?.cancel()
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: Exception) {
        }
        bluetoothGatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _uiState.value = _uiState.value.copy(connectionStatus = "CONNECTED")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                closeActiveExcursionIfNeeded()
                _uiState.value = _uiState.value.copy(
                    connectionStatus = "RECONNECTING",
                    currentBpm = 0,
                    inExcursion = false
                )
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
            if (_uiState.value.isArmed) {
                startBleScan()
            }
        }
    }

    // -------------------------------------------------------------------------
    // 6. DAY ROLLOVER (04:00 AM) & PERSISTENCE
    // -------------------------------------------------------------------------

    private fun currentPacingDayKey(): String {
        val now = Instant.now().atZone(ZoneId.systemDefault())
        val shifted = if (now.hour < 4) now.minusDays(1) else now
        return shifted.toLocalDate().toString()
    }

    private fun checkDayRollover() {
        val key = currentPacingDayKey()
        if (key != activeDayKey) {
            activeDayKey = key
            persistSpentPoints(0.0)
            _uiState.value = _uiState.value.copy(spentPts = 0.0)
            serviceScope.launch {
                refreshDailyGovernorFromHealthConnect()
            }
        }
    }

    private fun loadPersistedState() {
        val prefs = getSharedPreferences("haven_pacing_prefs", Context.MODE_PRIVATE)
        val savedDay = prefs.getString("active_day_key", activeDayKey)
        val savedSpent = if (savedDay == activeDayKey) {
            prefs.getFloat("spent_pts", 0f).toDouble()
        } else {
            0.0
        }
        _uiState.value = _uiState.value.copy(spentPts = savedSpent)
    }

    private fun persistSpentPoints(spent: Double) {
        val prefs = getSharedPreferences("haven_pacing_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("active_day_key", activeDayKey)
            .putFloat("spent_pts", spent.toFloat())
            .apply()
    }

    // -------------------------------------------------------------------------
    // 7. FOREGROUND NOTIFICATION
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
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
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
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        stopBleScan()
        disconnectGatt()
        unregisterSensors()
        tts?.stop()
        tts?.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }
}
