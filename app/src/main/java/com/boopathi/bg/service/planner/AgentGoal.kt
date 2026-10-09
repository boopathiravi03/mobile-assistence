package com.boopathi.bg.service.planner

enum class GoalCategory {
    DEVICE_CONTROL,
    APP_NAVIGATION,
    APP_INSPECTION,
    SEARCH,
    MESSAGING,
    NOTIFICATION_QUERY,
    MEMORY,
    INFORMATION,
    MULTI_STEP,
    UNKNOWN
}

data class AgentGoal(
    val rawInput: String,
    val normalizedGoal: String,
    val category: GoalCategory,
    val entities: Map<String, String> = emptyMap(),
    val confidence: Float = 1.0f
)

