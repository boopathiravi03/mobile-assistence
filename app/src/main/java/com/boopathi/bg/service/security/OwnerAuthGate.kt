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
 * Mockable test implementation. It fails closed unless a test explicitly requests success.
 */
open class SimpleOwnerAuthGate(
    var mockStatus: AuthResultStatus = AuthResultStatus.UNAVAILABLE
) : OwnerAuthGate {
    override suspend fun authenticate(title: String, subtitle: String): AuthResult {
        return AuthResult(status = mockStatus)
    }
}

/** Used until an Activity installs the system credential-confirmation flow. */
class UnavailableOwnerAuthGate : OwnerAuthGate {
    override suspend fun authenticate(title: String, subtitle: String): AuthResult =
        AuthResult(AuthResultStatus.UNAVAILABLE, "Device authentication is unavailable.")
}

