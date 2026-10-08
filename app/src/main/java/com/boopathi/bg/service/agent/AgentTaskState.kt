package com.boopathi.bg.service.agent

/**
 * Observable lifecycle states of a bounded multi-step AgentTask.
 */
enum class AgentTaskState {
    IDLE,
    PLANNING,
    WAITING_FOR_ACCESSIBILITY,
    EXECUTING,
    WAITING_FOR_CONFIRMATION,
    VERIFYING,
    PAUSED,
    SUCCESS,
    FAILED,
    CANCELLED,
    BLOCKED,
    TIMEOUT
}

