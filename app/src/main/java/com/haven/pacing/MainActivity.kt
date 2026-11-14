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
    private lateinit var btnArm: Button
    private lateinit var btnExport: Button
    private lateinit var btnRestore: Button

    private lateinit var vuHr: LedVuMeterView
    private lateinit var vuBudget: LedVuMeterView
    private lateinit var vuGrace: LedVuMeterView

    private lateinit var vaultManager: VaultManager
    private var lastRecordedExcursionSec: Int = 0

    // Indefinite Latched Peak Memory for POTS visual anchoring
    private var latchedPeakHr: Int = 0
    private var currentSurgePeakHr: Int = 0

    private val healthPermissions = setOf(
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class)
    )

    private val requestHealthPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(healthPermissions)) {
            Toast.makeText(this, "Health Connect Linked! Exporting 30-Day Vault...", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Health Connect Access Skipped — Exporting Excursions...", Toast.LENGTH_SHORT).show()
        }
        runFullVaultExport()
    }

    private val restoreFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                restoreFromUriWithFallback(uri)
            }
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
                val servicePeak = intent.getIntExtra(HavenPacingService.EXTRA_PEAK_HR, 0)
                val dailyPoints = intent.getDoubleExtra(HavenPacingService.EXTRA_DAILY_POINTS, 0.0)
                val budgetPoints = intent.getDoubleExtra(HavenPacingService.EXTRA_BUDGET_POINTS, 20.0)
                val excPoints = intent.getDoubleExtra(HavenPacingService.EXTRA_EXCURSION_POINTS, 0.0)

                renderTelemetry(
                    intent.getIntExtra(HavenPacingService.EXTRA_HR, 0),
                    intent.getIntExtra(HavenPacingService.EXTRA_EXCURSION_SEC, 0),
                    intent.getBooleanExtra(HavenPacingService.EXTRA_IN_EXCURSION, false),
                    servicePeak,
                    dailyPoints,
                    budgetPoints,
                    excPoints,
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

        btnArm.setOnClickListener {
            Toast.makeText(this, "Checking BLE & Permissions...", Toast.LENGTH_SHORT).show()
            checkPermissionsAndStartService()
        }

        btnExport.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val needsHealthPrompt = try {
                    val client = HealthConnectClient.getOrCreate(this@MainActivity)
                    val granted = client.permissionController.getGrantedPermissions()
                    !granted.containsAll(healthPermissions)
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

        // Long-press EXPORT VAULT to pick a different Google Drive folder anytime
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

    private fun runFullVaultExport() {
        lifecycleScope.launch(Dispatchers.IO) {
            vaultManager.exportVault(this@MainActivity)

            val savedUriStr = getSharedPreferences(HavenPacingService.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_DRIVE_EXPORT_URI, null)

            if (!savedUriStr.isNullOrEmpty()) {
                val uri = Uri.parse(savedUriStr)
                val synced = writeLatestExportToUri(uri)
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
        val candidates = listOf(
            "haven_vault_export.json",
            "haven_vault_backup.json",
            "haven_vault.json"
        )
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

            // 1. Pull 30 days of overnight HRV (rMSSD)
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
                    val rounded = Math.round(rec.heartRateVariabilityMillis * 10.0) / 10.0
                    entry.put("hrv", rounded)
                    dailyBioObj.put(key, entry)
                }
            } catch (_: Exception) { }

            // 2. Pull 30 days of Resting Heart Rate
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
        val candidates = listOf(
            "haven_vault_export.json",
            "haven_vault_backup.json",
            "haven_vault.json"
        )
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
        var count = -1
        try {
            count = vaultManager.restoreVaultFromUri(this@MainActivity, uri)
        } catch (_: Exception) {
            count = -1
        }

        if (count >= 0) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Restored $count Excursions!", Toast.LENGTH_SHORT).show()
            }
            return
        }

        try {
            val jsonText = contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().readText()
            }
            if (!jsonText.isNullOrEmpty()) {
                val fallbackCount = parseAndInsertExcursions(jsonText)
                withContext(Dispatchers.Main) {
                    if (fallbackCount >= 0) {
                        Toast.makeText(this@MainActivity, "Restored $fallbackCount Excursions!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Restore Failed: Empty or invalid vault", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Restore Failed: Could not read file stream", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers
