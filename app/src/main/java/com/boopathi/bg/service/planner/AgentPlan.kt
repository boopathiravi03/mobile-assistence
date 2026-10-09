package com.boopathi.bg.service.planner

data class AgentPlan(
    val goal: AgentGoal,
    val steps: List<AgentPlanStep>,
    val requiresConfirmation: Boolean = false,
    val risk: AgentPlanRisk = AgentPlanRisk.LOW,
    val explanation: String = "",
    val status: AgentPlanStatus = AgentPlanStatus.PROPOSED
) {
    val totalSteps: Int get() = steps.size
    val isEmpty: Boolean get() = steps.isEmpty()
}

