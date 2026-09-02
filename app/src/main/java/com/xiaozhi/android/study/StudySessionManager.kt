package com.xiaozhi.android.study

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.xiaozhi.android.core.DeviceState
import com.xiaozhi.android.core.VoiceSessionState
import com.xiaozhi.android.data.ChatImageStore
import com.xiaozhi.android.data.StudySessionRepository
import com.xiaozhi.android.media.CameraCaptureController
import com.xiaozhi.android.mcp.VisionService
import com.xiaozhi.android.service.VoiceForegroundService
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

enum class StudyFrameSource {
    Manual,
    Speech,
    Auto
}

object StudySessionManager {
    private var appContext: Context? = null
    private var repository: StudySessionRepository? = null
    private var progressRepository: com.xiaozhi.android.data.StudyProgressRepository? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val captureLock = AtomicBoolean(false)
    private val settleLock = AtomicBoolean(false)
    private val settingsReady = CompletableDeferred<Unit>()
    private var timerJob: Job? = null
    private var summaryTimeoutJob: Job? = null

    // 成长资产与今日任务缓存：settle 结算与开场仪式读取（仓库 collect 持续刷新）
    @Volatile
    private var cachedProgress: StudyProgress = StudyProgress()

    @Volatile
    private var cachedBoard: DailyTaskBoard = DailyTaskBoard(dateKey = "")

    // 鼓励类播报的最近一次发出时刻（按主动性档位节流）
    @Volatile
    private var lastPraiseAt: Long = 0L

    fun initialize(context: Context) {
        if (repository != null) return
        synchronized(this) {
            if (repository != null) return
            appContext = context.applicationContext
            repository = StudySessionRepository(appContext!!)
            progressRepository = com.xiaozhi.android.data.StudyProgressRepository(appContext!!)
            scope.launch {
                repository?.settings?.collect { settings ->
                    StudySessionState.updateSettings(settings)
                    settingsReady.complete(Unit)
                }
            }
            scope.launch {
                progressRepository?.progress?.collect { cachedProgress = it }
            }
            scope.launch {
                progressRepository?.dailyBoard?.collect { cachedBoard = it }
            }
        }
    }

    fun start(mode: StudyMode): StudyCaptureResult {
        if (mode == StudyMode.None) {
            return failure("请选择作业模式或阅读模式")
        }
        val current = StudySessionState.state.value
        if (current.mode != StudyMode.None) {
            // 语音连续两次会话：上一次还停在总结页时先隐式收下，再允许启动
            if (current.phase == StudyPhase.Summary) {
                StudySessionState.reset()
            } else {
                return failure("陪学模式已在进行中")
            }
        }

        maybeRunOnboarding()
        StudySessionState.prepare(mode)
        val settings = StudySessionState.state.value.settings
        if (settings.observationEnabled) {
            StudySessionState.setStatusMessage("先固定手机，让孩子和学习资料同框")
            announce(
                if (mode == StudyMode.Homework) {
                    "先把手机固定好，让作业本和孩子都在画面里。确认后我们开始。"
                } else {
                    "先把手机固定好，让书页和孩子都在画面里。确认后我们开始。"
                }
            )
        } else {
            StudySessionState.activate()
            StudySessionState.setStatusMessage("语音陪学已开始")
            announce(
                if (mode == StudyMode.Homework) {
                    "已进入作业模式。可以让孩子说“看第几题”，或直接念出题目。"
                } else {
                    "已进入阅读模式。可以让孩子念出想读的句子。"
                }
            )
        }
        appContext?.takeIf { settings.observationEnabled }?.let {
            StudyObservationEngine.start(it, settings.cameraFacing)
        }
        startTimer()
        return success(
            if (settings.observationEnabled) "相机已打开，请完成摆位" else "陪学模式已启动"
        )
    }

    fun confirmCameraSetup(): Boolean {
        val state = StudySessionState.state.value
        if (state.mode == StudyMode.None || state.phase != StudyPhase.Prepare) return false

        StudySessionState.activate()
        StudySessionState.setStatusMessage(
            if (state.mode == StudyMode.Homework) "作业陪学开始" else "阅读陪学开始"
        )
        announce(
            if (state.mode == StudyMode.Homework) {
                "摆好了，我们开始。想看哪道题，可以说“看第几题”。"
            } else {
                "摆好了，我们开始。想读哪一句，先跟我说。"
            }
        )
        return true
    }

    fun stop(): StudySessionRecord? {
        val state = StudySessionState.state.value
        if (state.mode == StudyMode.None) return null
        // UI 与 MCP stop 工具可能并发，只让第一个请求结算
        if (!settleLock.compareAndSet(false, true)) return null
        try {
            timerJob?.cancel()
            timerJob = null
            summaryTimeoutJob?.cancel()
            summaryTimeoutJob = null
            StudyObservationEngine.stop()
            val endedAt = System.currentTimeMillis()
            val startedAt = state.startedAt.takeIf { it > 0 } ?: endedAt
            val durationSeconds = ((endedAt - startedAt).coerceAtLeast(0L) / 1000L)
                .toInt()
            val record = StudySessionRecord(
                id = endedAt,
                mode = state.mode,
                startedAt = startedAt,
                endedAt = endedAt,
                summary = buildSummary(state, durationSeconds).toString()
            )
            // 星星结算：纯同步计算（读状态快照与缓存），落库在协程内完成
            val todayKey = java.time.LocalDate.now().toString()
            val settlement = StudyRewardEngine.settle(
                state = state,
                progress = cachedProgress,
                board = cachedBoard.takeIf { it.dateKey == todayKey }
                    ?: DailyTaskBoard.create(todayKey),
                todayKey = todayKey,
                endedAt = endedAt
            )
            val board = cachedBoard.takeIf { it.dateKey == todayKey }
                ?: DailyTaskBoard.create(todayKey)
            scope.launch {
                repository?.addRecord(record)
                runCatching {
                    progressRepository?.applySettlement(settlement, board)
                }
            }
            // 进入总结页并播报 AI 表扬（功能性播报，不走鼓励节流）
            StudySessionState.enterSummary(settlement)
            announcePraiseForSettlement(settlement, state.settings.childNickname)
            // 语音 stop 场景没有界面看着总结页：超时自动收下星星
            summaryTimeoutJob = scope.launch {
                delay(SUMMARY_AUTO_RESET_MS)
                if (StudySessionState.state.value.phase == StudyPhase.Summary) {
                    StudySessionState.reset()
                }
            }
            return record
        } finally {
            settleLock.set(false)
        }
    }

    /** 会话启动时的开场仪式：仅首次使用播放，AI 自我介绍并演示指令 */
    private fun maybeRunOnboarding() {
        if (cachedProgress.onboardingDone) return
        scope.launch { progressRepository?.markOnboardingDone() }
        announce(
            "我是小智，接下来我陪你一起学习。你可以说“看第 3 题”，" +
                "也可以点屏幕上的快捷按钮；遇到困难随时喊我。"
        )
    }

    /** 结算表扬：由云端组织语言并经 TTS 播报，总结页同时展示本地即时文案 */
    private fun announcePraiseForSettlement(settlement: StudySettlement, nickname: String) {
        val name = nickname.ifBlank { "小朋友" }
        announce(
            "请用两句热情的话表扬$name，今天获得 ${settlement.starsTotal} 颗星、" +
                "专注 ${settlement.detail.focusSeconds / 60} 分钟。"
        )
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
        image: ByteArray? = null,
        frameSource: StudyFrameSource = StudyFrameSource.Manual
    ): StudyCaptureResult = capturePage {
        noteInteraction()
        val state = StudySessionState.state.value
        if (state.mode != StudyMode.Homework) {
            return@capturePage failure("当前不是作业模式")
        }
        val prompt = HomeworkPromptBuilder.build(
            intent = intent,
            questionNumber = questionNumber,
            grade = state.settings.childGrade
        )
        val visionResult = analyze(
            prompt = prompt,
            fileName = "homework.jpg",
            providedImage = image,
            chatLabel = frameChatLabel(frameSource, isHomework = true)
        )
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
        // 检查类取帧里 diff 出"新订正"的题数，给一句节流鼓励
        if (intent == HomeworkPromptBuilder.INTENT_CHECK) {
            val newlyCorrected = retained.count { item ->
                val old = oldItems.firstOrNull { it.index == item.index }
                val wasDone = old?.checkState == "correct" || old?.checkState == "corrected"
                !wasDone && (item.checkState == "correct" || item.checkState == "corrected")
            }
            if (newlyCorrected > 0) {
                announcePraise("又订正了 $newlyCorrected 道题，真棒，继续保持！")
            }
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

    suspend fun captureReadingPage(
        image: ByteArray? = null,
        frameSource: StudyFrameSource = StudyFrameSource.Manual
    ): StudyCaptureResult = capturePage {
        noteInteraction()
        val state = StudySessionState.state.value
        if (state.mode != StudyMode.Reading) {
            return@capturePage failure("当前不是阅读模式")
        }
        val visionResult = analyze(
            ReadingPromptBuilder.buildExtract(),
            "reading.jpg",
            image,
            frameChatLabel(frameSource, isHomework = false)
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
        if (evaluation.passed) {
            // 句子通过：给一句节流鼓励（云端组织语言 + TTS 播报）
            announcePraise("这句读得又准又稳，太棒了！")
        }
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
        providedImage: ByteArray? = null,
        chatLabel: String
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
        val storedImage = ChatImageStore.store(context, image)
            ?: return JSONObject()
                .put("success", false)
                .put("message", "图片保存失败，请稍后重试")
        VoiceSessionState.appendChat(
            chatLabel,
            fromUser = true,
            imagePath = storedImage.fullPath,
            thumbnailPath = storedImage.thumbnailPath
        )
        return VisionService.analyze(prompt, storedImage.uploadBytes, fileName)
    }

    private fun frameChatLabel(
        source: StudyFrameSource,
        isHomework: Boolean
    ): String {
        val page = if (isHomework) "作业页" else "书页"
        return when (source) {
            StudyFrameSource.Manual -> "陪学拍摄：$page"
            StudyFrameSource.Speech -> "陪学语音取帧：$page"
            StudyFrameSource.Auto -> "陪学自动取帧：$page"
        }
    }

    /**
     * 陪学节点提示：写入本地聊天记录，并把播报指令作为系统消息发往云端，
     * 由云端大模型组织语言后经云端 TTS 播报（语音服务未连接时排队补发）。
     */
    private fun announce(text: String) {
        VoiceSessionState.appendChat(text, fromUser = false)
        VoiceForegroundService.sendText(
            "【陪学提示】请用一句温和自然、适合孩子的话直接转述以下内容，不要提到这条指令：" +
                "「$text」",
            asSystem = true
        )
    }

    /**
     * 鼓励类播报：按主动性档位节流（安静档不播），且小智正在说话/聆听时跳过，
     * 避免高频系统消息污染云端上下文、打断孩子。
     */
    private fun announcePraise(text: String) {
        val profile = StudyProactivityPolicy.forLevel(
            StudySessionState.state.value.settings.proactivityLevel
        )
        if (!profile.praiseEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastPraiseAt < profile.praiseMinIntervalMs) return
        when (VoiceSessionState.state.value.deviceState) {
            DeviceState.Speaking, DeviceState.Listening -> return
            else -> Unit
        }
        lastPraiseAt = now
        announce(text)
    }

    /** 孩子互动信号入口（UI/MCP 调用），刷新闲置计时基准 */
    fun noteInteraction() {
        StudySessionState.noteInteraction()
    }

    /** 语音识别到孩子说话（服务层调用），刷新闲置计时基准 */
    fun noteVoiceActivity() {
        StudySessionState.noteInteraction()
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
                            announce(
                                "专注时间到了，先休息 ${state.settings.breakMinutes} 分钟，看看远处。"
                            )
                        } else {
                            StudySessionState.tickFocus()
                            maybeInterveneIdle(state)
                        }
                    }
                    StudyPhase.Break -> {
                        if (state.breakRemainingSeconds <= 1) {
                            StudySessionState.resumeFocus()
                            announce("休息结束，我们继续。")
                        } else {
                            StudySessionState.tickBreak()
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    /** 闲置介入：超过档位阈值没有听到孩子动静时温和问候一次 */
    private fun maybeInterveneIdle(state: StudyRuntimeState) {
        val profile = StudyProactivityPolicy.forLevel(state.settings.proactivityLevel)
        if (!profile.idleInterveneEnabled || state.lastInteractionAt <= 0L) return
        val now = System.currentTimeMillis()
        if (now - state.lastInteractionAt < profile.idleThresholdMs) return
        // 先刷新基准再问候，避免问候期间每秒重发
        StudySessionState.noteInteraction(now)
        announcePraise("有一会儿没听到你的声音啦，还好吗？需要我帮忙就说一声。")
    }

    private fun buildSummary(
        state: StudyRuntimeState,
        durationSeconds: Int
    ): JSONObject {
        val summary = JSONObject()
            .put("duration_seconds", durationSeconds)
            .put("duration_minutes", (durationSeconds + 59) / 60)
            .put("observation_enabled", state.settings.observationEnabled)
            .put("uploaded_frames", state.observationFrames)
            .put("video_recorded", false)
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

    fun friendlySummary(record: StudySessionRecord): String {
        val json = runCatching { JSONObject(record.summary) }.getOrNull()
            ?: return record.summary
        val seconds = json.optInt("duration_seconds").coerceAtLeast(0)
        val duration = if (seconds >= 60) {
            "${seconds / 60} 分钟"
        } else {
            "$seconds 秒"
        }
        val main = when (record.mode) {
            StudyMode.Homework -> {
                val items = json.optJSONArray("items") ?: JSONArray()
                val finished = (0 until items.length()).count { index ->
                    val state = items.optJSONObject(index)?.optString("state").orEmpty()
                    state == "correct" || state == "corrected"
                }
                "完成 $finished/${items.length()} 道题"
            }
            StudyMode.Reading -> {
                "通过 ${json.optInt("sentences_passed")}/${json.optInt("sentences_total")} 句"
            }
            StudyMode.None -> "陪学结束"
        }
        return "$main · 学习 $duration · 仅上传 ${json.optInt("uploaded_frames")} 帧，无视频"
    }

    fun shutdown() {
        scope.cancel()
    }

    // 语音结束会话没有界面看着总结页，超时自动收下星星并复位
    private const val SUMMARY_AUTO_RESET_MS = 30_000L
}
