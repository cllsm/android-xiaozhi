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
    val cameraFacing: StudyCameraFacing = StudyCameraFacing.Back
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
    val homeworkPage: HomeworkPageState? = null,
    val readingPage: ReadingPageState? = null
)

data class StudySessionRecord(
    val id: Long,
    val mode: StudyMode,
    val startedAt: Long,
    val endedAt: Long,
    val summary: String
)
