package com.choptorch.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.choptorch.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private var isServiceRunning = false

    // ── Permission Launchers ───────────────────────────────────────────────

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startChopTorchService()
        } else {
            Toast.makeText(
                this,
                "Notification permission needed for background service.",
                Toast.LENGTH_LONG
            ).show()
            startChopTorchService() // Start anyway, just without notification on older paths
        }
    }

    private val batteryOptLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateBatteryWarning()
    }

    // ── Local Broadcast Receiver ──────────────────────────────────────────

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val isRunning = intent.getBooleanExtra(ChopTorchService.EXTRA_IS_RUNNING, false)
            val flashState = intent.getBooleanExtra(ChopTorchService.EXTRA_FLASH_STATE, false)
            updateServiceUI(isRunning, flashState)
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        restorePreferences()
        updateServiceUI(isServiceCurrentlyRunning(), false)
        updateBatteryWarning()
        checkOemWarning()
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            statusReceiver,
            IntentFilter(ChopTorchService.BROADCAST_STATUS_CHANGED)
        )
        updateServiceUI(isServiceCurrentlyRunning(), false)
        updateBatteryWarning()
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
    }

    // ── UI Setup ───────────────────────────────────────────────────────────

    private fun setupUI() {
        // Start / Stop button
        binding.btnToggleService.setOnClickListener {
            if (isServiceRunning) {
                stopChopTorchService()
            } else {
                requestPermissionsAndStart()
            }
        }

        // Sensitivity slider
        binding.seekbarSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val level = progress / 100f
                updateSensitivityLabel(level)
                if (fromUser) saveSensitivity(level)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val level = seekBar.progress / 100f
                // If service is running, update it live
                if (isServiceRunning) {
                    val intent = Intent(this@MainActivity, ChopTorchService::class.java).apply {
                        action = "UPDATE_SENSITIVITY"
                        putExtra(ChopTorchService.EXTRA_SENSITIVITY, level)
                    }
                    startService(intent)
                }
            }
        })

        // Battery optimization button
        binding.btnFixBattery.setOnClickListener {
            showBatteryOptimizationDialog()
        }

        // How it works info button
        binding.btnHowTo.setOnClickListener {
            HelpBottomSheet().show(supportFragmentManager, "help")
        }
    }

    private fun restorePreferences() {
        val prefs = getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        val sensitivity = prefs.getFloat(BootReceiver.KEY_SENSITIVITY, 0.5f)
        val progress = (sensitivity * 100).toInt()
        binding.seekbarSensitivity.progress = progress
        updateSensitivityLabel(sensitivity)
    }

    private fun updateSensitivityLabel(level: Float) {
        val label = when {
            level < 0.33f -> "High (sensitive)"
            level < 0.66f -> "Medium"
            else -> "Low (firm chops)"
        }
        binding.tvSensitivityLabel.text = "Sensitivity: $label"
    }

    private fun updateServiceUI(running: Boolean, flashOn: Boolean) {
        isServiceRunning = running

        if (running) {
            binding.btnToggleService.text = "Stop Service"
            binding.btnToggleService.setBackgroundColor(
                ContextCompat.getColor(this, android.R.color.holo_red_dark)
            )
            binding.tvStatus.text = "● RUNNING"
            binding.tvStatus.setTextColor(
                ContextCompat.getColor(this, android.R.color.holo_green_dark)
            )
            binding.tvFlashState.text = if (flashOn) "💡 Flashlight: ON" else "🔦 Flashlight: OFF"
            binding.tvFlashState.visibility = View.VISIBLE
        } else {
            binding.btnToggleService.text = "Start Service"
            binding.btnToggleService.setBackgroundColor(
                ContextCompat.getColor(this, android.R.color.holo_green_dark)
            )
            binding.tvStatus.text = "○ STOPPED"
            binding.tvStatus.setTextColor(
                ContextCompat.getColor(this, android.R.color.darker_gray)
            )
            binding.tvFlashState.visibility = View.GONE
        }
    }

    private fun updateBatteryWarning() {
        val optimized = BatteryOptimizationHelper.isOptimizationEnabled(this)
        if (optimized) {
            binding.cardBatteryWarning.visibility = View.VISIBLE
        } else {
            binding.cardBatteryWarning.visibility = View.GONE
        }
    }

    private fun checkOemWarning() {
        val warning = BatteryOptimizationHelper.getOemWarningMessage()
        if (warning != null) {
            binding.tvOemWarning.text = warning
            binding.tvOemWarning.visibility = View.VISIBLE
        } else {
            binding.tvOemWarning.visibility = View.GONE
        }
    }

    // ── Service Control ────────────────────────────────────────────────────

    private fun requestPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> startChopTorchService()

                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    AlertDialog.Builder(this)
                        .setTitle("Notification Permission")
                        .setMessage("Flashlight Shake needs notification permission to show its status while running in the background.")
                        .setPositiveButton("Grant") { _, _ ->
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        .setNegativeButton("Skip") { _, _ -> startChopTorchService() }
                        .show()
                }

                else -> notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            startChopTorchService()
        }
    }

    private fun startChopTorchService() {
        val sensitivity = binding.seekbarSensitivity.progress / 100f
        val intent = Intent(this, ChopTorchService::class.java).apply {
            putExtra(ChopTorchService.EXTRA_SENSITIVITY, sensitivity)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        // Save enabled state for boot receiver
        getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(BootReceiver.KEY_SERVICE_ENABLED, true)
            .putFloat(BootReceiver.KEY_SENSITIVITY, sensitivity)
            .apply()

        updateServiceUI(true, false)

        if (BatteryOptimizationHelper.isOptimizationEnabled(this)) {
            showBatteryOptimizationDialog()
        }
    }

    private fun stopChopTorchService() {
        val intent = Intent(this, ChopTorchService::class.java).apply {
            action = ChopTorchService.ACTION_STOP
        }
        startService(intent)

        getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(BootReceiver.KEY_SERVICE_ENABLED, false)
            .apply()

        updateServiceUI(false, false)
    }

    private fun isServiceCurrentlyRunning(): Boolean {
        // Check shared prefs as proxy — service broadcasts on create/destroy
        val prefs = getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(BootReceiver.KEY_SERVICE_ENABLED, false)
    }

    private fun saveSensitivity(level: Float) {
        getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(BootReceiver.KEY_SENSITIVITY, level)
            .apply()
    }

    // ── Dialogs ────────────────────────────────────────────────────────────

    private fun showBatteryOptimizationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Disable Battery Optimization")
            .setMessage(
                "To ensure Flashlight Shake keeps working in the background (especially when the screen is off), " +
                "please disable battery optimization for this app.\n\n" +
                "Tap OK to open settings."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    batteryOptLauncher.launch(
                        BatteryOptimizationHelper.buildExemptionIntent(this)
                    )
                } catch (e: Exception) {
                    batteryOptLauncher.launch(
                        BatteryOptimizationHelper.buildBatterySettingsIntent()
                    )
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    // showHowToDialog replaced by HelpBottomSheet
}
