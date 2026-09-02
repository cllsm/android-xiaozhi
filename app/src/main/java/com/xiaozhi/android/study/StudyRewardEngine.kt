package com.xiaozhi.android.study

/**
 * 星星结算引擎（纯 JVM，可单元测试）。
 *
 * 关键设计：实时星不是累计变量，而是每次从 StudyRuntimeState 快照重算——
 * STT/MCP/UI 任意线程更新状态都不会丢星或双计，UI 与结算共用同一套封顶规则。
 * 结算只在 StudySessionManager.stop() 单点入账。
 */
object StudyRewardEngine {
    /** 专注每满 10 分钟 +1 星 */
    const val STARS_PER_10_FOCUS_MINUTES = 1
    const val MAX_FOCUS_STARS_PER_SESSION = 3

    /** 每订正一题 +1 星（correct 与 corrected 都计入） */
    const val STARS_PER_CORRECTED_ITEM = 1
    const val MAX_CORRECTED_STARS = 5

    /** 每通过一句跟读 +1 星 */
    const val STARS_PER_PASSED_SENTENCE = 1
    const val MAX_READING_STARS = 5

    /** 每完成一条每日任务 +2 星 */
    const val DAILY_TASK_STAR_REWARD = 2

    /** 会话中实时展示的星星数（不入账），与 settle 使用完全相同的封顶规则 */
    fun liveStars(state: StudyRuntimeState): Int {
        return focusStars(state.focusElapsedSeconds) + when (state.mode) {
            StudyMode.Homework -> correctedStars(completedItemCount(state))
            StudyMode.Reading -> readingStars(passedSentenceCount(state))
            StudyMode.None -> 0
        }
    }

    /**
     * 会话结束结算（纯函数，不改库）：
     * 输入状态快照 + 当前成长资产 + 今日任务板，输出结算结果。
     */
    fun settle(
        state: StudyRuntimeState,
        progress: StudyProgress,
        board: DailyTaskBoard,
        todayKey: String,
        endedAt: Long
    ): StudySettlement {
        val completedItems = completedItemCount(state)
        val passedSentences = passedSentenceCount(state)
        val starsFocus = focusStars(state.focusElapsedSeconds)
        val starsCorrected = correctedStars(completedItems)
        val starsReading = readingStars(passedSentences)

        // 推进每日任务（完成一次会话 + 本次专注分钟 + 本次学习内容数）
        val advancedBoard = DailyTaskBoard.advance(
            board = board,
            focusMinutesDelta = state.focusElapsedSeconds / 60,
            progressItemsDelta = completedItems + passedSentences,
            sessionFinished = true,
            now = endedAt
        )
        // 只把"本次新打卡"的任务算作奖励：新板已完成而旧板未完成
        val completedTasks = advancedBoard.tasks.filter { task ->
            task.completedAt != null &&
                board.tasks.firstOrNull { it.type == task.type }?.completedAt == null
        }
        val starsTasks = completedTasks.size * DAILY_TASK_STAR_REWARD

        val starsTotal = starsFocus + starsCorrected + starsReading + starsTasks
        val streak = StudyStreakCalculator.nextStreak(
            lastDateKey = progress.streakLastDateKey,
            currentStreak = progress.streakDays,
            todayKey = todayKey
        )
        val endedHour = java.util.Calendar.getInstance()
            .apply { timeInMillis = endedAt }
            .get(java.util.Calendar.HOUR_OF_DAY)
        val detail = StudySessionDetail(
            durationSeconds = (endedAt - state.startedAt).coerceAtLeast(0L)
                .div(1000L).toInt(),
            focusSeconds = state.focusElapsedSeconds,
            completedItems = completedItems,
            passedSentences = passedSentences,
            uploadedFrames = state.observationFrames,
            endedHour = endedHour
        )

        // 勋章判定基于"入账后"的聚合值
        val aggregated = aggregateProgress(progress, state, settlement = null).copy(
            totalStars = progress.totalStars + starsTotal,
            streakDays = streak,
            streakLastDateKey = todayKey
        )
        val newlyUnlocked = evaluateAchievements(aggregated, detail)

        return StudySettlement(
            starsFocus = starsFocus,
            starsCorrected = starsCorrected,
            starsReading = starsReading,
            starsTasks = starsTasks,
            starsTotal = starsTotal,
            newlyUnlocked = newlyUnlocked,
            completedTasks = completedTasks,
            streakDays = streak,
            detail = detail,
            aggregatedProgress = aggregated
        )
    }

    /** 判定此刻应解锁的勋章（已解锁的不重复返回） */
    fun evaluateAchievements(
        aggregated: StudyProgress,
        detail: StudySessionDetail
    ): List<StudyAchievementId> {
        val unlocked = aggregated.unlockedAchievements.keys
        fun isNew(id: StudyAchievementId) = id.name !in unlocked
        return buildList {
            if (isNew(StudyAchievementId.FirstSession) && aggregated.sessionsTotal >= 1) {
                add(StudyAchievementId.FirstSession)
            }
            if (isNew(StudyAchievementId.Streak3) && aggregated.streakDays >= 3) {
                add(StudyAchievementId.Streak3)
            }
            if (isNew(StudyAchievementId.Streak7) && aggregated.streakDays >= 7) {
                add(StudyAchievementId.Streak7)
            }
            if (isNew(StudyAchievementId.Streak14) && aggregated.streakDays >= 14) {
                add(StudyAchievementId.Streak14)
            }
            if (isNew(StudyAchievementId.Focus30Single) &&
                detail.focusSeconds >= 30 * 60
            ) {
                add(StudyAchievementId.Focus30Single)
            }
            if (isNew(StudyAchievementId.Focus5HoursTotal) &&
                aggregated.focusSecondsTotal >= 5 * 3600
            ) {
                add(StudyAchievementId.Focus5HoursTotal)
            }
            if (isNew(StudyAchievementId.Reading50Sentences) &&
                aggregated.passedSentencesTotal >= 50
            ) {
                add(StudyAchievementId.Reading50Sentences)
            }
            if (isNew(StudyAchievementId.Corrected20Items) &&
                aggregated.correctedItemsTotal >= 20
            ) {
                add(StudyAchievementId.Corrected20Items)
            }
            if (isNew(StudyAchievementId.EarlyBird) &&
                detail.endedHour in 5..7
            ) {
                add(StudyAchievementId.EarlyBird)
            }
            if (isNew(StudyAchievementId.Stars100) && aggregated.totalStars >= 100) {
                add(StudyAchievementId.Stars100)
            }
        }
    }

    /** 把本次会话的聚合量累加到成长资产上（settlement 传入时一并加星） */
    fun aggregateProgress(
        progress: StudyProgress,
        state: StudyRuntimeState,
        settlement: StudySettlement?
    ): StudyProgress {
        return progress.copy(
            totalStars = progress.totalStars + (settlement?.starsTotal ?: 0),
            sessionsTotal = progress.sessionsTotal + 1,
            focusSecondsTotal = progress.focusSecondsTotal + state.focusElapsedSeconds,
            correctedItemsTotal = progress.correctedItemsTotal + completedItemCount(state),
            passedSentencesTotal = progress.passedSentencesTotal + passedSentenceCount(state)
        )
    }

    /** 作业模式：已做对与已订正的题都计入完成 */
    fun completedItemCount(state: StudyRuntimeState): Int {
        return state.homeworkPage?.items.orEmpty().count {
            it.checkState == "correct" || it.checkState == "corrected"
        }
    }

    /** 阅读模式：已通过的句数 */
    fun passedSentenceCount(state: StudyRuntimeState): Int {
        return state.readingPage?.sentences.orEmpty().count { it.status == "passed" }
    }

    private fun focusStars(focusSeconds: Int): Int {
        return (focusSeconds / 600 * STARS_PER_10_FOCUS_MINUTES)
            .coerceAtMost(MAX_FOCUS_STARS_PER_SESSION)
    }

    private fun correctedStars(completedItems: Int): Int {
        return (completedItems * STARS_PER_CORRECTED_ITEM).coerceAtMost(MAX_CORRECTED_STARS)
    }

    private fun readingStars(passedSentences: Int): Int {
        return (passedSentences * STARS_PER_PASSED_SENTENCE).coerceAtMost(MAX_READING_STARS)
    }
}
