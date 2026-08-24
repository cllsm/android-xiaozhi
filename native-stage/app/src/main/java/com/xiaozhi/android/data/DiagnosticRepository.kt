package com.xiaozhi.android.data

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.xiaozhi.android.core.ConnectionStatus
import com.xiaozhi.android.core.DiagnosticItem
import com.xiaozhi.android.core.DiagnosticReport
import com.xiaozhi.android.core.DiagnosticState
import com.xiaozhi.android.core.SettingsState
import com.xiaozhi.android.core.SettingsValidator
import com.xiaozhi.android.core.UserErrorMessages
import com.xiaozhi.android.core.VoiceRuntimeState
import com.xiaozhi.android.core.VoiceSessionState
import com.xiaozhi.android.network.OtaClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticRepository(
    private val context: Context,
    private val identityRepository: DeviceIdentityRepository = DeviceIdentityRepository(context)
) {
    private val otaClient = OtaClient()

    suspend fun build(
        settings: SettingsState,
        runtimeState: VoiceRuntimeState,
        includeServerProbe: Boolean
    ): DiagnosticReport {
        val version = appVersion()
        val identity = runCatching { identityRepository.ensureIdentity() }.getOrNull()
        val items = buildList {
            addAll(permissionItems(settings))
            addAll(powerItems())
            addAll(networkItems())
            addAll(settingsItems(settings))
            addAll(runtimeItems(runtimeState))
            if (includeServerProbe) addAll(serverItems(settings, identity))
        }

        return DiagnosticReport(
            generatedAt = System.currentTimeMillis(),
            appVersion = version,
            items = items,
            deviceSummary = buildString {
                appendLine("App：小智 $version")
                appendLine("Device-Id：${identity?.deviceId.orEmpty()}")
                appendLine("Client-Id：${identity?.clientId.orEmpty()}")
                appendLine("Android：${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            }
        )
    }

    fun asText(report: DiagnosticReport): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        return buildString {
            appendLine("小智诊断报告")
            appendLine("生成时间：${formatter.format(Date(report.generatedAt))}")
            appendLine()
            appendLine(report.deviceSummary)
            appendLine()
            report.items.forEach { item ->
                appendLine("${item.name}：${item.state.name} - ${item.detail}")
            }
            appendLine()
            appendLine("最近日志（已脱敏）")
            appendLine(recentLogs())
        }
    }

    fun recentLogs(limit: Int = 800): String {
        return runCatching {
            val process = ProcessBuilder("logcat", "-d", "-t", limit.toString())
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().use { reader ->
                reader.readText()
            }
            process.waitFor()
            redact(text)
        }.getOrElse { "日志读取失败：${it.message ?: it.javaClass.simpleName}" }
    }

    fun redact(text: String): String {
        return text.lineSequence()
            .filterNot { line ->
                line.contains("websocketToken", ignoreCase = true) ||
                    line.contains("Authorization:", ignoreCase = true)
            }
            .joinToString("\n") { line ->
                line.replace(Regex("(?i)(token|authorization|hmac)[=: ]+[^ ,]+"), "$1=***")
            }
    }

    private fun permissionItems(settings: SettingsState): List<DiagnosticItem> {
        fun granted(permission: String) =
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

        return listOf(
            DiagnosticItem(
                "麦克风",
                if (granted(android.Manifest.permission.RECORD_AUDIO)) DiagnosticState.Ok else DiagnosticState.Error,
                if (granted(android.Manifest.permission.RECORD_AUDIO)) "已授权，可进行语音对话" else "未授权，语音对话不可用"
            ),
            DiagnosticItem(
                "相机",
                if (granted(android.Manifest.permission.CAMERA)) DiagnosticState.Ok else DiagnosticState.Warning,
                if (granted(android.Manifest.permission.CAMERA)) "已授权，可拍照识别" else "未授权，拍照识别不可用"
            ),
            DiagnosticItem(
                "通知",
                if (Build.VERSION.SDK_INT < 33 || granted(android.Manifest.permission.POST_NOTIFICATIONS)) {
                    DiagnosticState.Ok
                } else {
                    DiagnosticState.Warning
                },
                if (Build.VERSION.SDK_INT < 33 || granted(android.Manifest.permission.POST_NOTIFICATIONS)) {
                    "已授权，可显示服务状态"
                } else {
                    "未授权，后台服务状态不可见"
                }
            ),
            DiagnosticItem(
                "悬浮窗",
                if (!settings.overlayEnabled || Settings.canDrawOverlays(context)) {
                    DiagnosticState.Ok
                } else {
                    DiagnosticState.Warning
                },
                if (!settings.overlayEnabled) {
                    "未启用，可在设置中开启"
                } else if (Settings.canDrawOverlays(context)) {
                    "已授权，后台可显示快捷球"
                } else {
                    "未授权，后台快捷球不可用"
                }
            )
        )
    }

    private fun networkItems(): List<DiagnosticItem> {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork
        val capabilities = network?.let { manager.getNetworkCapabilities(it) }
        val internet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val state = if (internet && validated) DiagnosticState.Ok else DiagnosticState.Error
        val detail = when {
            !internet -> "当前没有可用互联网连接"
            !validated -> "网络已连接，但暂时无法访问互联网"
            else -> "网络连接正常"
        }
        return listOf(DiagnosticItem("网络", state, detail))
    }

    private fun powerItems(): List<DiagnosticItem> {
        val manager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = manager.isIgnoringBatteryOptimizations(context.packageName)
        return listOf(
            DiagnosticItem(
                "后台运行",
                if (ignoring) DiagnosticState.Ok else DiagnosticState.Warning,
                if (ignoring) {
                    "已允许后台运行，语音服务更稳定"
                } else {
                    "未加入电池优化白名单，部分系统可能限制后台语音"
                }
            )
        )
    }

    private fun settingsItems(settings: SettingsState): List<DiagnosticItem> {
        val validation = SettingsValidator.validate(settings)
        return listOf(
            DiagnosticItem(
                "配置",
                if (validation.valid) DiagnosticState.Ok else DiagnosticState.Error,
                if (validation.valid) "配置格式检查通过" else validation.errors.joinToString("；")
            ),
            DiagnosticItem(
                "配置建议",
                if (validation.warnings.isEmpty()) DiagnosticState.Ok else DiagnosticState.Warning,
                validation.warnings.joinToString("；").ifBlank { "暂无特别提醒" }
            )
        )
    }

    private fun runtimeItems(runtimeState: VoiceRuntimeState): List<DiagnosticItem> {
        val state = when (runtimeState.status) {
            ConnectionStatus.Connected -> DiagnosticState.Ok
            ConnectionStatus.Error -> DiagnosticState.Error
            ConnectionStatus.Disconnected -> DiagnosticState.Warning
            else -> DiagnosticState.Checking
        }
        return listOf(
            DiagnosticItem(
                "语音链路",
                state,
                UserErrorMessages.from(runtimeState.statusText)
            )
        )
    }

    private suspend fun serverItems(
        settings: SettingsState,
        identity: com.xiaozhi.android.core.DeviceIdentity?
    ): List<DiagnosticItem> {
        if (identity == null) {
            return listOf(
                DiagnosticItem("OTA 服务", DiagnosticState.Error, "设备身份生成失败，请清除数据后重试")
            )
        }
        return runCatching {
            val config = otaClient.fetch(
                settings = settings,
                identity = identity,
                localIpAddress = "0.0.0.0"
            )
            val validUrl = config.websocketUrl.startsWith("ws") || config.websocketUrl.startsWith("wss")
            DiagnosticItem(
                "OTA 服务",
                if (validUrl) DiagnosticState.Ok else DiagnosticState.Warning,
                if (validUrl) {
                    "OTA 返回了可用的 WebSocket 配置"
                } else {
                    "OTA 可访问，但暂未返回 WebSocket 配置，可能等待激活"
                }
            )
        }.getOrElse { error ->
            DiagnosticItem(
                "OTA 服务",
                DiagnosticState.Error,
                UserErrorMessages.from(error.message ?: error.javaClass.simpleName)
            )
        }.let(::listOf)
    }

    private fun appVersion(): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= 28) {
            packageInfo.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toString()
        }
        return "${packageInfo.versionName} ($versionCode)"
    }

    companion object {
        fun currentStatus(): com.xiaozhi.android.core.ConnectionStatus {
            return VoiceSessionState.state.value.status
        }
    }
}
