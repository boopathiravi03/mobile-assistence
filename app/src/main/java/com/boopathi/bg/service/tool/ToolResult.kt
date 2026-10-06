package com.boopathi.bg.service.tool

data class ToolResult(
    val status: ToolStatus,
    val message: String,
    val toolName: String,
    val action: String? = null,
    val target: String? = null,
    val data: Map<String, Any?> = emptyMap()
) {
    val isSuccess: Boolean get() = status == ToolStatus.SUCCESS

    fun getBadgeText(): String {
        val act = action ?: toolName
        val tgt = target?.let { " | $it" } ?: ""
        return "⚡ $act$tgt [$status]"
    }
}

