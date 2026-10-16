package com.boopathi.bg.service.voice

import java.util.concurrent.atomic.AtomicLong

/** Thread-safe guard that rejects stale callbacks and duplicate final recognition results. */
internal class VoiceSessionGate {
    private val sequence = AtomicLong()
    @Volatile private var currentId: Long? = null
    @Volatile private var finalResultClaimed = false

    @Synchronized fun begin(): Long = sequence.incrementAndGet().also {
        currentId = it
        finalResultClaimed = false
    }

    fun isCurrent(id: Long): Boolean = currentId == id

    @Synchronized fun claimFinalResult(id: Long): Boolean {
        if (currentId != id || finalResultClaimed) return false
        finalResultClaimed = true
        return true
    }

    @Synchronized fun invalidate(id: Long? = null) {
        if (id == null || currentId == id) {
            currentId = null
            finalResultClaimed = false
        }
    }
}
