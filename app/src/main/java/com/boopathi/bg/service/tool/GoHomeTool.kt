package com.boopathi.bg.service.tool

import android.content.Context
import android.content.Intent
import android.util.Log

class GoHomeTool(
    private val context: Context
) : Tool {

    companion object {
        const val NAME = "GO_HOME"
        private const val TAG = "GoHomeTool"
    }

    override val name: String = NAME
    override val description: String = "Navigates to the Android home screen"
    override val riskLevel: RiskLevel = RiskLevel.LOW

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        return try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(homeIntent)
            Log.i(TAG, "Navigated to home screen via ACTION_MAIN + CATEGORY_HOME")
            ToolResult(
                status = ToolStatus.SUCCESS,
                message = "Going home, Boopathi.",
                toolName = name,
                action = NAME,
                target = "HOME"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to navigate to home: ${e.message}", e)
            ToolResult(
                status = ToolStatus.FAILED,
                message = "I couldn't navigate to the home screen.",
                toolName = name,
                action = NAME,
                target = "HOME",
                data = mapOf("error" to (e.message ?: "Failed to go home"))
            )
        }
    }
}

