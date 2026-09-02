package com.xiaozhi.android.mcp

import android.content.Context
import com.xiaozhi.android.media.CameraCaptureController
import com.xiaozhi.android.media.ScreenCaptureController
import com.xiaozhi.android.service.MediaProjectionForegroundService
import com.xiaozhi.android.study.StudyObservationEngine
import org.json.JSONObject

class LatestVisionResultTool : McpTool {
    override val definition = McpToolDefinition(
        name = "get_latest_vision_result",
        description = "Read the latest cached screen, camera, gallery, or study vision result without recapturing. Use it when the user says 朗读图片结果, then speak the message field verbatim.",
        resultTextLimitBytes = MAX_RESULT_TEXT_BYTES
    )

    override fun call(arguments: JSONObject): Any? {
        val result = VisionResultStore.latest()
            ?: "还没有可朗读的识别结果，请先完成一次屏幕或图片识别"
        return JSONObject()
            .put("success", true)
            .put("message", result)
            .put("result", result)
    }

    private companion object {
        private const val MAX_RESULT_TEXT_BYTES = 32_768
    }
}

class ScreenshotTool(private val context: Context) : McpTool {
    override val definition = McpToolDefinition(
        name = "take_screenshot",
        description = "Capture and analyze the current phone screen, including visible text, UI state, chat, or errors.",
        properties = JSONObject()
            .put(
                "question",
                JSONObject().put("type", "string")
            )
            .put(
                "display",
                JSONObject().put("type", "string")
            ),
        required = listOf("question")
    )

    override fun call(arguments: JSONObject): Any? {
        val question = arguments.optString("question").ifBlank { "描述当前屏幕内容" }
        var image = ScreenCaptureController.capture(context)
        if (image == null && ScreenCaptureController.hasPermission()) {
            MediaProjectionForegroundService.start(context)
            // 轮询等待投影前台服务就绪，成功即停，避免固定长睡眠占用请求线程
            for (attempt in 0 until PROJECTION_READY_ATTEMPTS) {
                Thread.sleep(PROJECTION_READY_WAIT_MS)
                image = ScreenCaptureController.capture(context)
                if (image != null) break
            }
        }
        image ?: return failure("截屏不可用，请先在首页重新授予屏幕识别权限")
        return VisionService.analyze(
            ScreenVisionPromptBuilder.build(question),
            image,
            "screenshot.jpg"
        )
    }

    private fun failure(message: String): JSONObject {
        return JSONObject().put("success", false).put("message", message)
    }

    private companion object {
        private const val PROJECTION_READY_WAIT_MS = 200L
        private const val PROJECTION_READY_ATTEMPTS = 4
    }
}

class CameraTool(private val context: Context) : McpTool {
    override val definition = McpToolDefinition(
        name = "take_photo",
        description = "Capture a photo with the phone camera and answer a question about it.",
        properties = JSONObject().put(
            "question",
            JSONObject().put("type", "string")
        ),
        required = listOf("question")
    )

    override fun call(arguments: JSONObject): Any? {
        val question = arguments.optString("question").ifBlank { "描述照片内容" }
        // 陪学固定机位观察运行时复用其相机会话取帧，避免重开相机把观察会话挤下线
        val image = if (StudyObservationEngine.isRunning) {
            StudyObservationEngine.captureFrame()
        } else {
            CameraCaptureController(context).capture()
        } ?: return failure("拍照不可用，请授予相机权限")
        return VisionService.analyze(
            ScreenVisionPromptBuilder.buildCameraPrompt(question),
            image,
            "camera.jpg"
        )
    }

    private fun failure(message: String): JSONObject {
        return JSONObject().put("success", false).put("message", message)
    }
}
