package com.xiaozhi.android.study

import org.junit.Assert.assertTrue
import org.junit.Test

class StudyPromptBuildersTest {
    @Test
    fun explainPromptKeepsQuestionFocusAndSuppressesAnswer() {
        val prompt = HomeworkPromptBuilder.build(
            intent = HomeworkPromptBuilder.INTENT_EXPLAIN,
            questionNumber = 3,
            grade = "三年级"
        )

        assertTrue(prompt.contains("优先完整提取第 3 题"))
        assertTrue(prompt.contains("不要解答题目"))
        assertTrue(prompt.contains("当前年级：三年级"))
        assertTrue(prompt.contains("不要 Markdown"))
    }

    @Test
    fun checkPromptMarksUncertainAnswers() {
        val prompt = HomeworkPromptBuilder.build(HomeworkPromptBuilder.INTENT_CHECK)

        assertTrue(prompt.contains("逐题识别孩子的作答"))
        assertTrue(prompt.contains("unreadable"))
        assertTrue(prompt.contains("不要猜测"))
    }

    @Test
    fun readingPromptAsksForSentenceList() {
        val prompt = ReadingPromptBuilder.buildExtract()

        assertTrue(prompt.contains("按自然句拆分"))
        assertTrue(prompt.contains("\"sentences\""))
        assertTrue(prompt.contains("不要 Markdown"))
    }

    @Test
    fun comprehensionQuestionDoesNotAskForAnswer() {
        val prompt = ReadingPromptBuilder.buildQuestion("小种子在土里睡着了。", "一年级")

        assertTrue(prompt.contains("只基于这句话"))
        assertTrue(prompt.contains("不给答案"))
        assertTrue(prompt.contains("一年级"))
    }
}
