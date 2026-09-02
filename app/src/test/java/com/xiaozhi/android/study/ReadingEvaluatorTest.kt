package com.xiaozhi.android.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingEvaluatorTest {
    @Test
    fun perfectReadingPasses() {
        val evaluation = ReadingEvaluator.evaluate(
            expected = "春天来了，柳树发芽了。",
            actual = "春天来了，柳树发芽了。"
        )

        assertTrue(evaluation.passed)
        assertEquals(1f, evaluation.accuracy, 0.001f)
    }

    @Test
    fun punctuationAndSpacesAreIgnored() {
        val evaluation = ReadingEvaluator.evaluate(
            expected = "春天来了，柳树发芽了。",
            actual = "春天来了 柳树发芽了"
        )

        assertTrue(evaluation.passed)
    }

    @Test
    fun sameSoundSubstitutionStillPasses() {
        val evaluation = ReadingEvaluator.evaluate(
            expected = "明天的太阳",
            actual = "明天的太阳"
        )

        assertTrue(evaluation.passed)
        val homophone = ReadingEvaluator.evaluate(
            expected = "明天的太阳",
            actual = "名天的太阳"
        )
        assertTrue(homophone.passed)
        assertEquals(1, homophone.substitutions.size)
        assertTrue(homophone.substitutions.single().sameSound)
    }

    @Test
    fun missingTextFailsAndReportsMissingCharacters() {
        val evaluation = ReadingEvaluator.evaluate(
            expected = "柳树发芽了",
            actual = "柳树芽了"
        )

        assertFalse(evaluation.passed)
        assertEquals("发", evaluation.missingText)
    }

    @Test
    fun wrongCharacterFails() {
        val evaluation = ReadingEvaluator.evaluate(
            expected = "春天来了",
            actual = "冬天来了"
        )

        assertFalse(evaluation.passed)
        val substitution = evaluation.substitutions.single()
        assertEquals("春", substitution.expected)
        assertEquals("冬", substitution.actual)
        assertFalse(substitution.sameSound)
    }

    @Test
    fun emptyActualFails() {
        val evaluation = ReadingEvaluator.evaluate("春天来了", "")

        assertFalse(evaluation.passed)
        assertEquals(0f, evaluation.accuracy, 0.001f)
    }
}
