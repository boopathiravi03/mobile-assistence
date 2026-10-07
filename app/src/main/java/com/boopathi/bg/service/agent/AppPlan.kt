package com.boopathi.bg.service.agent

/**
 * Deterministic plan representing an ordered series of actions to achieve a user goal.
 */
data class AppPlan(
    val goal: String,
    val actions: List<AppAction> = emptyList()
) {
    val size: Int get() = actions.size
    val isEmpty: Boolean get() = actions.isEmpty()
}

