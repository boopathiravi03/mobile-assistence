package com.boopathi.bg.service.planner

enum class PlannedAction {
    OPEN_APP,
    WAIT_FOR_WINDOW,
    INSPECT_UI,
    TAP,
    SCROLL,
    TYPE_TEXT,
    BACK,
    HOME,
    DEVICE_TOOL,
    READ_NOTIFICATION,
    QUERY_MEMORY,
    PREPARE_MESSAGE,
    SEND_MESSAGE,
    ASK_USER
}

data class AgentPlanStep(
    val stepId: String,
    val action: PlannedAction,
    val target: String? = null,
    val parameters: Map<String, String> = emptyMap(),
    val risk: AgentPlanRisk = AgentPlanRisk.LOW,
    val requiresConfirmation: Boolean = false,
    val description: String = ""
)

