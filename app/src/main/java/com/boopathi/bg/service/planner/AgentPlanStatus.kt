package com.boopathi.bg.service.planner

enum class AgentPlanRisk {
    LOW,
    MEDIUM,
    HIGH
}

enum class AgentPlanStatus {
    PROPOSED,
    VALIDATED,
    NEEDS_CONFIRMATION,
    EXECUTING,
    COMPLETED,
    FAILED,
    REJECTED,
    WAITING_FOR_CLARIFICATION
}

