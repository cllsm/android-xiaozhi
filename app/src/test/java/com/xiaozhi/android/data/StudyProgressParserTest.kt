package com.xiaozhi.android.data

import com.xiaozhi.android.study.DailyTaskType
import com.xiaozhi.android.study.StudyProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 成长资产与任务板的 JSON 编解码 */
class StudyProgressParserTest {

    @Test
    fun parsesNullAndBlankAsDefaults() {
        assertEquals(StudyProgress(), parseProgress(null))
        assertEquals(StudyProgress(), parseProgress(""))
        assertEquals(StudyProgress(), parseProgress("not-json"))
    }

    @Test
    fun progressRoundTripKeepsAllFields() {
        val progress = StudyProgress(
            totalStars = 42,
            sessionsTotal = 7,
            focusSecondsTotal = 9_000L,
            correctedItemsTotal = 12,
            passedSentencesTotal = 33,
            unlockedAchievements = mapOf("FirstSession" to 123L, "Streak3" to 456L),
            streakDays = 4,
            streakLastDateKey = "2026-09-01",
            onboardingDone = true
        )
        assertEquals(progress, parseProgress(serializeProgress(progress)))
    }

    @Test
    fun boardRoundTripKeepsCompletionState() {
        val board = com.xiaozhi.android.study.DailyTaskBoard.advance(
            com.xiaozhi.android.study.DailyTaskBoard.create("2026-09-02"),
            focusMinutesDelta = 20,
            progressItemsDelta = 1,
            sessionFinished = false,
            now = 999L
        )
        val parsed = parseBoard(serializeBoard(board))
        assertEquals("2026-09-02", parsed.dateKey)
        assertEquals(3, parsed.tasks.size)
        val focus = parsed.tasks.first { it.type == DailyTaskType.FocusMinutes }
        assertEquals(999L, focus.completedAt)
        val content = parsed.tasks.first { it.type == DailyTaskType.ProgressItems }
        assertEquals(1, content.progress)
        assertTrue(content.completedAt == null)
    }

    @Test
    fun boardParsesEmptyAsBlankDate() {
        val board = parseBoard(null)
        assertEquals("", board.dateKey)
        assertTrue(board.tasks.isEmpty())
    }

    @Test
    fun boardSkipsUnknownTaskTypes() {
        val raw = """
            {"date_key":"2026-09-02","tasks":[
              {"type":"future_type","target":1},
              {"type":"finish_session","target":1,"progress":1}
            ]}
        """.trimIndent()
        val board = parseBoard(raw)
        assertEquals(1, board.tasks.size)
        assertEquals(DailyTaskType.FinishSession, board.tasks[0].type)
    }
}
