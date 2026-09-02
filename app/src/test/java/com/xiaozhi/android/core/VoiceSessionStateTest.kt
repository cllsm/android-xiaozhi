package com.xiaozhi.android.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceSessionStateTest {
    @After
    fun tearDown() {
        VoiceSessionState.clearChat()
    }

    @Test
    fun appendChatKeepsImagePaths() {
        VoiceSessionState.appendChat(
            text = "描述这张图片",
            fromUser = true,
            imagePath = "/data/chat/full.jpg",
            thumbnailPath = "/data/chat/thumb.jpg"
        )

        val message = VoiceSessionState.chat.value.single()
        assertEquals("/data/chat/full.jpg", message.imagePath)
        assertEquals("/data/chat/thumb.jpg", message.thumbnailPath)
    }

    @Test
    fun appendChatKeepsDistinctImagesWithSamePrompt() {
        val prompt = "描述这张图片"
        VoiceSessionState.appendChat(prompt, fromUser = true, imagePath = "/a.jpg")
        VoiceSessionState.appendChat(prompt, fromUser = true, imagePath = "/b.jpg")

        assertEquals(2, VoiceSessionState.chat.value.size)
    }
}
