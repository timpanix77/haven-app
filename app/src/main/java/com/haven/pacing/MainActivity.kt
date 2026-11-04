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

    private val hudReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == HavenPacingService.ACTION_HUD_UPDATE) {
                val hr = intent.getIntExtra(HavenPacingService.EXTRA_HR, 0)
                val mode = intent.getStringExtra(HavenPacingService.EXTRA_MODE) ?: "COCKPIT_LIVE"
                val excSec = intent.getIntExtra(HavenPacingService.EXTRA_EXCURSION_SEC, 0)
                val peakHr = intent.getIntExtra(HavenPacingService.EXTRA_PEAK_HR, 0)
                val totalSec = intent.getIntExtra(HavenPacingService.EXTRA_DAILY_TOTAL_SEC, 0)
                val budgetSec = intent.getIntExtra(HavenPacingService.EXTRA_BUDGET_SEC, 1200)
                val stability = intent.getIntExtra(HavenPacingService.EXTRA_STABILITY_SCORE, 75)
                val ceiling = intent.getIntExtra(HavenPacingService.EXTRA_ACTIVE_CEILING, 88)

                renderTelemetry(hr, excSec, peakHr, totalSec, budgetSec, stability, ceiling, mode)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        vaultManager = VaultManager(HavenDatabase.getInstance(this))
        checkPermissionsAndStartService()
    }

    private fun initViews() {
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

        btnArm.setOnClickListener {
            checkPermissionsAndStartService()
        }

        btnExport.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val file = vaultManager.exportVault(this@MainActivity)
                withContext(Dispatchers.Main) {
                    if (file != null) {
                        Toast.makeText(this@MainActivity, "Vault Exported: ${file.name}", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Export Failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnRestore.setOnClickListener {
            Toast.makeText(this, "Place JSON file in Downloads to Restore", Toast.LENGTH_SHORT).show()
        }
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
                renderTelemetry(hr, excSec, 0, totalSec, 1200, 75, 88, mode)
            }
        }

        if (HavenPacingService.isServiceRunning) {
            btnArm.text = "ENGINE ACTIVE (ARMED)"
            btnArm.setTextColor(Color.parseColor("#00E676"))
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(hudReceiver)
        } catch (_: Exception) {}
        HavenPacingService.onTelemetryUpdate = null
    }

    private fun renderTelemetry(
        hr: Int,
        excSec: Int,
        peakHr: Int,
        totalSec: Int,
        budgetSec: Int,
        stability: Int,
        ceiling: Int,
        mode: String
    ) {
        // 1. Live BPM and Proximity Bar
        if (hr > 0) {
            tvLiveHr.text = hr.toString()
            vuHr.setLevel((hr - 40).coerceAtLeast(0).toFloat(), 70f) // 40-110 BPM range
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
                    tvLiveHr.setTextColor(Color.parseColor("#FF1744"))
                    tvHrStatus.text = "BPM / CEILING BREACH ($ceiling)"
                }
            }
        } else {
            tvLiveHr.text = "--"
            tvLiveHr.setTextColor(Color.parseColor("#8E8E93"))
        }

        // 2. Peak Hold Readout
        if (peakHr > 0) {
            tvPeakIndicator.text = "PEAK: $peakHr BPM"
        }

        // 3. Excursion Stopwatch & Latched Settlement
        if (excSec > 0) {
            lastRecordedExcursionSec = excSec
            val m = excSec / 60
            val s = excSec % 60
            tvExcursion.text = String.format("%02d:%02d", m, s)
            tvExcursion.setTextColor(Color.parseColor("#FF9100"))

            vuGrace.setLevel(excSec.coerceAtMost(120).toFloat(), 120f)
            val remainingGrace = (120 - excSec).coerceAtLeast(0)
            tvBufferStatus.text = "ACTIVE (${remainingGrace}s LEFT)"
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

        // 4. Budget Burn & Remaining Time
        val budgetMin = budgetSec / 60
        tvBudgetLabel.text = "DAILY BUDGET (${budgetMin}m CAP)"
        vuBudget.setLevel(totalSec.toFloat(), budgetSec.toFloat())

        val remainingSec = budgetSec - totalSec
        if (remainingSec >= 0) {
            val rM = remainingSec / 60
            val rS = remainingSec % 60
            tvBudgetRemaining.text = String
