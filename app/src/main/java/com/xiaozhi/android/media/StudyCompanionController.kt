package com.xiaozhi.android.media

import com.xiaozhi.android.mcp.StudyCompanionPromptBuilder
import com.xiaozhi.android.mcp.VisionService
import com.xiaozhi.android.service.VoiceForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * 陪学巡查控制器：当陪学前摄预览处于运行状态时，定时抓取画面送视觉大模型分析，
 * 将结论作为巡查报告发送给语音服务端，由云端大模型组织语言并经 TTS 播报，形成
 * "画面 → 视觉分析 → 大模型 → TTS 提醒"的闭环。
 *
 * 帧源由 [CameraPreviewController] 在预览开始运行时注册、停止时注销，
 * 巡逻循环仅在存在活跃帧源时工作，无需额外的启停管理。
 */
object StudyCompanionController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 活跃帧源：key 为控制器实例，value 为抓帧函数 */
    private val frameSources = ConcurrentHashMap<Any, () -> ByteArray?>()

    /** 最近一次巡查结果，供 UI 展示巡查状态 */
    data class PatrolResult(
        val timestamp: Long,
        val summary: String,
        val delivered: Boolean
    )

    private val lastResultFlow = MutableStateFlow<PatrolResult?>(null)
    val lastResult: StateFlow<PatrolResult?> = lastResultFlow.asStateFlow()

    init {
        scope.launch {
            // 首轮巡查稍等片刻，让预览画面稳定后再建立观察基线
            delay(FIRST_PATROL_DELAY_MS)
            while (isActive) {
                runCatching { patrolOnce() }
                delay(PATROL_INTERVAL_MS)
            }
        }
    }

    /** 注册一个活跃帧源（预览进入 Running 状态时调用） */
    fun registerFrameSource(key: Any, source: () -> ByteArray?) {
        frameSources[key] = source
    }

    /** 注销帧源（预览停止或出错时调用） */
    fun unregisterFrameSource(key: Any) {
        frameSources.remove(key)
    }

    /** 抓取当前陪学画面；无活跃帧源或抓取失败时返回 null */
    fun captureCurrentFrame(): ByteArray? {
        if (frameSources.isEmpty()) return null
        return frameSources.values.firstNotNullOfOrNull { source ->
            runCatching { source() }.getOrNull()
        }
    }

    private suspend fun patrolOnce() {
        val frame = captureCurrentFrame() ?: return
        if (!VisionService.isConfigured()) {
            // 视觉服务未配置（未连接官方云端）时跳过本轮，避免无效上报
            lastResultFlow.value = PatrolResult(
                timestamp = System.currentTimeMillis(),
                summary = "视觉服务未配置，巡查已跳过",
                delivered = false
            )
            return
        }
        val analysis = VisionService.analyze(
            StudyCompanionPromptBuilder.build(),
            frame,
            "study_patrol.jpg"
        )
        if (!analysis.optBoolean("success", true)) {
            // 上传或识别失败时只记录状态，不把失败信息送给大模型
            lastResultFlow.value = PatrolResult(
                timestamp = System.currentTimeMillis(),
                summary = analysis.optString("message").ifBlank { "视觉分析失败" },
                delivered = false
            )
            return
        }
        val summary = StudyCompanionPromptBuilder.summarize(analysis)
        val delivered = VoiceForegroundService.sendText(
            "${StudyCompanionPromptBuilder.PATROL_LEAD}$summary"
        )
        lastResultFlow.value = PatrolResult(
            timestamp = System.currentTimeMillis(),
            summary = summary,
            delivered = delivered
        )
    }

    /** 巡逻间隔：5 分钟一次，避免打扰过频 */
    private const val PATROL_INTERVAL_MS = 5 * 60 * 1000L

    /** 首轮巡查延迟：预览稳定后再开始观察 */
    private const val FIRST_PATROL_DELAY_MS = 60 * 1000L
}
