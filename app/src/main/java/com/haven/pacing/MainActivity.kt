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
                    servicePeak,
                    dailyPoints,
                    budgetPoints,
                    excPoints,
                    intent.getIntExtra(HavenPacingService.EXTRA_STABILITY_SCORE, 75),
                    intent.getIntExtra(HavenPacingService.EXTRA_ACTIVE_CEILING, 88),
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
                val success = vaultManager.exportVault(this@MainActivity)
                withContext(Dispatchers.Main) {
                    val msg = if (success) "Vault Exported to Downloads!" else "Export Failed"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
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

        renderTelemetry(0, 0, 0, 0.0, 20.0, 0.0, 75, 88, "COCKPIT_LIVE", "BLE: READY - TAP 'ARM' TO START")
        checkPermissionsAndStartService()
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
                    } catch (e: Exception) {
                        // Skip unreadable files and fall back to picker
                    }
                }
            }
        }
        return -1
    }

    private suspend fun restoreFromUriWithFallback(uri: Uri) {
        var count = -1
        try {
            count = vaultManager.restoreVaultFromUri(this@MainActivity, uri)
        } catch (e: Exception) {
            count = -1
        }

        if (count >= 0) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Restored $count Excursions!", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Bulletproof Stream Fallback: Direct content:// stream reading & flexible JSON decoding
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
                        val key = keys.next()
                        val v = obj.optJSONArray(key)
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
            val ts = item.optLong("timestamp", item.optLong("time", System.currentTimeMillis()))
            val dur = item.optInt("durationSeconds", item.optInt("duration", 0))
            val peak = item.optInt("peakHr", item.optInt("peak", 0))
            val settled = item.optInt("settledHr", item.optInt("settled", 0))
            val strain = item.optDouble("strainPoints", item.optDouble("points", 0.0))

            val record = ExcursionRecord(
                timestamp = ts,
                durationSeconds = dur,
                peakHr = peak,
                settledHr = settled,
                strainPoints = strain
            )
            db.havenDao().insert(record)
            count++
        }
        return count
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(HavenPacingService.ACTION_HUD_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(hudReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(hudReceiver, filter)
        }

        HavenPacingService.onTelemetryUpdate = { hr, excSec, totalSec, mode ->
            runOnUiThread {
                renderTelemetry(hr, excSec, 0, totalSec / 60.0, 20.0, 0.0, 75, 88, mode, "")
            }
        }

        if (HavenPacingService.isServiceRunning) {
            btnArm.text = "ENGINE ACTIVE (ARMED)"
            btnArm.setTextColor(Color.parseColor("#00E676"))
        } else {
            btnArm.text = "START PACING ENGINE"
            btnArm.setTextColor(Color.WHITE)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(hudReceiver)
        } catch (e: Exception) {}
        HavenPacingService.onTelemetryUpdate = null
    }

    private fun renderTelemetry(
        hr: Int,
        excSec: Int,
        servicePeakHr: Int,
        dailyPoints: Double,
        budgetPoints: Double,
        excPoints: Double,
        stability: Int,
        ceiling: Int,
        mode: String,
        bleStatus: String = ""
    ) {
        // 1. Live BPM and Proximity Bar
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
            tvHrStatus.text = if (bleStatus.isNotEmpty()) bleStatus else "BLE: SEARCHING..."
        }

        // 2. Indefinite Peak Latching Logic for POTS Visual Reminders
        if (excSec > 0) {
            if (currentSurgePeakHr == 0 || hr > currentSurgePeakHr) {
                currentSurgePeakHr = hr
            }
            latchedPeakHr = currentSurgePeakHr
        } else {
            currentSurgePeakHr = 0
        }

        if (servicePeakHr > latchedPeakHr) {
            latchedPeakHr = servicePeakHr
        }
        if (hr >= ceiling && hr > latchedPeakHr) {
            latchedPeakHr = hr
        }

        // Display latched peak indefinitely with Dark Blood Crimson at 115+ BPM
        if (latchedPeakHr > 0) {
            tvPeakIndicator.text = "PEAK: " + latchedPeakHr + " BPM"
            if (latchedPeakHr >= 115) {
                tvPeakIndicator.setTextColor(Color.parseColor("#660000"))
            } else if (latchedPeakHr >= ceiling) {
                tvPeakIndicator.setTextColor(Color.parseColor("#FF5252"))
            } else if (latchedPeakHr >= 76) {
                tvPeakIndicator.setTextColor(Color.parseColor("#FFEA00"))
            } else {
                tvPeakIndicator.setTextColor(Color.parseColor("#00E676"))
            }
        } else {
            tvPeakIndicator.text = "PEAK: --"
            tvPeakIndicator.setTextColor(Color.parseColor("#8E8E93"))
        }

        // 3. Excursion Stopwatch & Grace Buffer
        if (excSec > 0) {
            lastRecordedExcursionSec = excSec
            val m = excSec / 60
            val s = excSec % 60
            tvExcursion.text = String.format("%02d:%02d", m, s)
            tvExcursion.setTextColor(Color.parseColor("#FF9100"))

            vuGrace.setLevel(excSec.coerceAtMost(120).toFloat(), 120f)
            val remainingGrace = (120 - excSec).coerceAtLeast(0)
            tvBufferStatus.text = "ACTIVE (" + remainingGrace + "s LEFT)"
            tvBufferStatus.setTextColor(Color.parseColor("#FF9100"))
        } else {
            val m = lastRecordedExcursionSec / 60
            val s = lastRecordedExcursionSec % 60
            tvExcursion.text = String.format("%02d:%02d", m, s)
            tvExcursion.setTextColor(Color.WHITE)

            vuGrace.setLevel(0f, 120f)
            tvBufferStatus.text = "STANDBY"
            tvBufferStatus.setTextColor(Color.parseColor("#636366"))
        }

        // 4. Weighted PacePoint Budget Burn & Remaining Points
        tvBudgetLabel.text = String.format("PACE POINTS (%.0f CAP)", budgetPoints)
        vuBudget.setLevel(dailyPoints.toFloat(), budgetPoints.toFloat())

        val remainingPoints = budgetPoints - dailyPoints
        if (remainingPoints >= 0) {
            tvBudgetRemaining.text = String.format("%.1f PTS", remainingPoints)
            tvBudgetRemaining.setTextColor(Color.parseColor("#00E676"))

            if (dailyPoints >= budgetPoints * 0.75) {
                tvPaceStatus.text = "PACE: GUARDED"
                tvPaceStatus.setTextColor(Color.parseColor("#FF9100"))
            } else {
                tvPaceStatus.text = "PACE: ON TRACK"
                tvPaceStatus.setTextColor(Color.parseColor("#00E676"))
            }
        } else {
            val debtPoints = -remainingPoints
            tvBudgetRemaining.text = String.format("-%.1f PTS", debtPoints)
            tvBudgetRemaining.setTextColor(Color.parseColor("#FF5252"))
            tvPaceStatus.text = "PACE: OVER BUDGET"
            tvPaceStatus.setTextColor(Color.parseColor("#FF5252"))
        }

        // 5. Morning Stability Panel (0-100)
        val stabilityTier = when {
            stability >= 85 -> "[OPTIMAL]"
            stability >= 70 -> "[STABLE]"
            stability >= 50 -> "[GUARDED]"
            stability >= 30 -> "[VULNERABLE]"
            else            -> "[VETO / CRASH]"
        }
        tvStabilityBanner.text = String.format("STABILITY: %d/100 %s | CEIL: %d | BUD: %.0f PTS", stability, stabilityTier, ceiling, budgetPoints)
        val bannerColor = when {
            stability >= 70 -> Color.parseColor("#00E676")
            stability >= 50 -> Color.parseColor("#FFEA00")
            stability >= 30 -> Color.parseColor("#FF9100")
            else            -> Color.parseColor("#660000")
        }
        tvStabilityBanner.setTextColor(bannerColor)

        // 6. Cockpit Mode
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
}
