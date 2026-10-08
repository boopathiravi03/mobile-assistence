package com.boopathi.bg.service.agent

/**
 * Ephemeral execution context holding runtime observations and verified outcomes across steps.
 * Strict safety: Holds only immutable UiElementInfo instances, never raw AccessibilityNodeInfo.
 */
data class AgentExecutionContext(
    var currentPackage: String = "",
    var lastVisibleElements: List<UiElementInfo> = emptyList(),
    var lastAction: AppAction? = null,
    var lastActionResult: AppActionResult? = null,
    var lastVerificationResult: VerificationResult? = null,
    val taskStartTime: Long = System.currentTimeMillis(),
    var currentStep: AgentStep? = null
)

