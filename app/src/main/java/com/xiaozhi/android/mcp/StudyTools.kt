package com.xiaozhi.android.mcp

import com.xiaozhi.android.study.StudySessionManager
import com.xiaozhi.android.study.StudySessionState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

class StudyStartTool : McpTool {
    override val definition = McpToolDefinition(
        name = "study_start",
        description = "Start homework or reading study mode.",
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
        description = "Get current study mode and progress."
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
        description = "Capture the homework page for explain, check, or refresh.",
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
        description = "Get a cached homework question and its coaching context without recapturing.",
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
        description = "Capture the current book page and split it into reading sentences."
    )

    override fun call(arguments: JSONObject): Any? {
        return runBlocking { StudySessionManager.captureReadingPage() }.toJson()
    }
}

class ReadingGetContextTool : McpTool {
    override val definition = McpToolDefinition(
        name = "reading_get_context",
        description = "Get the cached book page and current reading sentence."
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
