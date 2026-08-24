package com.xiaozhi.android.media

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

object MusicPlaybackProbe {
    fun isPlayableUrl(client: OkHttpClient, url: String): Boolean {
        val endpoint = url.toHttpUrlOrNull() ?: return false
        if (endpoint.scheme !in setOf("http", "https")) return false

        val head = probe(client, Request.Builder().url(endpoint).head().build())
        if (head) return true
        return probe(
            client,
            Request.Builder()
                .url(endpoint)
                .header("Range", "bytes=0-0")
                .build()
        )
    }

    fun isValidPlaybackResponse(
        code: Int,
        contentType: String?,
        contentLength: Long
    ): Boolean {
        if (code != 200 && code != 206) return false
        if (contentLength == 0L) return false

        val normalizedType = contentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            .orEmpty()
        val playableType = normalizedType.startsWith("audio/") ||
            normalizedType.startsWith("video/") ||
            normalizedType in setOf(
                "application/octet-stream",
                "binary/octet-stream"
            )
        return playableType
    }

    private fun probe(client: OkHttpClient, request: Request): Boolean {
        return try {
            client.newCall(request).execute().use { response ->
                isValidPlaybackResponse(
                    code = response.code,
                    contentType = response.header("Content-Type"),
                    contentLength = response.body?.contentLength() ?: -1L
                )
            }
        } catch (_: Exception) {
            false
        }
    }
}
