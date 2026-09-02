package com.xiaozhi.android.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** 星星结算引擎：规则封顶、liveStars 与 settle 一致性、勋章边界 */
class StudyRewardEngineTest {

    private fun homeworkState(
        focusSeconds: Int,
        items: List<HomeworkItem> = emptyList()
    ) = StudyRuntimeState(
        mode = StudyMode.Homework,
        phase = StudyPhase.Active,
        startedAt = 1_000L,
        focusElapsedSeconds = focusSeconds,
        homeworkPage = HomeworkPageState(items = items)
    )

    private fun readingState(passed: Int, total: Int = passed) = StudyRuntimeState(
        mode = StudyMode.Reading,
        phase = StudyPhase.Active,
        startedAt = 1_000L,
        focusElapsedSeconds = 0,
        readingPage = ReadingPageState(
            sentences = List(total) { index ->
                ReadingSentence(
                    index = index,
                    text = "第${index + 1}句",
                    status = if (index < passed) "passed" else "pending"
                )
            }
        )
    )

    private fun atHour(hour: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun focusStarsFollowTenMinuteRuleWithCap() {
        // 专注 10 分钟 1 星、30 分钟封顶 3 星
        assertEquals(0, StudyRewardEngine.liveStars(homeworkState(focusSeconds = 599)))
        assertEquals(1, StudyRewardEngine.liveStars(homeworkState(focusSeconds = 600)))
        assertEquals(2, StudyRewardEngine.liveStars(homeworkState(focusSeconds = 1_200)))
        assertEquals(3, StudyRewardEngine.liveStars(homeworkState(focusSeconds = 1_800)))
        assertEquals(3, StudyRewardEngine.liveStars(homeworkState(focusSeconds = 3_600)))
    }

    @Test
    fun correctedStarsCapAtFive() {
        val items = (1..8).map { HomeworkItem(index = it, question = "题$it", checkState = "correct") }
        assertEquals(5, StudyRewardEngine.liveStars(homeworkState(focusSeconds = 0, items = items)))
        // wrong/unchecked 不计入
        val mixed = listOf(
            HomeworkItem(index = 1, question = "题1", checkState = "wrong"),
            HomeworkItem(index = 2, question = "题2", checkState = "corrected")
        )
        assertEquals(1, StudyRewardEngine.liveStars(homeworkState(focusSeconds = 0, items = mixed)))
    }

    @Test
    fun readingStarsCapAtFive() {
        assertEquals(5, StudyRewardEngine.liveStars(readingState(passed = 9)))
        assertEquals(3, StudyRewardEngine.liveStars(readingState(passed = 3)))
    }

    @Test
    fun liveStarsMatchesSettleForHomework() {
        val items = (1..6).map { HomeworkItem(index = it, question = "题$it", checkState = "correct") }
        val state = homeworkState(focusSeconds = 1_500, items = items)
        val settlement = StudyRewardEngine.settle(
            state = state,
            progress = StudyProgress(),
            board = DailyTaskBoard.create("2026-09-02"),
            todayKey = "2026-09-02",
            endedAt = atHour(15)
        )
        // 实时星（专注+订正）与结算专注+订正星完全一致；任务星只在结算时出现
        assertEquals(
            StudyRewardEngine.liveStars(state),
            settlement.starsFocus + settlement.starsCorrected
        )
        assertEquals(2, settlement.starsFocus)
        assertEquals(5, settlement.starsCorrected)
    }

    @Test
    fun settleGrantsTaskStarsAndStreak() {
        // 专注 20 分钟 + 3 题：三条每日任务全部打卡，+6 星
        val items = (1..3).map { HomeworkItem(index = it, question = "题$it", checkState = "corrected") }
        val settlement = StudyRewardEngine.settle(
            state = homeworkState(focusSeconds = 1_200, items = items),
            progress = StudyProgress(),
            board = DailyTaskBoard.create("2026-09-02"),
            todayKey = "2026-09-02",
            endedAt = atHour(15)
        )
        assertEquals(3, settlement.completedTasks.size)
        assertEquals(6, settlement.starsTasks)
        assertEquals(1, settlement.streakDays)
        // 汇总入账：专注分钟聚合与星星总数
        assertEquals(1_200L, settlement.aggregatedProgress.focusSecondsTotal)
        assertEquals(
            settlement.starsTotal,
            settlement.aggregatedProgress.totalStars
        )
        assertTrue(StudyAchievementId.FirstSession in settlement.newlyUnlocked)
    }

    @Test
    fun settleDoesNotDoubleCountExistingTaskProgress() {
        // 当天已专注 10 分钟、订正 1 题：本次再补 10 分钟 + 2 题才到齐
        val board = DailyTaskBoard.advance(
            board = DailyTaskBoard.create("2026-09-02"),
            focusMinutesDelta = 10,
            progressItemsDelta = 1,
            sessionFinished = false,
            now = 1L
        )
        val items = (1..2).map { HomeworkItem(index = it, question = "题$it", checkState = "corrected") }
        val settlement = StudyRewardEngine.settle(
            state = homeworkState(focusSeconds = 600, items = items),
            progress = StudyProgress(),
            board = board,
            todayKey = "2026-09-02",
            endedAt = atHour(16)
        )
        // 专注 10+10=20 满足、内容 1+2=3 满足、会话完成满足：三条全打卡
        assertEquals(3, settlement.completedTasks.size)
    }

    @Test
    fun achievementsUnlockAtBoundaries() {
        val detail = StudySessionDetail(
            durationSeconds = 60,
            focusSeconds = 1_800,
            completedItems = 0,
            passedSentences = 0,
            uploadedFrames = 0,
            endedHour = 15
        )
        val progress = StudyProgress(
            totalStars = 100,
            sessionsTotal = 1,
            focusSecondsTotal = 5 * 3600L,
            correctedItemsTotal = 20,
            passedSentencesTotal = 50,
            streakDays = 14
        )
        val unlocked = StudyRewardEngine.evaluateAchievements(progress, detail)
        assertTrue(StudyAchievementId.Streak14 in unlocked)
        assertTrue(StudyAchievementId.Focus30Single in unlocked)
        assertTrue(StudyAchievementId.Focus5HoursTotal in unlocked)
        assertTrue(StudyAchievementId.Reading50Sentences in unlocked)
        assertTrue(StudyAchievementId.Corrected20Items in unlocked)
        assertTrue(StudyAchievementId.Stars100 in unlocked)
        // 早鸟勋章：清晨 6 点结束
        val early = StudyRewardEngine.evaluateAchievements(
            StudyProgress(sessionsTotal = 1),
            detail.copy(focusSeconds = 0, endedHour = 6)
        )
        assertTrue(StudyAchievementId.EarlyBird in early)
    }

    @Test
    fun unlockedAchievementsAreNotRepeated() {
        val detail = StudySessionDetail(
            durationSeconds = 60,
            focusSeconds = 0,
            completedItems = 0,
            passedSentences = 0,
            uploadedFrames = 0,
            endedHour = 15
        )
        val progress = StudyProgress(
            sessionsTotal = 3,
            unlockedAchievements = mapOf(StudyAchievementId.FirstSession.name to 1L)
        )
        val unlocked = StudyRewardEngine.evaluateAchievements(progress, detail)
        assertTrue(StudyAchievementId.FirstSession !in unlocked)
    }
}
