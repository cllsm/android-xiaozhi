package com.xiaozhi.android.study

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.xiaozhi.android.core.VoiceSessionState
import com.xiaozhi.android.data.StudySessionRepository
import com.xiaozhi.android.media.CameraCaptureController
import com.xiaozhi.android.mcp.VisionService
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class StudyCaptureResult(
    val success: Boolean,
    val message: String,
    val payload: JSONObject? = null
)

object StudySessionManager {
    private var appContext: Context? = null
    private var repository: StudySessionRepository? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val captureLock = AtomicBoolean(false)
    private val settingsReady = CompletableDeferred<Unit>()
    private var timerJob: Job? = null

    fun initialize(context: Context) {
        if (repository != null) return
        synchronized(this) {
            if (repository != null) return
            appContext = context.applicationContext
            repository = StudySessionRepository(appContext!!)
            scope.launch {
                repository?.settings?.collect { settings ->
                    StudySessionState.updateSettings(settings)
                    settingsReady.complete(Unit)
                }
            }
        }
    }

    fun start(mode: StudyMode): StudyCaptureResult {
        if (mode == StudyMode.None) {
            return failure("请选择作业模式或阅读模式")
        }
        val current = StudySessionState.state.value
        if (current.mode != StudyMode.None) {
            return failure("陪学模式已在进行中")
        }

        StudySessionState.prepare(mode)
        StudySessionState.activate()
        val settings = StudySessionState.state.value.settings
        StudySessionState.setStatusMessage(
            if (mode == StudyMode.Homework) "已进入作业模式" else "已进入阅读模式"
        )
        VoiceSessionState.appendChat(
            if (mode == StudyMode.Homework) {
                "已进入作业模式。想看哪道题，把作业本放在摄像头前说“看第几题”。"
            } else {
                "已进入阅读模式。把书页放在摄像头前，点“拍书页”或说“看这一页”。"
            },
            fromUser = false
        )
        appContext?.takeIf { settings.observationEnabled }?.let(StudyObservationEngine::start)
        startTimer()
        return success("陪学模式已启动")
    }

    fun stop(): StudySessionRecord? {
        val state = StudySessionState.state.value
        if (state.mode == StudyMode.None) return null

        timerJob?.cancel()
        timerJob = null
        StudyObservationEngine.stop()
        val endedAt = System.currentTimeMillis()
        val record = StudySessionRecord(
            id = endedAt,
            mode = state.mode,
            startedAt = state.startedAt.takeIf { it > 0 } ?: endedAt,
            endedAt = endedAt,
            summary = buildSummary(state).toString()
        )
        scope.launch { repository?.addRecord(record) }
        StudySessionState.reset()
        return record
    }

    fun updateSettings(settings: StudySettings) {
        StudySessionState.updateSettings(settings)
        if (StudySessionState.state.value.mode != StudyMode.None) {
            if (settings.observationEnabled) {
                appContext?.let(StudyObservationEngine::start)
            } else {
                StudyObservationEngine.stop()
            }
        }
        scope.launch { repository?.updateSettings(settings) }
    }

    suspend fun captureHomeworkPage(
        intent: String,
        questionNumber: Int? = null,
        image: ByteArray? = null
    ): StudyCaptureResult = capturePage {
        val state = StudySessionState.state.value
        if (state.mode != StudyMode.Homework) {
            return@capturePage failure("当前不是作业模式")
        }
        val prompt = HomeworkPromptBuilder.build(
            intent = intent,
            questionNumber = questionNumber,
            grade = state.settings.childGrade
        )
        val visionResult = analyze(prompt, "homework.jpg", image)
        if (!visionResult.optBoolean("success", true)) {
            return@capturePage failure(visionResult.optString("message").ifBlank {
                "作业页识别失败"
            })
        }
        val page = StudyVisionResultParser.parseHomeworkPage(visionResult)
            ?: return@capturePage StudyCaptureResult(
                success = false,
                message = "作业页结构识别失败，可以让孩子念出题目",
                payload = visionResult
            )

        val oldItems = state.homeworkPage?.items.orEmpty()
        val retained = page.items.map { item ->
            oldItems.firstOrNull { it.index == item.index }?.let { old ->
                item.copy(hintLevel = old.hintLevel)
            } ?: item
        }
        StudySessionState.updateHomework { current ->
            page.copy(
                items = retained,
                selectedQuestionNumber = questionNumber
                    ?: state.homeworkPage?.selectedQuestionNumber
                    ?: retained.firstOrNull()?.index
            )
        }
        StudySessionState.setStatusMessage(
            "已识别 ${retained.size} 道题" + (
                questionNumber?.let { "，当前第 $it 题" } ?: ""
                )
        )
        success(
            "已识别 ${retained.size} 道题",
            JSONObject()
                .put("page_type", page.pageType)
                .put("subject_guess", page.subjectGuess)
                .put(
                    "items",
                    JSONArray(retained.map { item ->
                        JSONObject()
                            .put("index", item.index)
                            .put("question", item.question)
                            .put("question_type", item.questionType)
                            .put("student_answer", item.studentAnswer.orEmpty())
                            .put("answer_readable", item.answerReadable)
                            .put("confidence", item.confidence.toDouble())
                            .put("check_state", item.checkState)
                    })
                )
                .put("unreadable_regions", JSONArray(page.unreadableRegions))
        )
    }

    suspend fun captureReadingPage(image: ByteArray? = null): StudyCaptureResult = capturePage {
        val state = StudySessionState.state.value
        if (state.mode != StudyMode.Reading) {
            return@capturePage failure("当前不是阅读模式")
        }
        val visionResult = analyze(
            ReadingPromptBuilder.buildExtract(),
            "reading.jpg",
            image
        )
        if (!visionResult.optBoolean("success", true)) {
            return@capturePage failure(visionResult.optString("message").ifBlank {
                "书页识别失败"
            })
        }
        val page = StudyVisionResultParser.parseReadingPage(visionResult)
            ?: return@capturePage StudyCaptureResult(
                success = false,
                message = "书页文本识别失败，请调整光线和距离后重拍",
                payload = visionResult
            )

        val oldPage = state.readingPage
        val retainedSentences = page.sentences.map { sentence ->
            oldPage?.sentences
                ?.firstOrNull { it.text == sentence.text }
                ?.let { old -> sentence.copy(status = old.status) }
                ?: sentence
        }
        val retainedIndex = oldPage?.currentIndex
            ?.takeIf { retainedSentences.getOrNull(it) != null }
            ?: 0
        val retainedPage = page.copy(
            sentences = retainedSentences,
            currentIndex = retainedIndex
        )
        StudySessionState.updateReading {
            retainedPage.copy(lastEvaluation = oldPage?.lastEvaluation)
        }
        StudySessionState.setStatusMessage("已提取 ${page.sentences.size} 句")
        success(
            "已提取 ${page.sentences.size} 句",
            JSONObject()
                .put("title_guess", retainedPage.title)
                .putOpt("page_number", retainedPage.pageNumber)
                .put(
                    "sentences",
                    JSONArray(retainedSentences.map { sentence ->
                        JSONObject()
                            .put("index", sentence.index)
                            .put("text", sentence.text)
                            .put("status", sentence.status)
                    })
                )
        )
    }

    fun homeworkContext(questionNumber: Int?): StudyCaptureResult {
        val state = StudySessionState.state.value
        val page = state.homeworkPage
            ?: return failure("还没有作业页缓存，请先拍当前页")
        val selectedNumber = questionNumber ?: page.selectedQuestionNumber
        val item = selectedNumber?.let { number ->
            page.items.firstOrNull { it.index == number }
        } ?: page.items.firstOrNull()
            ?: return failure("作业页里没有识别到题目")

        val nextHintLevel = if (questionNumber != null &&
            page.selectedQuestionNumber != questionNumber
        ) {
            1
        } else {
            (item.hintLevel + 1).coerceAtMost(3)
        }
        StudySessionState.updateHomework { current ->
            current.copy(
                selectedQuestionNumber = item.index,
                items = current.items.map {
                    if (it.index == item.index) it.copy(hintLevel = nextHintLevel) else it
                }
            )
        }

        return success(
            "已获取第 ${item.index} 题上下文",
            JSONObject()
                .put("mode", "homework")
                .put("item_index", item.index)
                .put("question", item.question)
                .put("question_type", item.questionType)
                .put("student_answer", item.studentAnswer.orEmpty())
                .put("answer_readable", item.answerReadable)
                .put("confidence", item.confidence.toDouble())
                .put("hint_level", nextHintLevel)
                .put(
                    "teaching_rules",
                    JSONObject()
                        .put("answer_policy", state.settings.answerPolicy.name)
                        .put(
                            "instruction",
                            if (nextHintLevel == 1) {
                                "先带孩子读题和找关键条件，不给答案。"
                            } else if (nextHintLevel == 2) {
                                "只给解题思路的第一步，引导孩子说出下一步。"
                            } else {
                                "完整分步讲解，最后一步留给孩子自己完成。"
                            }
                        )
                        .put(
                            "allow_final_answer",
                            state.settings.answerPolicy == AnswerPolicy.GuidanceThenAnswer &&
                                nextHintLevel >= 2
                        )
                )
        )
    }

    fun readingContext(): StudyCaptureResult {
        val state = StudySessionState.state.value
        val page = state.readingPage ?: return failure("还没有书页缓存，请先拍当前页")
        val index = page.currentIndex.coerceIn(0, page.sentences.lastIndex)
        val sentence = page.sentences.getOrNull(index)
            ?: return failure("书页里没有识别到句子")

        return success(
            "当前第 ${index + 1} 句",
            JSONObject()
                .put("mode", "reading")
                .put("title_guess", page.title)
                .put("current_index", index)
                .put("sentence", sentence.text)
                .put("sentence_status", sentence.status)
                .put(
                    "teaching_rules",
                    "用简短温和的儿童导师口吻；孩子读错时先鼓励，再指出具体字词。"
                )
        )
    }

    fun evaluateTranscript(text: String): ReadingEvaluation? {
        val state = StudySessionState.state.value
        if (state.mode != StudyMode.Reading) return null
        val page = state.readingPage ?: return null
        val sentence = page.sentences.getOrNull(page.currentIndex) ?: return null
        val evaluation = ReadingEvaluator.evaluate(sentence.text, text)
        StudySessionState.updateReading { current ->
            current.copy(
                sentences = current.sentences.mapIndexed { index, item ->
                    if (index == current.currentIndex) {
                        item.copy(
                            status = if (evaluation.passed) "passed" else "needs_retry"
                        )
                    } else {
                        item
                    }
                },
                currentIndex = if (evaluation.passed) {
                    (current.currentIndex + 1).coerceAtMost(current.sentences.lastIndex)
                } else {
                    current.currentIndex
                },
                lastEvaluation = evaluation
            )
        }
        StudySessionState.setStatusMessage(ReadingEvaluator.feedbackFor(evaluation))
        return evaluation
    }

    fun moveReadingSentence(delta: Int): StudyCaptureResult {
        val page = StudySessionState.state.value.readingPage
            ?: return failure("还没有书页缓存")
        if (page.sentences.isEmpty()) return failure("书页里没有句子")
        val target = (page.currentIndex + delta).coerceIn(0, page.sentences.lastIndex)
        StudySessionState.updateReading { it.copy(currentIndex = target) }
        return success("已切换到第 ${target + 1} 句")
    }

    private suspend fun capturePage(block: suspend () -> StudyCaptureResult): StudyCaptureResult {
        if (!captureLock.compareAndSet(false, true)) {
            return failure("上一次拍摄还在处理，请稍候")
        }
        StudySessionState.setCaptureRunning(true)
        try {
            return withContext(Dispatchers.IO) { block() }
        } finally {
            StudySessionState.setCaptureRunning(false)
            captureLock.set(false)
        }
    }

    private suspend fun analyze(
        prompt: String,
        fileName: String,
        providedImage: ByteArray? = null
    ): JSONObject {
        val context = appContext ?: return JSONObject()
            .put("success", false)
            .put("message", "陪学模块未初始化")
        if (context.checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return JSONObject()
                .put("success", false)
                .put("message", "缺少相机权限，请先授予相机权限")
        }
        val image = providedImage ?: if (StudyObservationEngine.isRunning) {
            StudyObservationEngine.captureFrame()
        } else {
            CameraCaptureController(context).capture()
        }
            ?: return JSONObject()
                .put("success", false)
                .put("message", "拍照失败，请确认摄像头可用")
        return VisionService.analyze(prompt, image, fileName)
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive && StudySessionState.state.value.mode != StudyMode.None) {
                delay(1_000L)
                val state = StudySessionState.state.value
                when (state.phase) {
                    StudyPhase.Active -> {
                        if (state.focusRemainingSeconds <= 1) {
                            StudySessionState.enterBreak()
                            VoiceSessionState.appendChat(
                                "专注时间到了，先休息 ${state.settings.breakMinutes} 分钟，看看远处。",
                                fromUser = false
                            )
                        } else {
                            StudySessionState.tickFocus()
                        }
                    }
                    StudyPhase.Break -> {
                        if (state.breakRemainingSeconds <= 1) {
                            StudySessionState.resumeFocus()
                            VoiceSessionState.appendChat(
                                "休息结束，我们继续。",
                                fromUser = false
                            )
                        } else {
                            StudySessionState.tickBreak()
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun buildSummary(state: StudyRuntimeState): JSONObject {
        val summary = JSONObject()
            .put("duration_minutes", (
                (System.currentTimeMillis() - state.startedAt).coerceAtLeast(0L) / 60_000L
                ).toInt())
        when (state.mode) {
            StudyMode.Homework -> {
                summary.put(
                    "items",
                    JSONArray(state.homeworkPage?.items.orEmpty().map { item ->
                        JSONObject()
                            .put("index", item.index)
                            .put("state", item.checkState)
                            .put("hint_levels_used", item.hintLevel)
                    })
                )
            }
            StudyMode.Reading -> {
                val sentences = state.readingPage?.sentences.orEmpty()
                summary.put("sentences_total", sentences.size)
                summary.put("sentences_passed", sentences.count { it.status == "passed" })
            }
            StudyMode.None -> Unit
        }
        return summary
    }

    private fun success(message: String, payload: JSONObject? = null): StudyCaptureResult {
        return StudyCaptureResult(true, message, payload)
    }

    private fun failure(message: String): StudyCaptureResult {
        return StudyCaptureResult(false, message)
    }

    fun shutdown() {
        scope.cancel()
    }
}
