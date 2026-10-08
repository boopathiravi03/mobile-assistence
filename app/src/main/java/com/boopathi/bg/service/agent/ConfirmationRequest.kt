package com.boopathi.bg.service.agent

import com.boopathi.bg.service.tool.RiskLevel

data class ConfirmationRequest(
    val reason: String,
    val actionDescription: String,
    val riskLevel: RiskLevel = RiskLevel.MEDIUM,
    val timestamp: Long = System.currentTimeMillis()
)

