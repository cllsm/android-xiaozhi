package com.xiaozhi.android.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserTextStoreTest {

    @Test
    fun keepsLatestTextAndConsumesRequestEchoOnce() {
        UserTextStore.update(" 第一段长文本 ")
        UserTextStore.update("第二段长文本")
        UserTextStore.markPendingEcho()

        assertEquals("第二段长文本", UserTextStore.latest())
        assertTrue(UserTextStore.consumeEcho(UserTextStore.REQUEST_TEXT))
        assertFalse(UserTextStore.consumeEcho(UserTextStore.REQUEST_TEXT))
        assertFalse(UserTextStore.consumeEcho("其他文本"))
    }

    @Test
    fun latestUserTextToolReturnsCachedText() {
        UserTextStore.update("这是一段需要小智理解的长文本")

        val result = LatestUserTextTool().call(JSONObject()) as JSONObject

        assertTrue(result.getBoolean("success"))
        assertEquals("这是一段需要小智理解的长文本", result.getString("text"))
    }
}
