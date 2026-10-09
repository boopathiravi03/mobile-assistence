package com.boopathi.bg

import com.boopathi.bg.service.planner.AgentGoal
import com.boopathi.bg.service.planner.AgentPlan
import com.boopathi.bg.service.planner.AgentPlanRisk
import com.boopathi.bg.service.planner.AgentPlanStatus
import com.boopathi.bg.service.planner.AgentPlanStep
import com.boopathi.bg.service.planner.AgentPlanValidator
import com.boopathi.bg.service.planner.GoalCategory
import com.boopathi.bg.service.planner.PlannedAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase6APlanValidatorTest {

    private val validator = AgentPlanValidator()

    private fun createSamplePlan(steps: List<AgentPlanStep>): AgentPlan {
        return AgentPlan(
            goal = AgentGoal(
                rawInput = "Sample task",
                normalizedGoal = "Sample task",
                category = GoalCategory.MULTI_STEP
            ),
            steps = steps
        )
    }

    // 1. Valid plan passes
    @Test
    fun testValidPlanPasses() {
        val steps = listOf(
            AgentPlanStep("s1", PlannedAction.OPEN_APP, target = "Settings"),
            AgentPlanStep("s2", PlannedAction.WAIT_FOR_WINDOW, target = "com.android.settings"),
            AgentPlanStep("s3", PlannedAction.TAP, target = "Display")
        )
        val result = validator.validatePlan(createSamplePlan(steps))
        assertTrue(result.isValid)
        assertNotNull(result.validatedPlan)
        assertEquals(AgentPlanStatus.VALIDATED, result.validatedPlan!!.status)
        assertEquals(AgentPlanRisk.LOW, result.validatedPlan!!.risk)
        assertFalse(result.validatedPlan!!.requiresConfirmation)
    }

    // 2. Empty plan rejected
    @Test
    fun testEmptyPlanRejected() {
        val result = validator.validatePlan(createSamplePlan(emptyList()))
        assertFalse(result.isValid)
        assertNull(result.validatedPlan)
        assertTrue(result.rejectionReason!!.contains("empty", ignoreCase = true))
    }

    // 3. Plan > 10 steps rejected with exact bounded error
    @Test
    fun testPlanOverTenStepsRejected() {
        val steps = (1..11).map {
            AgentPlanStep("s$it", PlannedAction.SCROLL)
        }
        val result = validator.validatePlan(createSamplePlan(steps))
        assertFalse(result.isValid)
        assertNull(result.validatedPlan)
        assertEquals("That task is too large for one operation. I need to break it into smaller steps.", result.rejectionReason)
    }

    // 4. Prohibited sensitive action (OTP, password, credentials) rejected
    @Test
    fun testProhibitedSensitiveActionRejected() {
        val otpStep = AgentPlanStep(
            "s1",
            PlannedAction.TYPE_TEXT,
            target = "Enter OTP",
            parameters = mapOf("text" to "123456")
        )
        val result = validator.validatePlan(createSamplePlan(listOf(otpStep)))
        assertFalse(result.isValid)
        assertNull(result.validatedPlan)
        assertTrue(result.rejectionReason!!.contains("sensitive", ignoreCase = true))

        val passwordStep = AgentPlanStep(
            "s1",
            PlannedAction.TAP,
            target = "Show password"
        )
        val res2 = validator.validatePlan(createSamplePlan(listOf(passwordStep)))
        assertFalse(res2.isValid)
    }

    // 5. Financial / destructive actions rejected
    @Test
    fun testFinancialOrDestructiveActionsRejected() {
        val payStep = AgentPlanStep(
            "s1",
            PlannedAction.TAP,
            target = "Pay Now"
        )
        val result = validator.validatePlan(createSamplePlan(listOf(payStep)))
        assertFalse(result.isValid)
        assertNull(result.validatedPlan)
        assertTrue(result.rejectionReason!!.contains("financial", ignoreCase = true))

        val factoryResetStep = AgentPlanStep(
            "s1",
            PlannedAction.TAP,
            target = "Factory Reset"
        )
        val res2 = validator.validatePlan(createSamplePlan(listOf(factoryResetStep)))
        assertFalse(res2.isValid)
    }

    // 6. Confirmation requirement enforcement (ignoring LLM proposed low risk)
    @Test
    fun testConfirmationRequirementEnforcement() {
        // Even if LLM proposes LOW risk and requiresConfirmation=false for sending a message,
        // validator forces MEDIUM risk and requiresConfirmation=true
        val sendMsgStep = AgentPlanStep(
            stepId = "s1",
            action = PlannedAction.SEND_MESSAGE,
            target = "Karthi",
            parameters = mapOf("recipient" to "Karthi", "message" to "Hello"),
            risk = AgentPlanRisk.LOW,
            requiresConfirmation = false
        )
        val result = validator.validatePlan(createSamplePlan(listOf(sendMsgStep)))
        assertTrue(result.isValid)
        assertNotNull(result.validatedPlan)
        assertEquals(AgentPlanRisk.MEDIUM, result.validatedPlan!!.risk)
        assertTrue(result.validatedPlan!!.requiresConfirmation)
        assertEquals(AgentPlanStatus.NEEDS_CONFIRMATION, result.validatedPlan!!.status)
    }

    // 7. Medium risk tap requires confirmation
    @Test
    fun testMediumRiskTapRequiresConfirmation() {
        val deleteTapStep = AgentPlanStep(
            stepId = "s1",
            action = PlannedAction.TAP,
            target = "Delete chat",
            risk = AgentPlanRisk.LOW,
            requiresConfirmation = false
        )
        val result = validator.validatePlan(createSamplePlan(listOf(deleteTapStep)))
        assertTrue(result.isValid)
        assertEquals(AgentPlanRisk.MEDIUM, result.validatedPlan!!.risk)
        assertTrue(result.validatedPlan!!.requiresConfirmation)
        assertEquals(AgentPlanStatus.NEEDS_CONFIRMATION, result.validatedPlan!!.status)
    }
}

