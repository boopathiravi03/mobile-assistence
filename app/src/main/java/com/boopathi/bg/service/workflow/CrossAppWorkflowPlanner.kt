package com.boopathi.bg.service.workflow

import android.util.Log
import com.boopathi.bg.data.model.OwnerProfile
import com.boopathi.bg.service.ai.AIProvider
import com.boopathi.bg.service.messaging.MessagingTargetResolver
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Plans cross-app workflows using deterministic pattern matching first,
 * with structured AI fallback when available.
 */
class CrossAppWorkflowPlanner(
    private val aiProvider: AIProvider? = null,
    private val validator: CrossAppWorkflowValidator = CrossAppWorkflowValidator(),
    private val messagingTargetResolver: MessagingTargetResolver = MessagingTargetResolver()
) {

    companion object {
        private const val TAG = "CrossAppWorkflowPlanner"

        val WORKFLOW_PLANNING_PROMPT = """
You are the cross-app workflow planner for BG Android assistant.
Plan a multi-app workflow to satisfy the user goal.
Supported Workflow Types:
- INSPECT_ACROSS_APPS
- TRANSFER_USER_PROVIDED_TEXT
- PREPARE_CROSS_APP_MESSAGE
- OPEN_AND_INSPECT_DESTINATION

CRITICAL BOUNDS & RULES:
1. Max 10 steps total, max 4 app handoffs.
2. Only use supported apps: WhatsApp, YouTube, Settings, Messages, Calculator, Camera, Maps, Gmail, Chrome, Keep/Notes.
3. NEVER output shell commands, arbitrary coordinates, credentials, passwords, PINs, or OTPs.
4. Output STRICT JSON conforming to this schema:
{
  "type": "INSPECT_ACROSS_APPS | TRANSFER_USER_PROVIDED_TEXT | PREPARE_CROSS_APP_MESSAGE | OPEN_AND_INSPECT_DESTINATION",
  "goalDescription": "Brief summary",
  "steps": [
    {
      "appRole": "SOURCE | INTERMEDIATE | DESTINATION",
      "targetApp": "Settings | YouTube | WhatsApp | etc",
      "expectedPackage": "com.android.settings | etc",
      "actionReference": "OPEN_APP | INSPECT_SCREEN | TYPE_TEXT | PREPARE_MESSAGE",
      "parameters": {
        "text": "text to type if transfer"
      },
      "expectedOutcome": "Description of expected outcome",
      "requiresConfirmation": false,
      "isHandoff": false
    }
  ]
}
        """.trimIndent()
    }

    suspend fun planWorkflow(userInput: String, owner: OwnerProfile): CrossAppWorkflow? {
        val trimmed = userInput.trim()

        // 1. Deterministic planner first
        val deterministic = createDeterministicWorkflow(trimmed)
        if (deterministic != null) {
            val validation = validator.validateWorkflow(deterministic)
            if (validation.isValid && validation.validatedWorkflow != null) {
                Log.d(TAG, "Deterministic workflow planned & validated: ${deterministic.goalDescription}")
                return validation.validatedWorkflow
            }
        }

        // 2. AI Structured Fallback if available
        if (aiProvider != null && aiProvider.isAvailable) {
            try {
                val aiWorkflow = queryAiForWorkflow(trimmed, owner)
                if (aiWorkflow != null) {
                    val validation = validator.validateWorkflow(aiWorkflow)
                    if (validation.isValid && validation.validatedWorkflow != null) {
                        Log.d(TAG, "AI workflow planned & validated: ${aiWorkflow.goalDescription}")
                        return validation.validatedWorkflow
                    } else {
                        Log.w(TAG, "AI workflow rejected by safety policy: ${validation.rejectionReason}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "AI workflow planning failed: ${e.message}")
            }
        }

        return null
    }

    fun createDeterministicWorkflow(input: String): CrossAppWorkflow? {
        val lower = input.lowercase().trim()
            .removeSuffix(".").removeSuffix("!").removeSuffix("?").trim()

        // 1. Ambiguous check: "Take that text and send it there"
        if (lower.contains("take that text and send it there") ||
            (lower.startsWith("send that") && !lower.contains("to ") && !lower.contains("-ku"))
        ) {
            return null // Return null to trigger clarification
        }

        // 2. INSPECT_ACROSS_APPS: "Open Settings, inspect the screen, then open YouTube"
        // Also supports Tamil: "Settings open panni, paaru, apram YouTube open pannu"
        val inspectMatch = Regex(
            "(?:open\\s+([a-zA-Z]+)|([a-zA-Z]+)\\s+open)(?:[a-zA-Z\\s,]*)(?:inspect|paaru)(?:[a-zA-Z\\s,]*)(?:then|apram|next|and)\\s+(?:open\\s+([a-zA-Z]+)|([a-zA-Z]+)\\s+open)",
            RegexOption.IGNORE_CASE
        ).find(lower)

        if (inspectMatch != null) {
            val app1 = (inspectMatch.groupValues[1].ifBlank { inspectMatch.groupValues[2] }).trim()
            val app2 = (inspectMatch.groupValues[3].ifBlank { inspectMatch.groupValues[4] }).trim()

            val pkg1 = validator.resolvePackage(app1)
            val pkg2 = validator.resolvePackage(app2)

            if (pkg1 != null && pkg2 != null) {
                val steps = listOf(
                    CrossAppWorkflowStep(
                        id = "w1_s1",
                        appRole = WorkflowAppRole.SOURCE,
                        targetApp = app1,
                        expectedPackage = pkg1,
                        actionReference = "OPEN_APP",
                        expectedOutcome = "$app1 opened",
                        isHandoff = false
                    ),
                    CrossAppWorkflowStep(
                        id = "w1_s2",
                        appRole = WorkflowAppRole.SOURCE,
                        targetApp = app1,
                        expectedPackage = pkg1,
                        actionReference = "INSPECT_SCREEN",
                        expectedOutcome = "$app1 screen inspected",
                        isHandoff = false
                    ),
                    CrossAppWorkflowStep(
                        id = "w1_s3",
                        appRole = WorkflowAppRole.DESTINATION,
                        targetApp = app2,
                        expectedPackage = pkg2,
                        actionReference = "OPEN_APP",
                        expectedOutcome = "$app2 opened",
                        isHandoff = true
                    ),
                    CrossAppWorkflowStep(
                        id = "w1_s4",
                        appRole = WorkflowAppRole.DESTINATION,
                        targetApp = app2,
                        expectedPackage = pkg2,
                        actionReference = "INSPECT_SCREEN",
                        expectedOutcome = "$app2 screen inspected",
                        isHandoff = false
                    )
                )

                return CrossAppWorkflow(
                    workflowId = UUID.randomUUID().toString(),
                    type = WorkflowType.INSPECT_ACROSS_APPS,
                    goalDescription = "Inspect $app1 and $app2",
                    steps = steps,
                    status = CrossAppWorkflowStatus.READY
                )
            }
        }

        // 3. TRANSFER_USER_PROVIDED_TEXT:
        // "Open notes and copy this text into a draft in whatsapp: Meeting at 4 PM"
        // "Put 'Meeting at 4 PM' into a draft in whatsapp"
        val transferMatch = Regex(
            "(?:put|copy|transfer|draft)\\s+[\"']?([^\"']+)[\"']?\\s+(?:into|to|in)\\s+(?:a draft in|draft in|the app)?\\s*([a-zA-Z]+)",
            RegexOption.IGNORE_CASE
        ).find(input)

        if (transferMatch != null) {
            val rawText = transferMatch.groupValues[1].trim()
            val destApp = transferMatch.groupValues[2].trim()
            val destPkg = validator.resolvePackage(destApp)

            if (destPkg != null && rawText.isNotBlank()) {
                val steps = listOf(
                    CrossAppWorkflowStep(
                        id = "w2_s1",
                        appRole = WorkflowAppRole.DESTINATION,
                        targetApp = destApp,
                        expectedPackage = destPkg,
                        actionReference = "OPEN_APP",
                        expectedOutcome = "$destApp opened",
                        isHandoff = true
                    ),
                    CrossAppWorkflowStep(
                        id = "w2_s2",
                        appRole = WorkflowAppRole.DESTINATION,
                        targetApp = destApp,
                        expectedPackage = destPkg,
                        actionReference = "TRANSFER_TEXT",
                        parameters = mapOf("text" to rawText),
                        expectedOutcome = "Text prepared in $destApp draft",
                        isHandoff = false
                    )
                )

                return CrossAppWorkflow(
                    workflowId = UUID.randomUUID().toString(),
                    type = WorkflowType.TRANSFER_USER_PROVIDED_TEXT,
                    goalDescription = "Transfer text into $destApp",
                    steps = steps,
                    status = CrossAppWorkflowStatus.READY
                )
            }
        }

        // 4. PREPARE_CROSS_APP_MESSAGE:
        // "WhatsApp-la Karthi-ku hi message anuppa ready pannu" / "Prepare message hi to Karthi on WhatsApp"
        if (lower.contains("ready pannu") || lower.contains("prepare message") || lower.contains("ready to send")) {
            val tanglishMatch = Regex("^([a-zA-Z]+)-la\\s+(.+?)(?:-ku|\\s+ku)\\s+(.+?)\\s+message\\s+(?:anuppa\\s+)?ready\\s+pannu", RegexOption.IGNORE_CASE).find(input)
            val englishMatch = Regex("prepare\\s+message\\s+(?:to\\s+)?(.+?):?\\s+(.+?)\\s+on\\s+([a-zA-Z]+)", RegexOption.IGNORE_CASE).find(input)

            val appName = tanglishMatch?.groupValues?.get(1)?.trim() ?: englishMatch?.groupValues?.get(3)?.trim() ?: "WhatsApp"
            val rawRecipient = tanglishMatch?.groupValues?.get(2)?.trim() ?: englishMatch?.groupValues?.get(1)?.trim()
            val messageText = tanglishMatch?.groupValues?.get(3)?.trim() ?: englishMatch?.groupValues?.get(2)?.trim()

            if (rawRecipient != null && messageText != null) {
                val cleanRecipient = messagingTargetResolver.cleanRecipientName(rawRecipient)
                val destPkg = validator.resolvePackage(appName) ?: "com.whatsapp"
                val steps = listOf(
                    CrossAppWorkflowStep(
                        id = "w3_s1",
                        appRole = WorkflowAppRole.DESTINATION,
                        targetApp = appName,
                        expectedPackage = destPkg,
                        actionReference = "OPEN_APP",
                        expectedOutcome = "$appName opened",
                        isHandoff = true
                    ),
                    CrossAppWorkflowStep(
                        id = "w3_s2",
                        appRole = WorkflowAppRole.DESTINATION,
                        targetApp = appName,
                        expectedPackage = destPkg,
                        actionReference = "PREPARE_MESSAGE",
                        parameters = mapOf(
                            "recipient" to cleanRecipient,
                            "message" to messageText
                        ),
                        expectedOutcome = "Message prepared for $cleanRecipient",
                        requiresConfirmation = true,
                        isHandoff = false
                    )
                )

                return CrossAppWorkflow(
                    workflowId = UUID.randomUUID().toString(),
                    type = WorkflowType.PREPARE_CROSS_APP_MESSAGE,
                    goalDescription = "Prepare message to $cleanRecipient on $appName",
                    steps = steps,
                    status = CrossAppWorkflowStatus.WAITING_FOR_CONFIRMATION
                )
            }
        }

        return null
    }

    suspend fun queryAiForWorkflow(userInput: String, owner: OwnerProfile): CrossAppWorkflow? {
        val prompt = "$WORKFLOW_PLANNING_PROMPT\n\nUser instruction:\n\"$userInput\""
        val commandResult = aiProvider?.processCommand(
            prompt = prompt,
            owner = owner
        ) ?: return null

        if (!commandResult.isSuccess || commandResult.textResponse.isBlank()) {
            return null
        }

        return parseStructuredAiResponse(commandResult.textResponse, userInput)
    }

    fun parseStructuredAiResponse(rawJson: String, originalInput: String): CrossAppWorkflow? {
        val cleaned = rawJson.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        return try {
            val root = JSONObject(cleaned)
            val typeStr = root.optString("type", "INSPECT_ACROSS_APPS")
            val workflowType = try {
                WorkflowType.valueOf(typeStr)
            } catch (e: Exception) {
                WorkflowType.INSPECT_ACROSS_APPS
            }

            val goalDesc = root.optString("goalDescription", originalInput)
            val stepsArray = root.getJSONArray("steps")
            val stepsList = mutableListOf<CrossAppWorkflowStep>()

            for (i in 0 until stepsArray.length()) {
                val stepObj = stepsArray.getJSONObject(i)
                val roleStr = stepObj.optString("appRole", "INTERMEDIATE")
                val role = try {
                    WorkflowAppRole.valueOf(roleStr)
                } catch (e: Exception) {
                    WorkflowAppRole.INTERMEDIATE
                }

                val targetApp = stepObj.getString("targetApp")
                val expectedPkg = if (stepObj.has("expectedPackage") && !stepObj.isNull("expectedPackage")) {
                    stepObj.getString("expectedPackage")
                } else {
                    validator.resolvePackage(targetApp)
                }
                val actionRef = stepObj.getString("actionReference")
                val expectedOutcome = stepObj.optString("expectedOutcome", "$actionRef on $targetApp")
                val requiresConf = stepObj.optBoolean("requiresConfirmation", false)
                val isHandoff = stepObj.optBoolean("isHandoff", false)

                val paramsMap = mutableMapOf<String, String>()
                val paramsObj = stepObj.optJSONObject("parameters")
                if (paramsObj != null) {
                    val keys = paramsObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        paramsMap[key] = paramsObj.getString(key)
                    }
                }

                stepsList.add(
                    CrossAppWorkflowStep(
                        id = "ai_step_${i + 1}",
                        appRole = role,
                        targetApp = targetApp,
                        expectedPackage = expectedPkg,
                        actionReference = actionRef,
                        parameters = paramsMap,
                        expectedOutcome = expectedOutcome,
                        requiresConfirmation = requiresConf,
                        isHandoff = isHandoff
                    )
                )
            }

            CrossAppWorkflow(
                workflowId = UUID.randomUUID().toString(),
                type = workflowType,
                goalDescription = goalDesc,
                steps = stepsList,
                status = CrossAppWorkflowStatus.READY
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse structured AI workflow response: ${e.message}")
            null
        }
    }
}

