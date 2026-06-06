package com.choptorch.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * BootReceiver
 *
 * Listens for BOOT_COMPLETED and restarts the ChopTorchService if it was
 * previously enabled by the user. Uses SharedPreferences to persist the
 * user's preference across reboots.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        const val PREFS_NAME = "choptorch_prefs"
        const val KEY_SERVICE_ENABLED = "service_enabled"
        const val KEY_SENSITIVITY = "sensitivity"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received broadcast: $action")

        val isBootAction = action == Intent.ACTION_BOOT_COMPLETED ||
                action == "android.intent.action.QUICKBOOT_POWERON" ||
                action == "com.htc.intent.action.QUICKBOOT_POWERON"

        if (!isBootAction) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val serviceEnabled = prefs.getBoolean(KEY_SERVICE_ENABLED, false)

        Log.d(TAG, "Boot completed. Service was enabled: $serviceEnabled")

        if (serviceEnabled) {
            Log.d(TAG, "Restarting ChopTorchService after boot")
            val serviceIntent = Intent(context, ChopTorchService::class.java).apply {
                putExtra(ChopTorchService.EXTRA_FROM_BOOT, true)
                putExtra(
                    ChopTorchService.EXTRA_SENSITIVITY,
                    prefs.getFloat(KEY_SENSITIVITY, 0.5f)
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
