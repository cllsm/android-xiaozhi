package com.xiaozhi.android.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsValidatorTest {
    @Test
    fun acceptsDefaultSettings() {
        assertTrue(SettingsValidator.validate(SettingsState()).valid)
    }

    @Test
    fun rejectsEnglishWakeWordWhenEnabled() {
        val result = SettingsValidator.validate(
            SettingsState(wakeWordText = "hey xiaozhi")
        )

        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("中文唤醒词") })
    }

    @Test
    fun ignoresWakeWordWhenDisabled() {
        val result = SettingsValidator.validate(
            SettingsState(
                wakeWordEnabled = false,
                wakeWordText = "hey xiaozhi",
                websocketUrl = ""
            )
        )

        assertTrue(result.valid)
    }

    @Test
    fun rejectsInvalidDeveloperNetworkConfigurations() {
        val result = SettingsValidator.validate(
            SettingsState(
                otaUrl = "ftp://example.com",
                websocketUrl = "invalid",
                mcpEndpointUrl = "http://example.com/mcp/"
            )
        )

        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("OTA") })
        assertTrue(result.errors.any { it.contains("WebSocket") })
        assertTrue(result.errors.any { it.contains("MCP") })
    }

    @Test
    fun acceptsValidDeveloperNetworkConfigurations() {
        val result = SettingsValidator.validate(
            SettingsState(
                otaUrl = "https://api.example.com/ota/",
                websocketUrl = "wss://api.example.com/v1/",
                websocketToken = "temporary-token",
                mcpEndpointUrl = "wss://api.example.com/mcp/?token=secret"
            )
        )

        assertTrue(result.valid)
        assertTrue(result.warnings.none { it.contains("OTA") || it.contains("WebSocket") })
    }

    @Test
    fun rejectsNeteaseSourceWithoutApiUrl() {
        val result = SettingsValidator.validate(
            SettingsState(musicSourceMode = "netease")
        )

        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("网易云 API") })
    }

    @Test
    fun rejectsHttpNeteaseApiUrl() {
        val result = SettingsValidator.validate(
            SettingsState(
                musicSourceMode = "netease",
                musicNeteaseApiUrl = "http://192.168.1.10:3000"
            )
        )

        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("HTTPS") })
    }

    @Test
    fun acceptsHttpsNeteaseApiUrl() {
        val result = SettingsValidator.validate(
            SettingsState(
                musicSourceMode = "netease",
                musicNeteaseApiUrl = "https://music-api.example.com:3000"
            )
        )

        assertTrue(result.valid)
    }

    @Test
    fun rejectsLosslessSourceWithoutCredentials() {
        val result = SettingsValidator.validate(
            SettingsState(
                musicSourceMode = "netease_lossless",
                musicNeteaseLosslessAppKey = ""
            )
        )

        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("无损网关") })
    }

    @Test
    fun acceptsDefaultLosslessGateway() {
        val result = SettingsValidator.validate(
            SettingsState(
                musicSourceMode = "netease_lossless",
                musicNeteaseLosslessAppKey = "ak_test"
            )
        )

        assertTrue(result.valid)
    }

    @Test
    fun acceptsConfiguredLosslessSource() {
        val result = SettingsValidator.validate(
            SettingsState(
                musicSourceMode = "netease_lossless",
                musicNeteaseLosslessApiUrl = "https://gateway.example.com/api/gateway.php",
                musicNeteaseLosslessAppKey = "ak_test"
            )
        )

        assertTrue(result.valid)
    }
}
