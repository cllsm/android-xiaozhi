package com.xiaozhi.android.mcp

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

object VisionResultStore {
    private val latestResult = AtomicReference<String?>(null)
    private val pendingSpeech = AtomicBoolean(false)

    fun update(text: String) {
        val normalized = text.trim()
        if (normalized.isNotEmpty()) {
            latestResult.set(normalized)
        }
    }

    fun latest(): String? = latestResult.get()

    fun markPendingSpeech() {
        pendingSpeech.set(true)
    }

    fun consumePendingSpeech(): Boolean = pendingSpeech.compareAndSet(true, false)
}
