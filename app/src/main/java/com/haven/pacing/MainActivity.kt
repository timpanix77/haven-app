package com.haven.pacing

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import com.haven.pacing.data.ExcursionRecord
import com.haven.pacing.data.HavenDatabase
import com.haven.pacing.data.VaultManager
import com.haven.pacing.service.HavenPacingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvLiveHr: TextView
    private lateinit var tvHrStatus: TextView
    private lateinit var tvMode: TextView
    private lateinit var tvExcursion: TextView
    private lateinit var tvBudgetRemaining: TextView
    private lateinit var tvBudgetLabel: TextView
    private lateinit var tvPaceStatus: TextView
    private lateinit var tvBufferStatus: TextView
    private lateinit var tvPeakIndicator: TextView
    private lateinit var tvStabilityBanner: TextView
    private lateinit var btnAlertMode: Button
    private lateinit var btnArm: Button
    private lateinit var btnExport: Button
    private lateinit var btnRestore: Button

    private lateinit var vuHr: LedVuMeterView
    private lateinit var vuBudget: LedVuMeterView
    private lateinit var vuGrace: LedVuMeterView

    private lateinit var vaultManager: VaultManager
    private var lastRecordedExcursionSec: Int = 0
    private var latchedPeakHr: Int = 0
    private var currentSurgePeakHr: Int = 0

    private val healthPermissions = setOf(
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class)
    )

    private val requestHealthPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        val msg = if (granted.containsAll(healthPermissions)) {
            "Health Connect Linked! Exporting 30-Day Vault..."
        } else {
            "Health Connect Skipped — Exporting Excursions..."
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        runFullVaultExport()
    }

    private val restoreFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) { restoreFromUriWithFallback(uri) }
        } else {
            Toast.makeText(this, "Restore Cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    private val createDriveExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) { }

            getSharedPreferences(HavenPacingService.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DRIVE_EXPORT_URI, uri.toString())
                .apply()

            lifecycleScope.launch(Dispatchers.IO) {
                val ok = writeLatestExportToUri(uri)
                withContext(Dispatchers.Main) {
                    val msg = if (ok) "30-Day Vault Exported to Google Drive!" else "Drive Write Failed"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(this, "Saved to Downloads (Drive Not Linked)", Toast.LENGTH_SHORT).show()
        }
    }

    private val hudReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == HavenPacingService.ACTION_HUD_UPDATE) {
                renderTelemetry(
                    intent.getIntExtra(HavenPacingService.EXTRA_HR, 0),
                    intent.getIntExtra(HavenPacingService.EXTRA_EXCURSION_SEC, 0),
                    intent.getBooleanExtra(HavenPacingService.EXTRA_IN_EXCURSION, false),
                    intent.getIntExtra(HavenPacingService.EXTRA_PEAK_HR, 0),
                    intent.getDoubleExtra(HavenPacingService.EXTRA_DAILY_POINTS, 0.0),
                    intent.getDoubleExtra(HavenPacingService.EXTRA_BUDGET_POINTS, 20.0),
                    intent.getDoubleExtra(HavenPacingService.EXTRA_EXCURSION_POINTS, 0.0),
                    intent.getIntExtra(HavenPacingService.EXTRA_STABILITY_SCORE, 75),
                    intent.getIntExtra(HavenPacingService.EXTRA_ACTIVE_CEILING, 88),
                    intent.getIntExtra(HavenPacingService.EXTRA_GRACE_SEC, 120),
                    intent.getStringExtra(HavenPacingService.EXTRA_MODE) ?: "COCKPIT_LIVE",
                    intent.getStringExtra(HavenPacingService.EXTRA_BLE_STATUS) ?: ""
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvLiveHr = findViewById(R.id.tvLiveHr)
        tvHrStatus = findViewById(R.id.tvHrStatus)
        tvMode = findViewById(R.id.tvMode)
        tvExcursion = findViewById(R.id.tvExcursion)
        tvBudgetRemaining = findViewById(R.id.tvBudgetRemaining)
        tvBudgetLabel = findViewById(R.id.tvBudgetLabel)
        tvPaceStatus = findViewById(R.id.tvPaceStatus)
        tvBufferStatus = findViewById(R.id.tvBufferStatus)
        tvPeakIndicator = findViewById(R.id.tvPeakIndicator)
        tvStabilityBanner = findViewById(R.id.tvStabilityBanner)
        btnAlertMode = findViewById(R.id.btnAlertMode)
        btnArm = findViewById(R.id.btnArm)
        btnExport = findViewById(R.id.btnExport)
        btnRestore = findViewById(R.id.btnRestore)

        vuHr = findViewById(R.id.vuHr)
        vuBudget = findViewById(R.id.vuBudget)
        vuGrace = findViewById(R.id.vuGrace)

        vuHr.meterType = LedVuMeterView.MeterType.HR_PROXIMITY
        vuBudget.meterType = LedVuMeterView.MeterType.DAILY_BUDGET
        vuGrace.meterType = LedVuMeterView.MeterType.GRACE_BUFFER

        vaultManager = VaultManager(HavenDatabase.getDatabase(this))

        setupAlertModeToggle()

        btnArm.setOnClickListener {
            Toast.makeText(this, "Checking BLE & Permissions...", Toast.LENGTH_SHORT).show()
            checkPermissionsAndStartService()
        }

        btnExport.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val needsHealthPrompt = try {
                    val client = HealthConnectClient.getOrCreate(this@MainActivity)
                    !client.permissionController.getGrantedPermissions().containsAll(healthPermissions)
                } catch (_: Exception) {
                    false
                }

                if (needsHealthPrompt) {
                    withContext(Dispatchers.Main) {
                        try {
                            requestHealthPermissions.launch(healthPermissions)
                        } catch (_: Exception) {
                            runFullVaultExport()
                        }
                    }
                } else {
                    runFullVaultExport()
                }
            }
        }

        btnExport.setOnLongClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                vaultManager.exportVault(this@MainActivity)
                buildEnrichedVaultBytes()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Choose New Google Drive Target...", Toast.LENGTH_SHORT).show()
                    createDriveExportLauncher.launch("haven_vault_export.json")
                }
            }
            true
        }

        btnRestore.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val autoRestored = tryAutoRestoreFromDownloads()
                if (autoRestored > 0) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Auto-Restored $autoRestored Excursions!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        try {
                            restoreFileLauncher.launch(arrayOf("*/*"))
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Picker Error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        renderTelemetry(0, 0, false, 0, 0.0, 20.0, 0.0, 75, 88, 120, "COCKPIT_LIVE", "BLE: READY - TAP 'ARM' TO START")
        checkPermissionsAndStartService()
    }

    private fun setupAlertModeToggle() {
        val prefs = getSharedPreferences(HavenPacingService.PREFS_NAME, Context.MODE_PRIVATE)
        var currentMode = prefs.getString(HavenPacingService.KEY_ALERT_MODE, HavenPacingService.ALERT_MODE_VOX)
            ?: HavenPacingService.ALERT_MODE_VOX
        renderAlertModeButton(currentMode)

        btnAlertMode.setOnClickListener {
            currentMode = when (currentMode) {
                HavenPacingService.ALERT_MODE_VOX -> HavenPacingService.ALERT_MODE_VIBRATE
                HavenPacingService.ALERT_MODE_VIBRATE -> HavenPacingService.ALERT_MODE_SILENCE
                else -> HavenPacingService.ALERT_MODE_VOX
            }
            prefs.edit().putString(HavenPacingService.KEY_ALERT_MODE, currentMode).apply()
            renderAlertModeButton(currentMode)

            if (currentMode != HavenPacingService.ALERT_MODE_VOX) {
                try {
                    startService(Intent(this, HavenPacingService::class.java).apply {
                        action = HavenPacingService.ACTION_STOP_TTS
                    })
                } catch (_: Exception) { }
            }

            if (currentMode == HavenPacingService.ALERT_MODE_VIBRATE) {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(60L, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(60L)
                }
            }
        }
    }

    private fun renderAlertModeButton(mode: String) {
        when (mode) {
            HavenPacingService.ALERT_MODE_VIBRATE -> {
                btnAlertMode.text = "ALERT: VIBRATE"
                btnAlertMode.setTextColor(Color.parseColor("#FF9100"))
            }
            HavenPacingService.ALERT_MODE_SILENCE -> {
                btnAlertMode.text = "ALERT: SILENCE"
                btnAlertMode.setTextColor(Color.parseColor("#8E8E93"))
            }
            else -> {
                btnAlertMode.text = "ALERT: VOX"
                btnAlertMode.setTextColor(Color.parseColor("#00E676"))
            }
        }
    }

    private fun runFullVaultExport() {
        lifecycleScope.launch(Dispatchers.IO) {
            vaultManager.exportVault(this@MainActivity)
            val savedUriStr = getSharedPreferences(HavenPacingService.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_DRIVE_EXPORT_URI, null)

            if (!savedUriStr.isNullOrEmpty()) {
                val synced = writeLatestExportToUri(Uri.parse(savedUriStr))
                withContext(Dispatchers.Main) {
                    if (synced) {
                        Toast.makeText(this@MainActivity, "30-Day Vault Exported to Google Drive!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Select Google Drive Destination...", Toast.LENGTH_SHORT).show()
                        createDriveExportLauncher.launch("haven_vault_export.json")
                    }
                }
            } else {
                buildEnrichedVaultBytes()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Select Google Drive Folder...", Toast.LENGTH_SHORT).show()
                    createDriveExportLauncher.launch("haven_vault_export.json")
                }
            }
        }
    }

    private suspend fun buildEnrichedVaultBytes(): ByteArray {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val candidates = listOf("haven_vault_export.json", "haven_vault_backup.json", "haven_vault.json")
        var sourceFile: File? = null
        if (downloadsDir != null && downloadsDir.exists()) {
            for (name in candidates) {
                val f = File(downloadsDir, name)
                if (f.exists() && f.length() > 0L) {
                    sourceFile = f
                    break
                }
            }
            if (sourceFile == null) {
                sourceFile = downloadsDir.listFiles()
                    ?.filter { it.isFile && it.name.startsWith("haven_vault") && it.name.endsWith(".json") }
                    ?.maxByOrNull { it.lastModified() }
            }
        }

        val rootObj = try {
            val raw = sourceFile?.readText(Charsets.UTF_8)?.trim() ?: ""
            when {
                raw.startsWith("{") -> JSONObject(raw)
                raw.startsWith("[") -> JSONObject().put("records", JSONArray(raw))
                else -> JSONObject().put("records", JSONArray())
            }
        } catch (_: Exception) {
            JSONObject().put("records", JSONArray())
        }

        val dailyBioObj = rootObj.optJSONObject("dailyBio") ?: JSONObject()
        val zone = ZoneId.systemDefault()
        val dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

        fun instantToDayKey(inst: Instant): String {
            var zdt = inst.atZone(zone)
            if (zdt.hour < 4) zdt = zdt.minusDays(1)
            return zdt.format(dayFmt)
        }

        try {
            val client = HealthConnectClient.getOrCreate(this@MainActivity)
            val now = Instant.now()
            val start = now.minus(30, ChronoUnit.DAYS)

            try {
                val hrvRes = client.readRecords(
                    ReadRecordsRequest(
                        recordType = HeartRateVariabilityRmssdRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start, now)
                    )
                )
                for (rec in hrvRes.records) {
                    val key = instantToDayKey(rec.time)
                    val entry = dailyBioObj.optJSONObject(key) ?: JSONObject()
                    entry.put("hrv", Math.round(rec.heartRateVariabilityMillis * 10.0) / 10.0)
                    dailyBioObj.put(key, entry)
                }
            } catch (_: Exception) { }

            try {
                val rhrRes = client.readRecords(
                    ReadRecordsRequest(
                        recordType = RestingHeartRateRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start, now)
                    )
                )
                for (rec in rhrRes.records) {
                    val key = instantToDayKey(rec.time)
                    val entry = dailyBioObj.optJSONObject(key) ?: JSONObject()
                    entry.put("floorHr", rec.beatsPerMinute.toInt())
                    dailyBioObj.put(key, entry)
                }
            } catch (_: Exception) { }
        } catch (_: Exception) { }

        rootObj.put("dailyBio", dailyBioObj)
        val finalText = rootObj.toString(2)
        try {
            sourceFile?.writeText(finalText, Charsets.UTF_8)
        } catch (_: Exception) { }

        return finalText.toByteArray(Charsets.UTF_8)
    }

    private suspend fun writeLatestExportToUri(uri: Uri): Boolean {
        return try {
            val payloadBytes = buildEnrichedVaultBytes()
            contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(payloadBytes)
                out.flush()
                true
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun tryAutoRestoreFromDownloads(): Int {
        val candidates = listOf("haven_vault_export.json", "haven_vault_backup.json", "haven_vault.json")
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloadsDir != null && downloadsDir.exists()) {
            for (name in candidates) {
                val file = File(downloadsDir, name)
                if (file.exists() && file.length() > 0L) {
                    try {
                        val count = parseAndInsertExcursions(file.readText())
                        if (count > 0) return count
                    } catch (_: Exception) { }
                }
            }
        }
        return -1
    }

    private suspend fun restoreFromUriWithFallback(uri: Uri) {
        val count = try {
            vaultManager.restoreVaultFromUri(this@MainActivity, uri)
        } catch (_: Exception) {
            -1
        }

        if (count >= 0) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Restored $count Excursions!", Toast.LENGTH_SHORT).show()
            }
            return
        }

        try {
            val jsonText = contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            if (!jsonText.isNullOrEmpty()) {
                val fallbackCount = parseAndInsertExcursions(jsonText)
                withContext(Dispatchers.Main) {
                    val msg = if (fallbackCount >= 0) "Restored $fallbackCount Excursions!" else "Restore Failed: Empty or invalid vault"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Restore Failed: Could not read file stream", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Restore Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun parseAndInsertExcursions(jsonString: String): Int {
        val trimmed = jsonString.trim()
        val jsonArray = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            val obj = JSONObject(trimmed)
            when {
                obj.has("records") -> obj.getJSONArray("records")
                obj.has("excursions") -> obj.getJSONArray("excursions")
                obj.has("data") -> obj.getJSONArray("data")
                else -> {
                    var found: JSONArray? = null
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val v = obj.optJSONArray(keys.next())
                        if (v != null) {
                            found = v
                            break
                        }
                    }
                    found ?: return -1
                }
            }
        }

        val db = HavenDatabase.getDatabase(this@MainActivity)
        var count = 0
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)
            val record = ExcursionRecord(
                timestamp = item.optLong("timestamp", item.optLong("time", System.currentTimeMillis())),
                durationSeconds = item.optInt("durationSeconds", item.optInt("duration", 0)),
                peakHr = item.optInt("peakHr", item.optInt("peak", 0)),
                settledHr = item.optInt("settledHr", item.optInt("settled", 0)),
                strainPoints = item.optDouble("strainPoints", item.optDouble("points", 0.0))
            )
            db.havenDao().insert(record)
            count++
        }
        return count
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences(HavenPacingService.PREFS_NAME, Context.MODE_PRIVATE)
        val savedAlertMode = prefs.getString(HavenPacingService.KEY_ALERT_MODE, HavenPacingService.ALERT_MODE_VOX)
            ?: HavenPacingService.ALERT_MODE_VOX
        renderAlertModeButton(savedAlertMode)

        val filter = IntentFilter(HavenPacingService.ACTION_HUD_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(hudReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(hudReceiver, filter)
        }

        if (HavenPacingService.isServiceRunning) {
            btnArm.text = "ENGINE ACTIVE (ARMED)"
            btnArm.setTextColor(Color.parseColor("#00E676"))
            try {
                startService(Intent(this, HavenPacingService::class.java))
            } catch (_: Exception) { }
        } else {
            btnArm.text = "START PACING ENGINE"
            btnArm.setTextColor(Color.WHITE)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(hudReceiver)
        } catch (_: Exception) { }
    }

    private fun renderTelemetry(
        hr: Int,
        excSec: Int,
        inExcursion: Boolean,
        servicePeakHr: Int,
        dailyPoints: Double,
        budgetPoints: Double,
        excPoints: Double,
        stability: Int,
        ceiling: Int,
        graceSec: Int,
        mode: String,
        bleStatus: String = ""
    ) {
        if (hr > 0) {
            tvLiveHr.text = hr.toString()
            vuHr.setLevel((hr - 40).coerceAtLeast(0).toFloat(), 70f)
            when {
                hr <= 75 -> {
                    tvLiveHr.setTextColor(Color.parseColor("#00E676"))
                    tvHrStatus.text = "BPM / FLOOR: RECOVERED (<=75)"
                }
                hr < ceiling -> {
                    tvLiveHr.setTextColor(Color.WHITE)
                    tvHrStatus.text = "BPM / SAFE ACTIVE ZONE"
                }
                hr < ceiling + 3 -> {
                    tvLiveHr.setTextColor(Color.parseColor("#FF9100"))
                    tvHrStatus.text = "BPM / CEILING WARNING"
                }
                else -> {
                    tvLiveHr.setTextColor(Color.parseColor("#FF5252"))
                    tvHrStatus.text = "BPM / CEILING BREACH (" + ceiling + ")"
                }
            }
        } else {
            tvLiveHr.text = "--"
            tvLiveHr.setTextColor(Color.parseColor("#8E8E93"))
            vuHr.setLevel(0f, 70f)
            tvHrStatus.text = if (bleStatus.isNotEmpty()) bleStatus else "BLE: SEARCHING..."
        }

        if (inExcursion) {
            if (currentSurgePeakHr == 0 || hr > currentSurgePeakHr) {
                currentSurgePeakHr = hr
            }
            if (currentSurgePeakHr > latchedPeakHr) {
                latchedPeakHr = currentSurgePeakHr
            }
        } else {
            currentSurgePeakHr = 0
        }

        if (servicePeakHr >= ceiling && servicePeakHr > latchedPeakHr) {
            latchedPeakHr = servicePeakHr
        }
        if (hr >= ceiling && hr > latchedPeakHr) {
            latchedPeakHr = hr
        }

        if (latchedPeakHr > 0) {
            tvPeakIndicator.text = "PEAK: " + latchedPeakHr + " BPM"
            tvPeakIndicator.setTextColor(
                when {
                    latchedPeakHr >= 115 -> Color.parseColor("#FF1744")
                    latchedPeakHr >= ceiling -> Color.parseColor("#FF5252")
                    latchedPeakHr >= 76 -> Color.parseColor("#FFEA00")
                    else -> Color.parseColor("#00E676")
                }
            )
        } else {
            tvPeakIndicator.text = "PEAK: --"
            tvPeakIndicator.setTextColor(Color.parseColor("#8E8E93"))
        }

        val safeGrace = graceSec.coerceAtLeast(1)
        if (inExcursion) {
            lastRecordedExcursionSec = excSec
            tvExcursion.text = String.format(Locale.US, "%02d:%02d", excSec / 60, excSec % 60)
            tvExcursion.setTextColor(Color.parseColor("#FF9100"))
            vuGrace.setLevel(excSec.coerceAtMost(safeGrace).toFloat(), safeGrace.toFloat())
            val remainingGrace = (safeGrace - excSec).coerceAtLeast(0)
            if (remainingGrace > 0) {
                tvBufferStatus.text = "ACTIVE (" + remainingGrace + "s LEFT)"
                tvBufferStatus.setTextColor(Color.parseColor("#FF9100"))
            } else {
                tvBufferStatus.text = "OVERAGE (+" + (excSec - safeGrace) + "s)"
                tvBufferStatus.setTextColor(Color.parseColor("#FF5252"))
            }
        } else {
            if (excSec > 0) lastRecordedExcursionSec = excSec
            tvExcursion.text = String.format(Locale.US, "%02d:%02d", lastRecordedExcursionSec / 60, lastRecordedExcursionSec % 60)
            tvExcursion.setTextColor(Color.WHITE)
            vuGrace.setLevel(0f, safeGrace.toFloat())
            tvBufferStatus.text = "STANDBY"
            tvBufferStatus.setTextColor(Color.parseColor("#8E8E93"))
        }

        tvBudgetLabel.text = String.format(Locale.US, "PACE POINTS (%.0f CAP)", budgetPoints)
        vuBudget.setLevel(dailyPoints.toFloat(), budgetPoints.toFloat())

        val remainingPoints = budgetPoints - dailyPoints
        if (remainingPoints >= 0) {
            tvBudgetRemaining.text = String.format(Locale.US, "%.1f PTS", remainingPoints)
            tvBudgetRemaining.setTextColor(Color.parseColor("#00E676"))
            if (dailyPoints >= budgetPoints * 0.75) {
                tvPaceStatus.text = "PACE: GUARDED"
                tvPaceStatus.setTextColor(Color.parseColor("#FF9100"))
            } else {
                tvPaceStatus.text = "PACE: ON TRACK"
                tvPaceStatus.setTextColor(Color.parseColor("#00E676"))
            }
        } else {
            tvBudgetRemaining.text = String.format(Locale.US, "-%.1f PTS", -remainingPoints)
            tvBudgetRemaining.setTextColor(Color.parseColor("#FF5252"))
            tvPaceStatus.text = "PACE: OVER BUDGET"
            tvPaceStatus.setTextColor(Color.parseColor("#FF5252"))
        }

        val stabilityTier = when {
            stability >= 85 -> "[OPTIMAL]"
            stability >= 70 -> "[STABLE]"
            stability >= 50 -> "[GUARDED]"
            stability >= 30 -> "[VULNERABLE]"
            else -> "[VETO / CRASH]"
        }
        tvStabilityBanner.text = String.format(Locale.US, "STABILITY: %d/100 %s | CEIL: %d | BUD: %.0f PTS", stability, stabilityTier, ceiling, budgetPoints)
        tvStabilityBanner.setTextColor(
            when {
                stability >= 70 -> Color.parseColor("#00E676")
                stability >= 50 -> Color.parseColor("#FFEA00")
                stability >= 30 -> Color.parseColor("#FF9100")
                else -> Color.parseColor("#FF1744")
            }
        )

        if (mode == "COCKPIT_DARK") {
            tvMode.text = "[ COCKPIT DARK ]"
            tvMode.setTextColor(Color.parseColor("#757575"))
        } else {
            tvMode.text = "[ COCKPIT LIVE ]"
            tvMode.setTextColor(Color.parseColor("#00E676"))
        }
    }

    private fun checkPermissionsAndStartService() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            Toast.makeText(this, "Requesting OS Permissions...", Toast.LENGTH_SHORT).show()
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        } else {
            startHavenService()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            Toast.makeText(this, "Permissions Granted!", Toast.LENGTH_SHORT).show()
            startHavenService()
        } else {
            Toast.makeText(this, "Permission Denied in OS! Enable in App Settings", Toast.LENGTH_LONG).show()
        }
    }

    private fun startHavenService() {
        val serviceIntent = Intent(this, HavenPacingService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            btnArm.text = "ENGINE ACTIVE (ARMED)"
            btnArm.setTextColor(Color.parseColor("#00E676"))
            Toast.makeText(this, "Haven Engine Starting...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Service Start Error: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val KEY_DRIVE_EXPORT_URI = "drive_export_uri"
    }
}
