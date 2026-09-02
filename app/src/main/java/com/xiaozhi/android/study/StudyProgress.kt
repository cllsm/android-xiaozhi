package com.xiaozhi.android.study

/**
 * 陪学成长资产与游戏化数据模型（纯 JVM，可单元测试）。
 *
 * 会话记录是"日志"（可清理），成长资产是"状态"（永不清理），
 * 因此本模型由独立的 StudyProgressRepository 持久化，
 * 全期累计（专注总时长/订正总数/通过句总数）不依赖记录列表，
 * 记录清理后勋章判定依然正确。
 */

/** 勋章标识 */
enum class StudyAchievementId {
    FirstSession,
    Streak3,
    Streak7,
    Streak14,
    Focus30Single,
    Focus5HoursTotal,
    Reading50Sentences,
    Corrected20Items,
    EarlyBird,
    Stars100
}

/** 勋章静态定义：图标用字符串 key，UI 层自行映射 ImageVector，保持本文件纯 JVM */
data class StudyAchievementDefinition(
    val id: StudyAchievementId,
    val title: String,
    val description: String,
    val iconKey: String
)

/** 全部勋章定义（顺序即勋章墙展示顺序） */
val STUDY_ACHIEVEMENTS: List<StudyAchievementDefinition> = listOf(
    StudyAchievementDefinition(StudyAchievementId.FirstSession, "初次启航", "完成第一次陪学", "flag"),
    StudyAchievementDefinition(StudyAchievementId.Streak3, "三日之约", "连续学习 3 天", "fire"),
    StudyAchievementDefinition(StudyAchievementId.Streak7, "七日同行", "连续学习 7 天", "fire"),
    StudyAchievementDefinition(StudyAchievementId.Streak14, "十四天小冠军", "连续学习 14 天", "trophy"),
    StudyAchievementDefinition(StudyAchievementId.Focus30Single, "深度专注", "单次专注满 30 分钟", "timer"),
    StudyAchievementDefinition(StudyAchievementId.Focus5HoursTotal, "专注大师", "累计专注 5 小时", "timer"),
    StudyAchievementDefinition(StudyAchievementId.Reading50Sentences, "阅读之星", "累计通过 50 句跟读", "book"),
    StudyAchievementDefinition(StudyAchievementId.Corrected20Items, "订正小能手", "累计订正 20 道题", "check"),
    StudyAchievementDefinition(StudyAchievementId.EarlyBird, "早起的鸟儿", "清晨 8 点前完成一次陪学", "sun"),
    StudyAchievementDefinition(StudyAchievementId.Stars100, "百星达人", "累计获得 100 颗星", "star")
)

/** 全局成长资产（DataStore 单 key JSON 持久化） */
data class StudyProgress(
    val totalStars: Int = 0,
    val sessionsTotal: Int = 0,
    val focusSecondsTotal: Long = 0L,
    val correctedItemsTotal: Int = 0,
    val passedSentencesTotal: Int = 0,
    /** 勋章 id → 解锁时间戳 */
    val unlockedAchievements: Map<String, Long> = emptyMap(),
    val streakDays: Int = 0,
    /** 最近一次学习的日期，LocalDate ISO 格式（如 "2026-09-02"） */
    val streakLastDateKey: String = "",
    /** 开场引导是否已完成 */
    val onboardingDone: Boolean = false
)

/** 每日任务类型 */
enum class DailyTaskType {
    /** 累计专注分钟数 */
    FocusMinutes,
    /** 学习内容数：订正题数 + 通过句数 */
    ProgressItems,
    /** 完成一次完整会话 */
    FinishSession
}

/** 单条每日任务 */
data class DailyTask(
    val type: DailyTaskType,
    val target: Int,
    val progress: Int = 0,
    val starReward: Int = 2,
    /** 非空表示已打卡完成 */
    val completedAt: Long? = null
) {
    val done: Boolean get() = completedAt != null || progress >= target
}

/** 当日任务板：dateKey 不符即整体重建，无跨日残留 */
data class DailyTaskBoard(
    val dateKey: String,
    val tasks: List<DailyTask> = emptyList()
) {
    companion object {
        /** 生成某日的默认任务板：固定三项 */
        fun create(dateKey: String): DailyTaskBoard {
            return DailyTaskBoard(
                dateKey = dateKey,
                tasks = listOf(
                    DailyTask(type = DailyTaskType.FocusMinutes, target = 20),
                    DailyTask(type = DailyTaskType.ProgressItems, target = 3),
                    DailyTask(type = DailyTaskType.FinishSession, target = 1)
                )
            )
        }

        /**
         * 推进任务进度并打卡：已完成的任务不再变化。
         * 返回推进后的新任务板（纯函数，不落库）。
         */
        fun advance(
            board: DailyTaskBoard,
            focusMinutesDelta: Int,
            progressItemsDelta: Int,
            sessionFinished: Boolean,
            now: Long
        ): DailyTaskBoard {
            return board.copy(
                tasks = board.tasks.map { task ->
                    if (task.completedAt != null) {
                        task
                    } else {
                        val next = when (task.type) {
                            DailyTaskType.FocusMinutes ->
                                task.progress + focusMinutesDelta.coerceAtLeast(0)
                            DailyTaskType.ProgressItems ->
                                task.progress + progressItemsDelta.coerceAtLeast(0)
                            DailyTaskType.FinishSession ->
                                task.progress + if (sessionFinished) 1 else 0
                        }
                        val reached = next >= task.target
                        task.copy(
                            progress = next.coerceAtMost(task.target),
                            completedAt = if (reached) task.completedAt ?: now else null
                        )
                    }
                }
            )
        }
    }
}

/** 连续学习天数计算（纯函数，时区由调用方决定，测试注入日期串即可） */
object StudyStreakCalculator {
    /**
     * 会话结算时调用：lastDateKey == todayKey 当日多次不重复加；
     * 上次是昨天则 +1；更早或为空则重置为 1。
     */
    fun nextStreak(lastDateKey: String, currentStreak: Int, todayKey: String): Int {
        if (todayKey.isBlank()) return currentStreak.coerceAtLeast(0)
        if (lastDateKey == todayKey) return currentStreak.coerceAtLeast(0)
        if (lastDateKey.isBlank()) return 1
        val yesterdayKey = runCatching {
            java.time.LocalDate.parse(todayKey).minusDays(1).toString()
        }.getOrNull()
        return if (yesterdayKey != null && lastDateKey == yesterdayKey) {
            currentStreak.coerceAtLeast(0) + 1
        } else {
            1
        }
    }
}

/** 会话数据明细（结算后供总结页展示） */
data class StudySessionDetail(
    val durationSeconds: Int,
    val focusSeconds: Int,
    val completedItems: Int,
    val passedSentences: Int,
    val uploadedFrames: Int,
    /** 会话结束时刻的小时数（0-23），用于"早起的鸟儿"勋章 */
    val endedHour: Int
)

/** 一次会话的星星结算结果（唯一入账依据） */
data class StudySettlement(
    val starsFocus: Int,
    val starsCorrected: Int,
    val starsReading: Int,
    val starsTasks: Int,
    val starsTotal: Int,
    val newlyUnlocked: List<StudyAchievementId>,
    val completedTasks: List<DailyTask>,
    val streakDays: Int,
    val detail: StudySessionDetail,
    /** 入账后的成长资产快照（不含勋章解锁时间戳，由仓库落库时补齐） */
    val aggregatedProgress: StudyProgress = StudyProgress()
)
