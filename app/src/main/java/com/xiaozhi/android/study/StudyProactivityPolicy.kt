package com.xiaozhi.android.study

/**
 * AI 主动性三档参数映射（纯 JVM，可单元测试）。
 *
 * Moderate 为默认档，参数与既有行为一致（巡查 5 分钟、首轮 60 秒），
 * 家长在家长中心调节档位后，陪学巡查与闲置介入节奏随之变化。
 */
enum class ProactivityLevel {
    /** 安静：不主动打扰，只在关键节点与总结时说话 */
    Quiet,

    /** 适中：保持现状节奏，适度巡查与鼓励 */
    Moderate,

    /** 热情：更高频巡查、更快闲置介入、更多鼓励 */
    Enthusiastic
}

/** 某一主动性档位的完整行为参数 */
data class StudyProactivityProfile(
    /** 前摄巡查间隔 */
    val patrolIntervalMs: Long,
    /** 首轮巡查延迟 */
    val firstPatrolDelayMs: Long,
    /** 是否启用闲置介入 */
    val idleInterveneEnabled: Boolean,
    /** 闲置介入阈值 */
    val idleThresholdMs: Long,
    /** 是否播报实时鼓励（安静档只在总结页鼓励） */
    val praiseEnabled: Boolean,
    /** 鼓励播报最小间隔 */
    val praiseMinIntervalMs: Long
)

object StudyProactivityPolicy {
    private const val MINUTE = 60_000L

    fun forLevel(level: ProactivityLevel): StudyProactivityProfile {
        return when (level) {
            ProactivityLevel.Quiet -> StudyProactivityProfile(
                patrolIntervalMs = 10 * MINUTE,
                firstPatrolDelayMs = 90_000L,
                idleInterveneEnabled = false,
                idleThresholdMs = Long.MAX_VALUE,
                praiseEnabled = false,
                praiseMinIntervalMs = Long.MAX_VALUE
            )
            ProactivityLevel.Moderate -> StudyProactivityProfile(
                patrolIntervalMs = 5 * MINUTE,
                firstPatrolDelayMs = 60_000L,
                idleInterveneEnabled = true,
                idleThresholdMs = 10 * MINUTE,
                praiseEnabled = true,
                praiseMinIntervalMs = 2 * MINUTE
            )
            ProactivityLevel.Enthusiastic -> StudyProactivityProfile(
                patrolIntervalMs = 3 * MINUTE,
                firstPatrolDelayMs = 45_000L,
                idleInterveneEnabled = true,
                idleThresholdMs = 5 * MINUTE,
                praiseEnabled = true,
                praiseMinIntervalMs = 1 * MINUTE
            )
        }
    }

    /** 设置存储用字符串 <-> 枚举（缺省 Moderate，旧数据无痛升级） */
    fun levelFromStorage(raw: String?): ProactivityLevel {
        return when (raw) {
            "quiet" -> ProactivityLevel.Quiet
            "enthusiastic" -> ProactivityLevel.Enthusiastic
            else -> ProactivityLevel.Moderate
        }
    }

    fun levelToStorage(level: ProactivityLevel): String {
        return when (level) {
            ProactivityLevel.Quiet -> "quiet"
            ProactivityLevel.Moderate -> "moderate"
            ProactivityLevel.Enthusiastic -> "enthusiastic"
        }
    }
}
