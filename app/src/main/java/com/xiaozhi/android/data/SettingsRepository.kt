package com.xiaozhi.android.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xiaozhi.android.core.SettingsState
import com.xiaozhi.android.core.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsStore by preferencesDataStore(name = "xiaozhi_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val OtaUrl = stringPreferencesKey("ota_url")
        val WebsocketUrl = stringPreferencesKey("websocket_url")
        val WebsocketToken = stringPreferencesKey("websocket_token")
        val WakeWordEnabled = booleanPreferencesKey("wake_word_enabled")
        val WakeWordText = stringPreferencesKey("wake_word_text")
        val WakeWordSensitivity = floatPreferencesKey("wake_word_sensitivity")
        val KeywordsScore = floatPreferencesKey("keywords_score")
        val KeywordsThreshold = floatPreferencesKey("keywords_threshold")
        val OutputSampleRate = intPreferencesKey("output_sample_rate")
        val AecEnabled = booleanPreferencesKey("aec_enabled")
        val MusicEnabled = booleanPreferencesKey("music_enabled")
        val MusicSourceMode = stringPreferencesKey("music_source_mode")
        val MusicSearchUrl = stringPreferencesKey("music_search_url")
        val MusicNeteaseLosslessApiUrl = stringPreferencesKey("music_netease_lossless_api_url")
        val MusicNeteaseLosslessAppKey = stringPreferencesKey("music_netease_lossless_app_key")
        val MusicNeteaseApiUrl = stringPreferencesKey("music_netease_api_url")
        val MusicDefaultQuality = stringPreferencesKey("music_default_quality")
        val LegacyDarkTheme = booleanPreferencesKey("dark_theme")
        val ThemeModeKey = stringPreferencesKey("theme_mode")
        val MusicRememberSelection = booleanPreferencesKey("music_remember_selection")
        val OverlayEnabled = booleanPreferencesKey("overlay_enabled")
        val MusicIslandEnabled = booleanPreferencesKey("music_island_enabled")
        val ChatHistoryLimit = intPreferencesKey("chat_history_limit")
        val ConnectRetryEnabled = booleanPreferencesKey("connect_retry_enabled")
        val ConnectRetryCount = intPreferencesKey("connect_retry_count")
        val OnboardingCompleted = booleanPreferencesKey("onboarding_completed")
    }

    val settings: Flow<SettingsState> = context.settingsStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs ->
            val losslessAppKey = prefs[Keys.MusicNeteaseLosslessAppKey]
            SettingsState(
                otaUrl = prefs[Keys.OtaUrl] ?: SettingsState().otaUrl,
                websocketUrl = prefs[Keys.WebsocketUrl] ?: "",
                websocketToken = prefs[Keys.WebsocketToken] ?: "",
                wakeWordEnabled = prefs[Keys.WakeWordEnabled] ?: true,
                wakeWordText = prefs[Keys.WakeWordText] ?: "你好小智",
                wakeWordSensitivity = prefs[Keys.WakeWordSensitivity] ?: 0.25f,
                keywordsScore = prefs[Keys.KeywordsScore] ?: 1.8f,
                keywordsThreshold = prefs[Keys.KeywordsThreshold] ?: 0.25f,
                outputSampleRate = prefs[Keys.OutputSampleRate] ?: 24_000,
                aecEnabled = prefs[Keys.AecEnabled] ?: true,
                musicEnabled = prefs[Keys.MusicEnabled] ?: true,
                musicSourceMode = prefs[Keys.MusicSourceMode] ?: SettingsState().musicSourceMode,
                musicSearchUrl = prefs[Keys.MusicSearchUrl] ?: SettingsState().musicSearchUrl,
                musicNeteaseLosslessApiUrl = prefs[Keys.MusicNeteaseLosslessApiUrl]
                    ?.takeIf { it.isNotBlank() }
                    ?: SettingsState().musicNeteaseLosslessApiUrl,
                musicNeteaseLosslessAppKey = if (losslessAppKey.isNullOrBlank()) {
                    SettingsState().musicNeteaseLosslessAppKey
                } else {
                    losslessAppKey
                },
                musicNeteaseApiUrl = prefs[Keys.MusicNeteaseApiUrl] ?: "",
                musicDefaultQuality = prefs[Keys.MusicDefaultQuality] ?: "320k",
                themeMode = prefs[Keys.ThemeModeKey]?.toThemeMode()
                    ?: prefs[Keys.LegacyDarkTheme]?.let { dark ->
                        if (dark) ThemeMode.Dark else ThemeMode.Light
                    }
                    ?: ThemeMode.System,
                musicRememberSelection = prefs[Keys.MusicRememberSelection] ?: true,
                overlayEnabled = prefs[Keys.OverlayEnabled] ?: false,
                musicIslandEnabled = prefs[Keys.MusicIslandEnabled] ?: true,
                chatHistoryLimit = prefs[Keys.ChatHistoryLimit] ?: 200,
                connectRetryEnabled = prefs[Keys.ConnectRetryEnabled] ?: true,
                connectRetryCount = prefs[Keys.ConnectRetryCount] ?: 5,
                onboardingCompleted = prefs[Keys.OnboardingCompleted] ?: false
            )
        }

    suspend fun update(newState: SettingsState) {
        context.settingsStore.edit { prefs ->
            prefs[Keys.OtaUrl] = newState.otaUrl
            prefs[Keys.WebsocketUrl] = newState.websocketUrl
            prefs[Keys.WebsocketToken] = newState.websocketToken
            prefs[Keys.WakeWordEnabled] = newState.wakeWordEnabled
            prefs[Keys.WakeWordText] = newState.wakeWordText
            prefs[Keys.WakeWordSensitivity] = newState.wakeWordSensitivity
            prefs[Keys.KeywordsScore] = newState.keywordsScore
            prefs[Keys.KeywordsThreshold] = newState.keywordsThreshold
            prefs[Keys.OutputSampleRate] = newState.outputSampleRate
            prefs[Keys.AecEnabled] = newState.aecEnabled
            prefs[Keys.MusicEnabled] = newState.musicEnabled
            prefs[Keys.MusicSourceMode] = newState.musicSourceMode
            prefs[Keys.MusicSearchUrl] = newState.musicSearchUrl
            prefs[Keys.MusicNeteaseLosslessApiUrl] = newState.musicNeteaseLosslessApiUrl
            prefs[Keys.MusicNeteaseLosslessAppKey] = newState.musicNeteaseLosslessAppKey
            prefs[Keys.MusicNeteaseApiUrl] = newState.musicNeteaseApiUrl
            prefs[Keys.MusicDefaultQuality] = newState.musicDefaultQuality
            prefs[Keys.ThemeModeKey] = newState.themeMode.name
            prefs[Keys.MusicRememberSelection] = newState.musicRememberSelection
            prefs[Keys.OverlayEnabled] = newState.overlayEnabled
            prefs[Keys.MusicIslandEnabled] = newState.musicIslandEnabled
            prefs[Keys.ChatHistoryLimit] = newState.chatHistoryLimit
            prefs[Keys.ConnectRetryEnabled] = newState.connectRetryEnabled
            prefs[Keys.ConnectRetryCount] = newState.connectRetryCount
            prefs[Keys.OnboardingCompleted] = newState.onboardingCompleted
        }
    }
}

private fun emptyPreferences(): androidx.datastore.preferences.core.Preferences =
    androidx.datastore.preferences.core.emptyPreferences()

private fun String.toThemeMode(): ThemeMode? = when (lowercase()) {
    "system" -> ThemeMode.System
    "dark" -> ThemeMode.Dark
    "light" -> ThemeMode.Light
    else -> null
}
