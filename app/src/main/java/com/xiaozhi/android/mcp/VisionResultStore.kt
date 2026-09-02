package com.xiaozhi.android.mcp

import java.util.concurrent.atomic.AtomicReference

object VisionResultStore {
    private val latestResult = AtomicReference<String?>(null)

    fun update(text: String) {
        val normalized = text.trim()
        if (normalized.isNotEmpty()) {
            latestResult.set(normalized)
        }
    }

    fun latest(): String? = latestResult.get()
}
