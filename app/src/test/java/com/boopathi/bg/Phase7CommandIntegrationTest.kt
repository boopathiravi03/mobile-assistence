package com.boopathi.bg

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boopathi.bg.data.local.BGDatabase
import com.boopathi.bg.data.model.OwnerProfile
import com.boopathi.bg.data.repository.RoomBGRepository
import com.boopathi.bg.service.command.CommandProcessor
import com.boopathi.bg.service.memory.RoomMemoryManager
import com.boopathi.bg.service.multimodal.FakeMultimodalPerceptionProvider
import com.boopathi.bg.service.multimodal.MultimodalAnalysisResult
import com.boopathi.bg.service.multimodal.MultimodalImage
import com.boopathi.bg.service.multimodal.MultimodalSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Phase 7 Integration Tests: Natural Language Command Routing & Multimodal Dispatch.
 * - Multilingual queries in English, Tamil, and Tanglish.
 * - Session-aware perception vs no-image guidance prompt.
 * - Non-executable perception responses with action separation.
 */
@RunWith(AndroidJUnit4::class)
class Phase7CommandIntegrationTest {

    private lateinit var context: Context
    private lateinit var db: BGDatabase
    private lateinit var repository: RoomBGRepository
    private lateinit var memoryManager: RoomMemoryManager
    private lateinit var fakeMultimodalProvider: FakeMultimodalPerceptionProvider
    private lateinit var sessionManager: MultimodalSessionManager
    private lateinit var processor: CommandProcessor
    private val owner = OwnerProfile(name = "Boopathi", preferredLanguage = "en")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, BGDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomBGRepository(db, Dispatchers.Unconfined)
        memoryManager = RoomMemoryManager(repository)
        fakeMultimodalProvider = FakeMultimodalPerceptionProvider()
        sessionManager = MultimodalSessionManager()

        processor = CommandProcessor(
            memoryManager = memoryManager,
            multimodalProvider = fakeMultimodalProvider,
            multimodalSessionManager = sessionManager
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun sampleImage(): MultimodalImage {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
        val bytes = stream.toByteArray()
        return MultimodalImage(
            bytes = bytes,
            mimeType = "image/png",
            width = 10,
            height = 10
        )
    }

    @Test
    fun test_noActiveImagePromptWhenUserAsksAboutScreenshot() = runBlocking {
        // No image is active in session
        val resultEn = processor.processCommand("Summarize this image")
        assertFalse(resultEn.isSuccess)
        assertTrue(resultEn.textResponse.contains("Please select or attach an image"))

        val resultTa = processor.processCommand("Indha screenshot-la enna irukku?")
        assertFalse(resultTa.isSuccess)
        assertTrue(resultTa.textResponse.contains("attach", ignoreCase = true))
    }

    @Test
    fun test_activeImageQueryDispatchesToMultimodalProvider_English() = runBlocking {
        sessionManager.startSession(sampleImage())
        fakeMultimodalProvider.cannedResult = MultimodalAnalysisResult(
            summary = "The screenshot shows an error dialog: 'Network Connection Timeout'.",
            extractedText = "Network Connection Timeout",
            visualElements = listOf("OK button", "Retry button"),
            suggestedActions = listOf("Check your Wi-Fi or mobile data"),
            providerName = "FakeMultimodal"
        )

        val result = processor.processCommand("Explain this error message")
        assertTrue(result.isSuccess)
        assertEquals("FakeMultimodal", result.providerName)
        assertTrue(result.textResponse.contains("Network Connection Timeout"))
        assertEquals(1, fakeMultimodalProvider.callCount)
    }

    @Test
    fun test_activeImageQueryDispatchesToMultimodalProvider_Tanglish() = runBlocking {
        sessionManager.startSession(sampleImage())
        fakeMultimodalProvider.cannedResult = MultimodalAnalysisResult(
            summary = "Indha screen-la Settings icon mela valadhu pakkam irukku.",
            visualElements = listOf("Settings gear icon"),
            providerName = "FakeMultimodal"
        )

        val result = processor.processCommand("Indha screen-la Settings enga irukku?")
        assertTrue(result.isSuccess)
        assertTrue(result.textResponse.contains("Settings icon"))
    }

    @Test
    fun test_activeImageQueryDispatchesToMultimodalProvider_TamilExplanation() = runBlocking {
        sessionManager.startSession(sampleImage())
        fakeMultimodalProvider.cannedResult = MultimodalAnalysisResult(
            summary = "Indha error unga internet connection illa nu solludhu.",
            suggestedActions = listOf("Wi-Fi on pannunga"),
            providerName = "FakeMultimodal"
        )

        val result = processor.processCommand("Indha error-a simple Tamil-la explain pannu")
        assertTrue(result.isSuccess)
        assertTrue(result.textResponse.contains("internet connection"))
    }

    @Test
    fun test_followUpQueriesWithinActiveSessionUseHistory() = runBlocking {
        sessionManager.startSession(sampleImage())

        fakeMultimodalProvider.cannedResult = MultimodalAnalysisResult(
            summary = "Initial error analysis",
            providerName = "FakeMultimodal"
        )
        processor.processCommand("Explain this error message")

        fakeMultimodalProvider.cannedResult = MultimodalAnalysisResult(
            summary = "Follow up: the warning means server unavailable.",
            providerName = "FakeMultimodal"
        )
        val followUpResult = processor.processCommand("What does this warning mean?")
        assertTrue(followUpResult.isSuccess)
        assertTrue(followUpResult.textResponse.contains("warning means server unavailable"))

        // Check history recorded
        val session = sessionManager.getActiveSession()
        assertEquals(2, session?.conversationTurns?.size)
    }
}

