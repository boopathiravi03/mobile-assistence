package com.boopathi.bg.data.model

import com.boopathi.bg.service.agent.AppActionResult
import com.boopathi.bg.service.tool.ToolResult

data class CommandResult(
    val textResponse: String,
    val spokenResponse: String = textResponse,
    val providerName: String,
    val isSuccess: Boolean = true,
    val toolResult: ToolResult? = null,
    val appActionResult: AppActionResult? = null
)
