package com.boopathi.bg.service.multimodal

/**
 * Perception provider wrapper for text-only AI backends (e.g. Groq llama-3.1-8b or local command engines).
 *
 * Explicitly rejects multimodal requests and documents unsupported image capability.
 */
class TextOnlyMultimodalProvider(
    override val name: String = "Text-Only Engine"
) : MultimodalPerceptionProvider {

    override val isAvailable: Boolean = true
    override val supportsMultimodal: Boolean = false

    override suspend fun analyzeImage(
        image: MultimodalImage,
        prompt: String,
        taskContext: String?,
        conversationHistory: List<Pair<String, String>>
    ): Result<MultimodalAnalysisResult> {
        return Result.failure(
            UnsupportedOperationException(
                "Provider '$name' does not support multimodal image analysis. Please use a multimodal provider such as Gemini."
            )
        )
    }
}

