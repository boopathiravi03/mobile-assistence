package com.boopathi.bg.service.tool

import android.content.Context
import android.media.AudioManager
import android.util.Log

class VolumeTool(
    private val context: Context
) : Tool {

    companion object {
        const val NAME = "VOLUME"
        private const val TAG = "VolumeTool"
    }

    override val name: String = NAME
    override val description: String = "Adjusts media volume up, down, or to a specific level"
    override val riskLevel: RiskLevel = RiskLevel.LOW

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val audioManager = try {
            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        } catch (e: Exception) {
            null
        }

        if (audioManager == null) {
            Log.w(TAG, "AudioManager service unavailable.")
            return ToolResult(
                status = ToolStatus.UNAVAILABLE,
                message = "Volume control is not available on this device.",
                toolName = name,
                action = NAME
            )
        }

        val streamType = AudioManager.STREAM_MUSIC
        val maxVolume = audioManager.getStreamMaxVolume(streamType)

        val direction = (parameters["direction"] as? String)?.lowercase() ?: "increase"
        val explicitPercent = parameters["percent"] as? Int

        return try {
            if (explicitPercent != null) {
                val clamped = explicitPercent.coerceIn(0, 100)
                val targetIndex = ((clamped / 100f) * maxVolume).toInt()
                audioManager.setStreamVolume(streamType, targetIndex, AudioManager.FLAG_SHOW_UI)
                val updatedVol = audioManager.getStreamVolume(streamType)
                val finalPercent = ((updatedVol.toFloat() / maxVolume) * 100).toInt()
                Log.i(TAG, "Set media volume to $clamped% (index: $targetIndex / $maxVolume)")
                ToolResult(
                    status = ToolStatus.SUCCESS,
                    message = "Media volume set to $finalPercent percent, Boopathi.",
                    toolName = name,
                    action = NAME,
                    target = "$finalPercent%",
                    data = mapOf("volume" to updatedVol, "maxVolume" to maxVolume, "percent" to finalPercent)
                )
            } else {
                val adjustDirection = when (direction) {
                    "increase", "up", "raise" -> AudioManager.ADJUST_RAISE
                    "decrease", "down", "lower" -> AudioManager.ADJUST_LOWER
                    else -> AudioManager.ADJUST_SAME
                }

                audioManager.adjustStreamVolume(streamType, adjustDirection, AudioManager.FLAG_SHOW_UI)
                val updatedVol = audioManager.getStreamVolume(streamType)
                val finalPercent = ((updatedVol.toFloat() / maxVolume) * 100).toInt()
                val actionDesc = if (adjustDirection == AudioManager.ADJUST_RAISE) "increased" else "decreased"
                Log.i(TAG, "Volume $actionDesc to $finalPercent% (index: $updatedVol / $maxVolume)")
                ToolResult(
                    status = ToolStatus.SUCCESS,
                    message = "Volume $actionDesc to $finalPercent percent.",
                    toolName = name,
                    action = NAME,
                    target = if (adjustDirection == AudioManager.ADJUST_RAISE) "UP" else "DOWN",
                    data = mapOf("volume" to updatedVol, "maxVolume" to maxVolume, "percent" to finalPercent)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to adjust volume: ${e.message}", e)
            ToolResult(
                status = ToolStatus.FAILED,
                message = "I couldn't adjust the volume: ${e.message}",
                toolName = name,
                action = NAME,
                data = mapOf("error" to (e.message ?: "Audio error"))
            )
        }
    }
}

