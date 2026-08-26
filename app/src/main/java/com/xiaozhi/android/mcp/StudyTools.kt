package com.xiaozhi.android.mcp

import com.xiaozhi.android.study.StudySessionManager
import com.xiaozhi.android.study.StudySessionState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

class StudyStartTool : McpTool {
    override val definition = McpToolDefinition(
        name = "study_start",
        description = """
            进入陪学模式。当孩子说“陪我写作业/开始作业模式”时 mode=homework；
            说“陪我读书/开始阅读模式”时 mode=reading。
            进入后先说明摄像头只会按需拍摄作业本或书页。教学规则：引导优先，
            默认不给最终答案，用温和鼓励的儿童导师口吻。
        """.trimIndent(),
        properties = JSONObject().put(
            "mode",
            JSONObject()
                .put("type", "string")
                .put("enum", org.json.JSONArray(listOf("homework", "reading")))
        ),
        required = listOf("mode")
    )

    override fun call(arguments: JSONObject): Any? {
        val mode = when (arguments.optString("mode").lowercase()) {
            "homework", "homework_mode", "作业" -> "homework"
            "reading", "reading_mode", "阅读" -> "reading"
            else -> return failure("mode 必须是 homework 或 reading")
        }
        val result = StudySessionManager.start(
            if (mode == "homework") {
                com.xiaozhi.android.study.StudyMode.Homework
            } else {
                com.xiaozhi.android.study.StudyMode.Reading
            }
        )
        return result.toJson()
    }
}

class StudyStatusTool : McpTool {
    override val definition = McpToolDefinition(
        name = "study_status",
        description = "查询陪学模式状态。孩子问“现在学到哪/还剩多久”或切换话题前调用。"
    )

    override fun call(arguments: JSONObject): Any? {
        val state = StudySessionState.state.value
        return JSONObject()
            .put("success", true)
            .put("mode", state.mode.name.lowercase())
            .put("phase", state.phase.name.lowercase())
            .put("focus_remaining_seconds", state.focusRemainingSeconds)
            .put("observation_running", state.observationRunning)
            .put("observation_frames", state.observationFrames)
            .put(
                "observation_interval_seconds",
                state.settings.observationIntervalSeconds
            )
            .put("homework_items", state.homeworkPage?.items?.size ?: 0)
            .put("reading_sentences", state.readingPage?.sentences?.size ?: 0)
            .put("status_message", state.statusMessage)
    }
}

class HomeworkCapturePageTool : McpTool {
    override val definition = McpToolDefinition(
        name = "homework_capture_page",
        description = """
            拍摄当前作业页并缓存可见题目。孩子说“看第 3 题/这道题怎么做”时
            intent=explain 并传 question_number；说“检查作业”时 intent=check；
            说“重新看/我改好了”时 intent=refresh。
            拍摄结果只用于引导讲解，默认不得直接给最终答案。
        """.trimIndent(),
        properties = JSONObject()
            .put(
                "intent",
                JSONObject()
                    .put("type", "string")
                    .put("enum", org.json.JSONArray(listOf("explain", "check", "refresh")))
            )
            .put("question_number", JSONObject().put("type", "integer")),
        required = listOf("intent")
    )

    override fun call(arguments: JSONObject): Any? {
        val intent = arguments.optString("intent").lowercase().ifBlank { "explain" }
        val questionNumber = arguments.optInt("question_number")
            .takeIf { it > 0 }
        val result = runBlocking {
            StudySessionManager.captureHomeworkPage(intent, questionNumber)
        }
        if (!result.success) return result.toJson()

        val coaching = if (questionNumber != null) {
            StudySessionManager.homeworkContext(questionNumber).payload
        } else {
            null
        }
        val payload = result.payload ?: JSONObject()
        coaching?.let { payload.put("coaching", it) }
        return result.copy(payload = payload).toJson()
    }
}

class HomeworkGetContextTool : McpTool {
    override val definition = McpToolDefinition(
        name = "homework_get_context",
        description = """
            获取已缓存题目的题干和当前提示层级，不重新拍照。孩子追问同一题时调用；
            返回的 teaching_rules 决定这次提示应停留在读题、思路第一步还是完整分步。
            默认最终答案锁定，只有 allow_final_answer=true 才能给答案。
        """.trimIndent(),
        properties = JSONObject().put("question_number", JSONObject().put("type", "integer"))
    )

    override fun call(arguments: JSONObject): Any? {
        val questionNumber = arguments.optInt("question_number").takeIf { it > 0 }
        return StudySessionManager.homeworkContext(questionNumber).toJson()
    }
}

class ReadingCapturePageTool : McpTool {
    override val definition = McpToolDefinition(
        name = "reading_capture_page",
        description = """
            拍摄当前书页并按自然句提取文本。孩子说“看这一页/读这本书/换下一页”时调用。
            提取后先告诉孩子从第 1 句开始跟读；不要一次让孩子读完整页。
        """.trimIndent()
    )

    override fun call(arguments: JSONObject): Any? {
        return runBlocking { StudySessionManager.captureReadingPage() }.toJson()
    }
}

class ReadingGetContextTool : McpTool {
    override val definition = McpToolDefinition(
        name = "reading_get_context",
        description = """
            获取当前书页和当前跟读句，不重新拍照。孩子问词义、剧情或准备继续跟读时调用。
            若内容明显不是当前页，再调用 reading_capture_page。
        """.trimIndent()
    )

    override fun call(arguments: JSONObject): Any? {
        return StudySessionManager.readingContext().toJson()
    }
}

private fun com.xiaozhi.android.study.StudyCaptureResult.toJson(): JSONObject {
    val json = JSONObject()
        .put("success", success)
        .put("message", message)
    payload?.let { json.put("data", it) }
    return json
}

private fun failure(message: String): JSONObject {
    return JSONObject().put("success", false).put("message", message)
}
