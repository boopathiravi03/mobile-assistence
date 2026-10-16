package com.boopathi.bg.service.multimodal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Data container for an active ephemeral multimodal session.
 */
data class MultimodalSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val image: MultimodalImage,
    val createdAt: Long = System.currentTimeMillis(),
    var lastAccessedAt: Long = System.currentTimeMillis(),
    val conversationTurns: MutableList<Pair<String, String>> = mutableListOf(),
    val ttlMillis: Long = MultimodalSessionManager.DEFAULT_SESSION_TTL_MS
) {
    fun isExpired(currentTime: Long = System.currentTimeMillis()): Boolean {
        return (currentTime - lastAccessedAt) > ttlMillis
    }
}

/**
 * Manages the active ephemeral multimodal perception session.
 *
 * Guarantees:
 * - Keeps image data strictly in memory during an active session.
 * - Expires sessions automatically after [DEFAULT_SESSION_TTL_MS] (5 minutes).
 * - Immediate wipe/cleanup on explicit cancellation or image replacement.
 * - Multi-turn follow-up queries remain scoped to the single active image.
 */
class MultimodalSessionManager(
    val sessionTtlMs: Long = DEFAULT_SESSION_TTL_MS
) {
    companion object {
        const val DEFAULT_SESSION_TTL_MS = 5 * 60 * 1000L // 5 minutes
    }

    private val _activeSession = MutableStateFlow<MultimodalSession?>(null)
    val activeSession: StateFlow<MultimodalSession?> = _activeSession.asStateFlow()

    /**
     * Starts a new ephemeral session with the provided image, wiping any existing session.
     */
    @Synchronized
    fun startSession(image: MultimodalImage): MultimodalSession {
        clearSession() // Ensure any prior session's data is wiped first
        val newSession = MultimodalSession(
            image = image,
            ttlMillis = sessionTtlMs
        )
        _activeSession.value = newSession
        return newSession
    }

    /**
     * Retrieves the currently active session if valid and not expired.
     * Automatically invalidates and clears expired sessions.
     */
    @Synchronized
    fun getActiveSession(currentTime: Long = System.currentTimeMillis()): MultimodalSession? {
        val current = _activeSession.value ?: return null
        if (current.isExpired(currentTime)) {
            clearSession()
            return null
        }
        current.lastAccessedAt = currentTime
        return current
    }

    /**
     * Records a question/answer exchange for the active image session.
     */
    @Synchronized
    fun recordTurn(question: String, answer: String, currentTime: Long = System.currentTimeMillis()): Boolean {
        val current = getActiveSession(currentTime) ?: return false
        current.conversationTurns.add(question to answer)
        current.lastAccessedAt = currentTime
        return true
    }

    /**
     * Replaces the currently active image, clearing all previous conversation history.
     */
    @Synchronized
    fun replaceImage(newImage: MultimodalImage): MultimodalSession {
        return startSession(newImage)
    }

    /**
     * Clears and wipes the active session and its associated in-memory data.
     */
    @Synchronized
    fun clearSession() {
        _activeSession.value = null
    }

    /**
     * Checks if a valid, unexpired session currently exists.
     */
    @Synchronized
    fun hasActiveSession(currentTime: Long = System.currentTimeMillis()): Boolean {
        return getActiveSession(currentTime) != null
    }
}

