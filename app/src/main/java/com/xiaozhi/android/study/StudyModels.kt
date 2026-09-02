package com.xiaozhi.android.study

enum class StudyMode {
    None,
    Homework,
    Reading
}

enum class StudyPhase {
    Prepare,
    Active,
    Break,
    Summary
}

enum class StudyCameraFacing {
    Back,
    Front
}

enum class AnswerPolicy {
    GuidanceOnly,
    GuidanceThenAnswer
}

data class StudySettings(
    val childGrade: String = "三年级",
    val answerPolicy: AnswerPolicy = AnswerPolicy.GuidanceOnly,
    val focusMinutes: Int = 20,
    val breakMinutes: Int = 5,
    val observationEnabled: Boolean = true,
    val observationIntervalSeconds: Int = 10,
    val cameraFacing: StudyCameraFacing = StudyCameraFacing.Back,
    // AI 主动性档位：决定巡查频率、闲置介入与鼓励节奏（家长中心可调）
    val proactivityLevel: ProactivityLevel = ProactivityLevel.Moderate,
    // 孩子昵称：播报称呼用，空则用"小朋友"
    val childNickname: String = ""
) {
    val focusSeconds: Int get() = focusMinutes * 60
    val breakSeconds: Int get() = breakMinutes * 60
}

data class HomeworkItem(
    val index: Int,
    val question: String,
    val questionType: String = "unknown",
    val studentAnswer: String? = null,
    val answerReadable: Boolean = false,
    val confidence: Float = 0f,
    val hintLevel: Int = 0,
    val checkState: String = "unchecked"
)

data class HomeworkPageState(
    val pageType: String = "",
    val subjectGuess: String = "",
    val unreadableRegions: List<String> = emptyList(),
    val items: List<HomeworkItem> = emptyList(),
    val selectedQuestionNumber: Int? = null,
    val capturedAt: Long = 0L
)

data class ReadingSentence(
    val index: Int,
    val text: String,
    val status: String = "pending"
)

data class ReadingEvaluation(
    val expected: String,
    val actual: String,
    val accuracy: Float,
    val substitutions: List<Substitution> = emptyList(),
    val missingText: String = "",
    val extraText: String = "",
    val passed: Boolean
) {
    data class Substitution(
        val expected: String,
        val actual: String,
        val sameSound: Boolean
    )
}

data class ReadingPageState(
    val title: String = "",
    val pageNumber: Int? = null,
    val sentences: List<ReadingSentence> = emptyList(),
    val currentIndex: Int = 0,
    val lastEvaluation: ReadingEvaluation? = null,
    val capturedAt: Long = 0L
)

data class StudyRuntimeState(
    val mode: StudyMode = StudyMode.None,
    val phase: StudyPhase = StudyPhase.Prepare,
    val settings: StudySettings = StudySettings(),
    val startedAt: Long = 0L,
    val focusRemainingSeconds: Int = 0,
    val breakRemainingSeconds: Int = 0,
    // 本次会话累计专注秒数（跨多个专注周期持续累加，结算星星用）
    val focusElapsedSeconds: Int = 0,
    // 最近一次孩子互动时刻（STT/UI/MCP），闲置介入的计时基准；小智自己的播报不刷新
    val lastInteractionAt: Long = 0L,
    val captureRunning: Boolean = false,
    val observationRunning: Boolean = false,
    val observationFrames: Int = 0,
    val lastObservationAt: Long = 0L,
    val lastObservationAttemptAt: Long = 0L,
    val lastObservationTrigger: String = "",
    val observationIssue: String = "",
    val lastCaptureFailure: String = "",
    val consecutiveCaptureFailures: Int = 0,
    val statusMessage: String = "",
    // 预览与取帧的手动旋转校正（0/90/180/270），用于相机画面方向不符的设备（如模拟器虚拟相机）
    val previewRotationOffset: Int = 0,
    val homeworkPage: HomeworkPageState? = null,
    val readingPage: ReadingPageState? = null,
    // 会话结束后的星星结算（phase == Summary 时非空，驱动结束总结页）
    val summary: StudySettlement? = null
)

data class StudySessionRecord(
    val id: Long,
    val mode: StudyMode,
    val startedAt: Long,
    val endedAt: Long,
    val summary: String
)
