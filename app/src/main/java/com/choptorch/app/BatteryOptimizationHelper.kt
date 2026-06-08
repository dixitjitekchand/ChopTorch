package com.choptorch.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * BatteryOptimizationHelper
 *
 * Handles battery optimization detection and prompting.
 * Required to keep the foreground service alive on aggressive OEM ROMs
 * (Xiaomi MIUI, Samsung One UI, Realme UI, Vivo FunTouch OS, etc.)
 */
object BatteryOptimizationHelper {

    private const val TAG = "BatteryOptHelper"

    /**
     * Returns true if battery optimization is ENABLED for this app (bad for background services).
     */
    fun isOptimizationEnabled(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoring = pm.isIgnoringBatteryOptimizations(context.packageName)
        return !isIgnoring
    }

    /**
     * Returns an Intent to open the system battery optimization settings for this app.
     * The user must manually disable optimization there.
     */
    fun buildExemptionIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    /**
     * Returns an Intent to open the general battery optimization list.
     * Fallback when the direct exemption intent is not available.
     */
    fun buildBatterySettingsIntent(): Intent {
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    /**
     * Returns a manufacturer-specific warning message for known aggressive OEMs.
     */
    fun getOemWarningMessage(): String? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") ->
                "MIUI detected: Go to Settings → Apps → Flashlight Shake → Battery Saver → No restrictions. " +
                "Also disable 'Battery optimization' in Settings → Battery & Performance."

            manufacturer.contains("samsung") ->
                "One UI detected: Go to Settings → Battery → Background usage limits → " +
                "Never sleeping apps → Add Flashlight Shake."

            manufacturer.contains("huawei") || manufacturer.contains("honor") ->
               "EMUI detected: Go to Settings → Apps → Flashlight Shake → Battery → " +
                "Allow background activity and disable Power-intensive prompt."

            manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") ->
             "ColorOS/OxygenOS detected: Go to Settings → Battery → Battery Optimization → " +
                "Find Flashlight Shake → Don't optimize."

            manufacturer.contains("vivo") ->
                "FunTouch OS detected: Go to Settings → Battery → Background Power Consumption → " +
                "Allow Flashlight Shake to run in background."

            manufacturer.contains("meizu") ->
                "Flyme detected: Security Center → Permissions → Background → Enable for Flashlight Shake."

            manufacturer.contains("asus") ->
                "ASUS detected: Settings → Battery → Auto-start manager → Enable Flashlight Shake."

            else -> null // Stock Android / Pixel — no extra steps needed
        }
    }

    /**
     * Returns true if this device is from a known aggressive OEM.
     */
    fun isAggressiveOem(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val aggressiveOems = listOf(
            "xiaomi", "redmi", "poco", "samsung", "huawei", "honor",
            "oppo", "realme", "oneplus", "vivo", "meizu", "asus", "lenovo"
        )
        return aggressiveOems.any { manufacturer.contains(it) }
    }

    /**
     * Returns device info string for debugging.
     */
    fun getDeviceInfo(): String {
        return "Manufacturer: ${Build.MANUFACTURER}, Model: ${Build.MODEL}, " +
               "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }
}
