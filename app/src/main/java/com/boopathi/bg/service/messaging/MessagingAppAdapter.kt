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
        val recipientMatches = elements.any {
            it.text.contains(expectedRecipient, ignoreCase = true) ||
            it.contentDescription.contains(expectedRecipient, ignoreCase = true)
        }

        return recipientMatches
    }

    override suspend fun verifyDraft(bridge: AccessibilityBridge, expectedDraft: String): Boolean {
        val elements = bridge.getVisibleElements()
        val editable = elements.filter { it.isEditable }

        return editable.any {
            it.text.contains(expectedDraft, ignoreCase = true) ||
            it.contentDescription.contains(expectedDraft, ignoreCase = true)
        }
    }

    override suspend fun findSendButton(bridge: AccessibilityBridge): UiElementInfo? {
        val elements = bridge.getVisibleElements()

        // 1. Exact accessible label or description
        val candidates = elements.filter { el ->
            el.isClickable && el.isEnabled && (
                el.contentDescription.equals("Send", ignoreCase = true) ||
                el.text.equals("Send", ignoreCase = true) ||
                el.contentDescription.contains("Send message", ignoreCase = true) ||
                el.viewId.contains("send", ignoreCase = true)
            )
        }

        return candidates.firstOrNull()
    }

    open override suspend fun verifyMessageSent(
        bridge: AccessibilityBridge,
        expectedDraft: String,
        timeoutMs: Long
    ): Boolean {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            delay(500)
            val elements = bridge.getVisibleElements()

            // Signal 1: The input field is now empty or contains placeholder
            val editable = elements.filter { it.isEditable }
            val inputCleared = editable.isNotEmpty() && editable.all {
                it.text.isEmpty() || it.text.equals("Type a message", ignoreCase = true) || it.text.equals("Message", ignoreCase = true)
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

