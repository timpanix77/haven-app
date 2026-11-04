package com.haven.pacing

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.haven.pacing.data.HavenDatabase
import com.haven.pacing.data.VaultManager
import com.haven.pacing.service.HavenPacingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var database: HavenDatabase
    private lateinit var vaultManager: VaultManager

    private lateinit var tvLiveHr: TextView
    private lateinit var tvExcursion: TextView
    private lateinit var tvDailyDebt: TextView
    private lateinit var tvMode: TextView
    private lateinit var btnArm: Button

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { restoreFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvLiveHr = findViewById(R.id.tvLiveHr)
        tvExcursion = findViewById(R.id.tvExcursion)
        tvDailyDebt = findViewById(R.id.tvDailyDebt)
        tvMode = findViewById(R.id.tvMode)
        btnArm = findViewById(R.id.btnArmService)

        database = HavenDatabase.getInstance(this)
        vaultManager = VaultManager(database)

        requestRequiredPermissions()
        requestBatteryOptimizationExemption()

        btnArm.setOnClickListener {
            val intent = Intent(this, HavenPacingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            btnArm.text = "ENGINE ACTIVE (ARMED)"
            btnArm.setTextColor(Color.parseColor("#43A047"))
            Toast.makeText(this, "HAVEN Foreground Service Armed", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnExportVault).setOnClickListener {
            scope.launch {
                val file = vaultManager.exportVault(this@MainActivity)
                Toast.makeText(this@MainActivity, "Vault Exported: ${file.name}", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.btnImportVault).setOnClickListener {
            filePickerLauncher.launch("application/json")
        }
    }

    override fun onResume() {
        super.onResume()
        if (HavenPacingService.isServiceRunning) {
            btnArm.text = "ENGINE ACTIVE (ARMED)"
            btnArm.setTextColor(Color.parseColor("#43A047"))
        }

        // Live 1 Hz subscriber to the running background engine
        HavenPacingService.onTelemetryUpdate = { hr, excursionSec, debtSec, mode ->
            runOnUiThread {
                tvLiveHr.text = if (hr > 0) hr.toString() else "--"

                val exM = excursionSec / 60
                val exS = excursionSec % 60
                tvExcursion.text = String.format("%02d:%02d", exM, exS)

                val debtM = debtSec / 60
                val debtS = debtSec % 60
                tvDailyDebt.text = String.format("%02d:%02d", debtM, debtS)

                if (mode == "COCKPIT_DARK") {
                    tvMode.text = "[ 🌙 COCKPIT DARK ]"
                    tvMode.setTextColor(Color.parseColor("#757575"))
                } else {
                    tvMode.text = "[ ☀️ COCKPIT LIVE ]"
                    tvMode.setTextColor(Color.parseColor("#43A047"))
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Unhook UI callback when app is backgrounded to preserve GPU/screen cycles
        HavenPacingService.onTelemetryUpdate = null
    }

    private fun restoreFromUri(uri: Uri) {
        scope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }
                if (content != null) {
                    vaultManager.importVault(content)
                    Toast.makeText(this@MainActivity, "Vault Successfully Restored", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Import Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.POST_NOTIFICATIONS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 101)
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }
}
