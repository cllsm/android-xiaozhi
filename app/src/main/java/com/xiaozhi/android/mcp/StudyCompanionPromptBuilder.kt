package com.xiaozhi.android.mcp

/**
 * 陪学巡查提示词构建器：由前置摄像头画面分析孩子的坐姿、专注状态与环境，
 * 输出精简的结构化结论，供云端大模型组织语言后经 TTS 播报。
 */
object StudyCompanionPromptBuilder {

    fun build(question: String = ""): String {
        val userQuestion = question.trim()
        return """
            你是儿童学习陪伴助手，正在通过设备前置摄像头观察学习的孩子。

            请分析画面中的以下内容：
            1. 坐姿：端正 / 趴桌 / 歪头 / 后仰 / 不在画面中
            2. 专注状态：专注学习 / 走神发呆 / 玩耍分心 / 在玩别的物品 / 疑似睡着 / 不在座位
            3. 正在进行的活动：写字 / 看书 / 用电子设备 / 玩东西 / 其他
            4. 环境光线：充足 / 偏暗 / 过亮

            请严格按以下 JSON 格式输出（不要输出其他内容）：
            {"posture":"坐姿","focus_state":"专注状态","activity":"活动","lighting":"光线","focus_score":1到5的整数,"brief":"一句话画面描述"}

            注意：
            - 只描述画面中可见的内容，不要猜测画面外的情况
            - 画面中没有孩子时，各描述字段填"不在画面中"，focus_score 填 0
            - focus_score：5=非常专注，1=完全分心
            ${if (userQuestion.isBlank()) "" else "用户额外关注的问题：$userQuestion"}
        """.trimIndent()
    }

    /**
     * 将视觉分析的 JSON 结论压缩成一行简短中文摘要，
     * 作为巡查报告送给云端大模型（同时用于聊天记录展示，保持简洁）。
     * 兼容两种服务端返回：扁平字段，或 {"success":true,"response":{...}} 嵌套结构。
     */
    fun summarize(analysis: org.json.JSONObject): String {
        val data = analysis.optJSONObject("response") ?: analysis
        val posture = data.optString("posture")
        val focus = data.optString("focus_state")
        val activity = data.optString("activity")
        val score = data.optInt("focus_score", -1)
        val brief = data.optString("brief")
        val fields = buildList {
            if (posture.isNotBlank()) add("坐姿:$posture")
            if (focus.isNotBlank()) add("状态:$focus")
            if (activity.isNotBlank()) add("活动:$activity")
            if (score >= 0) add("专注度:$score/5")
            if (brief.isNotBlank()) add("画面:$brief")
        }
        if (fields.isNotEmpty()) {
            return fields.joinToString("；").take(MAX_SUMMARY_LENGTH)
        }
        // 结构化字段缺失时，退回 response 字符串或原始文本，截断避免过长
        val fallback = analysis.optString("response").ifBlank { data.toString() }
        return fallback.take(MAX_SUMMARY_LENGTH)
    }

    /** 巡查报告的导语：引导云端大模型生成简短温和的语音提醒 */
    const val PATROL_LEAD = "【陪学巡查】以下是前置摄像头画面的观察结果，" +
        "请用一两句温和、简短的话提醒孩子（情况严重时提醒家长），不要输出 JSON 或多余说明："

    private const val MAX_SUMMARY_LENGTH = 200
}
