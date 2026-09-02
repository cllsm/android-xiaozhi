package com.xiaozhi.android.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI 主动性三档映射与存储转换 */
class StudyProactivityPolicyTest {

    @Test
    fun quietDisablesIdleAndPraise() {
        val profile = StudyProactivityPolicy.forLevel(ProactivityLevel.Quiet)
        assertFalse(profile.idleInterveneEnabled)
        assertFalse(profile.praiseEnabled)
        assertEquals(10 * 60_000L, profile.patrolIntervalMs)
        assertEquals(90_000L, profile.firstPatrolDelayMs)
    }

    @Test
    fun moderateMatchesLegacyBehavior() {
        // 适中档等于既有节奏：巡查 5 分钟、首轮 60 秒、闲置 10 分钟、鼓励 2 分钟
        val profile = StudyProactivityPolicy.forLevel(ProactivityLevel.Moderate)
        assertEquals(5 * 60_000L, profile.patrolIntervalMs)
        assertEquals(60_000L, profile.firstPatrolDelayMs)
        assertTrue(profile.idleInterveneEnabled)
        assertEquals(10 * 60_000L, profile.idleThresholdMs)
        assertTrue(profile.praiseEnabled)
        assertEquals(2 * 60_000L, profile.praiseMinIntervalMs)
    }

    @Test
    fun enthusiasticIsMoreActiveThanModerate() {
        val profile = StudyProactivityPolicy.forLevel(ProactivityLevel.Enthusiastic)
        assertEquals(3 * 60_000L, profile.patrolIntervalMs)
        assertEquals(45_000L, profile.firstPatrolDelayMs)
        assertEquals(5 * 60_000L, profile.idleThresholdMs)
        assertEquals(1 * 60_000L, profile.praiseMinIntervalMs)
    }

    @Test
    fun storageRoundTripFallsBackToModerate() {
        assertEquals(
            ProactivityLevel.Quiet,
            StudyProactivityPolicy.levelFromStorage(
                StudyProactivityPolicy.levelToStorage(ProactivityLevel.Quiet)
            )
        )
        assertEquals(
            ProactivityLevel.Enthusiastic,
            StudyProactivityPolicy.levelFromStorage(
                StudyProactivityPolicy.levelToStorage(ProactivityLevel.Enthusiastic)
            )
        )
        // 旧数据/未知值缺省 Moderate
        assertEquals(ProactivityLevel.Moderate, StudyProactivityPolicy.levelFromStorage(null))
        assertEquals(ProactivityLevel.Moderate, StudyProactivityPolicy.levelFromStorage("junk"))
    }
}
