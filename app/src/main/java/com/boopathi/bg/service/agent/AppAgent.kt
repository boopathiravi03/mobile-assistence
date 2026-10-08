package com.boopathi.bg.service.agent

import android.util.Log
import com.boopathi.bg.service.tool.ToolRegistry
import com.boopathi.bg.service.tool.ToolStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Foundation of the Controlled App Agent for Android.
 * Orchestrates deterministic planning, risk-enforced execution, and observable verification.
 */
class AppAgent(
    val bridge: AccessibilityBridge,
    val toolRegistry: ToolRegistry? = null,
    val planner: AppPlanner = AppPlanner(),
    val verifier: AppVerifier = AppVerifier(bridge)
) {

    companion object {
        private const val TAG = "AppAgent"
    }

    private val _state = MutableStateFlow(AppAgentState.IDLE)
    val state: StateFlow<AppAgentState> = _state.asStateFlow()

    private val _currentAction = MutableStateFlow<AppAction?>(null)
    val currentAction: StateFlow<AppAction?> = _currentAction.asStateFlow()

    private val _lastResult = MutableStateFlow<AppActionResult?>(null)
    val lastResult: StateFlow<AppActionResult?> = _lastResult.asStateFlow()

    private val _visibleElements = MutableStateFlow<List<UiElementInfo>>(emptyList())
    val visibleElements: StateFlow<List<UiElementInfo>> = _visibleElements.asStateFlow()

    suspend fun executePlan(plan: AppPlan): AppActionResult = withContext(Dispatchers.Default) {
        _state.value = AppAgentState.PLANNING
        Log.i(TAG, "Starting plan '${plan.goal}' with ${plan.size} actions.")

        var finalResult = AppActionResult(
            actionType = AppActionType.INSPECT_UI,
            status = AppActionResultStatus.SUCCESS,
            targetDescription = plan.goal
        )

        for (action in plan.actions) {
            _currentAction.value = action
            finalResult = executeAction(action)
            _lastResult.value = finalResult

            if (!finalResult.isSuccess) {
                Log.w(TAG, "Plan '${plan.goal}' stopped at ${action.type}: ${finalResult.status} (${finalResult.reason})")
                break
            }
        }

        _state.value = if (finalResult.isSuccess) AppAgentState.SUCCESS else AppAgentState.FAILED
        _currentAction.value = null
        finalResult
    }

    suspend fun executeAction(action: AppAction): AppActionResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()

        // 1. Risk Evaluation Policy Check
        val riskDecision = AgentExecutionPolicy.evaluateActionRisk(action)
        if (!riskDecision.isPermitted) {
            _state.value = when (riskDecision.status) {
                AppActionResultStatus.REQUIRES_CONFIRMATION -> AppAgentState.REQUIRES_CONFIRMATION
                AppActionResultStatus.BLOCKED -> AppAgentState.BLOCKED
                else -> AppAgentState.FAILED
            }
            return@withContext AppActionResult(
                actionType = action.type,
                status = riskDecision.status,
                targetDescription = action.targetDescription,
                reason = riskDecision.reason,
                executionDurationMs = System.currentTimeMillis() - startTime
            )
        }

        // 2. Accessibility Connection Check (for UI inspection & interaction actions)
        if (action !is AppAction.OpenApp) {
            if (!bridge.isServiceEnabled()) {
                _state.value = AppAgentState.WAITING_FOR_ACCESSIBILITY
                return@withContext AppActionResult(
                    actionType = action.type,
                    status = AppActionResultStatus.REQUIRES_ACCESSIBILITY,
                    targetDescription = action.targetDescription,
                    reason = "App Control permission is not enabled. Please enable BG in Accessibility settings first.",
                    executionDurationMs = System.currentTimeMillis() - startTime
                )
            }
            if (!bridge.isConnected()) {
                _state.value = AppAgentState.WAITING_FOR_ACCESSIBILITY
                return@withContext AppActionResult(
                    actionType = action.type,
                    status = AppActionResultStatus.REQUIRES_ACCESSIBILITY,
                    targetDescription = action.targetDescription,
                    reason = "Accessibility service is enabled in settings but not currently connected.",
                    executionDurationMs = System.currentTimeMillis() - startTime
                )
            }
        }

        _state.value = AppAgentState.EXECUTING

        // 3. Action Execution and Verification
        when (action) {
            is AppAction.OpenApp -> {
                val toolResult = toolRegistry?.execute("OPEN_APP", mapOf("appName" to action.appName))
                val status = when (toolResult?.status) {
                    ToolStatus.SUCCESS -> AppActionResultStatus.SUCCESS
                    ToolStatus.UNAVAILABLE -> AppActionResultStatus.UNAVAILABLE
                    else -> AppActionResultStatus.FAILED
                }
                AppActionResult(
                    actionType = action.type,
                    status = status,
                    targetDescription = action.targetDescription,
                    reason = toolResult?.message,
                    executionDurationMs = System.currentTimeMillis() - startTime,
                    verificationDetails = if (status == AppActionResultStatus.SUCCESS) "App launch request issued." else null
                )
            }

            is AppAction.WaitForWindow -> {
                _state.value = AppAgentState.VERIFYING
                val verResult = verifier.verifyWindowActive(action.expectedPackage, action.timeoutMs)
                val status = if (verResult.isVerified) AppActionResultStatus.SUCCESS else AppActionResultStatus.TIMEOUT
                AppActionResult(
                    actionType = action.type,
                    status = status,
                    targetDescription = action.targetDescription,
                    reason = if (!verResult.isVerified) verResult.details else null,
                    executionDurationMs = System.currentTimeMillis() - startTime,
                    verificationDetails = verResult.details
                )
            }

            is AppAction.InspectUi -> {
                _state.value = AppAgentState.EXECUTING
                val elements = bridge.getVisibleElements()
                _visibleElements.value = elements
                _state.value = AppAgentState.VERIFYING
                val verResult = verifier.verifyUiInspection(elements)
                AppActionResult(
                    actionType = action.type,
                    status = if (verResult.isVerified) AppActionResultStatus.SUCCESS else AppActionResultStatus.FAILED,
                    targetDescription = "Inspected screen (${elements.size} elements)",
                    reason = if (!verResult.isVerified) verResult.details else null,
                    executionDurationMs = System.currentTimeMillis() - startTime,
                    verificationDetails = verResult.details,
                    data = mapOf("elements" to elements, "count" to elements.size)
                )
            }

            is AppAction.Tap -> {
                val elements = bridge.findByText(action.targetQuery, normalized = true)
                    .ifEmpty { bridge.findByContentDescription(action.targetQuery, normalized = true) }
                    .ifEmpty { bridge.findByViewId(action.targetQuery) }

                if (elements.isEmpty()) {
                    return@withContext AppActionResult(
                        actionType = action.type,
                        status = AppActionResultStatus.NOT_FOUND,
                        targetDescription = action.targetDescription,
                        reason = "Element '${action.targetQuery}' not found on the visible screen.",
                        executionDurationMs = System.currentTimeMillis() - startTime
                    )
                }

                // Ambiguity Check: If multiple interactive candidates exist with distinctly different labels and no exact match
                val exactMatches = elements.filter {
                    it.text.equals(action.targetQuery, ignoreCase = true) ||
                    it.contentDescription.equals(action.targetQuery, ignoreCase = true)
                }
                val candidates = if (exactMatches.isNotEmpty()) exactMatches else elements
                val distinctLabels = candidates.map { it.displayLabel }.distinct()

                if (exactMatches.isEmpty() && distinctLabels.size > 1) {
                    val candidateSummary = distinctLabels.take(3).joinToString(", ")
                    return@withContext AppActionResult(
                        actionType = action.type,
                        status = AppActionResultStatus.REQUIRES_CLARIFICATION,
                        targetDescription = action.targetDescription,
                        reason = "Multiple matching options found ($candidateSummary). Please clarify which one you mean.",
                        executionDurationMs = System.currentTimeMillis() - startTime
                    )
                }

                // Pick first actionable element or first matched element
                val target = candidates.firstOrNull { it.isClickable && it.isEnabled }
                    ?: candidates.first()

                if (!target.isEnabled) {
                    return@withContext AppActionResult(
                        actionType = action.type,
                        status = AppActionResultStatus.FAILED,
                        targetDescription = action.targetDescription,
                        reason = "Element '${target.displayLabel}' is disabled and cannot be tapped.",
                        executionDurationMs = System.currentTimeMillis() - startTime
                    )
                }

                val clickSuccess = bridge.click(target)
                _state.value = AppAgentState.VERIFYING
                val verResult = verifier.verifyActionOutcome(AppActionType.TAP, clickSuccess, target.displayLabel)
                AppActionResult(
                    actionType = action.type,
                    status = if (clickSuccess) AppActionResultStatus.SUCCESS else AppActionResultStatus.FAILED,
                    targetDescription = "Tapped '${target.displayLabel}'",
                    reason = if (!clickSuccess) "Click action was not accepted by the target element." else null,
                    executionDurationMs = System.currentTimeMillis() - startTime,
                    verificationDetails = verResult.details
                )
            }

            is AppAction.TypeText -> {
                val elements = bridge.findByText(action.targetQuery, normalized = true)
                    .ifEmpty { bridge.findByViewId(action.targetQuery) }

                if (elements.isEmpty()) {
                    return@withContext AppActionResult(
                        actionType = action.type,
                        status = AppActionResultStatus.NOT_FOUND,
                        targetDescription = action.targetDescription,
                        reason = "Input field '${action.targetQuery}' not found.",
                        executionDurationMs = System.currentTimeMillis() - startTime
                    )
                }

                val target = elements.firstOrNull { it.isEditable && it.isEnabled }
                    ?: elements.first()

                if (!target.isEditable) {
                    return@withContext AppActionResult(
                        actionType = action.type,
                        status = AppActionResultStatus.FAILED,
                        targetDescription = action.targetDescription,
                        reason = "Target '${target.displayLabel}' is not an editable text field.",
                        executionDurationMs = System.currentTimeMillis() - startTime
                    )
                }

                val typeSuccess = bridge.typeText(target, action.textToType)
                _state.value = AppAgentState.VERIFYING
                val verResult = verifier.verifyActionOutcome(AppActionType.TYPE_TEXT, typeSuccess, target.displayLabel)
                AppActionResult(
                    actionType = action.type,
                    status = if (typeSuccess) AppActionResultStatus.SUCCESS else AppActionResultStatus.FAILED,
                    targetDescription = "Typed into '${target.displayLabel}'",
                    reason = if (!typeSuccess) "Text entry rejected by target field." else null,
                    executionDurationMs = System.currentTimeMillis() - startTime,
                    verificationDetails = verResult.details
                )
            }

            is AppAction.Scroll -> {
                val scrollSuccess = bridge.scroll(action.direction)
                _state.value = AppAgentState.VERIFYING
                val verResult = verifier.verifyActionOutcome(AppActionType.SCROLL, scrollSuccess, action.direction.name)
                AppActionResult(
                    actionType = action.type,
                    status = if (scrollSuccess) AppActionResultStatus.SUCCESS else AppActionResultStatus.FAILED,
                    targetDescription = "Scrolled ${action.direction}",
                    reason = if (!scrollSuccess) "No scrollable container found in active window." else null,
                    executionDurationMs = System.currentTimeMillis() - startTime,
                    verificationDetails = verResult.details
                )
            }

            is AppAction.Back -> {
                val backSuccess = bridge.pressBack()
                _state.value = AppAgentState.VERIFYING
                val verResult = verifier.verifyActionOutcome(AppActionType.BACK, backSuccess, "Back")
                AppActionResult(
                    actionType = action.type,
                    status = if (backSuccess) AppActionResultStatus.SUCCESS else AppActionResultStatus.FAILED,
                    targetDescription = "Back navigation",
                    executionDurationMs = System.currentTimeMillis() - startTime,
                    verificationDetails = verResult.details
                )
            }

            is AppAction.Home -> {
                val homeSuccess = bridge.pressHome()
                _state.value = AppAgentState.VERIFYING
                val verResult = verifier.verifyActionOutcome(AppActionType.HOME, homeSuccess, "Home")
                AppActionResult(
                    actionType = action.type,
                    status = if (homeSuccess) AppActionResultStatus.SUCCESS else AppActionResultStatus.FAILED,
                    targetDescription = "Home navigation",
                    executionDurationMs = System.currentTimeMillis() - startTime,
                    verificationDetails = verResult.details
                )
            }
        }
    }
}

