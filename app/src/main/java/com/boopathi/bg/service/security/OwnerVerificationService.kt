package com.boopathi.bg.service.security

interface OwnerVerificationService {
    suspend fun verifySpeaker(audioData: ByteArray? = null): Boolean
}

class DefaultOwnerVerificationService : OwnerVerificationService {
    override suspend fun verifySpeaker(audioData: ByteArray?): Boolean {
        // Phase 1 foundation: passes all commands to prove pipeline
        // Future phases will incorporate voice biometric embeddings
        return true
    }
}

