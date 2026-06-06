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
 * Detects a "double-chop" gesture using accelerometer + gyroscope fusion.
 *
 * Gesture definition:
 *   1. Phone is roughly held in portrait or landscape orientation
 *   2. User makes a quick downward/forward wrist chop
 *   3. Immediately reverses direction (upward/backward)
 *   4. The full motion completes within CHOP_WINDOW_MS milliseconds
 *   5. A second chop within DOUBLE_CHOP_WINDOW_MS triggers the event
 *
 * Sensor fusion:
 *   - Accelerometer detects linear force spikes along Y-axis (chop axis)
 *   - Gyroscope detects rotational velocity to confirm wrist rotation
 *   - Both must exceed thresholds for a valid chop event
 */
class ChopGestureDetector(
    private val sensorManager: SensorManager,
    private val onGestureDetected: () -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "ChopGestureDetector"

        // ── Sensitivity Constants (tune these) ─────────────────────────────
        // Minimum linear acceleration (m/s²) to consider a chop motion.
        // Lower = more sensitive (more false positives)
        // Higher = less sensitive (requires harder chops)
        const val DEFAULT_ACCEL_THRESHOLD = 12.0f      // m/s²

        // Minimum gyroscope angular velocity (rad/s) to confirm wrist rotation
        const val DEFAULT_GYRO_THRESHOLD = 3.0f        // rad/s

        // Maximum time (ms) a single chop motion can take (down + reverse)
        const val CHOP_WINDOW_MS = 600L

        // Maximum time (ms) between two chops to count as a "double chop"
        const val DOUBLE_CHOP_WINDOW_MS = 1200L

        // Minimum time (ms) between two full double-chop events (debounce)
        const val DEBOUNCE_MS = 800L

        // Low-pass filter alpha for gravity separation
        const val LOW_PASS_ALPHA = 0.8f

        // Sensor sampling interval (SENSOR_DELAY_GAME = ~20ms, good balance)
        // Use SENSOR_DELAY_FASTEST for best detection, higher battery drain
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
        // Maps slider to threshold range [6, 20] for accel, [1.5, 6] for gyro
        accelThreshold = 6f + (level * 14f)
        gyroThreshold = 1.5f + (level * 4.5f)
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
        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]

        // Separate gravity using low-pass filter
        gravity[0] = LOW_PASS_ALPHA * gravity[0] + (1 - LOW_PASS_ALPHA) * ax
        gravity[1] = LOW_PASS_ALPHA * gravity[1] + (1 - LOW_PASS_ALPHA) * ay
        gravity[2] = LOW_PASS_ALPHA * gravity[2] + (1 - LOW_PASS_ALPHA) * az

        // Linear acceleration (gravity removed)
        val linX = ax - gravity[0]
        val linY = ay - gravity[1]
        val linZ = az - gravity[2]

        // Total linear acceleration magnitude
        val magnitude = sqrt(linX * linX + linY * linY + linZ * linZ)

        // Primary chop axis: Y-axis (device held portrait, chopping down)
        // Also check Z-axis for landscape-held devices
        val chopAxis = maxOf(abs(linY), abs(linZ))

        processChopMotion(chopAxis, magnitude, linY)
    }

    private fun handleGyroscope(event: SensorEvent) {
        val gx = event.values[0]
        val gy = event.values[1]
        val gz = event.values[2]

        lastGyroMagnitude = sqrt(gx * gx + gy * gy + gz * gz)

        // Confirm gyro rotation during active chop phase
        if (chopPhase != ChopPhase.IDLE && lastGyroMagnitude >= gyroThreshold) {
            gyroConfirmed = true
        }
    }

    private fun processChopMotion(chopAxis: Float, magnitude: Float, linY: Float) {
        val nowMs = System.currentTimeMillis()

        when (chopPhase) {

            ChopPhase.IDLE -> {
                // Look for initial chop impulse exceeding threshold
                if (chopAxis >= accelThreshold) {
                    chopPhase = ChopPhase.CHOP_DOWN
                    chopPhaseStartMs = nowMs
                    gyroConfirmed = hasGyroscope.not() // If no gyro, skip gyro check
                    Log.v(TAG, "CHOP_DOWN detected: chopAxis=$chopAxis")
                }
            }

            ChopPhase.CHOP_DOWN -> {
                val elapsed = nowMs - chopPhaseStartMs

                // Timeout — reset if motion took too long
                if (elapsed > CHOP_WINDOW_MS) {
                    Log.v(TAG, "CHOP_DOWN timed out")
                    resetState()
                    return
                }

                // Update gyro confirmation in this window
                if (lastGyroMagnitude >= gyroThreshold) {
                    gyroConfirmed = true
                }

                // Look for reversal: magnitude drops then spikes opposite direction,
                // or simply just drops below threshold and then rises again
                if (chopAxis >= accelThreshold && elapsed > 50L) {
                    // Check if this is a reversal (sign change on primary axis)
                    chopPhase = ChopPhase.CHOP_UP
                    Log.v(TAG, "CHOP_UP phase: elapsed=${elapsed}ms gyroOk=$gyroConfirmed")
                }
            }

            ChopPhase.CHOP_UP -> {
                val elapsed = nowMs - chopPhaseStartMs

                if (elapsed > CHOP_WINDOW_MS) {
                    Log.v(TAG, "CHOP_UP timed out")
                    resetState()
                    return
                }

                // Chop completed when magnitude drops below threshold
                if (magnitude < accelThreshold * 0.5f) {
                    // Require gyro confirmation if gyroscope is available
                    val gyroOk = !hasGyroscope || gyroConfirmed

                    if (gyroOk) {
                        onSingleChopCompleted(nowMs)
                    } else {
                        Log.v(TAG, "Chop rejected: insufficient wrist rotation")
                        resetState()
                    }
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
    }
}
