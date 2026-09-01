package com.xiaozhi.android.mcp

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyCompanionPromptBuilderTest {
    @Test
    fun buildContainsCoreAnalysisFields() {
        val prompt = StudyCompanionPromptBuilder.build()

        assertTrue(prompt.contains("前置摄像头"))
        assertTrue(prompt.contains("坐姿"))
        assertTrue(prompt.contains("专注状态"))
        assertTrue(prompt.contains("focus_score"))
        assertTrue(prompt.contains("不要猜测"))
    }

    @Test
    fun buildWithQuestionAppendsUserFocus() {
        val prompt = StudyCompanionPromptBuilder.build("孩子是不是在玩手机")

        assertTrue(prompt.contains("用户额外关注的问题：孩子是不是在玩手机"))
    }

    @Test
    fun buildWithoutQuestionHasNoUserFocusSection() {
        val prompt = StudyCompanionPromptBuilder.build("  ")

        assertFalse(prompt.contains("用户额外关注的问题"))
    }

    @Test
    fun summarizeCompressesAnalysisToJsonFields() {
        val analysis = JSONObject()
            .put("posture", "趴桌")
            .put("focus_state", "走神发呆")
            .put("activity", "玩东西")
            .put("lighting", "充足")
            .put("focus_score", 2)
            .put("brief", "孩子趴在桌上摆弄橡皮")

        val summary = StudyCompanionPromptBuilder.summarize(analysis)

        assertTrue(summary.contains("坐姿:趴桌"))
        assertTrue(summary.contains("状态:走神发呆"))
        assertTrue(summary.contains("专注度:2/5"))
        assertTrue(summary.contains("画面:孩子趴在桌上摆弄橡皮"))
        assertTrue(summary.length <= 200)
    }

    @Test
    fun summarizeFallsBackToRawTextForUnstructuredInput() {
        val analysis = JSONObject().put("response", "一段无法结构化的分析文本")

        val summary = StudyCompanionPromptBuilder.summarize(analysis)

        assertTrue(summary.contains("一段无法结构化的分析文本"))
    }

    @Test
    fun summarizeUnwrapsNestedResponseObject() {
        val analysis = JSONObject().put(
            "success", true
        ).put(
            "response",
            JSONObject()
                .put("posture", "端正")
                .put("focus_state", "专注学习")
                .put("focus_score", 4)
        )

        val summary = StudyCompanionPromptBuilder.summarize(analysis)

        assertTrue(summary.contains("坐姿:端正"))
        assertTrue(summary.contains("状态:专注学习"))
        assertTrue(summary.contains("专注度:4/5"))
    }

    @Test
    fun patrolLeadGuidesModelToSpeakBriefly() {
        val lead = StudyCompanionPromptBuilder.PATROL_LEAD

        assertTrue(lead.contains("陪学巡查"))
        assertTrue(lead.contains("一两句"))
        assertTrue(lead.contains("不要输出 JSON"))
    }
}
