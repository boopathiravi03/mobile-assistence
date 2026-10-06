package com.boopathi.bg.service.tool

interface Tool {
    val name: String
    val description: String
    val riskLevel: RiskLevel

    suspend fun execute(parameters: Map<String, Any?> = emptyMap()): ToolResult
}

