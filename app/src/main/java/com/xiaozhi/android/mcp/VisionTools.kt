package com.xiaozhi.android.mcp

import android.content.Context
import com.xiaozhi.android.media.CameraCaptureController
import com.xiaozhi.android.media.ScreenCaptureController
import com.xiaozhi.android.service.MediaProjectionForegroundService
import org.json.JSONObject

class LatestVisionResultTool : McpTool {
    override val definition = McpToolDefinition(
        name = "get_latest_vision_result",
        description = "Read the latest cached vision result without recapturing. Use this when the user asks to read the screen recognition result."
    )

    override fun call(arguments: JSONObject): Any? {
        VisionResultStore.consumePendingSpeech()
        val result = VisionResultStore.latest()
            ?: "还没有可朗读的识别结果，请先完成一次屏幕或图片识别"
        return JSONObject()
            .put("success", true)
            .put("message", result)
            .put("result", result)
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
        if (VisionResultStore.consumePendingSpeech()) {
            VisionResultStore.latest()?.let { result ->
                return JSONObject()
                    .put("success", true)
                    .put("message", result)
                    .put("result", result)
            }
        }
        val question = arguments.optString("question").ifBlank { "描述当前屏幕内容" }
        var image = ScreenCaptureController.capture(context)
        if (image == null && ScreenCaptureController.hasPermission()) {
            MediaProjectionForegroundService.start(context)
            Thread.sleep(PROJECTION_READY_WAIT_MS)
            image = ScreenCaptureController.capture(context)
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
        private const val PROJECTION_READY_WAIT_MS = 600L
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
        val image = CameraCaptureController(context).capture()
            ?: return failure("拍照不可用，请授予相机权限")
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
