package com.boopathi.bg

import com.boopathi.bg.data.model.OwnerProfile
import com.boopathi.bg.service.agent.AppAction
import com.boopathi.bg.service.agent.AppActionResult
import com.boopathi.bg.service.agent.AppActionResultStatus
import com.boopathi.bg.service.agent.AppAgent
import com.boopathi.bg.service.agent.UiElementInfo
import com.boopathi.bg.service.messaging.MessagingAppAdapter
import com.boopathi.bg.service.recovery.UiSnapshotObserver
import com.boopathi.bg.service.workflow.CrossAppHandoffVerifier
import com.boopathi.bg.service.workflow.CrossAppWorkflow
import com.boopathi.bg.service.workflow.CrossAppWorkflowExecutor
import com.boopathi.bg.service.workflow.CrossAppWorkflowStatus
import com.boopathi.bg.service.workflow.CrossAppWorkflowStep
import com.boopathi.bg.service.workflow.HandoffVerificationResult
import com.boopathi.bg.service.workflow.WorkflowAppRole
import com.boopathi.bg.service.workflow.WorkflowType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Phase 6C Integration Test Suite:
 * - Cross-app handoff execution & verification.
 * - Wrong foreground package halts execution safely.
 * - Safe text transfer preparation without auto-submitting.
 * - Non-editable field halts text transfer.
 * - Messaging preparation stops at explicit confirmation boundary.
 * - Workflow cancellation stops immediately and wipes transient context.
 */
class Phase6CWorkflowIntegrationTest {

    private val owner = OwnerProfile(name = "Boopathi", preferredLanguage = "en")

    private class TestBridge : FakeAccessibilityBridge() {
        override suspend fun click(element: UiElementInfo): Boolean = true
        override suspend fun typeText(element: UiElementInfo, text: String): Boolean {
            mockElements.removeIf { it.displayLabel == element.displayLabel }
            mockElements.add(
                UiElementInfo(
                    text = text,
                    contentDescription = "",
                    viewId = element.viewId,
                    className = "android.widget.EditText",
                    packageName = currentPkg,
                    isClickable = true,
                    isEditable = true,
                    isEnabled = true
                )
            )
            return true
        }
    }

    private fun createMockToolRegistry(): com.boopathi.bg.service.tool.ToolRegistry {
        val tool = object : com.boopathi.bg.service.tool.Tool {
            override val name: String = "OPEN_APP"
            override val description: String = "Mock Open App"
            override val riskLevel: com.boopathi.bg.service.tool.RiskLevel = com.boopathi.bg.service.tool.RiskLevel.LOW
            override suspend fun execute(parameters: Map<String, Any?>): com.boopathi.bg.service.tool.ToolResult {
                return com.boopathi.bg.service.tool.ToolResult(
                    com.boopathi.bg.service.tool.ToolStatus.SUCCESS,
                    "Launched",
                    "OPEN_APP"
                )
            }
        }
        return com.boopathi.bg.service.tool.ToolRegistry().apply { register(tool) }
    }

    @Test
    fun testHandoff_verifiesForegroundAppCorrectly() = runBlocking {
        val bridge = TestBridge().apply { currentPkg = "com.android.settings" }
        val verifier = CrossAppHandoffVerifier(bridge)

        val resultSuccess = verifier.verifyForegroundApp("com.android.settings")
        assertTrue("Matching package must succeed", resultSuccess is HandoffVerificationResult.Success)

        val resultMismatch = verifier.verifyForegroundApp("com.google.android.youtube")
        assertTrue("Mismatched package must report WrongForegroundApp", resultMismatch is HandoffVerificationResult.WrongForegroundApp)
    }

    @Test
    fun testHandoff_verifiesEditableFieldAndRejectsNonEditable() = runBlocking {
        val bridge = TestBridge().apply {
            currentPkg = "com.google.android.keep"
            mockElements.add(
                UiElementInfo(
                    text = "Notes Header",
                    contentDescription = "",
                    viewId = "title_text",
                    className = "android.widget.TextView",
                    packageName = "com.google.android.keep",
                    isClickable = false,
                    isEditable = false,
                    isEnabled = true
                )
            )
        }
        val verifier = CrossAppHandoffVerifier(bridge)

        val result = verifier.verifyEditableField("Notes Header", "com.google.android.keep")
        assertTrue("Non-editable field must report TargetNotEditable", result is HandoffVerificationResult.TargetNotEditable)
    }

    @Test
    fun testWorkflow_executesTwoAppInspectionSuccessfully() = runBlocking {
        val bridge = TestBridge().apply {
            currentPkg = "com.android.settings"
            mockElements.add(
                UiElementInfo(
                    text = "Wi-Fi",
                    contentDescription = "",
                    viewId = "wifi_row",
                    className = "android.widget.TextView",
                    packageName = "com.android.settings",
                    isClickable = true,
                    isEditable = false,
                    isEnabled = true
                )
            )
        }
        val appAgent = AppAgent(bridge = bridge, toolRegistry = createMockToolRegistry())
        val executor = CrossAppWorkflowExecutor(
            appAgent = appAgent,
            handoffVerifier = CrossAppHandoffVerifier(bridge)
        )

        val wf = CrossAppWorkflow(
            workflowId = UUID.randomUUID().toString(),
            type = WorkflowType.INSPECT_ACROSS_APPS,
            goalDescription = "Inspect Settings",
            steps = listOf(
                CrossAppWorkflowStep(
                    id = "s1",
                    appRole = WorkflowAppRole.SOURCE,
                    targetApp = "settings",
                    expectedPackage = "com.android.settings",
                    actionReference = "OPEN_APP",
                    expectedOutcome = "Settings opened"
                ),
                CrossAppWorkflowStep(
                    id = "s2",
                    appRole = WorkflowAppRole.SOURCE,
                    targetApp = "settings",
                    expectedPackage = "com.android.settings",
                    actionReference = "INSPECT_SCREEN",
                    expectedOutcome = "Settings screen inspected"
                )
            )
        )

        val res = executor.executeWorkflow(wf, owner)
        assertTrue(res.isSuccess)
        assertEquals(CrossAppWorkflowStatus.SUCCESS, executor.workflowStatus.value)
    }

    @Test
    fun testWorkflow_wrongForegroundAppHaltsExecutionSafely() = runBlocking {
        val bridge = TestBridge().apply {
            // App opens, but foreground remains home screen
            currentPkg = "com.android.launcher"
        }
        val appAgent = AppAgent(bridge = bridge, toolRegistry = createMockToolRegistry())
        val executor = CrossAppWorkflowExecutor(
            appAgent = appAgent,
            handoffVerifier = CrossAppHandoffVerifier(bridge)
        )

        val wf = CrossAppWorkflow(
            workflowId = UUID.randomUUID().toString(),
            type = WorkflowType.INSPECT_ACROSS_APPS,
            goalDescription = "Inspect YouTube",
            steps = listOf(
                CrossAppWorkflowStep(
                    id = "s1",
                    appRole = WorkflowAppRole.DESTINATION,
                    targetApp = "youtube",
                    expectedPackage = "com.google.android.youtube",
                    actionReference = "OPEN_APP",
                    expectedOutcome = "YouTube opened"
                )
            )
        )

        val res = executor.executeWorkflow(wf, owner)
        assertFalse("Mismatched foreground app must halt workflow safely", res.isSuccess)
        assertEquals(CrossAppWorkflowStatus.FAILED, executor.workflowStatus.value)
        assertTrue(res.textResponse.contains("Handoff verification failed", ignoreCase = true))
    }

    @Test
    fun testWorkflow_textTransferPreparesWithoutAutoSending() = runBlocking {
        val bridge = TestBridge().apply {
            currentPkg = "com.whatsapp"
            mockElements.add(
                UiElementInfo(
                    text = "Message input",
                    contentDescription = "Message field",
                    viewId = "entry",
                    className = "android.widget.EditText",
                    packageName = "com.whatsapp",
                    isClickable = true,
                    isEditable = true,
                    isEnabled = true
                )
            )
        }
        val appAgent = AppAgent(bridge = bridge, toolRegistry = createMockToolRegistry())
        val executor = CrossAppWorkflowExecutor(
            appAgent = appAgent,
            handoffVerifier = CrossAppHandoffVerifier(bridge)
        )

        val wf = CrossAppWorkflow(
            workflowId = UUID.randomUUID().toString(),
            type = WorkflowType.TRANSFER_USER_PROVIDED_TEXT,
            goalDescription = "Transfer text into WhatsApp draft",
            steps = listOf(
                CrossAppWorkflowStep(
                    id = "s1",
                    appRole = WorkflowAppRole.DESTINATION,
                    targetApp = "whatsapp",
                    expectedPackage = "com.whatsapp",
                    actionReference = "OPEN_APP",
                    expectedOutcome = "WhatsApp opened"
                ),
                CrossAppWorkflowStep(
                    id = "s2",
                    appRole = WorkflowAppRole.DESTINATION,
                    targetApp = "whatsapp",
                    expectedPackage = "com.whatsapp",
                    actionReference = "TRANSFER_TEXT",
                    parameters = mapOf("text" to "Meeting at 4 PM"),
                    expectedOutcome = "Text inserted into draft"
                )
            )
        )

        val res = executor.executeWorkflow(wf, owner)
        assertTrue(res.isSuccess)
        assertEquals(CrossAppWorkflowStatus.SUCCESS, executor.workflowStatus.value)
        // Verify text was inserted in editable field
        assertTrue(bridge.mockElements.any { it.text == "Meeting at 4 PM" })
    }

    @Test
    fun testWorkflow_messagingPreparationPausesAtConfirmation() = runBlocking {
        val bridge = TestBridge().apply {
            currentPkg = "com.whatsapp"
            mockElements.add(
                UiElementInfo(
                    text = "",
                    contentDescription = "Message",
                    viewId = "entry",
                    className = "android.widget.EditText",
                    packageName = "com.whatsapp",
                    isClickable = true,
                    isEditable = true,
                    isEnabled = true
                )
            )
        }
        val fakeAdapter = object : MessagingAppAdapter {
            override val supportedAppName: String = "WhatsApp"
            override val supportedPackage: String = "com.whatsapp"
            override suspend fun verifyRecipient(bridge: com.boopathi.bg.service.agent.AccessibilityBridge, expectedRecipient: String): Boolean = true
            override suspend fun verifyDraft(bridge: com.boopathi.bg.service.agent.AccessibilityBridge, expectedDraft: String): Boolean = true
            override suspend fun findSendButton(bridge: com.boopathi.bg.service.agent.AccessibilityBridge): UiElementInfo? = null
            override suspend fun verifyMessageSent(bridge: com.boopathi.bg.service.agent.AccessibilityBridge, expectedDraft: String, timeoutMs: Long): Boolean = false
        }

        val appAgent = AppAgent(bridge = bridge, toolRegistry = createMockToolRegistry(), messagingAdapter = fakeAdapter)
        val executor = CrossAppWorkflowExecutor(
            appAgent = appAgent,
            handoffVerifier = CrossAppHandoffVerifier(bridge),
            messagingAdapter = fakeAdapter
        )

        val wf = CrossAppWorkflow(
            workflowId = UUID.randomUUID().toString(),
            type = WorkflowType.PREPARE_CROSS_APP_MESSAGE,
            goalDescription = "Prepare WhatsApp message to Karthi",
            steps = listOf(
                CrossAppWorkflowStep(
                    id = "s1",
                    appRole = WorkflowAppRole.DESTINATION,
                    targetApp = "whatsapp",
                    expectedPackage = "com.whatsapp",
                    actionReference = "OPEN_APP",
                    expectedOutcome = "WhatsApp opened"
                ),
                CrossAppWorkflowStep(
                    id = "s2",
                    appRole = WorkflowAppRole.DESTINATION,
                    targetApp = "whatsapp",
                    expectedPackage = "com.whatsapp",
                    actionReference = "PREPARE_MESSAGE",
                    parameters = mapOf(
                        "recipient" to "Karthi",
                        "message" to "hi"
                    ),
                    expectedOutcome = "Prepare hi for Karthi",
                    requiresConfirmation = true
                )
            )
        )

        val res = executor.executeWorkflow(wf, owner)
        assertTrue(res.isSuccess)
        assertEquals(CrossAppWorkflowStatus.WAITING_FOR_CONFIRMATION, executor.workflowStatus.value)
        assertTrue(res.textResponse.contains("Please confirm", ignoreCase = true))
    }

    @Test
    fun testWorkflow_cancellationStopsSafely() = runBlocking {
        val bridge = TestBridge()
        val appAgent = AppAgent(bridge = bridge)
        val executor = CrossAppWorkflowExecutor(appAgent = appAgent)

        // Cancel before or during execution
        val cancelled = executor.cancelWorkflow("User stopped via button")
        assertFalse(cancelled) // No active workflow running yet

        val wf = CrossAppWorkflow(
            workflowId = "wf-cancel-test",
            type = WorkflowType.INSPECT_ACROSS_APPS,
            goalDescription = "Inspect apps",
            steps = listOf(
                CrossAppWorkflowStep(
                    id = "s1",
                    appRole = WorkflowAppRole.SOURCE,
                    targetApp = "settings",
                    expectedPackage = "com.android.settings",
                    actionReference = "OPEN_APP",
                    expectedOutcome = "Open"
                )
            ),
            status = CrossAppWorkflowStatus.CANCELLED
        )

        val res = executor.executeWorkflow(wf, owner)
        assertFalse(res.isSuccess)
        assertTrue(res.textResponse.contains("cancelled", ignoreCase = true))
    }
}

