package com.xiaozhi.android.core

import java.net.URI

object SettingsValidator {
    fun validate(settings: SettingsState): SettingsValidationResult {
        val errors = buildList {
            if (!isHttpUrl(settings.otaUrl)) {
                add("OTA 地址必须是有效的 http/https 地址")
            }
            if (settings.websocketUrl.isNotBlank() && !isWebSocketUrl(settings.websocketUrl)) {
                add("WebSocket 地址必须以 ws:// 或 wss:// 开头")
            }
            if (settings.mcpEndpointUrl.isNotBlank() && !isWebSocketUrl(settings.mcpEndpointUrl)) {
                add("MCP 接入点必须是有效的 ws:// 或 wss:// 地址")
            }
            if (settings.wakeWordEnabled) {
                if (settings.wakeWordText.none(Char::isLetterOrDigit)) {
                    add("唤醒词文本不能为空")
                }
                if (!settings.wakeWordText.any { it.code in 0x4E00..0x9FFF }) {
                    add("当前唤醒模型只支持中文唤醒词")
                }
            }
            val losslessMode = settings.musicSourceMode == "netease_lossless"
            if (settings.musicSourceMode !in setOf("auto", "kuwo", "netease", "netease_lossless")) {
                add("音乐源模式无效")
            }
            if (settings.musicSourceMode == "netease" &&
                !isHttpsUrl(settings.musicNeteaseApiUrl)
            ) {
                add("网易云 API 必须是有效的 HTTPS 地址")
            }
            if (losslessMode && !isHttpsUrl(settings.musicNeteaseLosslessApiUrl)) {
                add("网易云无损网关必须是有效的 HTTPS 地址")
            }
            if (losslessMode && settings.musicNeteaseLosslessAppKey.isBlank()) {
                add("网易云无损网关需要填写 AppKey")
            }
        }

        val warnings = buildList {
            if (!settings.aecEnabled) {
                add("关闭 AEC 后，扬声器外放时小智可能会听到自己的声音")
            }
            if (settings.musicNeteaseApiUrl.isNotBlank() &&
                !isHttpsUrl(settings.musicNeteaseApiUrl)
            ) {
                add("网易云 API 地址必须是 HTTPS，自动多源将跳过网易云")
            }
            if (settings.musicNeteaseLosslessApiUrl.isNotBlank() &&
                !isHttpsUrl(settings.musicNeteaseLosslessApiUrl)
            ) {
                add("网易云无损网关必须是 HTTPS，自动多源将跳过该源")
            }
        }

        return SettingsValidationResult(errors = errors.distinct(), warnings = warnings.distinct())
    }

    private fun isHttpsUrl(value: String): Boolean {
        if (value.isBlank()) return false
        return runCatching {
            val uri = URI(value)
            uri.scheme == "https" && !uri.host.isNullOrBlank()
        }.getOrDefault(false)
    }

    private fun isHttpUrl(value: String): Boolean {
        if (value.isBlank()) return false
        return runCatching {
            val uri = URI(value)
            uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
        }.getOrDefault(false)
    }

    private fun isWebSocketUrl(value: String): Boolean {
        return runCatching {
            val uri = URI(value)
            uri.scheme in setOf("ws", "wss") && !uri.host.isNullOrBlank()
        }.getOrDefault(false)
    }

}
