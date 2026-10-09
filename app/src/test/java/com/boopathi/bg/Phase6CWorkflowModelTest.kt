package com.boopathi.bg

import com.boopathi.bg.service.workflow.CrossAppWorkflow
import com.boopathi.bg.service.workflow.CrossAppWorkflowStep
import com.boopathi.bg.service.workflow.CrossAppWorkflowValidator
import com.boopathi.bg.service.workflow.WorkflowAppRole
import com.boopathi.bg.service.workflow.WorkflowContext
import com.boopathi.bg.service.workflow.WorkflowLimits
import com.boopathi.bg.service.workflow.WorkflowType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Phase 6C Unit Test Suite: Workflow Models, Bounds, and Context Life Cycle.
 * Tests items 1 to 8 in scope:
 * 1. Valid workflow accepted.
 * 2. Empty workflow rejected.
 * 3. Workflow exceeding 10 steps rejected.
 * 4. Workflow exceeding 4 app handoffs rejected.
 * 5. Unsupported app capability rejected.
 * 6. Duplicate workflow ID handled safely.
 * 7. Workflow cancellation clears transient context.
 * 8. Expired workflow context cannot resume without revalidation.
 */
class Phase6CWorkflowModelTest {

    private val validator = CrossAppWorkflowValidator()

    @Test
    fun test1_validWorkflowAccepted() {
        val workflow = CrossAppWorkflow(
            workflowId = UUID.randomUUID().toString(),
            type = WorkflowType.INSPECT_ACROSS_APPS,
            goalDescription = "Open Settings then open YouTube",
            steps = listOf(
                CrossAppWorkflowStep(
                    id = "s1",
                    appRole = WorkflowAppRole.SOURCE,
                    targetApp = "settings",
                    expectedPackage = "com.android.settings",
                    actionReference = "OPEN_APP",
                    expectedOutcome = "Settings opened",
                    isHandoff = false
                ),
                CrossAppWorkflowStep(
                    id = "s2",
                    appRole = WorkflowAppRole.DESTINATION,
                    targetApp = "youtube",
                    expectedPackage = "com.google.android.youtube",
                    actionReference = "OPEN_APP",
                    expectedOutcome = "YouTube opened",
                    isHandoff = true
                )
            )
        )

        val result = validator.validateWorkflow(workflow)
        assertTrue("Valid workflow should be accepted", result.isValid)
        assertNotNull(result.validatedWorkflow)
        assertNull(result.rejectionReason)
    }

    @Test
    fun test2_emptyWorkflowRejected() {
        val workflow = CrossAppWorkflow(
            workflowId = UUID.randomUUID().toString(),
            type = WorkflowType.INSPECT_ACROSS_APPS,
            goalDescription = "Empty workflow",
            steps = emptyList()
        )

        val result = validator.validateWorkflow(workflow)
        assertFalse("Empty workflow should be rejected", result.isValid)
        assertTrue(result.rejectionReason!!.contains("empty", ignoreCase = true))
    }

    @Test
    fun test3_workflowExceeding10StepsRejected() {
        val steps = (1..11).map { index ->
            CrossAppWorkflowStep(
                id = "s$index",
                appRole = WorkflowAppRole.INTERMEDIATE,
                targetApp = "settings",
                expectedPackage = "com.android.settings",
                actionReference = "INSPECT_SCREEN",
                expectedOutcome = "Inspect $index"
            )
        }

        val workflow = CrossAppWorkflow(
            workflowId = UUID.randomUUID().toString(),
            type = WorkflowType.INSPECT_ACROSS_APPS,
            goalDescription = "Too many steps",
            steps = steps
        )

        val result = validator.validateWorkflow(workflow)
        assertFalse("Workflow exceeding 10 steps must be rejected", result.isValid)
        assertTrue(result.rejectionReason!!.contains("maximum", ignoreCase = true))
    }

    @Test
    fun test4_workflowExceeding4HandoffsRejected() {
        val steps = (1..6).map { index ->
            CrossAppWorkflowStep(
                id = "s$index",
                appRole = WorkflowAppRole.INTERMEDIATE,
                targetApp = "settings",
                expectedPackage = "com.android.settings",
                actionReference = "OPEN_APP",
                expectedOutcome = "Handoff $index",
                isHandoff = true
            )
        }

        val workflow = CrossAppWorkflow(
            workflowId = UUID.randomUUID().toString(),
            type = WorkflowType.INSPECT_ACROSS_APPS,
            goalDescription = "Too many handoffs",
            steps = steps
        )

        val result = validator.validateWorkflow(workflow)
        assertFalse("Workflow exceeding 4 handoffs must be rejected", result.isValid)
        assertTrue(result.rejectionReason!!.contains("handoff", ignoreCase = true))
    }

    @Test
    fun test5_unsupportedAppCapabilityRejected() {
        val workflow = CrossAppWorkflow(
            workflowId = UUID.randomUUID().toString(),
            type = WorkflowType.INSPECT_ACROSS_APPS,
            goalDescription = "Open unknown app",
            steps = listOf(
                CrossAppWorkflowStep(
                    id = "s1",
                    appRole = WorkflowAppRole.SOURCE,
                    targetApp = "com.malicious.unknownapp",
                    expectedPackage = null,
                    actionReference = "OPEN_APP",
                    expectedOutcome = "Launch unknown"
                )
            )
        )

        val result = validator.validateWorkflow(workflow)
        assertFalse("Unsupported package must be rejected", result.isValid)
        assertTrue(result.rejectionReason!!.contains("unsupported", ignoreCase = true))
    }

    @Test
    fun test6_duplicateWorkflowIdHandledSafely() {
        val id = "duplicate-id-123"
        val wf1 = CrossAppWorkflow(
            workflowId = id,
            type = WorkflowType.INSPECT_ACROSS_APPS,
            goalDescription = "Goal 1",
            steps = listOf(
                CrossAppWorkflowStep(
                    id = "s1",
                    appRole = WorkflowAppRole.SOURCE,
                    targetApp = "settings",
                    expectedPackage = "com.android.settings",
                    actionReference = "OPEN_APP",
                    expectedOutcome = "Open"
                )
            )
        )
        val wf2 = wf1.copy(goalDescription = "Goal 2")

        assertEquals(wf1.workflowId, wf2.workflowId)
        val val1 = validator.validateWorkflow(wf1)
        val val2 = validator.validateWorkflow(wf2)
        assertTrue(val1.isValid)
        assertTrue(val2.isValid)
    }

    @Test
    fun test7_workflowCancellationClearsTransientContext() {
        val context = WorkflowContext(
            workflowId = "wf-cancel-test",
            authorizedGoal = "Transfer text"
        )
        context.setUserProvidedText("Meeting notes for team")
        context.verifiedSourcePackage = "com.google.android.keep"
        context.verifiedDestinationPackage = "com.whatsapp"

        assertEquals("Meeting notes for team", context.userProvidedText)

        // Cancel and clear
        context.isCancelled = true
        context.clearTransientData()

        assertNull("Transient text must be wiped on cancellation", context.userProvidedText)
        assertNull("Verified source must be wiped on cancellation", context.verifiedSourcePackage)
        assertNull("Verified destination must be wiped on cancellation", context.verifiedDestinationPackage)
        assertTrue(context.isCancelled)
    }

    @Test
    fun test8_expiredWorkflowContextCannotResumeWithoutRevalidation() {
        val context = WorkflowContext(
            workflowId = "wf-expiry-test",
            authorizedGoal = "Inspect apps",
            createdAtMillis = System.currentTimeMillis() - 200_000L,
            lastUpdatedMillis = System.currentTimeMillis() - 150_000L // 150 seconds ago (> 120s limit)
        )

        assertTrue("Context older than 120s must be marked expired", context.isExpired(120_000L))
    }
}

