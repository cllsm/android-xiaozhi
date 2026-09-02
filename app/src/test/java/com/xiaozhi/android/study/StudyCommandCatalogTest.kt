package com.xiaozhi.android.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 快捷指令目录：模式 × 阶段 × 有无页 的芯片集正确性 */
class StudyCommandCatalogTest {

    private fun actions(mode: StudyMode, phase: StudyPhase, hasPage: Boolean) =
        StudyCommandCatalog.forContext(mode, phase, hasPage).map { it.action }

    @Test
    fun noneModeHasNoCommands() {
        assertTrue(StudyCommandCatalog.forContext(StudyMode.None, StudyPhase.Active, false).isEmpty())
    }

    @Test
    fun homeworkWithoutPageOffersCaptureInsteadOfHint() {
        val actions = actions(StudyMode.Homework, StudyPhase.Active, hasPage = false)
        assertTrue(QuickCommandAction.CaptureExplain in actions)
        assertTrue(QuickCommandAction.CaptureCheck !in actions)
        assertTrue(QuickCommandAction.HintCurrent !in actions)
    }

    @Test
    fun homeworkWithPageOffersHintAndCheck() {
        val actions = actions(StudyMode.Homework, StudyPhase.Active, hasPage = true)
        assertTrue(QuickCommandAction.CaptureCheck in actions)
        assertTrue(QuickCommandAction.HintCurrent in actions)
        assertTrue(QuickCommandAction.RepeatReading !in actions)
    }

    @Test
    fun readingWithPageOffersSentenceActions() {
        val actions = actions(StudyMode.Reading, StudyPhase.Active, hasPage = true)
        assertTrue(QuickCommandAction.RepeatReading in actions)
        assertTrue(QuickCommandAction.AskComprehension in actions)
        assertTrue(QuickCommandAction.NextSentence in actions)
        assertTrue(QuickCommandAction.PrevSentence in actions)
        // 提示是作业专属
        assertTrue(QuickCommandAction.HintCurrent !in actions)
    }

    @Test
    fun readingWithoutPageOffersCapture() {
        val actions = actions(StudyMode.Reading, StudyPhase.Active, hasPage = false)
        assertTrue(QuickCommandAction.CaptureExplain in actions)
        assertTrue(QuickCommandAction.RepeatReading !in actions)
    }

    @Test
    fun activePhaseAlwaysOffersFinishAndEncourage() {
        val actions = actions(StudyMode.Homework, StudyPhase.Active, hasPage = true)
        assertTrue(QuickCommandAction.FinishSession in actions)
        assertTrue(
            StudyCommandCatalog.forContext(StudyMode.Homework, StudyPhase.Active, true)
                .any { it.action == QuickCommandAction.SendText }
        )
        // Prepare 阶段还没有会话可结束
        assertTrue(QuickCommandAction.FinishSession !in actions(StudyMode.Homework, StudyPhase.Prepare, true))
    }

    @Test
    fun catalogIsCappedAtSix() {
        val commands = StudyCommandCatalog.forContext(StudyMode.Reading, StudyPhase.Active, true)
        assertEquals(6, commands.size)
    }
}
