package com.boopathi.bg.service.ai

import android.util.Log
import com.boopathi.bg.BuildConfig
import com.boopathi.bg.data.model.ChatMessage
import com.boopathi.bg.data.model.CommandResult
import com.boopathi.bg.data.model.OwnerProfile
import com.boopathi.bg.data.model.Sender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiProvider(
    private val apiKey: String = BuildConfig.GEMINI_API_KEY,
    private val preferredModel: String = "gemini-2.5-flash"
) : AIProvider {

    override val name: String = "Gemini"
    override val isAvailable: Boolean = apiKey.isNotBlank()

    companion object {
        private const val TAG = "GeminiProvider"
        private const val FALLBACK_FAIL_MESSAGE = "I'm having trouble connecting to my AI service right now."

        private const val BG_SYSTEM_PERSONA =
            "You are BG, the personal AI mobile assistant created for Boopathi.\n" +
            "Owner: Boopathi.\n" +
            "Role: Intelligent, personal, spoken-friendly AI mobile assistant.\n\n" +
            "Core Guidelines:\n" +
            "1. Spoken-Friendly & Concise: Your response will be read aloud via Android Text-to-Speech (TTS). " +
            "Keep answers concise, direct, and conversational (usually 1 to 3 sentences unless asked for an explanation). " +
            "Never output markdown tables, long bullet points, code blocks, or unnecessary asterisks/emojis.\n" +
            "2. Conversational Context: Maintain conversational context across follow-up questions.\n" +
            "3. Multilingual Support: You naturally understand English, Tamil, and Tanglish (Tamil written in English script). " +
            "If the user speaks or asks in Tanglish or Tamil (e.g., 'Android pathi explain pannu', 'Inniku enna date?'), " +
            "understand the query effortlessly and respond in clean, natural Tanglish or English as appropriate.\n" +
            "4. Action Integrity: If the user asks you to perform an unsupported device action (such as sending a WhatsApp message, " +
            "making a phone call, or controlling an app), explicitly respond: 'I can't perform that action yet, but that capability is planned.' " +
            "Never claim that an action was executed when it was not."
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun processCommand(
        prompt: String,
        owner: OwnerProfile,
        conversationHistory: List<ChatMessage>,
        relevantMemories: List<com.boopathi.bg.data.local.entity.MemoryEntity>
    ): CommandResult {
        if (!isAvailable) {
            Log.d(TAG, "Gemini API key is not configured.")
            return CommandResult(
                textResponse = FALLBACK_FAIL_MESSAGE,
                providerName = name,
                isSuccess = false
            )
        }

        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            try {
                val candidateModels = listOf(preferredModel, "gemini-2.0-flash", "gemini-1.5-flash")
                var lastErrorMessage: String? = null

                for (model in candidateModels) {
                    val result = executeGenerateContent(model, prompt, owner, conversationHistory, relevantMemories)
                    if (result != null) {
                        val duration = System.currentTimeMillis() - startTime
                        Log.d(TAG, "Gemini ($model) responded in ${duration}ms")
                        return@withContext CommandResult(
                            textResponse = result,
                            providerName = name,
                            isSuccess = true
                        )
                    }
                }

                CommandResult(
                    textResponse = FALLBACK_FAIL_MESSAGE,
                    providerName = name,
                    isSuccess = false
                )
            } catch (e: Exception) {
                Log.w(TAG, "Gemini execution failed safely (${e.javaClass.simpleName})")
                CommandResult(
                    textResponse = FALLBACK_FAIL_MESSAGE,
                    providerName = name,
                    isSuccess = false
                )
            }
        }
    }

    private fun executeGenerateContent(
        model: String,
        prompt: String,
        owner: OwnerProfile,
        conversationHistory: List<ChatMessage>,
        relevantMemories: List<com.boopathi.bg.data.local.entity.MemoryEntity>
    ): String? {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val systemText = if (relevantMemories.isNotEmpty()) {
            BG_SYSTEM_PERSONA + "\n\nRelevant memories about ${owner.name}:\n" +
                relevantMemories.joinToString("\n") { "- ${it.title}: ${it.content}" }
        } else {
            BG_SYSTEM_PERSONA
        }

        val payload = JSONObject().apply {
            // 1. System instructions
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", systemText)
                    })
                })
            })

            // 2. Structured multi-turn conversation contents
            val contentsArray = JSONArray()

            // Build history with alternating user/model roles
            var expectedRoleIsUser = true
            for (msg in conversationHistory) {
                val role = if (msg.sender == Sender.USER) "user" else "model"
                if ((expectedRoleIsUser && role == "user") || (!expectedRoleIsUser && role == "model")) {
                    contentsArray.put(JSONObject().apply {
                        put("role", role)
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", msg.text) })
                        })
                    })
                    expectedRoleIsUser = !expectedRoleIsUser
                }
            }

            // If history ended expecting a model turn, trim the trailing user turn
            // so we can append the current prompt cleanly as the final user turn
            if (!expectedRoleIsUser && contentsArray.length() > 0) {
                contentsArray.remove(contentsArray.length() - 1)
            }

            // Append current prompt as final user turn
            contentsArray.put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", prompt) })
                })
            })

            put("contents", contentsArray)

            // 3. Generation configuration for low-latency conversational agent
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.7)
                put("maxOutputTokens", 350)
            })
        }

        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.w(TAG, "Model $model returned HTTP ${response.code}")
                    return null
                }

                val json = JSONObject(bodyString)
                val candidates = json.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val first = candidates.getJSONObject(0)
                    val contentObj = first.optJSONObject("content")
                    val parts = contentObj?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val text = parts.getJSONObject(0).optString("text", "").trim()
                        if (text.isNotEmpty()) {
                            // Strip any accidental markdown bolding/hashtags for cleaner speech
                            return text.replace(Regex("[#*`_]"), "")
                        }
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error executing request with model $model (${e.javaClass.simpleName})")
            null
        }
    }
}
