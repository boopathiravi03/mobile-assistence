package com.boopathi.bg.service.security

/**
 * Result of an owner / device authentication challenge.
 */
enum class AuthResultStatus {
    SUCCESS,
    CANCELLED,
    FAILED,
    UNAVAILABLE
}

data class AuthResult(
    val status: AuthResultStatus,
    val details: String? = null
) {
    val isSuccess: Boolean
        get() = status == AuthResultStatus.SUCCESS
}

/**
 * Device-owner authentication gate interface.
 * Implemented using Android BiometricPrompt with device credential fallback in production,
 * and easily mocked in test environments.
 */
interface OwnerAuthGate {
    suspend fun authenticate(
        title: String = "Confirm Message Dispatch",
        subtitle: String = "Authenticate to send message"
    ): AuthResult
}

/**
 * Default in-memory / mockable implementation of OwnerAuthGate for tests and head-less environments.
 */
open class SimpleOwnerAuthGate(
    var mockStatus: AuthResultStatus = AuthResultStatus.SUCCESS
) : OwnerAuthGate {
    override suspend fun authenticate(title: String, subtitle: String): AuthResult {
        return AuthResult(status = mockStatus)
    }
}

