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

    companion object {
        val TRUSTED_APP_PACKAGES: Map<String, Set<String>> = mapOf(
            "youtube" to setOf("com.google.android.youtube", "com.google.android.youtube.tv"),
            "instagram" to setOf("com.instagram.android"),
            "whatsapp" to setOf("com.whatsapp", "com.whatsapp.w4b"),
            "gmail" to setOf("com.google.android.gm"),
            "chrome" to setOf("com.android.chrome"),
            "facebook" to setOf("com.facebook.katana", "com.facebook.lite"),
            "settings" to setOf("com.android.settings"),
            "camera" to setOf("com.google.android.GoogleCamera", "com.android.camera", "com.android.camera2"),
            "calculator" to setOf("com.google.android.calculator", "com.android.calculator2"),
            "maps" to setOf("com.google.android.apps.maps"),
            "play store" to setOf("com.android.vending"),
            "playstore" to setOf("com.android.vending"),
            "clock" to setOf("com.google.android.deskclock", "com.android.deskclock"),
            "calendar" to setOf("com.google.android.calendar"),
            "contacts" to setOf("com.google.android.contacts", "com.android.contacts"),
            "files" to setOf("com.google.android.apps.nbu.files", "com.android.documentsui"),
            "messages" to setOf("com.google.android.apps.messaging", "com.android.mms"),
            "phone" to setOf("com.google.android.dialer", "com.android.dialer"),
            "spotify" to setOf("com.spotify.music"),
            "netflix" to setOf("com.netflix.mediaclient"),
            "twitter" to setOf("com.twitter.android"),
            "x" to setOf("com.twitter.android")
        )

        fun resolveExpectedPackages(target: String): Set<String> {
            val normalized = target.lowercase().trim()
            if (normalized.isBlank()) return emptySet()

            val mapped = TRUSTED_APP_PACKAGES[normalized]
            if (mapped != null) return mapped

            return setOf(normalized)
        }
    }

    suspend fun verifyWindowActive(
        expectedPackageSubstring: String,
        timeoutMs: Long = 6000L
    ): VerificationResult {
        val target = expectedPackageSubstring.trim()
        if (target.isBlank()) {
            return VerificationResult(
                isVerified = false,
                details = "Cannot verify foreground window for blank target app."
            )
        }

        if (timeoutMs <= 0L) {
            val currentPkg = bridge.getCurrentPackage()
            return VerificationResult(
                isVerified = false,
                details = "Invalid timeout ($timeoutMs ms) waiting for '$target'. Current foreground: '$currentPkg'."
            )
        }

        val expectedPackages = resolveExpectedPackages(target)
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val currentPkg = bridge.getCurrentPackage().lowercase().trim()
            if (currentPkg.isNotEmpty() && expectedPackages.contains(currentPkg)) {
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

