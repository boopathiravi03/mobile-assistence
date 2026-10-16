package com.boopathi.bg.service.multimodal

import java.util.UUID

/**
 * Source origin of an image provided to BG.
 */
enum class ImageSource {
    USER_PICKER,
    USER_SCREENSHOT
}

/**
 * Validated, bounded in-memory representation of an image authorized by the user for perception.
 *
 * Explicitly avoids:
 * - Persisting raw byte arrays into long-term Room SQLite database.
 * - Leaking image data outside the active ephemeral perception session.
 */
data class MultimodalImage(
    val id: String = UUID.randomUUID().toString(),
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long = bytes.size.toLong(),
    val source: ImageSource = ImageSource.USER_PICKER,
    val timestamp: Long = System.currentTimeMillis(),
    val uriString: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MultimodalImage
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * Outcome of evaluating an image prior to ingestion or remote transfer.
 */
sealed class ImageValidationResult {
    object Valid : ImageValidationResult()
    data class CorruptOrUnreadable(val reason: String) : ImageValidationResult()
    data class Oversized(val actualBytes: Long, val maxBytes: Long) : ImageValidationResult()
    data class UnsupportedFormat(val mimeType: String) : ImageValidationResult()

    val isValid: Boolean get() = this is Valid
}

/**
 * Structured, typed interpretation result produced by a MultimodalPerceptionProvider.
 *
 * Action Separation Safeguard:
 * [suggestedActions] are strictly advisory text descriptions for the user.
 * The multimodal perception layer possesses NO device execution capabilities and NEVER
 * executes an Android action directly.
 */
data class MultimodalAnalysisResult(
    val summary: String,
    val extractedText: String? = null,
    val visualElements: List<String> = emptyList(),
    val suggestedActions: List<String> = emptyList(),
    val confidence: Float = 1.0f,
    val uncertaintyIndicators: List<String> = emptyList(),
    val containsSensitiveContent: Boolean = false,
    val isPromptInjectionDetected: Boolean = false,
    val rawModelResponse: String? = null,
    val providerName: String,
    val analyzedAt: Long = System.currentTimeMillis()
)

