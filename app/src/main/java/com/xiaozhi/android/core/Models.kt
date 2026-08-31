package com.xiaozhi.android.core

import com.xiaozhi.android.BuildConfig

enum class DeviceState {
    Idle,
    Connecting,
    Listening,
    Speaking
}

enum class ConnectionStatus {
    Disconnected,
    Connecting,
    Connected,
    ActivationRequired,
    Error
}

enum class ThemeMode {
    System,
    Dark,
    Light
}

data class DeviceIdentity(
    val deviceId: String,
    val clientId: String,
    val serialNumber: String,
    val hmacKey: String,
    val activated: Boolean = false
)

data class VoiceRuntimeState(
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
    val statusText: String = "待启动",
    val deviceState: DeviceState = DeviceState.Idle,
    val waitingForNetwork: Boolean = false,
    val autoRecoveryEnabled: Boolean = true,
    val recoveryAttempt: Int = 0,
    val recoveryLimit: Int = 0,
    val wakeWordEnabled: Boolean = true,
    val activationCode: String = "",
    val deviceId: String = "",
    val clientId: String = "",
    val inputLevel: Float = 0f,
    val outputLevel: Float = 0f,
    val currentText: String = "",
    val emotion: String = "neutral",
    val chat: List<ChatMessage> = emptyList()
)

data class ChatMessage(
    val id: Long,
    val text: String,
    val fromUser: Boolean,
    val timestamp: Long,
    val imagePath: String? = null,
    val thumbnailPath: String? = null
)

data class SettingsState(
    val otaUrl: String = "https://api.tenclass.net/xiaozhi/ota/",
    val websocketUrl: String = "",
    val websocketToken: String = "",
    val wakeWordEnabled: Boolean = true,
    val wakeWordText: String = "你好小智",
    val wakeWordSensitivity: Float = 0.25f,
    val keywordsScore: Float = 1.8f,
    val keywordsThreshold: Float = 0.25f,
    val outputSampleRate: Int = 24_000,
    val aecEnabled: Boolean = true,
    val musicEnabled: Boolean = true,
    val musicSourceMode: String = "auto",
    val musicSearchUrl: String = "http://search.kuwo.cn/r.s",
    val musicNeteaseLosslessApiUrl: String = "https://api.j8y.cn/api/gateway.php",
    val musicNeteaseLosslessAppKey: String = BuildConfig.NETEASE_LOSSLESS_APP_KEY,
    val musicNeteaseApiUrl: String = "",
    val musicDefaultQuality: String = "320k",
    val themeMode: ThemeMode = ThemeMode.System,
    val musicRememberSelection: Boolean = true,
    val overlayEnabled: Boolean = false,
    val musicIslandEnabled: Boolean = true,
    val chatHistoryLimit: Int = 200,
    val connectRetryEnabled: Boolean = true,
    val connectRetryCount: Int = 5,
    val onboardingCompleted: Boolean = false
)

data class SettingsValidationResult(
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
) {
    val valid: Boolean get() = errors.isEmpty()
}

data class WakeWordTestState(
    val running: Boolean = false,
    val remainingSeconds: Int = 0,
    val hits: Int = 0,
    val message: String = ""
)

data class DiagnosticItem(
    val name: String,
    val state: DiagnosticState,
    val detail: String
)

enum class DiagnosticState {
    Ok,
    Warning,
    Error,
    Checking
}

data class DiagnosticReport(
    val generatedAt: Long,
    val appVersion: String,
    val items: List<DiagnosticItem>,
    val deviceSummary: String
)
