package com.example.limo_safe.Object

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.camera.core.Camera

class FlashlightControl(private val context: Context) {

    private var camera: Camera? = null


    fun toggleFlashlight(context: Context, turnOn: Boolean) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            // Iterate through available camera devices
            for (cameraId in cameraManager.cameraIdList) {
                val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
                // Check if the camera has a flash unit
                val hasFlash = cameraCharacteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (hasFlash) {
                    // Toggle the flashlight
                    cameraManager.setTorchMode(cameraId, turnOn)
                    break
                }
            }
        } catch (e: CameraAccessException) {
            // Handle exceptions
            e.printStackTrace()
        }
    }
}