package com.xiaozhi.android.mcp

import com.xiaozhi.android.media.StudyCompanionController
import org.json.JSONObject

/**
 * 陪学巡查 MCP 工具：抓取陪学前置摄像头当前画面，
 * 分析坐姿、专注状态、正在进行的活动与环境光线。
 * 陪学预览未开启时返回失败提示。
 */
class StudyCompanionCheckTool : McpTool {
    override val definition = McpToolDefinition(
        name = "study_companion_check",
        description = """
            陪学巡查工具。当用户询问孩子或学生的学习情况时调用，例如：
            孩子坐姿怎么样、还在认真学习吗、学习状态如何、专注吗、
            帮我看看孩子在干嘛、孩子是不是在玩手机。
            功能：抓取陪学前置摄像头当前画面，分析坐姿（端正/趴桌/歪头）、
            专注状态（专注/走神/分心）、正在进行的活动与环境光线。
        """.trimIndent(),
        properties = JSONObject().put(
            "question",
            JSONObject().put("type", "string")
        )
    )

    override fun call(arguments: JSONObject): Any? {
        val question = arguments.optString("question")
        val frame = StudyCompanionController.captureCurrentFrame()
            ?: return failure("陪学预览未开启，请先在首页打开陪学模式")
        return VisionService.analyze(
            StudyCompanionPromptBuilder.build(question),
            frame,
            "study_check.jpg"
        )
    }

    private fun failure(message: String): JSONObject {
        return JSONObject().put("success", false).put("message", message)
    }
}
