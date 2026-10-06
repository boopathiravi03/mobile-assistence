package com.boopathi.bg.service.tool

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

class FlashlightTool(
    private val context: Context
) : Tool {

    companion object {
        const val NAME = "FLASHLIGHT"
        private const val TAG = "FlashlightTool"
    }

    override val name: String = NAME
    override val description: String = "Controls the device flashlight/torch (turn on or turn off)"
    override val riskLevel: RiskLevel = RiskLevel.LOW

    private var isTorchOn: Boolean = false

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val enable = (parameters["enable"] as? Boolean)
            ?: (parameters["state"] as? String)?.equals("on", ignoreCase = true)
            ?: true

        val cameraManager = try {
            context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        } catch (e: Exception) {
            null
        }

        if (cameraManager == null) {
            Log.w(TAG, "CameraManager service unavailable.")
            return ToolResult(
                status = ToolStatus.UNAVAILABLE,
                message = "Flashlight is not available on this device.",
                toolName = name,
                action = NAME,
                target = if (enable) "ON" else "OFF"
            )
        }

        val cameraId = try {
            cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query camera characteristics: ${e.message}")
            null
        }

        if (cameraId == null) {
            Log.w(TAG, "No camera with flash unit found on this device.")
            return ToolResult(
                status = ToolStatus.UNAVAILABLE,
                message = "Flashlight is not available on this device.",
                toolName = name,
                action = NAME,
                target = if (enable) "ON" else "OFF"
            )
        }

        return try {
            cameraManager.setTorchMode(cameraId, enable)
            isTorchOn = enable
            Log.i(TAG, "Torch mode set to $enable for camera $cameraId")
            ToolResult(
                status = ToolStatus.SUCCESS,
                message = if (enable) "Flashlight turned on, Boopathi." else "Flashlight turned off, Boopathi.",
                toolName = name,
                action = NAME,
                target = if (enable) "ON" else "OFF",
                data = mapOf("enabled" to enable, "cameraId" to cameraId)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set torch mode: ${e.message}", e)
            ToolResult(
                status = ToolStatus.FAILED,
                message = "I couldn't control the flashlight: ${e.message}",
                toolName = name,
                action = NAME,
                target = if (enable) "ON" else "OFF",
                data = mapOf("error" to (e.message ?: "Camera/Torch error"))
            )
        }
    }
}

