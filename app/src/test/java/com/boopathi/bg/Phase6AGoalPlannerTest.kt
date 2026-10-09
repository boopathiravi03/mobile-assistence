package com.boopathi.bg

import com.boopathi.bg.data.model.ChatMessage
import com.boopathi.bg.data.model.CommandResult
import com.boopathi.bg.data.model.OwnerProfile
import com.boopathi.bg.service.ai.AIProvider
import com.boopathi.bg.service.planner.AgentGoal
import com.boopathi.bg.service.planner.AgentPlan
import com.boopathi.bg.service.planner.AgentPlanRisk
import com.boopathi.bg.service.planner.AgentPlanStatus
import com.boopathi.bg.service.planner.AgentPlanner
import com.boopathi.bg.service.planner.GoalCategory
import com.boopathi.bg.service.planner.PlannedAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class Phase6AGoalPlannerTest {

    private val owner = OwnerProfile(name = "Boopathi", preferredLanguage = "en")

    private class MockAIProvider(
        override val name: String = "MockAI",
        override val isAvailable: Boolean = true,
        var jsonResponse: String = ""
    ) : AIProvider {
        override suspend fun processCommand(
            prompt: String,
            owner: OwnerProfile,
            conversationHistory: List<ChatMessage>,
            relevantMemories: List<com.boopathi.bg.data.local.entity.MemoryEntity>
        ): CommandResult {
            return CommandResult(
                textResponse = jsonResponse,
                providerName = name,
                isSuccess = jsonResponse.isNotBlank()
            )
        }
    }

    // 1. Goal extraction from natural language (English)
    @Test
    fun testGoalExtractionEnglish() = runBlocking {
        val planner = AgentPlanner()
        val plan = planner.createDeterministicLocalPlan("Open YouTube and tap Subscriptions")
        assertNotNull(plan)
        assertEquals("Open YouTube and tap Subscriptions", plan!!.goal.normalizedGoal)
        assertEquals(GoalCategory.MULTI_STEP, plan.goal.category)
        assertEquals(4, plan.steps.size)
        assertEquals(PlannedAction.OPEN_APP, plan.steps[0].action)
        assertEquals("YouTube", plan.steps[0].target)
        assertEquals(PlannedAction.WAIT_FOR_WINDOW, plan.steps[1].action)
        assertEquals(PlannedAction.INSPECT_UI, plan.steps[2].action)
        assertEquals(PlannedAction.TAP, plan.steps[3].action)
        assertEquals("Subscriptions", plan.steps[3].target)
    }

    // 2. Goal extraction from Tanglish
    @Test
    fun testGoalExtractionTanglish() = runBlocking {
        val planner = AgentPlanner()
        val plan = planner.createDeterministicLocalPlan("YouTube open panni search tap pannu")
        assertNotNull(plan)
        assertEquals("Open YouTube and tap Search", plan!!.goal.normalizedGoal)
        assertEquals(GoalCategory.MULTI_STEP, plan.goal.category)
        assertEquals(4, plan.steps.size)
        assertEquals(PlannedAction.TAP, plan.steps[3].action)
        assertEquals("Search", plan.steps[3].target)
    }

    // 3. Goal categorization: DEVICE_CONTROL / APP_NAVIGATION / MESSAGING / NOTIFICATION_QUERY
    @Test
    fun testGoalCategorization() = runBlocking {
        val planner = AgentPlanner()

        // Notification query
        val notifPlan = planner.createDeterministicLocalPlan("innaiku enna notifications vandhurukku")
        assertNotNull(notifPlan)
        assertEquals(GoalCategory.NOTIFICATION_QUERY, notifPlan!!.goal.category)
        assertEquals(PlannedAction.READ_NOTIFICATION, notifPlan.steps[0].action)

        // Messaging
        val msgPlan = planner.createDeterministicLocalPlan("Karthi-ku hi message anuppu")
        assertNotNull(msgPlan)
        assertEquals(GoalCategory.MESSAGING, msgPlan!!.goal.category)
        assertEquals("Karthi", msgPlan.goal.entities["recipient"])
        assertEquals("hi", msgPlan.goal.entities["message"])

        // App navigation
        val navPlan = planner.createDeterministicLocalPlan("open Settings and scroll down")
        assertNotNull(navPlan)
        assertEquals(GoalCategory.APP_NAVIGATION, navPlan!!.goal.category)
        assertEquals(PlannedAction.OPEN_APP, navPlan.steps[0].action)
        assertEquals(PlannedAction.SCROLL, navPlan.steps[2].action)
    }

    // 4. Multi-step plan generation (ordered, typed)
    @Test
    fun testMultiStepPlanGeneration() = runBlocking {
        val planner = AgentPlanner()
        val plan = planner.planGoal("Open WhatsApp and tap Status", owner)
        assertNotNull(plan)
        assertEquals(AgentPlanStatus.VALIDATED, plan.status)
        assertEquals(4, plan.steps.size)
        assertEquals("s1", plan.steps[0].stepId)
        assertEquals(PlannedAction.OPEN_APP, plan.steps[0].action)
        assertEquals("WhatsApp", plan.steps[0].target)
        assertEquals(PlannedAction.WAIT_FOR_WINDOW, plan.steps[1].action)
        assertEquals(PlannedAction.INSPECT_UI, plan.steps[2].action)
        assertEquals(PlannedAction.TAP, plan.steps[3].action)
    }

    // 5. LLM JSON plan parsing with clean confidence and steps
    @Test
    fun testLlmJsonPlanParsing() = runBlocking {
        val mockAi = MockAIProvider(
            jsonResponse = """
            {
              "goal": {
                "normalizedGoal": "Check battery settings",
                "category": "APP_NAVIGATION",
                "confidence": 0.95
              },
              "explanation": "Navigating to battery settings",
              "steps": [
                {
                  "stepId": "s1",
                  "action": "OPEN_APP",
                  "target": "Settings",
                  "description": "Open Settings app"
                },
                {
                  "stepId": "s2",
                  "action": "TAP",
                  "target": "Battery",
                  "description": "Tap Battery option"
                }
              ]
            }
            """.trimIndent()
        )
        val planner = AgentPlanner(aiProvider = mockAi)
        val plan = planner.planGoal("Show my battery percentage", owner)

        assertNotNull(plan)
        assertEquals("Check battery settings", plan.goal.normalizedGoal)
        assertEquals(GoalCategory.APP_NAVIGATION, plan.goal.category)
        assertEquals(2, plan.steps.size)
        assertEquals(AgentPlanStatus.VALIDATED, plan.status)
    }

    // 6. Clarification request for low confidence (< 0.70)
    @Test
    fun testLowConfidenceClarification() = runBlocking {
        val mockAi = MockAIProvider(
            jsonResponse = """
            {
              "goal": {
                "normalizedGoal": "Unclear intent",
                "category": "UNKNOWN",
                "confidence": 0.45
              },
              "explanation": "Uncertain about goal",
              "steps": []
            }
            """.trimIndent()
        )
        val planner = AgentPlanner(aiProvider = mockAi)
        val plan = planner.planGoal("do the thing with that one", owner)

        assertNotNull(plan)
        assertEquals(AgentPlanStatus.WAITING_FOR_CLARIFICATION, plan.status)
        assertTrue(plan.explanation.contains("clarify", ignoreCase = true))
    }
}

