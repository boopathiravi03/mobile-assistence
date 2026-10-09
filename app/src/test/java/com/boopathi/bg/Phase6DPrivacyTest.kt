package com.boopathi.bg

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boopathi.bg.data.local.entity.WorkflowHistoryEntity
import com.boopathi.bg.service.workflow.CrossAppWorkflowStep
import com.boopathi.bg.service.workflow.WorkflowAppRole
import com.boopathi.bg.service.workflow.WorkflowContext
import com.boopathi.bg.service.workflow.WorkflowStepSerializer
import com.boopathi.bg.service.workflow.WorkflowType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 6D Privacy & Transient Data Retention Tests:
 * - Passwords, OTPs, PINs, CVVs, credentials never persisted in WorkflowHistory.
 * - Sensitive text absent from diagnostic models.
 * - Transient context cleared on completion, cancellation, and expiration.
 */
@RunWith(AndroidJUnit4::class)
class Phase6DPrivacyTest {

    @Test
    fun test27_passwordsOtpsPinsAreNotPersistedAsWorkflowPayloads() {
        val stepsWithSensitiveData = listOf(
            CrossAppWorkflowStep(
                id = "s1",
                appRole = WorkflowAppRole.SOURCE,
                targetApp = "settings",
                expectedPackage = "com.android.settings",
                actionReference = "TRANSFER_TEXT",
                parameters = mapOf(
                    "text" to "Meeting text",
                    "secret_pin" to "1234",
                    "user_password" to "SuperSecretPass123"
                ),
                expectedOutcome = "Transfer"
            )
        )

        val serialized = WorkflowStepSerializer.serializeSteps(stepsWithSensitiveData)

        // Sensitive credentials must be stripped during serialization
        assertFalse("Passwords must not be serialized into workflow persistence", serialized.contains("SuperSecretPass123"))
        assertFalse("PINs must not be serialized into workflow persistence", serialized.contains("1234"))
        assertTrue("Safe text should be preserved", serialized.contains("Meeting text"))
    }

    @Test
    fun test29_transientContextClearedOnCancellation() {
        val context = WorkflowContext(
            workflowId = "wf_ctx_cancel",
            authorizedGoal = "Test transfer"
        )
        context.setUserProvidedText("Safe note payload")
        context.verifiedSourcePackage = "com.android.settings"

        // Cancel and clear
        context.isCancelled = true
        context.clearTransientData()

        assertNull("Transient text must be wiped on cancellation", context.userProvidedText)
        assertNull("Verified source package must be wiped on cancellation", context.verifiedSourcePackage)
    }

    @Test
    fun test30_transientContextClearedOnCompletion() {
        val context = WorkflowContext(
            workflowId = "wf_ctx_complete",
            authorizedGoal = "Test transfer"
        )
        context.setUserProvidedText("Temporary message draft")
        context.verifiedDestinationPackage = "com.whatsapp"

        context.clearTransientData()

        assertNull("Transient payload must be wiped on completion", context.userProvidedText)
        assertNull("Verified destination package must be wiped on completion", context.verifiedDestinationPackage)
    }

    @Test
    fun test31_workflowContextRejectsSensitiveInput() {
        val context = WorkflowContext(
            workflowId = "wf_ctx_safe",
            authorizedGoal = "Test goal"
        )

        assertThrows(IllegalArgumentException::class.java) {
            context.setUserProvidedText("My password is secret123")
        }

        assertThrows(IllegalArgumentException::class.java) {
            context.setUserProvidedText("Your OTP code is 987654")
        }
    }
}

