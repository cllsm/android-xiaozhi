package com.xiaozhi.android.ui

import android.app.Application
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiaozhi.android.core.ChatMessage
import com.xiaozhi.android.core.DiagnosticReport
import com.xiaozhi.android.core.SettingsState
import com.xiaozhi.android.core.UserErrorMessages
import com.xiaozhi.android.core.VoiceRuntimeState
import com.xiaozhi.android.core.VoiceSessionState
import com.xiaozhi.android.data.ChatHistoryRepository
import com.xiaozhi.android.data.DeviceCredentialRepository
import com.xiaozhi.android.data.DiagnosticRepository
import com.xiaozhi.android.data.MusicHistoryRepository
import com.xiaozhi.android.data.RecentMusicRecord
import com.xiaozhi.android.data.SettingsRepository
import com.xiaozhi.android.media.CameraCaptureController
import com.xiaozhi.android.media.MusicPlaybackState
import com.xiaozhi.android.media.NativeMusicController
import com.xiaozhi.android.media.ScreenCaptureController
import com.xiaozhi.android.mcp.ScreenVisionPromptBuilder
import com.xiaozhi.android.mcp.VisionService
import com.xiaozhi.android.service.MediaProjectionForegroundService
import com.xiaozhi.android.service.VoiceForegroundService
import com.xiaozhi.android.data.StudySessionRepository
import com.xiaozhi.android.study.ReadingPromptBuilder
import com.xiaozhi.android.study.StudyCaptureResult
import com.xiaozhi.android.study.StudyMode
import com.xiaozhi.android.study.StudySessionManager
import com.xiaozhi.android.study.StudySessionState
import com.xiaozhi.android.study.StudySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class XiaozhiViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository(application)
    private val chatHistoryRepository = ChatHistoryRepository.initialize(application)
    private val diagnosticRepository = DiagnosticRepository(application)
    private val credentialRepository = DeviceCredentialRepository(application)
    private val musicHistoryRepository = MusicHistoryRepository.initialize(application)
    private val studySessionRepository = StudySessionRepository(application)
    private val visionRunning = AtomicBoolean(false)

    private val settingsFlow = MutableStateFlow(SettingsState())
    val settings: StateFlow<SettingsState> = settingsFlow.asStateFlow()

    private val settingsReadyFlow = MutableStateFlow(false)
    val settingsReady: StateFlow<Boolean> = settingsReadyFlow.asStateFlow()

    val chat: StateFlow<List<ChatMessage>> = VoiceSessionState.chat

    val runtimeState: StateFlow<VoiceRuntimeState> = VoiceSessionState.state

    val recentMusic: StateFlow<List<RecentMusicRecord>> = musicHistoryRepository.records

    val musicPlaybackState = MusicPlaybackState.state

    val musicSelectionPrompt = NativeMusicController.selectionPrompt

    val studyState = StudySessionState.state
    val studySettings = studySessionRepository.settings
    val studyRecords = studySessionRepository.records

    private val diagnosticReportFlow = MutableStateFlow<DiagnosticReport?>(null)
    val diagnosticReport: StateFlow<DiagnosticReport?> = diagnosticReportFlow.asStateFlow()

    private val diagnosticRunningFlow = MutableStateFlow(false)
    val diagnosticRunning: StateFlow<Boolean> = diagnosticRunningFlow.asStateFlow()

    private val operationMessageFlow = MutableStateFlow<String?>(null)
    val operationMessage: StateFlow<String?> = operationMessageFlow.asStateFlow()

    private val musicOperationMessageFlow = MutableStateFlow<String?>(null)
    val musicOperationMessage: StateFlow<String?> = musicOperationMessageFlow.asStateFlow()

    init {
        viewModelScope.launch {
            repository.settings.collect {
                settingsFlow.value = it
                VoiceSessionState.setChatHistoryLimit(it.chatHistoryLimit)
                settingsReadyFlow.value = true
            }
        }
    }

    fun updateSettings(newState: SettingsState) {
        val oldState = settingsFlow.value
        settingsFlow.value = newState
        VoiceSessionState.setChatHistoryLimit(newState.chatHistoryLimit)
        viewModelScope.launch {
            repository.update(newState)
            val wakeDetectionChanged = oldState.wakeWordText != newState.wakeWordText ||
                oldState.wakeWordSensitivity != newState.wakeWordSensitivity ||
                oldState.keywordsScore != newState.keywordsScore ||
                oldState.keywordsThreshold != newState.keywordsThreshold
            if (oldState.wakeWordEnabled != newState.wakeWordEnabled &&
                VoiceForegroundService.isRunning()
            ) {
                VoiceForegroundService.setWakeWordEnabled(
                    getApplication(),
                    newState.wakeWordEnabled
                )
            }
            if (wakeDetectionChanged && VoiceForegroundService.isRunning()) {
                VoiceForegroundService.reloadWakeWord(getApplication())
            }
        }
    }

    fun completeOnboarding() {
        if (settingsFlow.value.onboardingCompleted) return
        updateSettings(settingsFlow.value.copy(onboardingCompleted = true))
    }

    fun resetSettings() {
        updateSettings(SettingsState(onboardingCompleted = true))
        operationMessageFlow.value = "已恢复默认设置"
    }

    fun clearChat() {
        VoiceSessionState.clearChat()
    }

    fun replayMusic(record: RecentMusicRecord) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                NativeMusicController.configure(settingsFlow.value)
                NativeMusicController.searchAndPlay(record.title)
            }
            musicOperationMessageFlow.value = result
        }
    }

    fun toggleMusicPlayback() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (musicPlaybackState.value.paused) {
                    NativeMusicController.resume()
                } else {
                    NativeMusicController.pause()
                }
            }
            musicOperationMessageFlow.value = result
        }
    }

    fun selectMusicCandidate(number: Int) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                NativeMusicController.configure(settingsFlow.value)
                NativeMusicController.selectPendingCandidate(number)
            }
            VoiceSessionState.appendChat(result, fromUser = false)
            musicOperationMessageFlow.value = result
        }
    }

    fun dismissMusicSelection() {
        NativeMusicController.clearPendingSelection()
    }

    fun clearMusicHistory() {
        musicHistoryRepository.clear()
        musicOperationMessageFlow.value = "最近播放已清空"
    }

    fun clearMusicOperationMessage() {
        musicOperationMessageFlow.value = null
    }

    fun startStudy(mode: StudyMode) {
        val result = StudySessionManager.start(mode)
        VoiceSessionState.appendChat(result.message, fromUser = false)
    }

    fun captureHomeworkPage(intent: String, questionNumber: Int? = null) {
        runStudyCapture {
            StudySessionManager.captureHomeworkPage(intent, questionNumber)
        }
    }

    fun captureReadingPage() {
        runStudyCapture {
            StudySessionManager.captureReadingPage()
        }
    }

    fun requestHomeworkHint(questionNumber: Int) {
        val context = StudySessionManager.homeworkContext(questionNumber)
        if (!context.success) {
            VoiceSessionState.appendChat(context.message, fromUser = false)
            return
        }
        val payload = context.payload ?: return
        val prompt = """
            请按陪学导师规则讲解第 ${payload.optInt("item_index")} 题。
            题目：${payload.optString("question")}
            年级：${studyState.value.settings.childGrade}
            提示层级：${payload.optInt("hint_level")}
            ${payload.optJSONObject("teaching_rules")?.optString("instruction").orEmpty()}
            要求：先肯定孩子已经做到的部分；不要提工具和 JSON；
            除最终计算结果外可以逐步引导；最后让孩子说出或写出下一步。
        """.trimIndent()
        sendText(prompt)
    }

    fun repeatReadingSentence() {
        val context = StudySessionManager.readingContext()
        if (!context.success) {
            VoiceSessionState.appendChat(context.message, fromUser = false)
            return
        }
        val sentence = context.payload?.optString("sentence").orEmpty()
        sendText("请用清晰、温和的儿童领读口吻只朗读这句话：$sentence")
    }

    fun askReadingComprehension() {
        val context = StudySessionManager.readingContext()
        if (!context.success) {
            VoiceSessionState.appendChat(context.message, fromUser = false)
            return
        }
        val sentence = context.payload?.optString("sentence").orEmpty()
        sendText(ReadingPromptBuilder.buildQuestion(sentence, studyState.value.settings.childGrade))
    }

    fun moveReadingSentence(delta: Int) {
        StudySessionManager.moveReadingSentence(delta)
    }

    fun stopStudy() {
        val record = StudySessionManager.stop()
        VoiceSessionState.appendChat(
            record?.let { "本次陪学已结束，报告已保存" } ?: "当前没有进行中的陪学会话",
            fromUser = false
        )
    }

    fun updateStudySettings(settings: StudySettings) {
        StudySessionManager.updateSettings(settings)
    }

    fun clearStudyRecords() {
        viewModelScope.launch { studySessionRepository.clearRecords() }
    }

    fun sendText(text: String): Boolean {
        val application = getApplication<Application>()
        if (application.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return VoiceForegroundService.sendText(text, application)
        }

        VoiceSessionState.update(
            status = VoiceSessionState.state.value.status,
            statusText = UserErrorMessages.from("缺少麦克风权限")
        )
        return false
    }

    fun analyzeScreen(prompt: String) {
        runDirectVision(
            prompt = prompt,
            runningText = "正在识别屏幕..."
        ) {
            val image = captureScreen()
                ?: return@runDirectVision visionFailure("截屏不可用，请重新授予屏幕识别权限")
            VisionService.analyze(
                question = ScreenVisionPromptBuilder.build(
                    prompt,
                    structuredOutput = false
                ),
                image = image,
                fileName = "screenshot.jpg"
            )
        }
    }

    fun analyzeCamera(prompt: String) {
        runDirectVision(
            prompt = prompt,
            runningText = "正在拍照识别..."
        ) {
            val image = CameraCaptureController(getApplication()).capture()
                ?: return@runDirectVision visionFailure("拍照不可用，请授予相机权限")
            VisionService.analyze(
                question = ScreenVisionPromptBuilder.buildCameraPrompt(
                    prompt,
                    structuredOutput = false
                ),
                image = image,
                fileName = "camera.jpg"
            )
        }
    }

    fun runDiagnostics(includeServerProbe: Boolean) {
        if (diagnosticRunningFlow.value) return
        diagnosticRunningFlow.value = true
        viewModelScope.launch {
            try {
                diagnosticReportFlow.value = diagnosticRepository.build(
                    settings = settingsFlow.value,
                    runtimeState = runtimeState.value,
                    includeServerProbe = includeServerProbe
                )
            } catch (error: Exception) {
                operationMessageFlow.value = UserErrorMessages.from(
                    error.message ?: "诊断执行失败"
                )
            } finally {
                diagnosticRunningFlow.value = false
            }
        }
    }

    fun diagnosticText(): String {
        val report = diagnosticReportFlow.value ?: return ""
        return diagnosticRepository.asText(report)
    }

    fun exportChat(uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val messages = chat.value
                    if (messages.isEmpty()) error("没有可导出的聊天记录")
                    val payload = JSONObject()
                        .put("version", EXPORT_VERSION)
                        .put("exportedAt", System.currentTimeMillis())
                        .put("messages", JSONArray().apply {
                            messages.forEach { message ->
                                put(
                                    JSONObject()
                                        .put("id", message.id)
                                        .put("text", message.text)
                                        .put("from_user", message.fromUser)
                                        .put("timestamp", message.timestamp)
                                )
                            }
                        })
                    val output = getApplication<Application>().contentResolver
                        .openOutputStream(uri, "wt")
                        ?: error("无法打开导出文件")
                    output.use { stream ->
                        stream.write(payload.toString().toByteArray(Charsets.UTF_8))
                    }
                }
                operationMessageFlow.value = "聊天记录已导出"
            } catch (error: Exception) {
                operationMessageFlow.value = error.message ?: "聊天导出失败"
            }
        }
    }

    fun importChat(uri: Uri) {
        viewModelScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    val input = getApplication<Application>().contentResolver
                        .openInputStream(uri) ?: error("无法打开导入文件")
                    val bytes = input.use { stream -> stream.readBytes() }
                    if (bytes.size > MAX_IMPORT_BYTES) error("导入文件不能超过 2MB")
                    parseChatExport(String(bytes, Charsets.UTF_8))
                }
                chatHistoryRepository.replace(imported)
                operationMessageFlow.value = "已导入 ${imported.size} 条聊天记录"
            } catch (error: Exception) {
                operationMessageFlow.value = error.message ?: "聊天导入失败"
            }
        }
    }

    fun exportCredential(uri: Uri, password: String) {
        viewModelScope.launch {
            try {
                credentialRepository.export(uri, password)
                operationMessageFlow.value = "激活凭证已备份"
            } catch (error: Exception) {
                operationMessageFlow.value = error.message ?: "激活凭证备份失败"
            }
        }
    }

    fun importCredential(uri: Uri, password: String) {
        viewModelScope.launch {
            try {
                val application = getApplication<Application>()
                if (VoiceForegroundService.isRunning()) {
                    VoiceForegroundService.stop(application)
                    kotlinx.coroutines.delay(SERVICE_STOP_WAIT_MS)
                }
                credentialRepository.import(uri, password)
                operationMessageFlow.value = "激活凭证已恢复，请重新启动语音服务"
            } catch (error: Exception) {
                operationMessageFlow.value = error.message ?: "激活凭证恢复失败"
            }
        }
    }

    fun clearOperationMessage() {
        operationMessageFlow.value = null
    }

    private fun runStudyCapture(capture: suspend () -> StudyCaptureResult) {
        if (studyState.value.captureRunning) return
        VoiceSessionState.appendChat("正在拍摄识别...", fromUser = false)
        viewModelScope.launch {
            try {
                val ready = withContext(Dispatchers.IO) { ensureVisionServiceReady() }
                val result = if (ready) {
                    withContext(Dispatchers.IO) { capture() }
                } else {
                    StudyCaptureResult(false, "视觉分析服务暂未就绪，请先开启语音服务并完成连接")
                }
                VoiceSessionState.appendChat(result.message, fromUser = false)
            } catch (error: Exception) {
                VoiceSessionState.appendChat(
                    error.message ?: "陪学识别失败，请稍后重试",
                    fromUser = false
                )
            }
        }
    }

    private fun parseChatExport(raw: String): List<ChatMessage> {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            error("导入文件不是有效的 JSON")
        }
        val source = runCatching {
            if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
        }.getOrElse { error("导入文件 JSON 格式不正确") }
        val array = when (source) {
            is JSONArray -> source
            is JSONObject -> source.optJSONArray("messages") ?: error("导入文件缺少 messages 字段")
            else -> error("导入文件格式不正确")
        }

        val now = System.currentTimeMillis()
        val parsed = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val text = item.optString("text").trim()
                if (text.isBlank()) continue
                add(
                    ChatMessage(
                        id = item.optLong("id", 0L),
                        text = text,
                        fromUser = item.optBoolean("from_user", false),
                        timestamp = item.optLong("timestamp", 0L).takeIf { it > 0 } ?: now
                    )
                )
            }
        }.sortedBy { it.timestamp }

        val usedIds = mutableSetOf<Long>()
        var nextId = parsed.maxOfOrNull { it.id }?.coerceAtLeast(0L) ?: 0L
        return parsed.map { message ->
            if (message.id > 0 && message.id !in usedIds) {
                usedIds.add(message.id)
                message
            } else {
                nextId += 1
                message.copy(id = nextId).also { usedIds.add(nextId) }
            }
        }.takeLast(VoiceSessionState.chatHistoryLimit)
    }

    private fun runDirectVision(
        prompt: String,
        runningText: String,
        analyze: suspend () -> JSONObject
    ) {
        val normalizedPrompt = prompt.trim()
        if (normalizedPrompt.isEmpty()) return

        if (!visionRunning.compareAndSet(false, true)) {
            VoiceSessionState.appendChat("上一次视觉识别还在处理，请稍候", fromUser = false)
            return
        }

        VoiceSessionState.appendChat(normalizedPrompt, fromUser = true)
        VoiceSessionState.updateConversation(currentText = runningText)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (!ensureVisionServiceReady()) {
                        visionFailure("视觉分析服务暂未就绪，请先开启语音服务并完成连接")
                    } else {
                        analyze()
                    }
                }
                VoiceSessionState.appendChat(
                    directVisionText(result),
                    fromUser = false
                )
            } catch (error: Exception) {
                VoiceSessionState.appendChat(
                    error.message ?: "视觉识别失败，请稍后重试",
                    fromUser = false
                )
            } finally {
                VoiceSessionState.updateConversation(currentText = "")
                visionRunning.set(false)
            }
        }
    }

    private suspend fun ensureVisionServiceReady(): Boolean {
        if (VisionService.isConfigured()) return true

        val application = getApplication<Application>()
        if (application.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        withContext(Dispatchers.Main) {
            VoiceForegroundService.start(application)
        }
        return VisionService.awaitConfigured(VISION_CONFIG_TIMEOUT_MS)
    }

    private suspend fun captureScreen(): ByteArray? {
        val application = getApplication<Application>()
        var image = ScreenCaptureController.capture(application)
        if (image == null && ScreenCaptureController.hasPermission()) {
            withContext(Dispatchers.Main) {
                MediaProjectionForegroundService.start(application)
            }
            delay(PROJECTION_READY_WAIT_MS)
            image = ScreenCaptureController.capture(application)
        }
        return image
    }

    private fun directVisionText(result: JSONObject): String {
        if (!result.optBoolean("success", true)) {
            return result.optString("message")
                .ifBlank { "视觉识别失败，请稍后重试" }
        }

        result.optString("response")
            .ifBlank { result.optString("text") }
            .ifBlank { result.optString("content") }
            .ifBlank { result.optString("message") }
            .let { text ->
                if (text.isNotBlank()) {
                    return formatVisionJson(text) ?: text
                }
            }

        result.optJSONObject("data")?.let { data ->
            data.optString("response")
                .ifBlank { data.optString("text") }
                .ifBlank { data.optString("content") }
                .let { text ->
                    if (text.isNotBlank()) return text
                }
        }

        return formatVisionJson(result.toString()) ?: result.toString(2)
    }

    private fun formatVisionJson(raw: String): String? {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val lines = buildList {
            json.optString("app").takeIf { it.isNotBlank() }?.let { add("应用：$it") }
            json.optString("page").takeIf { it.isNotBlank() }?.let { add("页面：$it") }
            json.optString("chat_target").takeIf { it.isNotBlank() }?.let {
                add("聊天对象：$it")
            }
            json.optString("main_content").takeIf { it.isNotBlank() }?.let { add("内容：$it") }
            json.optString("topic").takeIf { it.isNotBlank() }?.let { add("话题：$it") }
            json.optString("last_message").takeIf { it.isNotBlank() }?.let {
                add("对方最后一句：$it")
            }
            json.optString("tone").takeIf { it.isNotBlank() }?.let { add("语气：$it") }
            json.optString("reply_suggestion").takeIf { it.isNotBlank() }?.let {
                add("建议回复：$it")
            }
            json.optString("full_text").takeIf { it.isNotBlank() }?.let { add("完整文字：$it") }

            json.optJSONArray("messages")?.let { messages ->
                val visible = buildList {
                    for (index in 0 until messages.length()) {
                        val message = messages.optJSONObject(index) ?: continue
                        val sender = message.optString("sender").ifBlank { "未知" }
                        val content = message.optString("content")
                        if (content.isNotBlank()) add("$sender：$content")
                    }
                }
                if (visible.isNotEmpty()) {
                    add("可见消息：")
                    addAll(visible)
                }
            }

            json.optJSONArray("actionable_items")?.let { items ->
                val labels = buildList {
                    for (index in 0 until items.length()) add(items.optString(index))
                }.filter { it.isNotBlank() }
                if (labels.isNotEmpty()) add("可操作：${labels.joinToString("、")}")
            }

            json.optJSONArray("alerts")?.let { alerts ->
                val labels = buildList {
                    for (index in 0 until alerts.length()) add(alerts.optString(index))
                }.filter { it.isNotBlank() }
                if (labels.isNotEmpty()) add("提示：${labels.joinToString("；")}")
            }

            json.optJSONArray("suggestions")?.let { suggestions ->
                val labels = buildList {
                    for (index in 0 until suggestions.length()) {
                        add(suggestions.optString(index))
                    }
                }.filter { it.isNotBlank() }
                if (labels.isNotEmpty()) {
                    add("建议：")
                    labels.forEachIndexed { index, suggestion -> add("${index + 1}. $suggestion") }
                }
            }
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun visionFailure(message: String): JSONObject {
        return JSONObject().put("success", false).put("message", message)
    }

    private companion object {
        const val EXPORT_VERSION = 1
        const val MAX_IMPORT_BYTES = 2 * 1024 * 1024
        const val SERVICE_STOP_WAIT_MS = 300L
        const val VISION_CONFIG_TIMEOUT_MS = 8_000L
        const val PROJECTION_READY_WAIT_MS = 600L
    }
}
