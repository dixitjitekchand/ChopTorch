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

        // Original working values — do not lower these thresholds.
        // Entry at 2.5 rad/s catches the ramp-up of a real wrist twist without
        // triggering on ambient micro-motion or walking vibration.
        const val DEFAULT_ACCEL_THRESHOLD = 2.5f

        // Reversal confirmation at 2.0 rad/s. Must be high enough that noise
        // during the CHOP_DOWN coast phase cannot prematurely trigger reversal.
        const val DEFAULT_GYRO_THRESHOLD = 2.0f

        // 600 ms is the correct window for one full twist cycle.
        // Extending this to 800 ms allows corrupted partial-twist state to
        // persist longer and accumulate noise, causing missed double-chops.
        const val CHOP_WINDOW_MS = 600L

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
        // Original working sensitivity range.
        // level: 0.0 (slider left = High sensitivity) → 1.0 (slider right = Low sensitivity)
        // At level=0.0 (High): accel=6.0,  gyro=1.5  — triggers on gentle twists
        // At level=0.5 (Med): accel=13.0, gyro=3.75  — triggers on normal twists
        // At level=1.0 (Low): accel=20.0, gyro=6.0   — requires firm twists only
        // The [3,8]/[1.2,3.7] range made the entire slider too hair-triggered —
        // even "Low" sensitivity was too responsive, causing false positives from
        // walking, pocket movement, and natural hand motion.
        accelThreshold = 6.0f + (level * 14.0f)
        gyroThreshold = 1.5f + (level * 4.5f)
        Log.d(TAG, "Sensitivity updated: level=$level accel=$accelThreshold gyro=$gyroThreshold")
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
                if (elapsed > CHOP_WINDOW_MS) { resetState(clearChopTimestamp = true); return }

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
                if (elapsed > CHOP_WINDOW_MS) { resetState(clearChopTimestamp = true); return }

                // Twist complete when gz settles below 40% of entry threshold.
                // At default sensitivity accelThreshold≈5.5, this requires gz < 2.2 rad/s —
                // a realistic deceleration target reached naturally at the end of a wrist twist.
                // The 0.15 multiplier lowered this to ~0.83 rad/s which requires the wrist
                // to almost fully stop before completing, causing CHOP_UP to time out at
                // CHOP_WINDOW_MS, triggering resetState(clearChopTimestamp=true), wiping the
                // inter-chop timestamp, and making double-chop detection impossible.
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

    private fun resetState(clearChopTimestamp: Boolean = false) {
        chopPhase = ChopPhase.IDLE
        chopPhaseStartMs = 0L
        gyroConfirmed = false
        lastGyroMagnitude = 0f
        firstTwistSign = 0
        // Only clear the inter-chop timestamp when explicitly requested (timeout).
        // A completed chop must preserve its timestamp for double-chop detection.
        if (clearChopTimestamp) lastChopCompletedMs = 0L
    }
}
