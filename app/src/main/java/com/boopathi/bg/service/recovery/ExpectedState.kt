package com.boopathi.bg.service.recovery

/**
 * Expected UI state target against which actual UI state snapshots are verified.
 */
enum class ExpectedStateType {
    APP_FOREGROUND,
    TARGET_VISIBLE,
    TARGET_ACTIONABLE,
    EDITOR_VISIBLE,
    EDITOR_CONTAINS_EXPECTED_TEXT,
    SCREEN_CHANGED,
    SCROLL_STATE_CHANGED,
    HOME_VISIBLE,
    SETTINGS_SECTION_VISIBLE
}

/**
 * Specification of an expected UI state for a step verification or post-action verification.
 */
data class ExpectedState(
    val type: ExpectedStateType,
    val expectedPackage: String? = null,
    val targetQuery: String? = null,
    val expectedText: String? = null,
    val baselineSnapshot: UiStateSnapshot? = null
)

/**
 * Verification outcome of comparing an ExpectedState with a UiStateSnapshot.
 */
enum class StateVerificationStatus {
    VERIFIED,
    NOT_VERIFIED,
    OBSERVATION_UNAVAILABLE
}

data class StateVerificationResult(
    val status: StateVerificationStatus,
    val mismatch: UiMismatch? = null,
    val details: String = ""
) {
    val isVerified: Boolean get() = status == StateVerificationStatus.VERIFIED
}

