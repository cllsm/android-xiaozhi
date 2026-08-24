package com.xiaozhi.android.mcp

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ScreenVisionPromptBuilderTest {
    @Test
    fun buildChatPrompt() {
        val prompt = ScreenVisionPromptBuilder.build("看看我和小明的聊天记录怎么回")

        assertTrue(prompt.contains("聊天对话"))
        assertTrue(prompt.contains("reply_suggestion"))
        assertTrue(prompt.contains("用户问题: 看看我和小明的聊天记录怎么回"))
    }

    @Test
    fun buildTextPrompt() {
        val prompt = ScreenVisionPromptBuilder.build("帮我读一下屏幕上的文字")

        assertTrue(prompt.contains("精确提取"))
        assertTrue(prompt.contains("full_text"))
    }

    @Test
    fun buildErrorPrompt() {
        val prompt = ScreenVisionPromptBuilder.build("这里为什么报错")

        assertTrue(prompt.contains("错误或异常状态"))
        assertTrue(prompt.contains("possible_causes"))
    }

    @Test
    fun buildOperationPrompt() {
        val prompt = ScreenVisionPromptBuilder.build("这个按钮应该怎么点")

        assertTrue(prompt.contains("可点击元素"))
        assertTrue(prompt.contains("clickable_elements"))
    }

    @Test
    fun buildCameraPrompt() {
        val prompt = ScreenVisionPromptBuilder.buildCameraPrompt("这是什么东西")

        assertTrue(prompt.contains("摄像头视觉助手"))
        assertTrue(prompt.contains("用户问题: 这是什么东西"))
    }

    @Test
    fun buildDirectPromptUsesNaturalLanguage() {
        val screenPrompt = ScreenVisionPromptBuilder.build(
            "帮我看看屏幕上是什么",
            structuredOutput = false
        )
        val cameraPrompt = ScreenVisionPromptBuilder.buildCameraPrompt(
            "这是什么东西",
            structuredOutput = false
        )

        assertTrue(screenPrompt.contains("简洁自然的中文"))
        assertFalse(screenPrompt.contains("输出格式"))
        assertTrue(cameraPrompt.contains("不要输出 JSON"))
    }
}
