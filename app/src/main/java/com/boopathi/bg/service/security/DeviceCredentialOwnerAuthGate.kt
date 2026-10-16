package com.boopathi.bg.service.security

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.app.Activity
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Uses Android's device credential UI and fails closed when the device has no secure lock. */
class DeviceCredentialOwnerAuthGate(
    context: Context
) : OwnerAuthGate {
    private val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private val lock = Any()
    private var pending: CancellableContinuation<AuthResult>? = null
    @Volatile private var launchConfirmation: ((Intent) -> Unit)? = null

    fun attachLauncher(launcher: (Intent) -> Unit) {
        launchConfirmation = launcher
    }

    override suspend fun authenticate(title: String, subtitle: String): AuthResult =
        suspendCancellableCoroutine { continuation ->
            if (!keyguard.isDeviceSecure) {
                continuation.resume(AuthResult(AuthResultStatus.UNAVAILABLE, "Set a device screen lock before sending messages."))
                return@suspendCancellableCoroutine
            }
            val intent = keyguard.createConfirmDeviceCredentialIntent(title, subtitle)
            if (intent == null) {
                continuation.resume(AuthResult(AuthResultStatus.UNAVAILABLE, "Device credential confirmation is unavailable."))
                return@suspendCancellableCoroutine
            }
            val launch = launchConfirmation
            if (launch == null) {
                continuation.resume(AuthResult(AuthResultStatus.UNAVAILABLE, "Device credential confirmation is unavailable."))
                return@suspendCancellableCoroutine
            }
            synchronized(lock) {
                if (pending != null) {
                    continuation.resume(AuthResult(AuthResultStatus.FAILED, "Another authentication request is active."))
                    return@suspendCancellableCoroutine
                }
                pending = continuation
            }
            // Keep the slot occupied until Android returns the result. Otherwise a cancelled
            // request could be followed by a new one and consume the old prompt's result.
            try {
                launch(intent)
            } catch (_: Exception) {
                finish(AuthResultStatus.UNAVAILABLE)
            }
        }

    fun onActivityResult(resultCode: Int) {
        finish(if (resultCode == Activity.RESULT_OK) AuthResultStatus.SUCCESS else AuthResultStatus.CANCELLED)
    }

    private fun finish(status: AuthResultStatus) {
        val continuation = synchronized(lock) { pending.also { pending = null } } ?: return
        if (continuation.isActive) continuation.resume(AuthResult(status))
    }
}
