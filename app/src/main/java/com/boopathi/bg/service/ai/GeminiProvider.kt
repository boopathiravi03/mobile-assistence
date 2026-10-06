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

class GeminiProvider(
    private val apiKey: String = BuildConfig.GEMINI_API_KEY
) : AIProvider {

    override val name: String = "Gemini API (Flash)"
    override val isAvailable: Boolean = apiKey.isNotBlank()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun processCommand(prompt: String, owner: OwnerProfile): CommandResult {
        if (!isAvailable) {
            return CommandResult(
                textResponse = "Gemini API key is not configured.",
                providerName = name,
                isSuccess = false
            )
        }

        return withContext(Dispatchers.IO) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"

                val payload = JSONObject().apply {
                    val systemInstruction = JSONObject().apply {
                        val parts = JSONArray().apply {
                            put(JSONObject().apply {
                                put(
                                    "text",
                                    "You are BG, an intelligent personal AI mobile agent assisting ${owner.name}. " +
                                            "Keep your answers spoken-friendly, concise, accurate, and direct. " +
                                            "Never output markdown tables or complex symbols that cannot be read aloud."
                                )
                            })
                        }
                        put("parts", parts)
                    }
                    put("systemInstruction", systemInstruction)

                    val contents = JSONArray().apply {
                        val content = JSONObject().apply {
                            put("role", "user")
                            val parts = JSONArray().apply {
                                put(JSONObject().apply { put("text", prompt) })
                            }
                            put("parts", parts)
                        }
                        put(content)
                    }
                    put("contents", contents)
                }

                val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""

                    if (!response.isSuccessful) {
                        Log.w("GeminiProvider", "API response error: HTTP ${response.code}")
                        return@withContext CommandResult(
                            textResponse = "I encountered an error connecting to Gemini (HTTP ${response.code}).",
                            providerName = name,
                            isSuccess = false
                        )
                    }

                    val json = JSONObject(responseBody)
                    val candidates = json.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val firstCandidate = candidates.getJSONObject(0)
                        val contentObj = firstCandidate.optJSONObject("content")
                        val parts = contentObj?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            val text = parts.getJSONObject(0).optString("text", "").trim()
                            return@withContext CommandResult(
                                textResponse = text,
                                providerName = name,
                                isSuccess = true
                            )
                        }
                    }

                    CommandResult(
                        textResponse = "I received an empty response from Gemini.",
                        providerName = name,
                        isSuccess = false
                    )
                }
            } catch (e: Exception) {
                Log.w("GeminiProvider", "Failed to call Gemini API: ${e.message}")
                CommandResult(
                    textResponse = "I could not reach Gemini due to a network or connection issue.",
                    providerName = name,
                    isSuccess = false
                )
            }
        }
    }
}

