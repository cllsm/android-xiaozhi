package com.xiaozhi.android.mcp

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object UserTextStore {
    const val REQUEST_TEXT = "读取用户长文本"

    private val latestText = AtomicReference<String?>(null)
    private val pendingEcho = AtomicBoolean(false)

    fun update(text: String) {
        val normalized = text.trim()
        if (normalized.isNotEmpty()) {
            latestText.set(normalized)
        }
    }

    fun latest(): String? = latestText.get()

    fun markPendingEcho() {
        pendingEcho.set(true)
    }

    fun consumeEcho(text: String): Boolean {
        if (!pendingEcho.get() || text.trim() != REQUEST_TEXT) return false
        return pendingEcho.compareAndSet(true, false)
    }
}
