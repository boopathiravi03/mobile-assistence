package com.boopathi.bg.service.planner

import android.util.Log
import com.boopathi.bg.data.model.CommandResult
import com.boopathi.bg.data.model.OwnerProfile
import com.boopathi.bg.service.agent.AgentStep
import com.boopathi.bg.service.agent.AgentTask
import com.boopathi.bg.service.agent.AgentTaskState
import com.boopathi.bg.service.agent.AppAction
import com.boopathi.bg.service.agent.AppAgent
import com.boopathi.bg.service.agent.MultiStepAgent
import com.boopathi.bg.service.agent.ScrollDirection
import com.boopathi.bg.service.messaging.MessageDraft
import com.boopathi.bg.service.notification.NotificationAccessManager
import com.boopathi.bg.service.tool.RiskLevel
import com.boopathi.bg.service.tool.ToolRegistry
import java.util.UUID

class AgentPlanExecutor(
    private val appAgent: AppAgent? = null,
    private val multiStepAgent: MultiStepAgent? = null,
    private val toolRegistry: ToolRegistry? = null,
    private val notificationAccessManager: NotificationAccessManager? = null
) {

    companion object {
        private const val TAG = "AgentPlanExecutor"
    }

    suspend fun executePlan(
        plan: AgentPlan,
        owner: OwnerProfile
    ): CommandResult {
        // If plan is rejected or needs clarification, return directly without running
        if (plan.status == AgentPlanStatus.REJECTED) {
            return CommandResult(
                textResponse = plan.explanation.ifBlank { "That request violates the safety policy and cannot be executed." },
                providerName = "AgentPlanValidator",
                isSuccess = false
            )
        }

        if (plan.status == AgentPlanStatus.WAITING_FOR_CLARIFICATION) {
            return CommandResult(
                textResponse = plan.explanation.ifBlank { "Could you clarify what you would like me to do?" },
                providerName = "AgentPlanner",
                isSuccess = false
            )
        }

        // Plans are untrusted data. Re-run local policy immediately before any tool or UI action.
        val validation = AgentPlanValidator().validatePlan(plan)
        val checkedPlan = validation.validatedPlan
        if (!validation.isValid || checkedPlan == null) {
            return CommandResult(
                textResponse = validation.rejectionReason ?: "That plan did not pass the local safety checks.",
                providerName = "AgentPlanValidator",
                isSuccess = false
            )
        }

        // Single step device tool execution
        if (checkedPlan.steps.size == 1 && checkedPlan.steps[0].action == PlannedAction.DEVICE_TOOL) {
            val step = checkedPlan.steps[0]
            val toolName = step.parameters["tool"] ?: step.target ?: ""
            if (toolRegistry != null) {
                val toolResult = toolRegistry.execute(toolName, step.parameters)
                return CommandResult(
                    textResponse = toolResult.message,
                    providerName = "ToolRegistry",
                    isSuccess = toolResult.isSuccess,
                    toolResult = toolResult
                )
            }
        }

        // Notification reading
        if (checkedPlan.steps.any { it.action == PlannedAction.READ_NOTIFICATION }) {
            if (notificationAccessManager != null && notificationAccessManager.isAccessGranted()) {
                val notifs = notificationAccessManager.repository.getRecentNotifications()
                val response = if (notifs.isEmpty()) {
                    "You have no recent unread notifications, ${owner.name}."
                } else {
                    val summary = notifs.take(5).joinToString("\n") { "• ${it.appLabel}: ${it.title}" }
                    "Here are your recent notifications, ${owner.name}:\n$summary"
                }
                return CommandResult(
                    textResponse = response,
                    providerName = "NotificationManager",
                    isSuccess = true
                )
            } else {
                return CommandResult(
                    textResponse = "Notification access is not enabled. Please grant permission in Android Settings.",
                    providerName = "NotificationManager",
                    isSuccess = false
                )
            }
        }

        // Bridge to MultiStepAgent
        val agentTask = convertPlanToAgentTask(checkedPlan)
        if (multiStepAgent != null) {
            Log.d(TAG, "Executing validated plan via MultiStepAgent (${agentTask.totalSteps} steps)")
            val executedTask = multiStepAgent.executeTask(agentTask)

            val responseText = formatTaskResponse(executedTask, owner)
            return CommandResult(
                textResponse = responseText,
                providerName = "AgentPlanExecutor",
                isSuccess = executedTask.state == AgentTaskState.SUCCESS,
                agentTask = executedTask
            )
        }

        // Fallback: If MultiStepAgent is not provided but AppAgent is provided, execute step by step
        if (appAgent != null) {
            Log.d(TAG, "Executing validated plan via AppAgent fallback")
            for (step in agentTask.steps) {
                val result = appAgent.executeAction(step.action)
                if (!result.isSuccess) {
                    return CommandResult(
                        textResponse = "Execution failed at step '${step.description}': ${result.reason ?: "Unknown error"}",
                        providerName = "AgentPlanExecutor",
                        isSuccess = false,
                        appActionResult = result
                    )
                }
            }
            return CommandResult(
                textResponse = "Completed the validated plan.",
                providerName = "AgentPlanExecutor",
                isSuccess = true
            )
        }

        return CommandResult(
            textResponse = "No execution agent is available to execute this plan.",
            providerName = "AgentPlanExecutor",
            isSuccess = false
        )
    }

    fun convertPlanToAgentTask(plan: AgentPlan): AgentTask {
        val agentSteps = plan.steps.map { planStep ->
            val appAction = convertPlannedActionToAppAction(planStep)
            val riskLevel = when (planStep.risk) {
                AgentPlanRisk.LOW -> RiskLevel.LOW
                AgentPlanRisk.MEDIUM -> RiskLevel.MEDIUM
                AgentPlanRisk.HIGH -> RiskLevel.HIGH
            }
            AgentStep(
                stepId = planStep.stepId,
                action = appAction,
                description = planStep.description.ifBlank { planStep.action.name },
                riskLevel = riskLevel
            )
        }.toMutableList()

        val messagingStep = plan.steps.find { it.action == PlannedAction.SEND_MESSAGE || it.action == PlannedAction.PREPARE_MESSAGE }
        val draft = if (messagingStep != null) {
            val recipient = messagingStep.parameters["recipient"] ?: messagingStep.target.orEmpty()
            val messageText = messagingStep.parameters["message"] ?: messagingStep.parameters["text"].orEmpty()
            val sourceApp = messagingStep.parameters["app"] ?: "WhatsApp"
            MessageDraft(
                recipient = recipient,
                messageText = messageText,
                sourceApp = sourceApp
            )
        } else null

        return AgentTask(
            taskId = UUID.randomUUID().toString(),
            originalCommand = plan.goal.rawInput,
            normalizedGoal = plan.goal.normalizedGoal,
            steps = agentSteps,
            draft = draft
        )
    }

    private fun convertPlannedActionToAppAction(step: AgentPlanStep): AppAction {
        val target = step.target.orEmpty()
        return when (step.action) {
            PlannedAction.OPEN_APP -> AppAction.OpenApp(target)
            PlannedAction.WAIT_FOR_WINDOW -> {
                val timeout = step.parameters["timeoutMs"]?.toLongOrNull() ?: 6000L
                AppAction.WaitForWindow(expectedPackage = target, timeoutMs = timeout)
            }
            PlannedAction.INSPECT_UI -> AppAction.InspectUi
            PlannedAction.TAP -> {
                val isViewId = step.parameters["isViewId"]?.toBooleanStrictOrNull() ?: false
                AppAction.Tap(targetQuery = target, isViewId = isViewId)
            }
            PlannedAction.TYPE_TEXT -> {
                val text = step.parameters["text"] ?: ""
                AppAction.TypeText(targetQuery = target, textToType = text)
            }
            PlannedAction.SCROLL -> {
                val dirStr = step.parameters["direction"]?.uppercase() ?: "DOWN"
                val dir = try {
                    ScrollDirection.valueOf(dirStr)
                } catch (e: Exception) {
                    ScrollDirection.DOWN
                }
                AppAction.Scroll(dir)
            }
            PlannedAction.BACK -> AppAction.Back
            PlannedAction.HOME -> AppAction.Home
            PlannedAction.SEND_MESSAGE -> {
                val recipient = step.parameters["recipient"] ?: target
                val messageText = step.parameters["message"] ?: step.parameters["text"].orEmpty()
                val app = step.parameters["app"] ?: "WhatsApp"
                AppAction.SendMessage(recipient, messageText, app)
            }
            PlannedAction.PREPARE_MESSAGE -> {
                val recipient = step.parameters["recipient"] ?: target
                val messageText = step.parameters["message"] ?: step.parameters["text"].orEmpty()
                val app = step.parameters["app"] ?: "WhatsApp"
                AppAction.SendMessage(recipient, messageText, app)
            }
            PlannedAction.DEVICE_TOOL -> {
                AppAction.Home
            }
            PlannedAction.READ_NOTIFICATION,
            PlannedAction.QUERY_MEMORY,
            PlannedAction.ASK_USER -> {
                AppAction.InspectUi
            }
        }
    }

    private fun formatTaskResponse(task: AgentTask, owner: OwnerProfile): String {
        return when (task.state) {
            AgentTaskState.SUCCESS -> "Task completed successfully, ${owner.name}."
            AgentTaskState.WAITING_FOR_CONFIRMATION -> {
                val req = task.confirmationRequest
                if (req != null) "Confirmation required: ${req.reason} (${req.actionDescription}). Proceed?" else "Confirmation required to proceed."
            }
            AgentTaskState.BLOCKED -> "Action was blocked: ${task.failureReason ?: "Safety policy restriction."}"
            AgentTaskState.FAILED -> "Task failed: ${task.failureReason ?: "Unknown error"}"
            AgentTaskState.CANCELLED -> "Task was cancelled."
            AgentTaskState.TIMEOUT -> "Task timed out waiting for UI response."
            else -> "Task status: ${task.state.name}"
        }
    }
}
