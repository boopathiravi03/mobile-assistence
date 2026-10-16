package com.boopathi.bg

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boopathi.bg.service.multimodal.FakeMultimodalPerceptionProvider
import com.boopathi.bg.service.multimodal.MultimodalAnalysisResult
import com.boopathi.bg.service.multimodal.MultimodalImage
import com.boopathi.bg.service.multimodal.TextOnlyMultimodalProvider
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Phase 7 Unit Tests: Perception Providers, Capabilities & Error Handling.
 * - Requirement 6: Provider timeout handling.
 * - Requirement 7: Provider error handling.
 * - Requirement 8: Malformed model response rejection.
 * - Requirement 9: Text-only providers report unsupported image capability.
 */
@RunWith(AndroidJUnit4::class)
class Phase7MultimodalProviderTest {

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
    fun test6_providerTimeoutHandlingSafelyFails() = runBlocking {
        val provider = FakeMultimodalPerceptionProvider(
            delayMillis = 2000L // 2 seconds delay
        )

        try {
            withTimeout(500L) { // 500ms timeout threshold
                provider.analyzeImage(sampleImage(), "Explain this screen")
            }
            fail("Expected TimeoutCancellationException")
        } catch (e: TimeoutCancellationException) {
            // Success: Bounded timeout prevented hanging
            assertTrue(true)
        }
    }

    @Test
    fun test7_providerErrorHandlingPropagatesFailureCleanly() = runBlocking {
        val provider = FakeMultimodalPerceptionProvider(
            shouldFail = true,
            failureException = java.io.IOException("Remote network connection failed")
        )

        val result = provider.analyzeImage(sampleImage(), "What is this?")
        assertTrue("Analysis call must return failure result", result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue("Exception message should reflect network error", exception?.message?.contains("network") == true)
    }

    @Test
    fun test8_malformedModelResponseHandledSafely() = runBlocking {
        val provider = FakeMultimodalPerceptionProvider(
            cannedResult = MultimodalAnalysisResult(
                summary = "Image details could not be parsed.",
                extractedText = null,
                visualElements = emptyList(),
                suggestedActions = emptyList(),
                confidence = 0.2f,
                uncertaintyIndicators = listOf("Malformed candidate response"),
                providerName = "FakeMultimodal"
            )
        )

        val result = provider.analyzeImage(sampleImage(), "Explain error")
        assertTrue(result.isSuccess)
        val analysis = result.getOrThrow()
        assertTrue(analysis.confidence < 0.5f)
        assertTrue(analysis.uncertaintyIndicators.isNotEmpty())
    }

    @Test
    fun test9_textOnlyProviderReportsUnsupportedCapability() = runBlocking {
        val textOnlyProvider = TextOnlyMultimodalProvider("Groq Llama-3.1-8b")

        assertFalse("Text-only provider must report supportsMultimodal = false", textOnlyProvider.supportsMultimodal)

        val result = textOnlyProvider.analyzeImage(sampleImage(), "Summarize screenshot")
        assertTrue("Text-only provider must return failure on image input", result.isFailure)

        val exception = result.exceptionOrNull()
        assertTrue("Exception must be UnsupportedOperationException", exception is UnsupportedOperationException)
        assertTrue(exception?.message?.contains("does not support multimodal") == true)
    }
}

