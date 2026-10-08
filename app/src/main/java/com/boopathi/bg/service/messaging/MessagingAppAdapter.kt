package com.boopathi.bg.service.messaging

import android.util.Log
import com.boopathi.bg.service.agent.AccessibilityBridge
import com.boopathi.bg.service.agent.UiElementInfo
import kotlinx.coroutines.delay

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
        if (!currentPkg.contains("whatsapp", ignoreCase = true)) {
            Log.w(TAG, "Foreground package mismatch: $currentPkg")
            return false
        }

        val elements = bridge.getVisibleElements()

        // 1. Prioritize conversation header / action bar elements near the top of the screen (bounds.bottom <= 800)
        // or any element if bounds are not set / empty (e.g. In unit tests)
        val candidateElements = elements.filter {
            it.bounds.isEmpty || it.bounds.bottom <= 800
        }

        val recipientMatches = candidateElements.any {
            val textMatch = it.text.contains(expectedRecipient, ignoreCase = true)
            val descMatch = it.contentDescription.contains(expectedRecipient, ignoreCase = true)
            textMatch || descMatch
        }

        return recipientMatches
    }

    override suspend fun verifyDraft(bridge: AccessibilityBridge, expectedDraft: String): Boolean {
        val elements = bridge.getVisibleElements()
        val editable = elements.filter { it.isEditable }

        return editable.any {
            val fieldText = it.text.trim()
            val expected = expectedDraft.trim()
            fieldText.equals(expected, ignoreCase = true) || fieldText.contains(expected, ignoreCase = true)
        }
    }

    override suspend fun findSendButton(bridge: AccessibilityBridge): UiElementInfo? {
        val elements = bridge.getVisibleElements()

        // Filter for actionable candidates while strictly excluding voice note / audio controls
        val candidates = elements.filter { el ->
            val desc = el.contentDescription.lowercase().trim()
            val txt = el.text.lowercase().trim()
            val id = el.viewId.lowercase().trim()

            // Disallow voice message / audio recorder controls
            val isVoice = desc.contains("voice") || desc.contains("record") || desc.contains("audio") ||
                    id.contains("voice") || id.contains("record")
            if (isVoice) return@filter false

            el.isClickable && el.isEnabled && (
                desc == "send" ||
                desc == "send message" ||
                desc == "anuppu" ||
                desc == "enviar" ||
                desc.contains("send") ||
                txt == "send" ||
                txt == "anuppu" ||
                id.contains("send") ||
                id.contains("entry_action")
            )
        }

        // Prefer leaf nodes (higher depth in hierarchy) to hit the exact clickable ImageButton
        return candidates.maxByOrNull { it.depth } ?: candidates.firstOrNull()
    }

    open override suspend fun verifyMessageSent(
        bridge: AccessibilityBridge,
        expectedDraft: String,
        timeoutMs: Long
    ): Boolean {
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
            val messageInChat = elements.any {
                !it.isEditable && it.text.contains(expectedDraft, ignoreCase = true)
            }

            if (inputCleared && messageInChat) {
                Log.i(TAG, "Message verified: input cleared and message bubble found.")
                return true
            }

            // Or if either strong signal is detected
            if (inputCleared || messageInChat) {
                Log.i(TAG, "Message verified by single signal (inputCleared=$inputCleared, messageInChat=$messageInChat)")
                return true
            }
        }

        Log.w(TAG, "Post-send verification timed out after ${timeoutMs}ms.")
        return false
    }
}

