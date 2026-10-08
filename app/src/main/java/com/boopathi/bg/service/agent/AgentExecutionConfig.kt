package com.boopathi.bg.service.agent

/**
 * Execution bounds preventing runaway execution loops.
 */
data class AgentExecutionConfig(
    val maxSteps: Int = 10,
    val maxStepRetries: Int = 1,
    val maxTaskDurationMs: Long = 30_000L,
    val windowWaitTimeoutMs: Long = 6_000L
)

