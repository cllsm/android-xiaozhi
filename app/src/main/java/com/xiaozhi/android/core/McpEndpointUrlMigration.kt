package com.xiaozhi.android.core

import org.json.JSONObject
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64

object McpEndpointUrlMigration {

    fun resolve(savedUrl: String?, defaultUrl: String): String {
        if (savedUrl.isNullOrBlank() || savedUrl == defaultUrl) return defaultUrl
        val payload = officialEndpointPayload(savedUrl) ?: return savedUrl
        return if (payload.optString("endpointId").isBlank()) defaultUrl else savedUrl
    }

    private fun officialEndpointPayload(url: String): JSONObject? {
        return runCatching {
            val uri = URI(url)
            if (uri.host != OFFICIAL_HOST || uri.path?.trimEnd('/') != OFFICIAL_PATH) {
                return null
            }
            val token = uri.rawQuery
                ?.split('&')
                ?.map { parameter -> parameter.split('=', limit = 2) }
                ?.firstOrNull { it.size == 2 && it.first() == TOKEN_PARAMETER }
                ?.get(1)
                ?: return null
            val encodedPayload = token.split('.').getOrNull(1) ?: return null
            val decoded = Base64.getUrlDecoder()
                .decode(encodedPayload.padBase64())
                .toString(StandardCharsets.UTF_8)
            val payload = JSONObject(decoded)
            if (payload.optString("purpose") != ENDPOINT_PURPOSE) null else payload
        }.getOrNull()
    }

    private fun String.padBase64(): String {
        return this + "=".repeat((4 - length % 4) % 4)
    }

    private const val OFFICIAL_HOST = "api.xiaozhi.me"
    private const val OFFICIAL_PATH = "/mcp"
    private const val TOKEN_PARAMETER = "token"
    private const val ENDPOINT_PURPOSE = "mcp-endpoint"
}
