package com.boopathi.bg.service.messaging

import android.util.Log
import com.boopathi.bg.service.agent.AccessibilityBridge
import com.boopathi.bg.service.agent.UiElementInfo
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Interface defining messaging app automation operations.
 */
interface MessagingAppAdapter {
    val supportedAppName: String
    val supportedPackage: String

    suspend fun verifyRecipient(bridge: AccessibilityBridge, expectedRecipient: String): Boolean
    suspend fun verifyDraft(bridge: AccessibilityBridge, expectedDraft: String): Boolean
    suspend fun findSendButton(bridge: AccessibilityBridge): UiElementInfo?
    suspend fun verifyMessageSent(bridge: AccessibilityBridge, expectedDraft: String, timeoutMs: Long = 5000L): Boolean

    suspend fun exactMessageMatchCount(bridge: AccessibilityBridge, expectedDraft: String): Int =
        bridge.getVisibleElements().count { element ->
            !element.isEditable && listOf(element.text, element.contentDescription)
                .any { normalizeDraft(it) == normalizeDraft(expectedDraft) }
        }

    suspend fun verifyMessageSent(
        bridge: AccessibilityBridge,
        expectedDraft: String,
        previousMatchCount: Int,
        timeoutMs: Long = 5000L
    ): Boolean = false

    fun normalizeDraft(value: String): String = value.replace("\r\n", "\n")
        .trim()
        .replace(Regex("[ \\t]+"), " ")
}

/**
 * WhatsApp specific messaging adapter using accessible UI structure.
 */
open class WhatsAppMessagingAdapter : MessagingAppAdapter {
    override val supportedAppName: String = "WhatsApp"
    override val supportedPackage: String = "com.whatsapp"

    companion object {
        private const val TAG = "WhatsAppAdapter"
    }

    open override suspend fun verifyRecipient(bridge: AccessibilityBridge, expectedRecipient: String): Boolean {
        val currentPkg = bridge.getCurrentPackage()
        if (currentPkg != supportedPackage) {
            Log.w(TAG, "Foreground package mismatch: $currentPkg")
            return false
        }

        val elements = bridge.getVisibleElements()

        // 1. Prioritize conversation header / action bar elements near the top of the screen (bounds.bottom <= 800)
        // or any element if bounds are not set / empty (e.g. In unit tests)
        val candidateElements = elements.filter {
            it.bounds.isEmpty || it.bounds.bottom <= 800
        }

        val expected = normalizeRecipient(expectedRecipient)
        if (expected.isBlank()) return false
        val expectedDigits = expectedRecipient.filter(Char::isDigit)
        val expectedIsPhone = expectedDigits.length >= 7 && expectedRecipient.all { it.isDigit() || it in "+-(). /" }

        val recipientMatches = candidateElements.any { element ->
            listOf(element.text, element.contentDescription).any { rawCandidate ->
                if (expectedIsPhone) {
                    val candidateDigits = rawCandidate.filter(Char::isDigit)
                    candidateDigits.isNotEmpty() && candidateDigits == expectedDigits
                } else {
                    normalizeRecipient(rawCandidate) == expected
                }
            }
        }

        return recipientMatches
    }

    override suspend fun verifyDraft(bridge: AccessibilityBridge, expectedDraft: String): Boolean {
        val expected = normalizeDraft(expectedDraft)
        if (expected.isBlank()) return false
        val elements = bridge.getVisibleElements()
        val editable = elements.filter { it.isEditable }

        return editable.any { normalizeDraft(it.text) == expected }
    }

    override suspend fun findSendButton(bridge: AccessibilityBridge): UiElementInfo? {
        val currentPkg = bridge.getCurrentPackage()
        if (currentPkg != supportedPackage) {
            Log.w(TAG, "Cannot find send button: foreground package mismatch '$currentPkg' != '$supportedPackage'")
            return null
        }

        val elements = bridge.getVisibleElements()

        val recognizedSendLabels = setOf("send", "send message", "anuppu", "enviar")
        val recognizedSendIds = setOf("send", "send_btn", "send_button", "entry_action", "conversation_send_button")

        val candidates = elements.filter { el ->
            val desc = el.contentDescription.lowercase().trim()
            val txt = el.text.lowercase().trim()
            val rawId = el.viewId.substringAfterLast("/").substringAfterLast(":").lowercase().trim()

            // Disallow voice message / audio recorder controls
            val isVoice = desc.contains("voice") || desc.contains("record") || desc.contains("audio") ||
                    desc.contains("mic") || rawId.contains("voice") || rawId.contains("record") || rawId.contains("audio")
            if (isVoice) return@filter false

            val matchesLabel = recognizedSendLabels.contains(desc) || recognizedSendLabels.contains(txt)
            val matchesId = recognizedSendIds.contains(rawId)

            el.isClickable && el.isEnabled && (matchesLabel || matchesId)
        }

        // If multiple plausible Send buttons remain, return no target instead of guessing.
        if (candidates.size > 1) {
            Log.w(TAG, "Ambiguous send buttons found (${candidates.size} matches). Failing closed without guessing.")
            return null
        }

        return candidates.singleOrNull()
    }

    @Deprecated("Use the overload that supplies previousMatchCount captured before tapping Send.")
    open override suspend fun verifyMessageSent(
        bridge: AccessibilityBridge,
        expectedDraft: String,
        timeoutMs: Long
    ): Boolean {
        Log.w(TAG, "Cannot verify message without a pre-send baseline.")
        return false
    }

    override suspend fun verifyMessageSent(
        bridge: AccessibilityBridge,
        expectedDraft: String,
        previousMatchCount: Int,
        timeoutMs: Long
    ): Boolean {
        val expected = normalizeDraft(expectedDraft)
        if (expected.isBlank()) return false
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            delay(300)
            val elements = bridge.getVisibleElements()

            // Signal 1: The input field is now empty or contains placeholder
            val editable = elements.filter { it.isEditable }
            val inputCleared = editable.isNotEmpty() && editable.all {
                val t = it.text.trim()
                t.isEmpty() || t.equals("Type a message", ignoreCase = true) ||
                t.equals("Message", ignoreCase = true) || t.equals("Enter message", ignoreCase = true)
            }

            // Signal 2: The message text appears in the conversation history as an outgoing message bubble
            val currentMatchCount = exactMessageMatchCount(bridge, expected)

            if (inputCleared && currentMatchCount > previousMatchCount) {
                Log.i(TAG, "Message verified: input cleared and a new exact message bubble appeared.")
                return true
            }
        }

        Log.w(TAG, "Post-send verification timed out after ${timeoutMs}ms.")
        return false
    }

    private fun normalizeRecipient(value: String): String = value.trim()
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.ROOT)
}

