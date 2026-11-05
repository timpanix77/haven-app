package com.haven.pacing

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.haven.pacing.data.HavenDatabase
import com.haven.pacing.data.VaultManager
import com.haven.pacing.service.HavenPacingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val hudReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == HavenPacingService.ACTION_HUD_UPDATE) {
                val servicePeak = intent.getIntExtra(HavenPacingService.EXTRA_PEAK_HR, 0)
                renderTelemetry(
                    intent.getIntExtra(HavenPacingService.EXTRA_HR, 0),
                    intent.getIntExtra(HavenPacingService.EXTRA_EXCURSION_SEC, 0),
                    servicePeak,
                    intent.getIntExtra(HavenPacingService.EXTRA_DAILY_TOTAL_SEC, 0),
                    intent.getIntExtra(HavenPacingService.EXTRA_BUDGET_SEC, 1200),
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
                val count = vaultManager.restoreVault(this@MainActivity)
                withContext(Dispatchers.Main) {
                    val msg = if (count >= 0) "Restored $count Excursions from Downloads" else "No Vault Backup Found"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }

        renderTelemetry(0, 0, 0, 0, 1200, 75, 88, "COCKPIT_LIVE", "BLE: READY - TAP 'ARM' TO START")
        checkPermissionsAndStartService()
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
                renderTelemetry(hr, excSec, 0, totalSec, 1200, 75, 88, mode, "")
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
        totalSec: Int,
        budgetSec: Int,
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
            val peakColor = when {
                latchedPeakHr >= 115 -> Color.parseColor("#660000") // Dark Blood Crimson (Severe POTS Warning)
                latchedPeakHr >= ceiling -> Color.parseColor("#FF5252") // Alert Red
                latchedPeakHr >= 76 -> Color.parseColor("#FFEA00") // Warning Yellow
                else -> Color.parseColor("#00E676") // Green
            }
            tvPeakIndicator.setTextColor(
