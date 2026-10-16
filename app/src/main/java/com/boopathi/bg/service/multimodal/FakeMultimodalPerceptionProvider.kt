package com.boopathi.bg.service.multimodal

import kotlinx.coroutines.delay

/**
 * Deterministic test and offline simulation implementation of MultimodalPerceptionProvider.
 */
class FakeMultimodalPerceptionProvider(
    override val name: String = "FakeMultimodal",
    var cannedResult: MultimodalAnalysisResult? = null,
    var shouldFail: Boolean = false,
    var failureException: Exception = RuntimeException("Simulated multimodal perception failure"),
    var delayMillis: Long = 0L,
    override var isAvailable: Boolean = true,
    override var supportsMultimodal: Boolean = true
) : MultimodalPerceptionProvider {

    var lastAnalyzedImage: MultimodalImage? = null
    var lastPrompt: String? = null
    var lastTaskContext: String? = null
    var lastHistory: List<Pair<String, String>> = emptyList()
    var callCount: Int = 0

    override suspend fun analyzeImage(
        image: MultimodalImage,
        prompt: String,
        taskContext: String?,
        conversationHistory: List<Pair<String, String>>
    ): Result<MultimodalAnalysisResult> {
        callCount++
        lastAnalyzedImage = image
        lastPrompt = prompt
        lastTaskContext = taskContext
        lastHistory = conversationHistory

        if (delayMillis > 0) {
            delay(delayMillis)
        }

        if (!supportsMultimodal) {
            return Result.failure(
                UnsupportedOperationException("Provider '$name' does not support multimodal image analysis.")
            )
        }

        if (shouldFail) {
            return Result.failure(failureException)
        }

        // Validate image constraints
        val validation = MultimodalImagePreprocessor.validateImage(image.bytes, image.mimeType)
        if (!validation.isValid) {
            val err = when (validation) {
                is ImageValidationResult.CorruptOrUnreadable -> "Corrupt image: ${validation.reason}"
                is ImageValidationResult.Oversized -> "Image oversized: ${validation.actualBytes} > ${validation.maxBytes}"
                is ImageValidationResult.UnsupportedFormat -> "Unsupported format: ${validation.mimeType}"
                else -> "Invalid image"
            }
            return Result.failure(IllegalArgumentException(err))
        }

        val hasPromptInjection = MultimodalSafetyFilter.isPromptInjectionDetected(prompt)
        val hasSensitive = MultimodalSafetyFilter.containsSensitiveData(prompt)

        val result = cannedResult ?: MultimodalAnalysisResult(
            summary = "Image shows UI element for $prompt",
            extractedText = "Settings -> Network & Internet",
            visualElements = listOf("Wi-Fi switch", "Back button", "Search icon"),
            suggestedActions = listOf("Check Wi-Fi connection"),
            confidence = 0.95f,
            containsSensitiveContent = hasSensitive,
            isPromptInjectionDetected = hasPromptInjection,
            rawModelResponse = "Analysis complete for: $prompt",
            providerName = name
        )

        return Result.success(result)
    }
}

