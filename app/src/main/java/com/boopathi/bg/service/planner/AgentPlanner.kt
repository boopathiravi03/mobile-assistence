package com.boopathi.bg.service.planner

import android.util.Log
import com.boopathi.bg.data.model.OwnerProfile
import com.boopathi.bg.service.agent.AppAction
import com.boopathi.bg.service.agent.AppPlan
import com.boopathi.bg.service.agent.AppPlanner
import com.boopathi.bg.service.agent.ScrollDirection
import com.boopathi.bg.service.ai.AIProvider
import com.boopathi.bg.service.messaging.MessagingTargetResolver
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class AgentPlanner(
    private val aiProvider: AIProvider? = null,
    private val localAppPlanner: AppPlanner = AppPlanner(),
    private val planValidator: AgentPlanValidator = AgentPlanValidator()
) {

    companion object {
        private const val TAG = "AgentPlanner"
        private const val CONFIDENCE_THRESHOLD = 0.70f

        val PLANNING_SYSTEM_PROMPT = """
You are the natural-language task planner for BG, an Android AI assistant.
Your goal is to parse user instructions (in English, Tamil, or Tanglish) into a bounded, structured task plan.

You MUST respond strictly with valid JSON. Do not include markdown ticks, explanation outside JSON, or conversational filler.
JSON schema:
{
  "goal": {
    "normalizedGoal": "Short summary of the task in English",
    "category": "DEVICE_CONTROL | APP_NAVIGATION | APP_INSPECTION | SEARCH | MESSAGING | NOTIFICATION_QUERY | MEMORY | INFORMATION | MULTI_STEP | UNKNOWN",
    "entities": {
      "app": "optional app name",
      "recipient": "optional recipient",
      "target": "optional target element"
    },
    "confidence": 0.0 to 1.0
  },
  "explanation": "Brief explanation of the plan",
  "steps": [
    {
      "stepId": "s1",
      "action": "OPEN_APP | WAIT_FOR_WINDOW | INSPECT_UI | TAP | SCROLL | TYPE_TEXT | BACK | HOME | DEVICE_TOOL | READ_NOTIFICATION | QUERY_MEMORY | PREPARE_MESSAGE | SEND_MESSAGE | ASK_USER",
      "target": "target app name, button label, query, etc.",
      "parameters": {
        "text": "text to type if any",
        "direction": "DOWN | UP (for SCROLL)",
        "tool": "FLASHLIGHT | VOLUME | OPEN_SETTINGS (for DEVICE_TOOL)",
        "recipient": "contact name",
        "message": "message content"
      },
      "description": "Short human readable description of this step"
    }
  ]
}

CRITICAL RULES:
1. Steps count must be between 1 and 10. Never exceed 10 steps.
2. NEVER output direct shell commands, private APIs, credentials, OTP, or financial actions.
3. For messaging: use PREPARE_MESSAGE or SEND_MESSAGE with explicit recipient and text.
4. If the user input is ambiguous or unclear, output a plan with confidence < 0.70 or action ASK_USER.
        """.trimIndent()
    }

    suspend fun planGoal(userInput: String, owner: OwnerProfile): AgentPlan {
        val trimmed = userInput.trim()

        // 1. First attempt deterministic local plan parsing (fastest, 100% reliable for standard commands)
        val localPlan = createDeterministicLocalPlan(trimmed)
        if (localPlan != null) {
            val validation = planValidator.validatePlan(localPlan)
            if (validation.isValid && validation.validatedPlan != null) {
                Log.d(TAG, "Local deterministic plan generated and validated successfully: ${localPlan.goal.normalizedGoal}")
                return validation.validatedPlan
            }
        }

        // 2. If AI Provider is available, attempt dynamic LLM planning
        if (aiProvider != null && aiProvider.isAvailable) {
            try {
                val structuredPlan = queryAiForPlan(trimmed, owner)
                if (structuredPlan != null) {
                    if (structuredPlan.goal.confidence < CONFIDENCE_THRESHOLD) {
                        Log.d(TAG, "AI plan confidence ${structuredPlan.goal.confidence} below threshold $CONFIDENCE_THRESHOLD; asking for clarification.")
                        return structuredPlan.copy(
                            status = AgentPlanStatus.WAITING_FOR_CLARIFICATION,
                            explanation = "I'm not completely sure what you'd like me to do. Could you clarify your request?"
                        )
                    }

                    val validation = planValidator.validatePlan(structuredPlan)
                    if (validation.isValid && validation.validatedPlan != null) {
                        Log.d(TAG, "AI plan validated successfully: ${structuredPlan.goal.normalizedGoal}")
                        return validation.validatedPlan
                    } else {
                        Log.w(TAG, "AI plan rejected by local policy.")
                        return structuredPlan.copy(
                            status = AgentPlanStatus.REJECTED,
                            explanation = validation.rejectionReason ?: "Plan was rejected by safety policy."
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "AI dynamic planning failed safely (${e.javaClass.simpleName})")
            }
        }

        // 3. Fallback when neither local nor AI produced a valid executable plan
        val fallbackGoal = AgentGoal(
            rawInput = trimmed,
            normalizedGoal = "Clarify: $trimmed",
            category = GoalCategory.UNKNOWN,
            confidence = 0.0f
        )
        return AgentPlan(
            goal = fallbackGoal,
            steps = emptyList(),
            explanation = "I could not formulate a safe plan for that request.",
            status = AgentPlanStatus.WAITING_FOR_CLARIFICATION
        )
    }

    fun createDeterministicLocalPlan(userInput: String): AgentPlan? {
        val clean = userInput.trim().lowercase()
            .removeSuffix(".")
            .removeSuffix("?")
            .removeSuffix("!")
            .trim()

        // Check for messaging preparation intents first (e.g., "send hi to karthi on whatsapp" or "karthi-ku hi message anuppu")
        val messagingGoal = tryParseMessagingPlan(userInput)
        if (messagingGoal != null) {
            return messagingGoal
        }

        // Check for Notification querying
        if (clean in listOf(
                "notifications", "read notifications", "show notifications",
                "enna notifications vandhurukku", "notifications enna irukku",
                "check notifications", "any new notifications"
            ) || clean.contains("notifications vandhurukku") || clean.contains("read notifications")
        ) {
            val goal = AgentGoal(
                rawInput = userInput,
                normalizedGoal = "Read recent notifications",
                category = GoalCategory.NOTIFICATION_QUERY,
                confidence = 1.0f
            )
            val step = AgentPlanStep(
                stepId = UUID.randomUUID().toString(),
                action = PlannedAction.READ_NOTIFICATION,
                target = "notifications",
                description = "Read privacy-filtered recent notifications"
            )
            return AgentPlan(
                goal = goal,
                steps = listOf(step),
                explanation = "Check incoming notifications",
                status = AgentPlanStatus.PROPOSED
            )
        }

        // Check local AppPlanner for UI actions
        val appPlan = localAppPlanner.createPlan(userInput)
        if (appPlan != null) {
            val steps = appPlan.actions.mapIndexed { idx, action ->
                mapAppActionToPlanStep(action, idx + 1)
            }
            val category = when {
                steps.any { it.action == PlannedAction.TAP || it.action == PlannedAction.TYPE_TEXT } -> GoalCategory.MULTI_STEP
                steps.any { it.action == PlannedAction.INSPECT_UI } -> GoalCategory.APP_INSPECTION
                steps.any { it.action == PlannedAction.OPEN_APP } -> GoalCategory.APP_NAVIGATION
                else -> GoalCategory.APP_NAVIGATION
            }

            return AgentPlan(
                goal = AgentGoal(
                    rawInput = userInput,
                    normalizedGoal = appPlan.goal,
                    category = category,
                    confidence = 1.0f
                ),
                steps = steps,
                explanation = "Execute: ${appPlan.goal}",
                status = AgentPlanStatus.PROPOSED
            )
        }

        return null
    }

    private fun tryParseMessagingPlan(userInput: String): AgentPlan? {
        val lower = userInput.lowercase().trim().removeSuffix(".").removeSuffix("!").trim()
        val resolver = MessagingTargetResolver()

        var rawRecipient: String? = null
        var messageBody: String? = null
        val appName = "WhatsApp"

        val tanglishRegex1 = Regex("^(.+?)(?:-ku|\\s+ku)\\s+(.+?)\\s+message\\s+(?:anuppu|prepare\\s+pannu)$", RegexOption.IGNORE_CASE)
        val tanglishRegex2 = Regex("^(.+?)(?:-ku|\\s+ku)\\s+(.+?)\\s+anuppu$", RegexOption.IGNORE_CASE)
        val englishRegex1 = Regex("^(?:send|prepare\\s+message)\\s+(?:to\\s+)?(.+?):?\\s+(?:message\\s+)?(.+)$", RegexOption.IGNORE_CASE)

        val m1 = tanglishRegex1.matchEntire(lower)
        val m2 = tanglishRegex2.matchEntire(lower)
        val m3 = englishRegex1.matchEntire(lower)

        when {
            m1 != null -> {
                rawRecipient = m1.groupValues[1].trim()
                messageBody = m1.groupValues[2].trim()
            }
            m2 != null -> {
                rawRecipient = m2.groupValues[1].trim()
                messageBody = m2.groupValues[2].trim()
            }
            m3 != null -> {
                rawRecipient = m3.groupValues[1].trim()
                messageBody = m3.groupValues[2].trim()
            }
        }

        if (rawRecipient.isNullOrBlank() || messageBody.isNullOrBlank()) {
            return null
        }

        val cleanRecipient = resolver.cleanRecipientName(rawRecipient)
        val goal = AgentGoal(
            rawInput = userInput,
            normalizedGoal = "Send message to $cleanRecipient via $appName",
            category = GoalCategory.MESSAGING,
            entities = mapOf(
                "recipient" to cleanRecipient,
                "app" to appName,
                "message" to messageBody
            ),
            confidence = 1.0f
        )

        val steps = listOf(
            AgentPlanStep(
                stepId = "s1",
                action = PlannedAction.OPEN_APP,
                target = appName,
                description = "Open $appName"
            ),
            AgentPlanStep(
                stepId = "s2",
                action = PlannedAction.WAIT_FOR_WINDOW,
                target = if (appName.equals("whatsapp", ignoreCase = true)) "com.whatsapp" else appName,
                description = "Wait for $appName to appear"
            ),
            AgentPlanStep(
                stepId = "s3",
                action = PlannedAction.PREPARE_MESSAGE,
                target = cleanRecipient,
                parameters = mapOf(
                    "recipient" to cleanRecipient,
                    "message" to messageBody,
                    "app" to appName
                ),
                description = "Prepare message for $cleanRecipient"
            ),
            AgentPlanStep(
                stepId = "s4",
                action = PlannedAction.SEND_MESSAGE,
                target = cleanRecipient,
                parameters = mapOf(
                    "recipient" to cleanRecipient,
                    "message" to messageBody,
                    "app" to appName
                ),
                risk = AgentPlanRisk.MEDIUM,
                requiresConfirmation = true,
                description = "Send message to $cleanRecipient"
            )
        )

        return AgentPlan(
            goal = goal,
            steps = steps,
            requiresConfirmation = true,
            risk = AgentPlanRisk.MEDIUM,
            explanation = "Prepare and confirm message dispatch to $cleanRecipient",
            status = AgentPlanStatus.PROPOSED
        )
    }

    private fun mapAppActionToPlanStep(action: AppAction, stepNum: Int): AgentPlanStep {
        val stepId = "s$stepNum"
        return when (action) {
            is AppAction.OpenApp -> AgentPlanStep(
                stepId = stepId,
                action = PlannedAction.OPEN_APP,
                target = action.appName,
                description = "Open ${action.appName}"
            )
            is AppAction.WaitForWindow -> AgentPlanStep(
                stepId = stepId,
                action = PlannedAction.WAIT_FOR_WINDOW,
                target = action.expectedPackage,
                parameters = mapOf("timeoutMs" to action.timeoutMs.toString()),
                description = "Wait for ${action.expectedPackage} window"
            )
            is AppAction.InspectUi -> AgentPlanStep(
                stepId = stepId,
                action = PlannedAction.INSPECT_UI,
                target = "screen",
                description = "Inspect visible UI elements"
            )
            is AppAction.Tap -> AgentPlanStep(
                stepId = stepId,
                action = PlannedAction.TAP,
                target = action.targetQuery,
                parameters = mapOf("isViewId" to action.isViewId.toString()),
                description = "Tap on ${action.targetQuery}"
            )
            is AppAction.TypeText -> AgentPlanStep(
                stepId = stepId,
                action = PlannedAction.TYPE_TEXT,
                target = action.targetQuery,
                parameters = mapOf("text" to action.textToType),
                description = "Type '${action.textToType}' into ${action.targetQuery}"
            )
            is AppAction.Scroll -> AgentPlanStep(
                stepId = stepId,
                action = PlannedAction.SCROLL,
                target = "screen",
                parameters = mapOf("direction" to action.direction.name),
                description = "Scroll ${action.direction.name.lowercase()}"
            )
            is AppAction.Back -> AgentPlanStep(
                stepId = stepId,
                action = PlannedAction.BACK,
                description = "Navigate Back"
            )
            is AppAction.Home -> AgentPlanStep(
                stepId = stepId,
                action = PlannedAction.HOME,
                description = "Navigate Home"
            )
            is AppAction.SendMessage -> AgentPlanStep(
                stepId = stepId,
                action = PlannedAction.SEND_MESSAGE,
                target = action.expectedRecipient,
                parameters = mapOf("recipient" to action.expectedRecipient, "message" to action.expectedMessage),
                risk = AgentPlanRisk.MEDIUM,
                requiresConfirmation = true,
                description = "Send message to ${action.expectedRecipient}"
            )
        }
    }

    private suspend fun queryAiForPlan(userInput: String, owner: OwnerProfile): AgentPlan? {
        val prompt = "$PLANNING_SYSTEM_PROMPT\n\nUser instruction:\n\"$userInput\""
        val commandResult = aiProvider?.processCommand(
            prompt = prompt,
            owner = owner
        ) ?: return null

        if (!commandResult.isSuccess || commandResult.textResponse.isBlank()) {
            return null
        }

        return parsePlanFromJson(commandResult.textResponse, userInput)
    }

    fun parsePlanFromJson(jsonText: String, originalInput: String): AgentPlan? {
        return try {
            // Clean markdown code blocks if present
            var cleanJson = jsonText.trim()
            if (cleanJson.startsWith("```json")) {
                cleanJson = cleanJson.removePrefix("```json").trim()
            } else if (cleanJson.startsWith("```")) {
                cleanJson = cleanJson.removePrefix("```").trim()
            }
            if (cleanJson.endsWith("```")) {
                cleanJson = cleanJson.removeSuffix("```").trim()
            }

            val root = JSONObject(cleanJson)
            val goalObj = root.optJSONObject("goal") ?: JSONObject()
            val normalizedGoal = goalObj.optString("normalizedGoal", originalInput)
            val categoryStr = goalObj.optString("category", "UNKNOWN").uppercase()
            val category = try {
                GoalCategory.valueOf(categoryStr)
            } catch (e: Exception) {
                GoalCategory.UNKNOWN
            }
            val confidence = goalObj.optDouble("confidence", 1.0).toFloat()

            val entitiesMap = mutableMapOf<String, String>()
            val entitiesObj = goalObj.optJSONObject("entities")
            if (entitiesObj != null) {
                val keys = entitiesObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    entitiesMap[k] = entitiesObj.optString(k, "")
                }
            }

            val goal = AgentGoal(
                rawInput = originalInput,
                normalizedGoal = normalizedGoal,
                category = category,
                entities = entitiesMap,
                confidence = confidence
            )

            val explanation = root.optString("explanation", "")
            val stepsArray = root.optJSONArray("steps") ?: JSONArray()
            val stepsList = mutableListOf<AgentPlanStep>()

            for (i in 0 until stepsArray.length()) {
                val stepObj = stepsArray.getJSONObject(i)
                val stepId = stepObj.optString("stepId", "s${i + 1}")
                val actionStr = stepObj.optString("action", "ASK_USER").uppercase()
                val action = try {
                    PlannedAction.valueOf(actionStr)
                } catch (e: Exception) {
                    PlannedAction.ASK_USER
                }
                val target = if (stepObj.has("target") && !stepObj.isNull("target")) stepObj.getString("target") else null
                val desc = stepObj.optString("description", "")

                val paramsMap = mutableMapOf<String, String>()
                val paramsObj = stepObj.optJSONObject("parameters")
                if (paramsObj != null) {
                    val pKeys = paramsObj.keys()
                    while (pKeys.hasNext()) {
                        val pk = pKeys.next()
                        paramsMap[pk] = paramsObj.optString(pk, "")
                    }
                }

                stepsList.add(
                    AgentPlanStep(
                        stepId = stepId,
                        action = action,
                        target = target,
                        parameters = paramsMap,
                        description = desc
                    )
                )
            }

            AgentPlan(
                goal = goal,
                steps = stepsList,
                explanation = explanation,
                status = AgentPlanStatus.PROPOSED
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse AgentPlan JSON (${e.javaClass.simpleName})")
            null
        }
    }
}

