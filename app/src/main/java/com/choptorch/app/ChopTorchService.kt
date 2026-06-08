package com.choptorch.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.SensorManager
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.localbroadcastmanager.content.LocalBroadcastManager

/**
 * ChopTorchService
 *
 * Foreground service that:
 *  - Registers sensor listeners for gesture detection
 *  - Controls the flashlight on gesture events
 *  - Vibrates on toggle
 *  - Holds a WakeLock so sensors keep working with screen OFF
 *  - Provides notification with Stop and Toggle Flashlight actions
 */
class ChopTorchService : LifecycleService() {

    companion object {
        private const val TAG = "ChopTorchService"

        const val CHANNEL_ID = "choptorch_channel"
        const val CHANNEL_ID_SILENT = "choptorch_channel_silent"
        const val NOTIFICATION_ID = 1001

        const val ACTION_STOP = "com.choptorch.app.STOP"
        const val ACTION_TOGGLE_FLASH = "com.choptorch.app.TOGGLE_FLASH"

        const val EXTRA_FROM_BOOT = "from_boot"
        const val EXTRA_SENSITIVITY = "sensitivity"

        // LocalBroadcast actions to update MainActivity
        const val BROADCAST_STATUS_CHANGED = "com.choptorch.app.STATUS_CHANGED"
        const val EXTRA_IS_RUNNING = "is_running"
        const val EXTRA_FLASH_STATE = "flash_state"

        // WakeLock tag
        private const val WAKE_LOCK_TAG = "FlashlightShake:SensorWakeLock"
    }

    private lateinit var gestureDetector: ChopGestureDetector
    private lateinit var flashlightController: FlashlightController

    private var wakeLock: PowerManager.WakeLock? = null
    private var vibrator: Vibrator? = null

    private var isFlashlightOn = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        createNotificationChannel()

        flashlightController = FlashlightController(this)

        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gestureDetector = ChopGestureDetector(sensorManager) {
            onChopGestureDetected()
        }

        vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "Stop action received")
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_TOGGLE_FLASH -> {
                Log.d(TAG, "Toggle flash action received")
                toggleFlashlight()
                return START_STICKY
            }
        }

        // Read sensitivity from intent (from boot or MainActivity)
        val sensitivity = intent?.getFloatExtra(EXTRA_SENSITIVITY, 0.5f) ?: 0.5f
        gestureDetector.setSensitivity(sensitivity)

        // Acquire WakeLock to keep sensors active with screen OFF
        acquireWakeLock()

        // Start foreground with persistent notification
        startForeground(NOTIFICATION_ID, buildNotification())

        // Start gesture detection
        gestureDetector.start()

        Log.d(TAG, "Service started. Sensitivity: $sensitivity")
        broadcastStatus(true)

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        gestureDetector.stop()
        flashlightController.release()
        releaseWakeLock()

        broadcastStatus(false)

        // Save service state as disabled
        getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(BootReceiver.KEY_SERVICE_ENABLED, false)
            .apply()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    // ── Gesture Callback ───────────────────────────────────────────────────

    private fun onChopGestureDetected() {
        Log.d(TAG, "Chop gesture detected!")
        toggleFlashlight()
    }

    private fun toggleFlashlight() {
        val newState = flashlightController.toggle()
        if (newState != null) {
            isFlashlightOn = newState
            vibrate()
            // No notification update needed — notification content is now state-independent.
            broadcastStatus(true)
        }
    }

    // ── Vibration ──────────────────────────────────────────────────────────

    private fun vibrate() {
        try {
            val effect = VibrationEffect.createOneShot(
                if (isFlashlightOn) 100L else 50L,
                VibrationEffect.DEFAULT_AMPLITUDE
            )
            vibrator?.vibrate(effect)
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed", e)
        }
    }

    // ── WakeLock ───────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG
        ).also {
            it.acquire(24 * 60 * 60 * 1000L) // 24 hour max (service restart reacquires)
        }
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        Log.d(TAG, "WakeLock released")
    }

    // ── Notification ───────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Single silent channel used for ALL states (ON and OFF).
        // IMPORTANCE_MIN = no sound, no peek, no badge, collapsed by default.
        val silentChannel = NotificationChannel(
            CHANNEL_ID_SILENT,
            "ChopTorch Running",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Required system indicator"
            setShowBadge(false)
            setSound(null, null)
            enableLights(false)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }

        nm.createNotificationChannel(silentChannel)
        // Delete the old high-importance channel so it no longer appears in settings.
        nm.deleteNotificationChannel(CHANNEL_ID)
    }

    private fun buildNotification(): Notification {
        // Mandatory foreground notification required by Android for foreground services.
        // Configured for maximum invisibility: no sound, no vibration, no badge,
        // hidden from lock screen, lowest possible priority, no action buttons.
        return NotificationCompat.Builder(this, CHANNEL_ID_SILENT)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true)
            .setShowWhen(false)
            .build()
    }

    // updateNotification() removed — notification is state-independent and never needs updating.
    // ── Local Broadcast ────────────────────────────────────────────────────

    private fun broadcastStatus(isRunning: Boolean) {
        val intent = Intent(BROADCAST_STATUS_CHANGED).apply {
            putExtra(EXTRA_IS_RUNNING, isRunning)
            putExtra(EXTRA_FLASH_STATE, isFlashlightOn)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    // ── Sensitivity Update ─────────────────────────────────────────────────

    fun updateSensitivity(level: Float) {
        gestureDetector.setSensitivity(level)
    }
}
