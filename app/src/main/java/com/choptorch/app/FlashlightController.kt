package com.choptorch.app

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log

/**
 * FlashlightController
 *
 * Controls the device flashlight using the modern CameraManager API.
 * Handles devices without flash gracefully.
 * Thread-safe toggle and explicit on/off methods.
 */
class FlashlightController(private val context: Context) {

    companion object {
        private const val TAG = "FlashlightController"
    }

    private val cameraManager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private var cameraId: String? = null
    private var isFlashlightOn = false
    private var isAvailable = false

    // Callback for torch state changes from system
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cId: String, enabled: Boolean) {
            if (cId == cameraId) {
                isFlashlightOn = enabled
                Log.d(TAG, "Torch state changed externally: $enabled")
            }
        }

        override fun onTorchModeUnavailable(cId: String) {
            if (cId == cameraId) {
                isFlashlightOn = false
                isAvailable = false
                Log.w(TAG, "Torch mode became unavailable")
            }
        }
    }

    init {
        initialize()
    }

    private fun initialize() {
        try {
            // Find the rear-facing camera with a flash unit
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)

                if (hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id
                    isAvailable = true
                    Log.d(TAG, "Flash camera found: id=$id")
                    break
                }
            }

            if (!isAvailable) {
                // Fallback: use any camera with flash
                for (id in cameraManager.cameraIdList) {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    if (hasFlash) {
                        cameraId = id
                        isAvailable = true
                        Log.d(TAG, "Fallback flash camera found: id=$id")
                        break
                    }
                }
            }

            if (isAvailable) {
                cameraManager.registerTorchCallback(torchCallback, null)
            } else {
                Log.w(TAG, "No flash hardware found on this device")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FlashlightController", e)
            isAvailable = false
        }
    }

    /**
     * Toggle flashlight state.
     * @return new state (true = ON, false = OFF), or null if unavailable
     */
    fun toggle(): Boolean? {
        if (!isAvailable || cameraId == null) {
            Log.w(TAG, "Flash not available, cannot toggle")
            return null
        }
        return if (isFlashlightOn) {
            turnOff()
            false
        } else {
            turnOn()
            true
        }
    }

    fun turnOn() {
        if (!isAvailable || cameraId == null) return
        try {
            cameraManager.setTorchMode(cameraId!!, true)
            isFlashlightOn = true
            Log.d(TAG, "Flashlight ON")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to turn on flashlight", e)
        }
    }

    fun turnOff() {
        if (!isAvailable || cameraId == null) return
        try {
            cameraManager.setTorchMode(cameraId!!, false)
            isFlashlightOn = false
            Log.d(TAG, "Flashlight OFF")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to turn off flashlight", e)
        }
    }

    fun isOn(): Boolean = isFlashlightOn

    fun hasFlash(): Boolean = isAvailable

    fun release() {
        try {
            if (isFlashlightOn) turnOff()
            cameraManager.unregisterTorchCallback(torchCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing FlashlightController", e)
        }
    }
}
