package com.boopathi.bg.service.messaging

import com.boopathi.bg.service.agent.AgentStep
import com.boopathi.bg.service.agent.AgentTask
import com.boopathi.bg.service.agent.AppAction
import com.boopathi.bg.service.agent.ConfirmationRequest
import com.boopathi.bg.service.tool.RiskLevel

/**
 * Creates bounded AgentTask instances for message preparation.
 *
 * CRITICAL SAFETY BOUNDARY:
 * 1. Prepares the message in the input field.
 * 2. STOPS immediately and requests confirmation.
 * 3. Does NOT include any Tap("Send") or dispatch action.
 */
object MessagePreparationTask {

    fun createPreparationTask(draft: MessageDraft): AgentTask {
        val app = draft.sourceApp
        val pkg = when (app.lowercase()) {
            "whatsapp" -> "com.whatsapp"
            "telegram" -> "org.telegram.messenger"
            "signal" -> "org.thoughtcrime.securesms"
            else -> "com.whatsapp"
        }

        val steps = mutableListOf(
            AgentStep(
                action = AppAction.OpenApp(app),
                description = "Open $app",
                riskLevel = RiskLevel.LOW
            ),
            AgentStep(
                action = AppAction.WaitForWindow(pkg, timeoutMs = 6000L),
                description = "Wait for $app",
                riskLevel = RiskLevel.LOW
            ),
            AgentStep(
                action = AppAction.InspectUi,
                description = "Inspect contacts list",
                riskLevel = RiskLevel.LOW
            ),
            AgentStep(
                action = AppAction.Tap(draft.recipient),
                description = "Open conversation with ${draft.recipient}",
                riskLevel = RiskLevel.LOW
            ),
            AgentStep(
                action = AppAction.WaitForWindow(pkg, timeoutMs = 6000L),
                description = "Wait for conversation view",
                riskLevel = RiskLevel.LOW
            ),
            AgentStep(
                action = AppAction.TypeText(targetQuery = "Type a message", textToType = draft.messageText),
                description = "Prepare draft '${draft.messageText}'",
                riskLevel = RiskLevel.LOW
            )
        )

        val task = AgentTask(
            originalCommand = "Prepare message to ${draft.recipient}: ${draft.messageText}",
            normalizedGoal = "Prepare message to ${draft.recipient}",
            steps = steps
        )

        // Attach confirmation request and draft to boundary
        task.draft = draft
        task.confirmationCreatedAt = System.currentTimeMillis()
        task.confirmationRequest = ConfirmationRequest(
            reason = "Ready to send message to ${draft.recipient}: '${draft.messageText}' via $app",
            actionDescription = "Send message to ${draft.recipient}",
            riskLevel = RiskLevel.MEDIUM
        )

        return task
    }
}

