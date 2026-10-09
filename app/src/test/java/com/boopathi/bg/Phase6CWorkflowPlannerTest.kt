package com.boopathi.bg

import com.boopathi.bg.data.model.OwnerProfile
import com.boopathi.bg.service.ai.AIProvider
import com.boopathi.bg.service.workflow.CrossAppWorkflowPlanner
import com.boopathi.bg.service.workflow.CrossAppWorkflowStatus
import com.boopathi.bg.service.workflow.WorkflowAppRole
import com.boopathi.bg.service.workflow.WorkflowType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/**
 * Phase 6C Unit Test Suite: Workflow Planner, Ambiguity Detection, and Language Parsing.
 */
@RunWith(AndroidJUnit4::class)
class Phase6CWorkflowPlannerTest {

    private val owner = OwnerProfile(name = "Boopathi", preferredLanguage = "ta")

    @Test
    fun testPlanner_parsesInspectAcrossApps_English() {
        val planner = CrossAppWorkflowPlanner()
        val wf = planner.createDeterministicWorkflow("Open Settings, inspect the screen, then open YouTube")

        assertNotNull("Should parse English multi-app inspection", wf)
        assertEquals(WorkflowType.INSPECT_ACROSS_APPS, wf?.type)
        assertEquals(4, wf?.steps?.size)
        assertEquals("settings", wf?.steps?.get(0)?.targetApp)
        assertEquals("youtube", wf?.steps?.get(2)?.targetApp)
        assertTrue(wf?.steps?.get(2)?.isHandoff == true)
    }

    @Test
    fun testPlanner_parsesInspectAcrossApps_Tamil() {
        val planner = CrossAppWorkflowPlanner()
        val wf = planner.createDeterministicWorkflow("Settings open panni, paaru, apram YouTube open pannu")

        assertNotNull("Should parse Tamil multi-app inspection", wf)
        assertEquals(WorkflowType.INSPECT_ACROSS_APPS, wf?.type)
        assertEquals(4, wf?.steps?.size)
    }

    @Test
    fun testPlanner_parsesTransferText_English() {
        val planner = CrossAppWorkflowPlanner()
        val wf = planner.createDeterministicWorkflow("Put 'Meeting at 4 PM' into a draft in whatsapp")

        assertNotNull("Should parse text transfer workflow", wf)
        assertEquals(WorkflowType.TRANSFER_USER_PROVIDED_TEXT, wf?.type)
        assertEquals("Meeting at 4 PM", wf?.steps?.get(1)?.parameters?.get("text"))
        assertEquals("whatsapp", wf?.steps?.get(0)?.targetApp)
    }

    @Test
    fun testPlanner_parsesPrepareCrossAppMessage() {
        val planner = CrossAppWorkflowPlanner()
        val wf = planner.createDeterministicWorkflow("WhatsApp-la Karthi-ku hi message anuppa ready pannu")

        assertNotNull("Should parse messaging preparation workflow", wf)
        assertEquals(WorkflowType.PREPARE_CROSS_APP_MESSAGE, wf?.type)
        assertEquals(CrossAppWorkflowStatus.WAITING_FOR_CONFIRMATION, wf?.status)
        assertEquals("Karthi", wf?.steps?.get(1)?.parameters?.get("recipient"))
        assertEquals("hi", wf?.steps?.get(1)?.parameters?.get("message"))
    }

    @Test
    fun testPlanner_ambiguousCommandRequiresClarification() {
        val planner = CrossAppWorkflowPlanner()
        val wf = planner.createDeterministicWorkflow("Take that text and send it there")

        assertNull("Ambiguous source/destination must return null to trigger clarification", wf)
    }

    @Test
    fun testPlanner_aiFallbackStructuredJsonParsing() {
        val fakeAiProvider = object : AIProvider {
            override val name: String = "FakeAI"
            override val isAvailable: Boolean = true
            override suspend fun processCommand(
                prompt: String,
                owner: OwnerProfile,
                conversationHistory: List<com.boopathi.bg.data.model.ChatMessage>,
                relevantMemories: List<com.boopathi.bg.data.local.entity.MemoryEntity>
            ) = com.boopathi.bg.data.model.CommandResult(
                textResponse = """
                {
                  "type": "INSPECT_ACROSS_APPS",
                  "goalDescription": "Inspect Settings and Calculator",
                  "steps": [
                    {
                      "appRole": "SOURCE",
                      "targetApp": "settings",
                      "expectedPackage": "com.android.settings",
                      "actionReference": "OPEN_APP",
                      "expectedOutcome": "Settings opened",
                      "isHandoff": false
                    },
                    {
                      "appRole": "DESTINATION",
                      "targetApp": "calculator",
                      "expectedPackage": "com.google.android.calculator",
                      "actionReference": "OPEN_APP",
                      "expectedOutcome": "Calculator opened",
                      "isHandoff": true
                    }
                  ]
                }
                """.trimIndent(),
                providerName = "Fake",
                isSuccess = true
            )
        }

        val planner = CrossAppWorkflowPlanner(aiProvider = fakeAiProvider)
        val wf = runBlocking { planner.planWorkflow("Custom multi-app request", owner) }

        assertNotNull(wf)
        assertEquals(WorkflowType.INSPECT_ACROSS_APPS, wf?.type)
        assertEquals(2, wf?.steps?.size)
        assertEquals("calculator", wf?.steps?.get(1)?.targetApp)
    }

    @Test
    fun testPlanner_malformedAiOutputRejected() {
        val fakeAiProvider = object : AIProvider {
            override val name: String = "FakeMalformedAI"
            override val isAvailable: Boolean = true
            override suspend fun processCommand(
                prompt: String,
                owner: OwnerProfile,
                conversationHistory: List<com.boopathi.bg.data.model.ChatMessage>,
                relevantMemories: List<com.boopathi.bg.data.local.entity.MemoryEntity>
            ) = com.boopathi.bg.data.model.CommandResult(
                textResponse = "Not valid JSON at all!",
                providerName = "Fake",
                isSuccess = true
            )
        }

        val planner = CrossAppWorkflowPlanner(aiProvider = fakeAiProvider)
        val wf = runBlocking { planner.planWorkflow("Arbitrary request", owner) }

        assertNull("Malformed JSON from AI must not result in an executable workflow", wf)
    }
}

