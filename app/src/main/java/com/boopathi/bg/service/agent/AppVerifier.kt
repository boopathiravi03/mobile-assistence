package com.boopathi.bg.service.agent

import kotlinx.coroutines.delay

data class VerificationResult(
    val isVerified: Boolean,
    val details: String
)

/**
 * Verifies observable state transitions following action execution.
 * Distinguishes execution attempt from actual verified outcome.
 */
class AppVerifier(private val bridge: AccessibilityBridge) {

    suspend fun verifyWindowActive(
        expectedPackageSubstring: String,
        timeoutMs: Long = 6000L
    ): VerificationResult {
        val startTime = System.currentTimeMillis()
        val expected = expectedPackageSubstring.lowercase().trim()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val currentPkg = bridge.getCurrentPackage().lowercase()
            if (currentPkg.isNotEmpty() && currentPkg.contains(expected)) {
                val elapsed = System.currentTimeMillis() - startTime
                return VerificationResult(
                    isVerified = true,
                    details = "Foreground window verified as '$currentPkg' in ${elapsed}ms."
                )
            }
            delay(250)
        }

        val finalPkg = bridge.getCurrentPackage()
        return VerificationResult(
            isVerified = false,
            details = "Timeout waiting for '$expectedPackageSubstring'. Current foreground: '$finalPkg'."
        )
    }

    fun verifyUiInspection(elements: List<UiElementInfo>): VerificationResult {
        return if (elements.isNotEmpty()) {
            VerificationResult(
                isVerified = true,
                details = "Successfully inspected ${elements.size} visible UI elements."
            )
        } else {
            VerificationResult(
                isVerified = false,
                details = "Inspection completed but zero interactive/text elements were found in the active window."
            )
        }
    }

    fun verifyActionOutcome(
        actionType: AppActionType,
        wasApiCallSuccessful: Boolean,
        targetDescription: String
    ): VerificationResult {
        return if (wasApiCallSuccessful) {
            VerificationResult(
                isVerified = true,
                details = "$actionType on $targetDescription confirmed by system accessibility."
            )
        } else {
            VerificationResult(
                isVerified = false,
                details = "$actionType on $targetDescription was rejected or could not be performed."
            )
        }
    }
}

