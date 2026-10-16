package com.boopathi.bg.service.tool

import android.util.Log

class ToolRegistry {

    companion object {
        private const val TAG = "ToolRegistry"
    }

    private val tools = mutableMapOf<String, Tool>()

    fun register(tool: Tool) {
        tools[tool.name.uppercase()] = tool
        Log.d(TAG, "Registered tool [${tool.name}] with risk [${tool.riskLevel}]")
    }

    fun getTool(name: String): Tool? {
        return tools[name.uppercase()]
    }

    fun getAllTools(): List<Tool> = tools.values.toList()

    suspend fun execute(toolName: String, parameters: Map<String, Any?> = emptyMap()): ToolResult {
        val tool = getTool(toolName)
        if (tool == null) {
            Log.w(TAG, "Attempted to execute unregistered tool: $toolName")
            return ToolResult(
                status = ToolStatus.UNAVAILABLE,
                message = "Tool '$toolName' is not registered.",
                toolName = toolName
            )
        }

        if (tool.riskLevel != RiskLevel.LOW) {
            Log.w(TAG, "Blocked tool [${tool.name}] due to risk level [${tool.riskLevel}]")
            return ToolResult(
                status = ToolStatus.FAILED,
                message = "Action '${tool.name}' has risk level ${tool.riskLevel}. Execution requires user authorization.",
                toolName = tool.name
            )
        }

        return try {
            Log.d(TAG, "Executing tool [${tool.name}] with ${parameters.size} parameters")
            tool.execute(parameters)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing tool [${tool.name}] (${e.javaClass.simpleName})")
            ToolResult(
                status = ToolStatus.FAILED,
                message = "Failed to execute ${tool.name}.",
                toolName = tool.name
            )
        }
    }
}

