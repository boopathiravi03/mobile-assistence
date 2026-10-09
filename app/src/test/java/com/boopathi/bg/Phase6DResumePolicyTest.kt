package com.boopathi.bg

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boopathi.bg.data.local.entity.WorkflowHistoryEntity
import com.boopathi.bg.service.workflow.CrossAppWorkflowStatus
import com.boopathi.bg.service.workflow.CrossAppWorkflowStep
import com.boopathi.bg.service.workflow.WorkflowAppRole
import com.boopathi.bg.service.workflow.WorkflowResumePolicy
import com.boopathi.bg.service.workflow.WorkflowStepSerializer
import com.boopathi.bg.service.workflow.WorkflowType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 6D Unit Tests: Workflow Resume Policy & Duplicate Action Prevention.
 */
@RunWith(AndroidJUnit4::class)
class Phase6DResumePolicyTest {

    private val resumePolicy = WorkflowResumePolicy()

    private fun createSampleHistoryEntity(
        status: CrossAppWorkflowStatus = CrossAppWorkflowStatus.INTERRUPTED,
        isEligible: Boolean = true,
        expiresAt: Long = System.currentTimeMillis() + 3600_000L,
        consequentialAttempted: Boolean = false,
        consequentialUncertain: Boolean = false,
        steps: List<CrossAppWorkflowStep> = listOf(
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
                appRole = WorkflowAppRole.DESTINATION,
                targetApp = "youtube",
                expectedPackage = "com.google.android.youtube",
                actionReference = "OPEN_APP",
                expectedOutcome = "YouTube opened",
                isHandoff = true
            )
        )
    ): WorkflowHistoryEntity {
        return WorkflowHistoryEntity(
            workflowId = "wf_test_resume",
            goalDescription = "Open Settings then open YouTube",
            workflowType = WorkflowType.INSPECT_ACROSS_APPS.name,
            status = status.name,
            currentStepIndex = 1,
            totalSteps = steps.size,
            expiresAt = expiresAt,
            isEligibleForResume = isEligible,
            consequentialActionAttempted = consequentialAttempted,
            consequentialOutcomeUncertain = consequentialUncertain,
            stepsJson = WorkflowStepSerializer.serializeSteps(steps)
        )
    }

    @Test
    fun test14_validEligibleNonConsequentialTaskCanRequestResumption() {
        val entity = createSampleHistoryEntity()
        val result = resumePolicy.validateResumeEligibility(entity)

        assertTrue("Eligible non-consequential task must be accepted for resumption", result.isEligible)
        assertNotNull(result.revalidatedWorkflow)
        assertEquals(1, result.revalidatedWorkflow?.currentStepIndex)
    }

    @Test
    fun test15_expiredTaskIsRejected() {
        val entity = createSampleHistoryEntity(
            expiresAt = System.currentTimeMillis() - 1000L
        )
        val result = resumePolicy.validateResumeEligibility(entity)

        assertFalse("Expired workflow must be rejected from resumption", result.isEligible)
        assertTrue(result.rejectionReason?.contains("expired", ignoreCase = true) == true)
    }

    @Test
    fun test16_unsupportedAppIsRejected() {
        val invalidStep = CrossAppWorkflowStep(
            id = "s1",
            appRole = WorkflowAppRole.SOURCE,
            targetApp = "unknown_suspicious_malware_app",
            expectedPackage = "com.suspicious.app",
            actionReference = "INSPECT_SCREEN",
            expectedOutcome = "Inspect unknown"
        )
        val entity = createSampleHistoryEntity(
            steps = listOf(invalidStep)
        ).copy(currentStepIndex = 0)

        val result = resumePolicy.validateResumeEligibility(entity)
        assertFalse("Resume targeting unsupported app must be rejected", result.isEligible)
    }

    @Test
    fun test17_wrongForegroundAppBlocksResume() {
        val inspectStep = CrossAppWorkflowStep(
            id = "s2",
            appRole = WorkflowAppRole.DESTINATION,
            targetApp = "youtube",
            expectedPackage = "com.google.android.youtube",
            actionReference = "INSPECT_SCREEN",
            expectedOutcome = "Inspect YouTube"
        )
        val entity = createSampleHistoryEntity(
            steps = listOf(
                CrossAppWorkflowStep("s1", WorkflowAppRole.SOURCE, "settings", "com.android.settings", "OPEN_APP", expectedOutcome = "Open"),
                inspectStep
            )
        ).copy(currentStepIndex = 1)

        val result = resumePolicy.validateResumeEligibility(
            entity,
            currentForegroundPackage = "com.whatsapp" // Expected com.google.android.youtube
        )

        assertFalse("Wrong foreground app must block resumption", result.isEligible)
        assertTrue(result.rejectionReason?.contains("does not match expected app") == true)
    }

    @Test
    fun test22_interruptedMessageDispatchDoesNotAutomaticallyResend() {
        val sendStep = CrossAppWorkflowStep(
            id = "s3",
            appRole = WorkflowAppRole.DESTINATION,
            targetApp = "whatsapp",
            expectedPackage = "com.whatsapp",
            actionReference = "SEND_MESSAGE",
            expectedOutcome = "Send message"
        )
        val entity = createSampleHistoryEntity(
            steps = listOf(sendStep)
        ).copy(currentStepIndex = 0)

        val result = resumePolicy.validateResumeEligibility(entity)
        assertFalse("Resume must never automatically execute consequential SEND_MESSAGE", result.isEligible)
    }

    @Test
    fun test23_uncertainPreviousSendRemainsUnverified() {
        val entity = createSampleHistoryEntity(
            consequentialAttempted = true,
            consequentialUncertain = true
        )
        val result = resumePolicy.validateResumeEligibility(entity)

        assertFalse("Uncertain consequential outcome must prohibit resumption to prevent duplicate dispatch", result.isEligible)
        assertTrue(result.rejectionReason?.contains("prevent duplicate action") == true)
    }

    @Test
    fun test9_10_11_terminalTasksCannotBeResumed() {
        for (terminalStatus in listOf(
            CrossAppWorkflowStatus.SUCCESS,
            CrossAppWorkflowStatus.FAILED,
            CrossAppWorkflowStatus.CANCELLED,
            CrossAppWorkflowStatus.EXPIRED
        )) {
            val entity = createSampleHistoryEntity(status = terminalStatus)
            val result = resumePolicy.validateResumeEligibility(entity)
            assertFalse("Terminal status $terminalStatus cannot be resumed", result.isEligible)
        }
    }
}

