package com.boopathi.bg

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boopathi.bg.service.multimodal.MultimodalImage
import com.boopathi.bg.service.multimodal.MultimodalSessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Phase 7 Unit Tests: Ephemeral Session Management, Context Isolation & Expiration.
 * - Requirement 4: Image replacement clears stale context.
 * - Requirement 5: Cancellation clears temporary data.
 * - Requirement 10: User questions remain associated with the correct active image.
 * - Requirement 11: Follow-up questions cannot access an expired image session.
 */
@RunWith(AndroidJUnit4::class)
class Phase7SessionManagementTest {

    private lateinit var sessionManager: MultimodalSessionManager

    @Before
    fun setUp() {
        sessionManager = MultimodalSessionManager(sessionTtlMs = 1000L) // 1 second TTL for test
    }

    private fun createImage(label: String): MultimodalImage {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
        val bytes = stream.toByteArray()
        return MultimodalImage(
            id = label,
            bytes = bytes,
            mimeType = "image/png",
            width = 10,
            height = 10
        )
    }

    @Test
    fun test10_userQuestionsRemainAssociatedWithActiveImage() {
        val img1 = createImage("img_1")
        val session = sessionManager.startSession(img1)

        assertEquals("img_1", session.image.id)
        assertTrue(sessionManager.hasActiveSession())

        // Add first turn
        sessionManager.recordTurn("What is this?", "This is a settings screen.")
        // Add follow-up turn
        sessionManager.recordTurn("Where is Wi-Fi?", "Wi-Fi is the first option.")

        val active = sessionManager.getActiveSession()
        assertNotNull(active)
        assertEquals(2, active?.conversationTurns?.size)
        assertEquals("What is this?", active?.conversationTurns?.get(0)?.first)
        assertEquals("Where is Wi-Fi?", active?.conversationTurns?.get(1)?.first)
        assertEquals("img_1", active?.image?.id)
    }

    @Test
    fun test4_imageReplacementClearsStaleContextAndHistory() {
        val img1 = createImage("img_1")
        sessionManager.startSession(img1)
        sessionManager.recordTurn("Question about img 1", "Answer for img 1")

        val img2 = createImage("img_2")
        val newSession = sessionManager.replaceImage(img2)

        assertEquals("img_2", newSession.image.id)
        assertTrue("New session should have clean history", newSession.conversationTurns.isEmpty())

        val active = sessionManager.getActiveSession()
        assertEquals("img_2", active?.image?.id)
        assertEquals(0, active?.conversationTurns?.size)
    }

    @Test
    fun test5_cancellationClearsTemporarySessionData() {
        val img = createImage("img_cancel")
        sessionManager.startSession(img)
        sessionManager.recordTurn("Question", "Answer")

        assertTrue(sessionManager.hasActiveSession())

        // Explicit cancellation
        sessionManager.clearSession()

        assertFalse("Session must be inactive after clearSession", sessionManager.hasActiveSession())
        assertNull("Active session must be null after cancellation", sessionManager.getActiveSession())
    }

    @Test
    fun test11_followUpQuestionsCannotAccessExpiredSession() {
        val img = createImage("img_ttl")
        val session = sessionManager.startSession(img)
        sessionManager.recordTurn("First turn", "Answer 1")

        val initialTime = session.lastAccessedAt
        // Simulate time advancing beyond TTL (1000ms + 100ms)
        val expiredTime = initialTime + 1100L

        assertNull("Expired session must return null", sessionManager.getActiveSession(expiredTime))
        assertFalse("Expired session must report hasActiveSession = false", sessionManager.hasActiveSession(expiredTime))
    }
}

