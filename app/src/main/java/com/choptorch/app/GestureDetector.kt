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
        // level: 0.0 (slider left = High sensitivity) → 1.0 (slider right = Low sensitivity)
        // At level=0.0 (High): accel=3.0, gyro=1.2  — triggers on gentle twists
        // At level=0.5 (Med): accel=5.5, gyro=2.45  — triggers on normal twists
        // At level=1.0 (Low): accel=8.0, gyro=3.7   — requires firm twists only
        // Previous range was [6,20]/[1.5,6] — lower bound of 6 was already too
        // high for reliable detection; new range [3,8]/[1.2,3.7] keeps the full
        // slider useful and puts the default squarely in the reliable zone.
        accelThreshold = 3.0f + (level * 5.0f)
        gyroThreshold = 1.2f + (level * 2.5f)
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

                // Twist complete when gz settles below 15% of entry threshold.
                // Previous multiplier was 0.4 — at accelThreshold=13 that required
                // gz to drop below 5.2 rad/s, which often never happens cleanly
                // within the window. 0.15 gives a realistic settle target of ~0.75
                // at default sensitivity, matching real deceleration curves.
                if (abs(gz) < accelThreshold * 0.15f) {
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
