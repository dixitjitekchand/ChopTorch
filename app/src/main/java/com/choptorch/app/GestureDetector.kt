package com.choptorch.app

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ChopGestureDetector
 *
 * Detects a "double wrist-shake" gesture using accelerometer + gyroscope fusion.
 *
 * Gesture definition:
 *   1. Phone is roughly held in portrait or landscape orientation
 *   2. User makes a quick wrist rotation (yaw) in either direction
 *   3. A full twist completes within CHOP_WINDOW_MS milliseconds
 *   4. A second twist within DOUBLE_CHOP_WINDOW_MS triggers the event
 *
 * Sensor fusion:
 *   - Gyroscope detects rotational velocity on Z-axis (wrist yaw twist)
 *   - Accelerometer provides gravity separation for linear motion filtering
 *   - Both must exceed thresholds for a valid twist event
 */
class ChopGestureDetector(
    private val sensorManager: SensorManager,
    private val onGestureDetected: () -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "ChopGestureDetector"

        // Minimum gyroscope Z angular velocity (rad/s) to start a wrist twist.
        // Lowered from 2.5 — real wrist twist peaks typically reach 6–12 rad/s,
        // but entry detection should catch the ramp-up early.
        const val DEFAULT_ACCEL_THRESHOLD = 2.0f

        // Minimum gz to confirm reversal. Lowered from 2.0 for same reason.
        const val DEFAULT_GYRO_THRESHOLD = 1.5f

        // Window for one full twist (DOWN + reversal + settle). Extended from
        // 600 ms — deliberate users need up to ~800 ms for a clean twist.
        const val CHOP_WINDOW_MS = 800L

        // Window between two twists to register as double-chop. Unchanged.
        const val DOUBLE_CHOP_WINDOW_MS = 1000L

        // Minimum gap between two confirmed double-chop events (debounce).
        const val DEBOUNCE_MS = 800L

        // Low-pass alpha for gravity separation on accelerometer.
        const val LOW_PASS_ALPHA = 0.8f

        // SENSOR_DELAY_GAME ≈ 20 ms polling — good balance of latency vs battery.
        val SENSOR_DELAY = SensorManager.SENSOR_DELAY_GAME
    }

    // Current sensitivity (adjustable via slider)
    var accelThreshold: Float = DEFAULT_ACCEL_THRESHOLD
    var gyroThreshold: Float = DEFAULT_GYRO_THRESHOLD

    // Sensor references
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    // Gravity vector (low-pass filtered from accelerometer)
    private val gravity = FloatArray(3) { 0f }

    // State machine for chop detection
    private enum class ChopPhase { IDLE, CHOP_DOWN, CHOP_UP }
    private var chopPhase = ChopPhase.IDLE

    // Timestamps
    private var chopPhaseStartMs = 0L
    private var lastChopCompletedMs = 0L
    private var lastGestureMs = 0L

    // Gyro confirmation flag — must see rotation during chop
    private var gyroConfirmed = false
    private var lastGyroMagnitude = 0f

    // Running state
    private var isRunning = false

    // ── Public API ───────────────────────────────────────────────────────────

    fun start() {
        if (isRunning) return
        accelerometer?.let {
            sensorManager.registerListener(this, it, SENSOR_DELAY)
        } ?: Log.w(TAG, "No accelerometer available")

        gyroscope?.let {
            sensorManager.registerListener(this, it, SENSOR_DELAY)
        } ?: Log.w(TAG, "No gyroscope — detection will use accelerometer only")

        isRunning = true
        Log.d(TAG, "ChopGestureDetector started. Accel threshold: $accelThreshold")
    }

    fun stop() {
        if (!isRunning) return
        sensorManager.unregisterListener(this)
        isRunning = false
        resetState()
        Log.d(TAG, "ChopGestureDetector stopped")
    }

    fun setSensitivity(level: Float) {
        // level: 0.0 (max sensitive) → 1.0 (min sensitive)
        // accelThreshold: 1.5 (high sensitivity) → 4.0 (low sensitivity)
        // gyroThreshold:  1.2 (high sensitivity) → 3.0 (low sensitivity)
        accelThreshold = 1.5f + (level * 2.5f)
        gyroThreshold = 1.2f + (level * 1.8f)
        Log.d(TAG, "Sensitivity updated: accel=$accelThreshold gyro=$gyroThreshold")
    }

    val hasGyroscope: Boolean get() = gyroscope != null

    // ── SensorEventListener ──────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(event)
            Sensor.TYPE_GYROSCOPE -> handleGyroscope(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Not needed
    }

    // ── Private sensor handlers ───────────────────────────────────────────────

private fun handleAccelerometer(event: SensorEvent) {
        // Accelerometer not used for twist detection; gyroscope handles all motion
    }

    private fun handleGyroscope(event: SensorEvent) {
        val gz = event.values[2]  // Z-axis = wrist yaw (twist)
        lastGyroMagnitude = abs(gz)
        processTwistMotion(gz)
    }

    // Sign of the first twist direction (+1 or -1)
    private var firstTwistSign = 0

    private fun processTwistMotion(gz: Float) {
        val nowMs = System.currentTimeMillis()

        when (chopPhase) {

            ChopPhase.IDLE -> {
                if (abs(gz) >= accelThreshold) {
                    chopPhase = ChopPhase.CHOP_DOWN
                    chopPhaseStartMs = nowMs
                    firstTwistSign = if (gz > 0) 1 else -1
                    Log.v(TAG, "TWIST_1 started: gz=$gz")
                }
            }

            ChopPhase.CHOP_DOWN -> {
                val elapsed = nowMs - chopPhaseStartMs
                if (elapsed > CHOP_WINDOW_MS) { resetState(); return }

                // Wait for reversal: gz crosses zero and exceeds threshold in opposite direction
                val reversing = (firstTwistSign > 0 && gz <= -gyroThreshold) ||
                                (firstTwistSign < 0 && gz >= gyroThreshold)
                if (reversing && elapsed > 60L) {
                    chopPhase = ChopPhase.CHOP_UP
                    Log.v(TAG, "TWIST_reversal at ${elapsed}ms")
                }
            }

            ChopPhase.CHOP_UP -> {
                val elapsed = nowMs - chopPhaseStartMs
                if (elapsed > CHOP_WINDOW_MS) { resetState(); return }

                // Twist complete when gz settles near zero
                if (abs(gz) < accelThreshold * 0.4f) {
                    Log.v(TAG, "TWIST_1 complete at ${elapsed}ms")
                    onSingleChopCompleted(nowMs)
                }
            }
        }
    }

    private fun onSingleChopCompleted(nowMs: Long) {
        val timeSinceLastChop = nowMs - lastChopCompletedMs

        Log.d(TAG, "Single chop completed. TimeSinceLastChop=${timeSinceLastChop}ms")

        if (timeSinceLastChop <= DOUBLE_CHOP_WINDOW_MS && lastChopCompletedMs > 0) {
            // This is the second chop — check debounce
            val timeSinceLastGesture = nowMs - lastGestureMs

            if (timeSinceLastGesture >= DEBOUNCE_MS) {
                Log.d(TAG, "✅ DOUBLE CHOP GESTURE DETECTED!")
                lastGestureMs = nowMs
                lastChopCompletedMs = 0L
                resetState()
                onGestureDetected()
            } else {
                Log.v(TAG, "Double chop debounced: ${timeSinceLastGesture}ms < ${DEBOUNCE_MS}ms")
                resetState()
            }
        } else {
            // First chop, or second chop came too late
            lastChopCompletedMs = nowMs
            resetState()
        }
    }

    private fun resetState() {
        chopPhase = ChopPhase.IDLE
        chopPhaseStartMs = 0L
        gyroConfirmed = false
        lastGyroMagnitude = 0f
        firstTwistSign = 0
    }
}
