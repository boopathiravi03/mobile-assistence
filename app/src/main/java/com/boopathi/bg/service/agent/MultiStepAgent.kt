package com.boopathi.bg.service.agent

import android.util.Log
import com.boopathi.bg.service.tool.RiskLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Bounded Multi-Step Execution Engine for Android App Automation (Phase 5A).
 * Enforces step-by-step risk policy checks, verified state transitions, retry bounds,
 * timeout limits, ambiguity handling, and cooperative cancellation.
 */
class MultiStepAgent(
    val appAgent: AppAgent,
    val config: AgentExecutionConfig = AgentExecutionConfig()
) {

    companion object {
        private const val TAG = "MultiStepAgent"
    }

    private val _currentTask = MutableStateFlow<AgentTask?>(null)
    val currentTask: StateFlow<AgentTask?> = _currentTask.asStateFlow()

    private val _taskState = MutableStateFlow(AgentTaskState.IDLE)
    val taskState: StateFlow<AgentTaskState> = _taskState.asStateFlow()

    private var activeJob: Job? = null

    fun cancelCurrentTask(reason: String = "User requested cancellation"): Boolean {
        val task = _currentTask.value
        if (task != null && !task.isComplete) {
            Log.i(TAG, "Task ${task.taskId} cancelled: $reason")
            task.state = AgentTaskState.CANCELLED
            task.failureReason = reason
            task.updatedAt = System.currentTimeMillis()
            _taskState.value = AgentTaskState.CANCELLED
            return true
        }
        return false
    }

    suspend fun executeTask(task: AgentTask): AgentTask = withContext(Dispatchers.Default) {
        activeJob = coroutineContext[Job]
        _currentTask.value = task
        task.updatedAt = System.currentTimeMillis()

        // 1. Validation: Max steps bound
        if (task.steps.isEmpty()) {
            task.state = AgentTaskState.FAILED
            task.failureReason = "Task contains no executable steps."
            _taskState.value = AgentTaskState.FAILED
            return@withContext task
        }

        if (task.steps.size > config.maxSteps) {
            task.state = AgentTaskState.FAILED
            task.failureReason = "Task exceeds maximum step limit (${config.maxSteps}). Provided: ${task.steps.size}."
            _taskState.value = AgentTaskState.FAILED
            return@withContext task
        }

        val context = AgentExecutionContext(taskStartTime = System.currentTimeMillis())
        _taskState.value = AgentTaskState.PLANNING
        task.state = AgentTaskState.PLANNING

        Log.i(TAG, "Starting task '${task.normalizedGoal}' (${task.steps.size} steps, max duration ${config.maxTaskDurationMs}ms)")

        for (index in task.steps.indices) {
            if (!isActive || task.state == AgentTaskState.CANCELLED) {
                task.state = AgentTaskState.CANCELLED
                _taskState.value = AgentTaskState.CANCELLED
                return@withContext task
            }

            // Check overall task duration bound
            val elapsedTotal = System.currentTimeMillis() - context.taskStartTime
            if (elapsedTotal > config.maxTaskDurationMs) {
                val timeoutMsg = "Task timed out after ${elapsedTotal}ms (limit: ${config.maxTaskDurationMs}ms)."
                Log.w(TAG, timeoutMsg)
                task.state = AgentTaskState.TIMEOUT
                task.failureReason = timeoutMsg
                _taskState.value = AgentTaskState.TIMEOUT
                return@withContext task
            }

            task.currentStepIndex = index
            val step = task.steps[index]
            context.currentStep = step
            step.status = StepStatus.RUNNING

            Log.i(TAG, "Step ${index + 1}/${task.steps.size}: ${step.description} (${step.action.type})")

            // 2. Risk Evaluation Policy Check
            val riskDecision = AgentExecutionPolicy.evaluateActionRisk(step.action)
            if (!riskDecision.isPermitted) {
                when (riskDecision.status) {
                    AppActionResultStatus.BLOCKED -> {
                        step.status = StepStatus.BLOCKED
                        task.state = AgentTaskState.BLOCKED
                        task.failureReason = riskDecision.reason ?: "Action blocked by safety policy."
                        _taskState.value = AgentTaskState.BLOCKED
                        Log.w(TAG, "Step ${index + 1} blocked: ${task.failureReason}")
                        return@withContext task
                    }

                    AppActionResultStatus.REQUIRES_CONFIRMATION -> {
                        step.status = StepStatus.WAITING_FOR_CONFIRMATION
                        task.state = AgentTaskState.WAITING_FOR_CONFIRMATION
                        task.confirmationRequest = ConfirmationRequest(
                            reason = riskDecision.reason ?: "Action requires confirmation.",
                            actionDescription = step.description,
                            riskLevel = RiskLevel.MEDIUM
                        )
                        _taskState.value = AgentTaskState.WAITING_FOR_CONFIRMATION
                        Log.i(TAG, "Step ${index + 1} paused for confirmation: ${task.confirmationRequest?.reason}")
                        return@withContext task
                    }

                    else -> {
                        step.status = StepStatus.FAILED
                        task.state = AgentTaskState.FAILED
                        task.failureReason = riskDecision.reason ?: "Risk policy rejected action."
                        _taskState.value = AgentTaskState.FAILED
                        return@withContext task
                    }
                }
            }

            // 3. Step Execution with bounded retry for transient failures
            _taskState.value = AgentTaskState.EXECUTING
            var stepSuccess = false
            var finalStepResult: AppActionResult? = null

            while (step.retryCount <= step.maxRetries && !stepSuccess) {
                if (!isActive || task.state == AgentTaskState.CANCELLED) {
                    task.state = AgentTaskState.CANCELLED
                    _taskState.value = AgentTaskState.CANCELLED
                    return@withContext task
                }

                val actionResult = appAgent.executeAction(step.action)
                context.lastAction = step.action
                context.lastActionResult = actionResult
                finalStepResult = actionResult

                if (actionResult.isSuccess) {
                    // Update execution context
                    context.currentPackage = appAgent.bridge.getCurrentPackage()
                    if (step.action is AppAction.InspectUi) {
                        context.lastVisibleElements = appAgent.visibleElements.value
                    }

                    step.status = StepStatus.SUCCESS
                    stepSuccess = true
                    Log.i(TAG, "Step ${index + 1} succeeded & verified.")
                } else {
                    Log.w(TAG, "Step ${index + 1} failed: ${actionResult.status} (${actionResult.reason})")

                    // Non-retryable terminal statuses:
                    if (actionResult.status == AppActionResultStatus.REQUIRES_ACCESSIBILITY) {
                        step.status = StepStatus.FAILED
                        task.state = AgentTaskState.WAITING_FOR_ACCESSIBILITY
                        task.failureReason = actionResult.reason
                        _taskState.value = AgentTaskState.WAITING_FOR_ACCESSIBILITY
                        return@withContext task
                    }

                    if (actionResult.status == AppActionResultStatus.REQUIRES_CLARIFICATION) {
                        step.status = StepStatus.FAILED
                        task.state = AgentTaskState.PAUSED
                        task.failureReason = actionResult.reason
                        _taskState.value = AgentTaskState.PAUSED
                        return@withContext task
                    }

                    if (actionResult.status == AppActionResultStatus.BLOCKED ||
                        actionResult.status == AppActionResultStatus.REQUIRES_CONFIRMATION
                    ) {
                        step.status = StepStatus.FAILED
                        task.state = AgentTaskState.FAILED
                        task.failureReason = actionResult.reason
                        _taskState.value = AgentTaskState.FAILED
                        return@withContext task
                    }

                    // Retryable transient failures (TIMEOUT, NOT_FOUND on window transitions)
                    if (step.retryCount < step.maxRetries) {
                        step.retryCount++
                        step.status = StepStatus.RETRYING
                        Log.i(TAG, "Retrying step ${index + 1} (attempt ${step.retryCount}/${step.maxRetries})...")
                        delay(500)
                    } else {
                        // Max retries exceeded
                        step.status = StepStatus.FAILED
                        break
                    }
                }
            }

            // 4. Strict Stop-on-Failure boundary: Never proceed if unverified
            if (!stepSuccess) {
                task.state = AgentTaskState.FAILED
                task.failureReason = finalStepResult?.reason ?: "Step ${index + 1} failed verification."
                _taskState.value = AgentTaskState.FAILED
                Log.w(TAG, "Task '${task.normalizedGoal}' terminated at step ${index + 1}: ${task.failureReason}")
                return@withContext task
            }
        }

        // 5. Completion
        task.state = AgentTaskState.SUCCESS
        task.updatedAt = System.currentTimeMillis()
        _taskState.value = AgentTaskState.SUCCESS
        Log.i(TAG, "Task '${task.normalizedGoal}' completed successfully.")
        task
    }
}

