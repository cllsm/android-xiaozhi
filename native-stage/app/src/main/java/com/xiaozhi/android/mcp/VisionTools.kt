package com.xiaozhi.android.mcp

import android.content.Context
import com.xiaozhi.android.media.CameraCaptureController
import com.xiaozhi.android.media.ScreenCaptureController
import com.xiaozhi.android.service.MediaProjectionForegroundService
import org.json.JSONObject

class ScreenshotTool(private val context: Context) : McpTool {
    override val definition = McpToolDefinition(
        name = "take_screenshot",
        description = """
            截取当前屏幕并使用视觉AI分析屏幕内容。
            调用时机（满足任意一条即调用）：
            - 用户提到截屏、截图、看看屏幕、看看这个
            - 用户想了解屏幕上的内容：在干嘛、显示什么、写了什么、这是哪
            - 聊天相关：聊天记录、怎么回、说了什么、对方回什么、群里消息
            - 帮助类：帮我看看、帮我读一下、念一下、上面写的啥
            - 问题类：报错了、出问题、怎么回事、卡住了、打不开
            - 操作类：怎么操作、怎么用、点哪、在哪设置、下一步
            - 任何隐含需要"看到屏幕才能回答"的问题

            参数说明：
            - question: 告诉视觉AI应该关注什么。请根据用户意图构造清晰的指令，不要直接传用户原话。
            - display: 显示器选择（可选，Android 端忽略）

            工具会自动识别场景（聊天分析/文字提取/错误诊断/操作指引/屏幕理解），
            并使用对应的提示词模板进行分析，确保结果精准。
        """.trimIndent(),
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
        description = """
            拍照识图工具。当用户提到：拍照、拍张照、照张相、看一下、看看、帮我看、这是什么、识别、
            识图、看图、图片、照片、帮我瞧瞧时调用本工具。
            功能：拍照并分析图片内容，回答用户关于图片的问题。
            使用场景：
            1. 用户要求拍照看东西（例如：帮我看看这是什么、拍个照、看看前面是什么）
            2. 物体/场景识别（这是什么东西、帮我认一下、识别一下）
            3. 文字识别OCR（读一下上面的字、提取文字、这上面写的什么）
            4. 图片问答（图里有几个人、这个是什么颜色、上面有什么内容）

            参数说明：question 是用户想了解的关于照片的问题。
            示例：帮我看看这是什么 / 拍个照 / 看看前面 / take a photo / what is this。
        """.trimIndent(),
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
