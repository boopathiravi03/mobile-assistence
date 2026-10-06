package com.boopathi.bg.service.tool

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

class OpenSettingsTool(
    private val context: Context
) : Tool {

    companion object {
        const val NAME = "OPEN_SETTINGS"
        private const val TAG = "OpenSettingsTool"
    }

    override val name: String = NAME
    override val description: String = "Opens Android Settings screens (General, WiFi, Bluetooth, Display)"
    override val riskLevel: RiskLevel = RiskLevel.LOW

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val target = (parameters["target"] as? String)?.lowercase() ?: "general"

        val (intentAction, displayName) = when {
            target.contains("wifi") || target.contains("wi-fi") ->
                Pair(Settings.ACTION_WIFI_SETTINGS, "Wi-Fi")
            target.contains("bluetooth") ->
                Pair(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth")
            target.contains("display") || target.contains("screen") ->
                Pair(Settings.ACTION_DISPLAY_SETTINGS, "Display")
            else ->
                Pair(Settings.ACTION_SETTINGS, "System")
        }

        return try {
            val intent = Intent(intentAction).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "Opened $displayName settings screen via $intentAction")
            ToolResult(
                status = ToolStatus.SUCCESS,
                message = "Opening $displayName settings, Boopathi.",
                toolName = name,
                action = NAME,
                target = displayName,
                data = mapOf("screen" to displayName, "action" to intentAction)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open settings: ${e.message}", e)
            ToolResult(
                status = ToolStatus.FAILED,
                message = "I couldn't open the $displayName settings.",
                toolName = name,
                action = NAME,
                target = displayName,
                data = mapOf("error" to (e.message ?: "Failed to open settings"))
            )
        }
    }
}

