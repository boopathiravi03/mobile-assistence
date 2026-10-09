package com.boopathi.bg.service.recovery

import android.util.Log
import com.boopathi.bg.service.agent.AgentStep
import com.boopathi.bg.service.agent.AppAction
import com.boopathi.bg.service.agent.ScrollDirection
import com.boopathi.bg.service.ai.AIProvider
import com.boopathi.bg.service.planner.AgentPlanValidator
import org.json.JSONObject

/**
 * Proposed recovery plan when a step execution or UI verification observes a mismatch.
 */
data class RecoveryProposal(
    val mismatchType: UiMismatchType,
    val recoverySteps: List<AgentStep>,
    val explanation: String,
    val isExecutable: Boolean = true
) {
    companion object {
        fun abort(mismatchType: UiMismatchType, reason: String): RecoveryProposal =
            RecoveryProposal(
                mismatchType = mismatchType,
                recoverySteps = emptyList(),
                explanation = reason,
                isExecutable = false
            )
    }
}

/**
 * Evaluates UI mismatches and produces bounded, safe recovery plans.
 * Prioritizes deterministic recovery strategies first.
 * If deterministic recovery cannot resolve the state, falls back to structured AI recovery
 * if available, strictly validating proposed actions via AgentPlanValidator and RecoveryPolicy.
 */
class RecoveryPlanner(
    private val aiProvider: AIProvider? = null,
    private val planValidator: AgentPlanValidator = AgentPlanValidator()
) {

    companion object {
        private const val TAG = "RecoveryPlanner"

        val RECOVERY_SYSTEM_PROMPT = """
You are the bounded UI recovery planner for BG, an Android AI assistant.
An automated step failed because the current UI state does not match expectations.
Propose at most 1 to 3 bounded recovery actions to return to a known state or reach the target.
Rules:
1. Max 3 steps.
2. Only allowed actions: WAIT_FOR_WINDOW, INSPECT_UI, TAP, SCROLL, TYPE_TEXT, BACK, HOME.
3. NEVER propose SEND_MESSAGE, deleting content, making purchases, or entering passwords/PINs/OTPs.
4. If recovery is impossible, return "steps": [].
5. Output ONLY valid JSON:
{
  "explanation": "Why this recovery path is safe",
  "steps": [
    {
      "action": "INSPECT_UI | WAIT_FOR_WINDOW | TAP | SCROLL | BACK | HOME | TYPE_TEXT",
      "target": "target element label or app name",
      "direction": "DOWN | UP (only for SCROLL)",
      "text": "text (only for TYPE_TEXT)",
      "description": "Short explanation"
    }
  ]
}
""".trimIndent()
    }

    /**
     * Synthesizes a bounded recovery proposal for the observed mismatch.
     */
    suspend fun planRecovery(
        failedStep: AgentStep,
        mismatch: UiMismatch,
        stepRecoveryCount: Int,
        totalRecoveryCount: Int,
        recoveryStartTimeMs: Long
    ): RecoveryProposal {
        // 1. Boundary & Policy Enforcement
        val check = RecoveryPolicy.canAttemptRecovery(
            stepRecoveryCount = stepRecoveryCount,
            totalRecoveryCount = totalRecoveryCount,
            recoveryStartTimeMs = recoveryStartTimeMs,
            action = failedStep.action
        )
        if (!check.allowed) {
            Log.w(TAG, "Recovery disallowed by policy: ${check.reason}")
            return RecoveryProposal.abort(mismatch.type, check.reason ?: "Recovery policy rejected retry.")
        }

        // 2. Deterministic Recovery Strategy
        val deterministicProposal = planDeterministicRecovery(failedStep, mismatch)
        if (deterministicProposal != null) {
            Log.i(TAG, "Using deterministic recovery for ${mismatch.type}: ${deterministicProposal.explanation}")
            return deterministicProposal
        }

        // 3. AI-Assisted Recovery Fallback (strictly structured & locally validated)
        if (aiProvider != null) {
            val aiProposal = planAiRecovery(failedStep, mismatch)
            if (aiProposal != null && aiProposal.isExecutable) {
                Log.i(TAG, "Using validated AI recovery proposal (${aiProposal.recoverySteps.size} steps)")
                return aiProposal
            }
        }

        // 4. Fallback safe abort
        return RecoveryProposal.abort(
            mismatch.type,
            "Cannot safely recover from UI mismatch '${mismatch.type}'. Stopping safely."
        )
    }

    /**
     * Deterministic recovery paths based on established Android state patterns.
     */
    private fun planDeterministicRecovery(
        failedStep: AgentStep,
        mismatch: UiMismatch
    ): RecoveryProposal? {
        val currentSnapshot = mismatch.observedSnapshot

        return when (mismatch.type) {
            UiMismatchType.SCREEN_NOT_READY -> {
                // UI is transitioning: bounded wait then inspect
                val steps = listOf(
                    AgentStep(
                        action = AppAction.WaitForWindow(
                            expectedPackage = mismatch.expectedPackage ?: currentSnapshot?.packageName ?: "",
                            timeoutMs = 2500L
                        ),
                        description = "Wait for screen to settle",
                        maxRetries = 0
                    ),
                    AgentStep(
                        action = AppAction.InspectUi,
                        description = "Inspect settled UI",
                        maxRetries = 0
                    )
                )
                RecoveryProposal(
                    mismatchType = mismatch.type,
                    recoverySteps = steps,
                    explanation = "Screen transition in progress. Waiting briefly to inspect settled UI."
                )
            }

            UiMismatchType.TARGET_NOT_FOUND -> {
                val query = mismatch.targetQuery ?: ""
                when {
                    // If target wasn't found, try scrolling down once if scrollable content exists
                    currentSnapshot != null && currentSnapshot.scrollableLabels.isNotEmpty() -> {
                        val steps = listOf(
                            AgentStep(
                                action = AppAction.Scroll(ScrollDirection.DOWN),
                                description = "Scroll down to reveal '$query'",
                                maxRetries = 0
                            ),
                            AgentStep(
                                action = AppAction.InspectUi,
                                description = "Inspect UI after scroll",
                                maxRetries = 0
                            )
                        )
                        RecoveryProposal(
                            mismatchType = mismatch.type,
                            recoverySteps = steps,
                            explanation = "Target '$query' not visible on current screen. Scrolling down to search."
                        )
                    }

                    // Otherwise refresh inspection once to catch newly loaded nodes
                    else -> {
                        val steps = listOf(
                            AgentStep(
                                action = AppAction.InspectUi,
                                description = "Re-inspect screen for '$query'",
                                maxRetries = 0
                            )
                        )
                        RecoveryProposal(
                            mismatchType = mismatch.type,
                            recoverySteps = steps,
                            explanation = "Re-inspecting screen to detect asynchronously rendered target '$query'."
                        )
                    }
                }
            }

            UiMismatchType.TARGET_AMBIGUOUS -> {
                // When multiple targets match, do NOT guess or click randomly
                val candidates = mismatch.candidateMatches.take(3).joinToString(", ")
                RecoveryProposal.abort(
                    mismatchType = mismatch.type,
                    reason = "Target '${mismatch.targetQuery}' is ambiguous. Found options: [$candidates]. Please clarify."
                )
            }

            UiMismatchType.WRONG_FOREGROUND_APP -> {
                // Active app does not match expected app. Attempt to reopen expected app if known
                val expectedPkg = mismatch.expectedPackage
                if (!expectedPkg.isNullOrBlank()) {
                    val steps = listOf(
                        AgentStep(
                            action = AppAction.OpenApp(expectedPkg),
                            description = "Re-open $expectedPkg",
                            maxRetries = 0
                        ),
                        AgentStep(
                            action = AppAction.WaitForWindow(expectedPkg, timeoutMs = 4000L),
                            description = "Wait for $expectedPkg to foreground",
                            maxRetries = 0
                        )
                    )
                    RecoveryProposal(
                        mismatchType = mismatch.type,
                        recoverySteps = steps,
                        explanation = "Wrong application foregrounded. Relaunching expected package '$expectedPkg'."
                    )
                } else {
                    RecoveryProposal.abort(
                        mismatchType = mismatch.type,
                        reason = "Foreground app '${mismatch.actualPackage}' is not expected."
                    )
                }
            }

            UiMismatchType.OBSERVATION_UNAVAILABLE -> {
                RecoveryProposal.abort(
                    mismatchType = mismatch.type,
                    reason = "Accessibility service is unavailable. Cannot inspect UI state."
                )
            }

            UiMismatchType.ACTION_FAILED, UiMismatchType.EXPECTED_STATE_NOT_REACHED -> {
                // If action was Back or Home that failed, or generic state mismatch, try refreshing state
                val steps = listOf(
                    AgentStep(
                        action = AppAction.InspectUi,
                        description = "Inspect UI state after failure",
                        maxRetries = 0
                    )
                )
                RecoveryProposal(
                    mismatchType = mismatch.type,
                    recoverySteps = steps,
                    explanation = "Action did not reach expected state. Re-evaluating UI."
                )
            }
        }
    }

    /**
     * Structured AI fallback recovery.
     */
    private suspend fun planAiRecovery(
        failedStep: AgentStep,
        mismatch: UiMismatch
    ): RecoveryProposal? {
        val prompt = "$RECOVERY_SYSTEM_PROMPT\n\n${buildRecoveryPrompt(failedStep, mismatch)}"
        return try {
            val defaultOwner = com.boopathi.bg.data.model.OwnerProfile(name = "User")
            val commandResult = aiProvider?.processCommand(prompt = prompt, owner = defaultOwner)
            val response = commandResult?.textResponse?.trim()
            if (response.isNullOrBlank() || commandResult?.isSuccess != true) return null

            val jsonStr = extractJson(response) ?: return null
            val json = JSONObject(jsonStr)

            val explanation = json.optString("explanation", "AI suggested recovery plan")
            val stepsArray = json.optJSONArray("steps") ?: return null

            if (stepsArray.length() == 0 || stepsArray.length() > RecoveryPolicy.MAX_RECOVERY_PLAN_STEPS) {
                return null
            }

            val steps = mutableListOf<AgentStep>()
            for (i in 0 until stepsArray.length()) {
                val stepObj = stepsArray.getJSONObject(i)
                val actionName = stepObj.optString("action").uppercase().trim()
                val target = stepObj.optString("target").trim()
                val desc = stepObj.optString("description", "Recovery step ${i + 1}")

                // Validation block against prohibited actions
                val action = when (actionName) {
                    "INSPECT_UI" -> AppAction.InspectUi
                    "WAIT_FOR_WINDOW" -> AppAction.WaitForWindow(target.ifBlank { mismatch.expectedPackage ?: "" })
                    "TAP" -> {
                        if (target.isBlank() || isSensitiveTarget(target)) return null
                        AppAction.Tap(target)
                    }
                    "TYPE_TEXT" -> {
                        val text = stepObj.optString("text")
                        if (target.isBlank() || isSensitiveTarget(target) || isSensitiveTarget(text)) return null
                        AppAction.TypeText(target, text)
                    }
                    "SCROLL" -> {
                        val dir = if (stepObj.optString("direction").equals("UP", ignoreCase = true)) {
                            ScrollDirection.UP
                        } else {
                            ScrollDirection.DOWN
                        }
                        AppAction.Scroll(dir)
                    }
                    "BACK" -> AppAction.Back
                    "HOME" -> AppAction.Home
                    else -> return null // Prohibited or unknown action
                }

                // Check recoverable action policy
                if (!RecoveryPolicy.isActionRecoverable(action)) {
                    return null
                }

                steps.add(AgentStep(action = action, description = desc, maxRetries = 0))
            }

            if (steps.isEmpty()) return null
            RecoveryProposal(
                mismatchType = mismatch.type,
                recoverySteps = steps,
                explanation = explanation,
                isExecutable = true
            )
        } catch (e: Exception) {
            Log.w(TAG, "AI recovery generation failed: ${e.message}")
            null
        }
    }

    private fun buildRecoveryPrompt(step: AgentStep, mismatch: UiMismatch): String {
        val snapshot = mismatch.observedSnapshot
        val visible = snapshot?.visibleLabels?.take(15)?.joinToString(", ") ?: "None"
        val actionable = snapshot?.actionableLabels?.take(10)?.joinToString(", ") ?: "None"
        return """
Failed step: ${step.description} (${step.action.type})
Mismatch type: ${mismatch.type}
Details: ${mismatch.description}
Current package: ${snapshot?.packageName ?: "Unknown"}
Visible labels: [$visible]
Actionable elements: [$actionable]
Propose bounded recovery steps.
""".trimIndent()
    }

    private fun isSensitiveTarget(query: String): Boolean {
        val lower = query.lowercase()
        val keywords = listOf("otp", "pin", "password", "cvv", "upi", "card", "pay", "buy", "transfer", "delete all", "wipe")
        return keywords.any { lower.contains(it) }
    }

    private fun extractJson(raw: String): String? {
        val cleaned = raw.trim()
        if (cleaned.startsWith("{") && cleaned.endsWith("}")) return cleaned
        val start = cleaned.indexOf("{")
        val end = cleaned.lastIndexOf("}")
        return if (start != -1 && end != -1 && end > start) cleaned.substring(start, end + 1) else null
    }
}

