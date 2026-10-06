package com.boopathi.bg.service.ai

import android.util.Log
import com.boopathi.bg.BuildConfig
import com.boopathi.bg.data.model.CommandResult
import com.boopathi.bg.data.model.OwnerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GroqProvider(
    private val apiKey: String = BuildConfig.GROQ_API_KEY
) : AIProvider {

    override val name: String = "Groq API (Fallback)"
    override val isAvailable: Boolean = apiKey.isNotBlank()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun processCommand(prompt: String, owner: OwnerProfile): CommandResult {
        if (!isAvailable) {
            return CommandResult(
                textResponse = "Groq API key is not configured.",
                providerName = name,
                isSuccess = false
            )
        }

        return withContext(Dispatchers.IO) {
            try {
                val url = "https://api.groq.com/openai/v1/chat/completions"

                val payload = JSONObject().apply {
                    put("model", "llama-3.1-8b-instant")
                    val messages = JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put(
                                "content",
                                "You are BG, a personal AI mobile agent for ${owner.name}. " +
                                        "Keep answers brief, conversational, and direct."
                            )
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                    }
                    put("messages", messages)
                    put("temperature", 0.7)
                }

                val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""

                    if (!response.isSuccessful) {
                        Log.w("GroqProvider", "API response error: HTTP ${response.code}")
                        return@withContext CommandResult(
                            textResponse = "Groq API returned an error (HTTP ${response.code}).",
                            providerName = name,
                            isSuccess = false
                        )
                    }

                    val json = JSONObject(responseBody)
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val firstChoice = choices.getJSONObject(0)
                        val messageObj = firstChoice.optJSONObject("message")
                        val content = messageObj?.optString("content", "")?.trim() ?: ""
                        if (content.isNotBlank()) {
                            return@withContext CommandResult(
                                textResponse = content,
                                providerName = name,
                                isSuccess = true
                            )
                        }
                    }

                    CommandResult(
                        textResponse = "Received empty response from Groq.",
                        providerName = name,
                        isSuccess = false
                    )
                }
            } catch (e: Exception) {
                Log.w("GroqProvider", "Failed to call Groq API: ${e.message}")
                CommandResult(
                    textResponse = "Could not reach Groq due to network failure.",
                    providerName = name,
                    isSuccess = false
                )
            }
        }
    }
}

