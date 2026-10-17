package com.boopathi.bg

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boopathi.bg.data.model.OwnerProfile
import com.boopathi.bg.service.agent.AppAction
import com.boopathi.bg.service.agent.AppActionResultStatus
import com.boopathi.bg.service.agent.AppAgent
import com.boopathi.bg.service.messaging.MessageDraft
import com.boopathi.bg.service.messaging.MessagingPolicy
import com.boopathi.bg.service.notification.NotificationInfo
import com.boopathi.bg.service.notification.NotificationPrivacyPolicy
import com.boopathi.bg.service.planner.AgentGoal
import com.boopathi.bg.service.planner.AgentPlan
import com.boopathi.bg.service.planner.AgentPlanExecutor
import com.boopathi.bg.service.planner.AgentPlanStep
import com.boopathi.bg.service.planner.AgentPlanValidator
import com.boopathi.bg.service.planner.GoalCategory
import com.boopathi.bg.service.planner.PlannedAction
import com.boopathi.bg.service.security.AuthResultStatus
import com.boopathi.bg.service.security.MessageDispatchAuthorization
import com.boopathi.bg.service.security.SimpleOwnerAuthGate
import com.boopathi.bg.service.workflow.CrossAppWorkflow
import com.boopathi.bg.service.workflow.CrossAppWorkflowStep
import com.boopathi.bg.service.workflow.CrossAppWorkflowValidator
import com.boopathi.bg.service.workflow.WorkflowAppRole
import com.boopathi.bg.service.workflow.WorkflowType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Phase10SecurityRegressionTest {
    @Test
    fun defaultTestAuthGateFailsClosed() = runBlocking {
        assertEquals(AuthResultStatus.UNAVAILABLE, SimpleOwnerAuthGate().authenticate().status)
    }

    @Test
    fun directAppAgentSendCannotDispatchWithoutAuthorization() = runBlocking {
        val bridge = FakeAccessibilityBridge(currentPkg = "com.whatsapp")
        val agent = AppAgent(bridge)
        val result = agent.executeAction(AppAction.SendMessage("Karthi", "Hi"))

        assertEquals(AppActionResultStatus.REQUIRES_CONFIRMATION, result.status)
        assertTrue(bridge.executedActions.isEmpty())
    }

    @Test
    fun messageAuthorizationIsBoundToTaskActionAndOneUse() {
        val draft = MessageDraft("Karthi", "Hi")
        val action = AppAction.SendMessage("Karthi", "Hi")
        val token = MessageDispatchAuthorization.issue("task-1", draft, 10_000L)

        assertFalse(token.consume("task-2", action, 11_000L))
        assertFalse(token.consume("task-1", action, 11_000L)) // mismatched attempt consumes it

        val freshToken = MessageDispatchAuthorization.issue("task-1", draft, 10_000L)
        assertTrue(freshToken.consume("task-1", action, 11_000L))
        assertFalse(freshToken.consume("task-1", action, 11_001L))
    }

    @Test
    fun messageAuthorizationExpiresAndRejectsChangedRecipientOrText() {
        val draft = MessageDraft("Karthi", "Hi")
        val expired = MessageDispatchAuthorization.issue("task-1", draft, 10_000L)
        assertFalse(expired.consume("task-1", AppAction.SendMessage("Karthi", "Hi"), 70_001L))

        val mismatched = MessageDispatchAuthorization.issue("task-1", draft, 10_000L)
        assertFalse(mismatched.consume("task-1", AppAction.SendMessage("Suresh", "Hi"), 11_000L))
    }

    @Test
    fun futureDatedConfirmationsAreNotFresh() {
        assertFalse(MessagingPolicy.isConfirmationFresh(20_000L, now = 19_999L))
        assertTrue(MessagingPolicy.isConfirmationFresh(10_000L, now = 20_000L))
    }

    @Test
    fun sensitiveNotificationDoesNotRetainTitleSenderOrConversation() {
        val safe = NotificationPrivacyPolicy.sanitize(
            NotificationInfo(
                notificationId = "n1",
                packageName = "example.auth",
                appLabel = "Authenticator",
                title = "Your OTP is 492019",
                text = "Sign-in code 492019",
                conversationKey = "private conversation",
                senderName = "private sender"
            )
        )

        assertTrue(safe.isRedacted)
        assertEquals("Sensitive notification", safe.title)
        assertFalse(safe.title.contains("492019"))
        assertEquals("", safe.senderName)
        assertEquals("", safe.conversationKey)
    }

    @Test
    fun contextualVerificationCodeIsRedactedButUnrelatedNumberIsNot() {
        val withCode = NotificationPrivacyPolicy.sanitize(
            NotificationInfo(
                notificationId = "n-code",
                packageName = "example.app",
                appLabel = "Example",
                title = "Sign in",
                text = "Your code: 492019"
            )
        )
        val ordinaryNumber = NotificationPrivacyPolicy.sanitize(
            NotificationInfo(
                notificationId = "n-number",
                packageName = "example.app",
                appLabel = "Example",
                title = "Delivery update",
                text = "Your order 492019 is on the way"
            )
        )

        assertTrue(withCode.isRedacted)
        assertFalse(ordinaryNumber.isRedacted)
    }

    @Test
    fun sensitiveExpandedNotificationTextTriggersRedaction() {
        val sanitized = NotificationPrivacyPolicy.sanitize(
            NotificationInfo(
                notificationId = "n-expanded",
                packageName = "example.app",
                appLabel = "Example",
                title = "Message",
                text = "You have a new message"
            ),
            additionalText = "Your login code is 492019"
        )

        assertTrue(sanitized.isRedacted)
        assertFalse(sanitized.text.contains("492019"))
    }

    @Test
    fun planValidatorRejectsCoordinateAndCommandParameters() = runBlocking {
        val coordinatePlan = plan(AgentPlanStep("s1", PlannedAction.TAP, target = "x=120,y=400"))
        assertFalse(AgentPlanValidator().validatePlan(coordinatePlan).isValid)

        val commandPlan = plan(AgentPlanStep(
            "s1", PlannedAction.DEVICE_TOOL, target = "FLASHLIGHT",
            parameters = mapOf("tool" to "FLASHLIGHT", "shellCommand" to "rm -rf /")
        ))
        val result = AgentPlanExecutor().executePlan(commandPlan, OwnerProfile())
        assertFalse(result.isSuccess)
        assertEquals("AgentPlanValidator", result.providerName)
    }

    @Test
    fun workflowValidatorRejectsUnknownActions() {
        val workflow = CrossAppWorkflow(
            workflowId = "wf-1",
            type = WorkflowType.INSPECT_ACROSS_APPS,
            goalDescription = "Inspect Settings",
            steps = listOf(CrossAppWorkflowStep(
                id = "s1",
                appRole = WorkflowAppRole.SOURCE,
                targetApp = "settings",
                expectedPackage = "com.android.settings",
                actionReference = "RUN_UNRESTRICTED_CODE",
                expectedOutcome = "done"
            ))
        )

        assertFalse(CrossAppWorkflowValidator().validateWorkflow(workflow).isValid)
    }

    private fun plan(step: AgentPlanStep) = AgentPlan(
        goal = AgentGoal("test", "test", GoalCategory.DEVICE_CONTROL),
        steps = listOf(step)
    )
}
