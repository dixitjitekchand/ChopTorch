package com.choptorch.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * ServiceControlReceiver
 *
 * Handles intents from foreground notification action buttons:
 *  - Stop Service
 *  - Toggle Flashlight manually
 */
class ServiceControlReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ServiceControlReceiver"
        const val ACTION_STOP_SERVICE = "com.choptorch.app.ACTION_STOP_SERVICE"
        const val ACTION_TOGGLE_FLASHLIGHT = "com.choptorch.app.ACTION_TOGGLE_FLASHLIGHT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received: ${intent.action}")

        when (intent.action) {
            ACTION_STOP_SERVICE -> {
                val stopIntent = Intent(context, ChopTorchService::class.java).apply {
                    action = ChopTorchService.ACTION_STOP
                }
                context.startService(stopIntent)
            }

            ACTION_TOGGLE_FLASHLIGHT -> {
                val toggleIntent = Intent(context, ChopTorchService::class.java).apply {
                    action = ChopTorchService.ACTION_TOGGLE_FLASH
                }
                context.startService(toggleIntent)
            }
        }
    }
}
