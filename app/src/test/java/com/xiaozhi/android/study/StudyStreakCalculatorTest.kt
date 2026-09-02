package com.xiaozhi.android.study

import org.junit.Assert.assertEquals
import org.junit.Test

/** 连续学习天数：同日多次、跨日 +1、断签重置 */
class StudyStreakCalculatorTest {

    @Test
    fun sameDayDoesNotIncrease() {
        assertEquals(5, StudyStreakCalculator.nextStreak("2026-09-02", 5, "2026-09-02"))
        assertEquals(0, StudyStreakCalculator.nextStreak("2026-09-02", 0, "2026-09-02"))
    }

    @Test
    fun consecutiveDayIncrements() {
        assertEquals(6, StudyStreakCalculator.nextStreak("2026-09-01", 5, "2026-09-02"))
        assertEquals(2, StudyStreakCalculator.nextStreak("2026-02-28", 1, "2026-03-01"))
    }

    @Test
    fun gapResetsToOne() {
        // 断签两天：重置为 1
        assertEquals(1, StudyStreakCalculator.nextStreak("2026-08-31", 5, "2026-09-02"))
        assertEquals(1, StudyStreakCalculator.nextStreak("2026-09-01", 0, "2026-09-03"))
    }

    @Test
    fun firstSessionStartsAtOne() {
        assertEquals(1, StudyStreakCalculator.nextStreak("", 0, "2026-09-02"))
    }

    @Test
    fun blankTodayKeepsCurrent() {
        assertEquals(5, StudyStreakCalculator.nextStreak("2026-09-01", 5, ""))
    }
}
