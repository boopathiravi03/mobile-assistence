package com.boopathi.bg.service.agent

import com.boopathi.bg.service.tool.RiskLevel
import java.util.UUID

enum class StepStatus {
    PENDING,
    RUNNING,
    VERIFYING,
    SUCCESS,
    FAILED,
    RETRYING,
    SKIPPED,
    BLOCKED,
    WAITING_FOR_CONFIRMATION
}

/**
 * Individual discrete executable unit in a multi-step AgentTask.
 */
data class AgentStep(
    val stepId: String = UUID.randomUUID().toString(),
    val action: AppAction,
    val description: String,
    val riskLevel: RiskLevel = RiskLevel.LOW,
    var status: StepStatus = StepStatus.PENDING,
    var retryCount: Int = 0,
    val maxRetries: Int = 1,
    val verificationRequirement: String? = null
)

