package com.boopathi.bg.service.recovery

/**
 * Categorization of discrepancies between expected UI state and actual observed UI state.
 */
enum class UiMismatchType {
    TARGET_NOT_FOUND,
    TARGET_AMBIGUOUS,
    WRONG_FOREGROUND_APP,
    SCREEN_NOT_READY,
    EXPECTED_STATE_NOT_REACHED,
    OBSERVATION_UNAVAILABLE,
    ACTION_FAILED
}

/**
 * Detailed representation of an observed mismatch.
 */
data class UiMismatch(
    val type: UiMismatchType,
    val description: String,
    val expectedPackage: String? = null,
    val actualPackage: String? = null,
    val targetQuery: String? = null,
    val candidateMatches: List<String> = emptyList(),
    val observedSnapshot: UiStateSnapshot? = null
)

