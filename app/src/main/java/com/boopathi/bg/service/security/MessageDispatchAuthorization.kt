package com.boopathi.bg.service.security

import com.boopathi.bg.service.agent.AppAction
import com.boopathi.bg.service.agent.ConfirmationRequest
import com.boopathi.bg.service.messaging.MessageDraft
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Single-use, short-lived proof bound to the exact message reviewed and authenticated. */
internal class MessageDispatchAuthorization private constructor(
    private val taskId: String,
    private val fingerprint: String,
    private val issuedAt: Long
) {
    private val consumed = AtomicBoolean(false)

    fun consume(expectedTaskId: String, action: AppAction.SendMessage, now: Long): Boolean {
        val validAge = expectedTaskId == taskId && now >= issuedAt && now - issuedAt <= CONFIRMATION_TIMEOUT_MS
        val sameAction = MessageDigest.isEqual(
            fingerprint.toByteArray(Charsets.US_ASCII),
            fingerprint(taskId, action.expectedRecipient, action.expectedMessage, action.targetApp).toByteArray(Charsets.US_ASCII)
        )
        val firstUse = consumed.compareAndSet(false, true)
        return validAge && sameAction && firstUse
    }

    companion object {
        private const val CONFIRMATION_TIMEOUT_MS = 60_000L

        fun fingerprint(taskId: String, draft: MessageDraft): String =
            fingerprint(taskId, draft.recipient, draft.messageText, draft.sourceApp)

        fun confirmationFingerprint(taskId: String, draft: MessageDraft, request: ConfirmationRequest): String {
            val canonical = listOf(
                fingerprint(taskId, draft), request.reason, request.actionDescription,
                request.riskLevel.name, request.timestamp.toString()
            ).joinToString("\u0000")
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
        }

        private fun fingerprint(taskId: String, recipient: String, text: String, app: String): String {
            val canonical = listOf(taskId, recipient, text, app).joinToString("\u0000")
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
        }

        fun issue(taskId: String, draft: MessageDraft, now: Long): MessageDispatchAuthorization =
            MessageDispatchAuthorization(taskId, fingerprint(taskId, draft), now)
    }
}
