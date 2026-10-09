package com.boopathi.bg.service.workflow

import com.boopathi.bg.data.local.entity.WorkflowHistoryEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Checkpoint model capturing verified progress at safe non-consequential workflow step boundaries.
 *
 * Explicitly avoids storing:
 * - Live AccessibilityNodeInfo
 * - Passwords, PINs, OTPs, CVVs, credentials
 * - Stale UI coordinates or full views
 */
data class WorkflowCheckpoint(
    val workflowId: String,
    val completedStepIndex: Int,
    val expectedPackage: String?,
    val expectedOutcome: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("workflowId", workflowId)
        obj.put("completedStepIndex", completedStepIndex)
        obj.put("expectedPackage", expectedPackage ?: "")
        obj.put("expectedOutcome", expectedOutcome)
        obj.put("timestamp", timestamp)
        return obj.toString()
    }

    companion object {
        fun fromJson(jsonStr: String?): WorkflowCheckpoint? {
            if (jsonStr.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(jsonStr)
                WorkflowCheckpoint(
                    workflowId = obj.getString("workflowId"),
                    completedStepIndex = obj.getInt("completedStepIndex"),
                    expectedPackage = obj.optString("expectedPackage", "").takeIf { it.isNotEmpty() },
                    expectedOutcome = obj.optString("expectedOutcome", ""),
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Serializes and deserializes minimal, safe step representations for workflow persistence.
 */
object WorkflowStepSerializer {

    fun serializeSteps(steps: List<CrossAppWorkflowStep>): String {
        val array = JSONArray()
        for (step in steps) {
            val obj = JSONObject()
            obj.put("id", step.id)
            obj.put("appRole", step.appRole.name)
            obj.put("targetApp", step.targetApp)
            obj.put("expectedPackage", step.expectedPackage ?: "")
            obj.put("actionReference", step.actionReference)
            obj.put("expectedOutcome", step.expectedOutcome)
            obj.put("requiresConfirmation", step.requiresConfirmation)
            obj.put("isHandoff", step.isHandoff)

            val paramsObj = JSONObject()
            for ((k, v) in step.parameters) {
                // Safeguard: Never serialize sensitive parameters into database
                if (!WorkflowContext.containsSensitiveData(k) && !WorkflowContext.containsSensitiveData(v)) {
                    paramsObj.put(k, v)
                }
            }
            obj.put("parameters", paramsObj)
            array.put(obj)
        }
        return array.toString()
    }

    fun deserializeSteps(jsonStr: String): List<CrossAppWorkflowStep> {
        if (jsonStr.isBlank()) return emptyList()
        val list = mutableListOf<CrossAppWorkflowStep>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val paramsMap = mutableMapOf<String, String>()
                val paramsObj = obj.optJSONObject("parameters")
                if (paramsObj != null) {
                    val keys = paramsObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        paramsMap[key] = paramsObj.getString(key)
                    }
                }

                list.add(
                    CrossAppWorkflowStep(
                        id = obj.getString("id"),
                        appRole = try {
                            WorkflowAppRole.valueOf(obj.getString("appRole"))
                        } catch (e: Exception) {
                            WorkflowAppRole.INTERMEDIATE
                        },
                        targetApp = obj.getString("targetApp"),
                        expectedPackage = obj.optString("expectedPackage", "").takeIf { it.isNotEmpty() },
                        actionReference = obj.getString("actionReference"),
                        parameters = paramsMap,
                        expectedOutcome = obj.optString("expectedOutcome", ""),
                        requiresConfirmation = obj.optBoolean("requiresConfirmation", false),
                        isHandoff = obj.optBoolean("isHandoff", false)
                    )
                )
            }
        } catch (e: Exception) {
            // Safe fallback: return whatever was parsed
        }
        return list
    }
}

