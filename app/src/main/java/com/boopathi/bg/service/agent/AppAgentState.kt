package com.boopathi.bg.service.agent

/**
 * Lifecycle states of the deterministic App Agent.
 */
enum class AppAgentState {
    IDLE,
    PLANNING,
    WAITING_FOR_ACCESSIBILITY,
    EXECUTING,
    VERIFYING,
    SUCCESS,
    FAILED,
    BLOCKED,
    REQUIRES_CONFIRMATION
}

