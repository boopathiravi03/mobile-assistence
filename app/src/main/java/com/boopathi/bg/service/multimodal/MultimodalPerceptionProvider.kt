package com.boopathi.bg.service.multimodal

/**
 * Common abstraction for multimodal AI perception engines capable of interpreting
 * authorized user-provided images alongside natural language queries.
 */
interface MultimodalPerceptionProvider {
    val name: String
    val isAvailable: Boolean
    val supportsMultimodal: Boolean

    /**
     * Analyzes the supplied authorized image and produces a structured perception result.
     *
     * @param image User-authorized in-memory image container.
     * @param prompt User's question or instruction about the image (English, Tamil, Tanglish).
     * @param taskContext Optional minimal context (e.g. current foreground app or workflow goal).
     * @param conversationHistory Multi-turn follow-up history within the active image session.
     */
    suspend fun analyzeImage(
        image: MultimodalImage,
        prompt: String,
        taskContext: String? = null,
        conversationHistory: List<Pair<String, String>> = emptyList()
    ): Result<MultimodalAnalysisResult>
}

