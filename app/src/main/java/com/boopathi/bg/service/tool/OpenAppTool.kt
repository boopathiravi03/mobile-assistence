package com.boopathi.bg.service.tool

import android.content.Context
import android.content.Intent
import android.util.Log

class OpenAppTool(
    private val context: Context,
    private val appResolver: AppResolver = AppResolver(context)
) : Tool {

    companion object {
        const val NAME = "OPEN_APP"
        private const val TAG = "OpenAppTool"
    }

    override val name: String = NAME
    override val description: String = "Resolves and opens an installed Android application"
    override val riskLevel: RiskLevel = RiskLevel.LOW

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val appQuery = (parameters["appName"] as? String
            ?: parameters["app"] as? String
            ?: "").trim()

        if (appQuery.isEmpty()) {
            return ToolResult(
                status = ToolStatus.FAILED,
                message = "Which application would you like me to open, Boopathi?",
                toolName = name,
                action = NAME
            )
        }

        val resolved = appResolver.resolveApp(appQuery)
        if (resolved == null || !resolved.isInstalled) {
            Log.w(TAG, "Application '$appQuery' is not installed or could not be resolved.")
            return ToolResult(
                status = ToolStatus.UNAVAILABLE,
                message = "Boopathi, I couldn't find $appQuery on this phone.",
                toolName = name,
                action = NAME,
                target = appQuery,
                data = mapOf("app" to appQuery)
            )
        }

        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(resolved.packageName)
            if (launchIntent == null) {
                Log.w(TAG, "No launch intent found for package: ${resolved.packageName}")
                ToolResult(
                    status = ToolStatus.UNAVAILABLE,
                    message = "Boopathi, I couldn't find a launchable screen for ${resolved.appName}.",
                    toolName = name,
                    action = NAME,
                    target = resolved.appName,
                    data = mapOf("app" to resolved.appName, "package" to resolved.packageName)
                )
            } else {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                Log.i(TAG, "Successfully launched app: ${resolved.appName} (${resolved.packageName})")
                ToolResult(
                    status = ToolStatus.SUCCESS,
                    message = "Done, Boopathi. ${resolved.appName} is open.",
                    toolName = name,
                    action = NAME,
                    target = resolved.appName,
                    data = mapOf("app" to resolved.appName, "package" to resolved.packageName)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching application ${resolved.appName}: ${e.message}", e)
            ToolResult(
                status = ToolStatus.FAILED,
                message = "I couldn't open ${resolved.appName}.",
                toolName = name,
                action = NAME,
                target = resolved.appName,
                data = mapOf("app" to resolved.appName, "error" to (e.message ?: "Launch failed"))
            )
        }
    }
}

