package com.boopathi.bg.service.workflow

import com.boopathi.bg.service.agent.AccessibilityBridge
import com.boopathi.bg.service.recovery.SafeTargetMatcher
import com.boopathi.bg.service.recovery.TargetMatchResult
import com.boopathi.bg.service.recovery.UiSnapshotObserver
import com.boopathi.bg.service.recovery.UiStateSnapshot

/**
 * Result of a cross-app handoff or screen verification.
 */
sealed class HandoffVerificationResult {
    data class Success(
        val packageName: String,
        val snapshot: UiStateSnapshot,
        val details: String = "App and screen verified."
    ) : HandoffVerificationResult()

    data class WrongForegroundApp(
        val expectedPackage: String,
        val actualPackage: String?,
        val details: String
    ) : HandoffVerificationResult()

    data class AppUnavailable(
        val expectedAppOrPackage: String,
        val details: String
    ) : HandoffVerificationResult()

    data class ScreenNotReady(
        val packageName: String,
        val details: String
    ) : HandoffVerificationResult()

    data class TargetNotFound(
        val targetQuery: String,
        val packageName: String,
        val details: String
    ) : HandoffVerificationResult()

    data class TargetAmbiguous(
        val targetQuery: String,
        val candidates: List<String>,
        val details: String
    ) : HandoffVerificationResult()

    data class TargetNotEditable(
        val targetQuery: String,
        val details: String
    ) : HandoffVerificationResult()

    data class TextMismatch(
        val expectedText: String,
        val actualText: String?,
        val details: String
    ) : HandoffVerificationResult()
}

/**
 * Verifies app transitions, active foreground packages, editable field accessibility,
 * and text injection outcomes without guessing.
 */
class CrossAppHandoffVerifier(
    private val bridge: AccessibilityBridge,
    private val snapshotObserver: UiSnapshotObserver = UiSnapshotObserver(bridge)
) {

    /**
     * Verifies that the expected app package is currently in the foreground.
     */
    suspend fun verifyForegroundApp(
        expectedPackage: String,
        expectedAppName: String? = null
    ): HandoffVerificationResult {
        val snapshot = snapshotObserver.captureSnapshot()
        if (!snapshot.isAvailable) {
            return HandoffVerificationResult.ScreenNotReady(
                packageName = expectedPackage,
                details = "Accessibility observation is currently unavailable."
            )
        }

        val actualPkg = snapshot.packageName?.lowercase() ?: ""
        val targetPkg = expectedPackage.lowercase()

        val isMatch = actualPkg == targetPkg ||
                actualPkg.endsWith(".$targetPkg") ||
                targetPkg.endsWith(".$actualPkg")

        return if (isMatch) {
            HandoffVerificationResult.Success(
                packageName = actualPkg,
                snapshot = snapshot,
                details = "App '$expectedPackage' verified in foreground."
            )
        } else {
            HandoffVerificationResult.WrongForegroundApp(
                expectedPackage = expectedPackage,
                actualPackage = snapshot.packageName,
                details = "Expected '$expectedPackage' in foreground, but found '${snapshot.packageName ?: "none"}'."
            )
        }
    }

    /**
     * Inspects the newly active screen after a handoff, ensuring stale UI elements from prior screens
     * are not reused.
     */
    suspend fun inspectHandoffScreen(expectedPackage: String): HandoffVerificationResult {
        val appCheck = verifyForegroundApp(expectedPackage)
        if (appCheck !is HandoffVerificationResult.Success) {
            return appCheck
        }

        val snapshot = appCheck.snapshot
        return HandoffVerificationResult.Success(
            packageName = snapshot.packageName ?: expectedPackage,
            snapshot = snapshot,
            details = "Screen re-inspected: ${snapshot.visibleLabels.size} visible elements."
        )
    }

    /**
     * Verifies that a target input field is present, unique (unambiguous), and editable.
     */
    suspend fun verifyEditableField(
        targetQuery: String,
        expectedPackage: String
    ): HandoffVerificationResult {
        val appCheck = verifyForegroundApp(expectedPackage)
        if (appCheck !is HandoffVerificationResult.Success) {
            return appCheck
        }

        val snapshot = appCheck.snapshot
        val elements = try { bridge.getVisibleElements() } catch (e: Exception) { emptyList() }

        // Match against editable elements first
        val editableElements = elements.filter { it.isEditable && it.isEnabled }
        val matchResult = SafeTargetMatcher.matchTarget(
            elements = editableElements,
            query = targetQuery
        )

        return when (matchResult) {
            is TargetMatchResult.SingleMatch -> {
                HandoffVerificationResult.Success(
                    packageName = snapshot.packageName ?: expectedPackage,
                    snapshot = snapshot,
                    details = "Editable field '${matchResult.element.displayLabel}' verified."
                )
            }
            is TargetMatchResult.MultipleMatches -> {
                HandoffVerificationResult.TargetAmbiguous(
                    targetQuery = targetQuery,
                    candidates = matchResult.distinctLabels,
                    details = "Multiple editable candidates found matching '$targetQuery'."
                )
            }
            is TargetMatchResult.NoMatch -> {
                // Check if it appears as non-editable text in visible elements
                val visibleMatch = SafeTargetMatcher.matchTarget(elements, targetQuery)
                if (visibleMatch is TargetMatchResult.SingleMatch) {
                    HandoffVerificationResult.TargetNotEditable(
                        targetQuery = targetQuery,
                        details = "Target '$targetQuery' found, but it is not an editable input field."
                    )
                } else {
                    HandoffVerificationResult.TargetNotFound(
                        targetQuery = targetQuery,
                        packageName = snapshot.packageName ?: expectedPackage,
                        details = "Target input field '$targetQuery' was not found on screen."
                    )
                }
            }
        }
    }

    /**
     * Verifies that the inserted text exists in the destination field or visible snapshot.
     */
    suspend fun verifyInsertedText(
        expectedText: String,
        expectedPackage: String
    ): HandoffVerificationResult {
        val snapshot = snapshotObserver.captureSnapshot()
        val actualPkg = snapshot.packageName?.lowercase() ?: ""

        if (actualPkg != expectedPackage.lowercase()) {
            return HandoffVerificationResult.WrongForegroundApp(
                expectedPackage = expectedPackage,
                actualPackage = snapshot.packageName,
                details = "Foreground app changed during text verification."
            )
        }

        val match = snapshot.visibleLabels.any { it.contains(expectedText, ignoreCase = true) } ||
                snapshot.editableLabels.any { it.contains(expectedText, ignoreCase = true) }

        return if (match) {
            HandoffVerificationResult.Success(
                packageName = actualPkg,
                snapshot = snapshot,
                details = "Inserted text verified in target app."
            )
        } else {
            HandoffVerificationResult.TextMismatch(
                expectedText = expectedText,
                actualText = snapshot.editableLabels.firstOrNull(),
                details = "Inserted text could not be verified in target UI."
            )
        }
    }
}

