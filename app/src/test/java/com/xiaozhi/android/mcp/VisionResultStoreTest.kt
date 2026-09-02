package com.xiaozhi.android.mcp

import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONObject

class VisionResultStoreTest {

    @Test
    fun keepsLatestReadableResult() {
        VisionResultStore.update("  第一张图片的识别结果  ")
        VisionResultStore.update("第二张图片的识别结果")

        assertEquals("第二张图片的识别结果", VisionResultStore.latest())
    }

    @Test
    fun latestVisionToolReturnsCachedResultForCloudSpeech() {
        val expected = "图片里是一页数学作业，第三题需要进位。"
        VisionResultStore.update(expected)

        val result = LatestVisionResultTool().call(JSONObject()) as JSONObject

        assertEquals(expected, result.getString("message"))
        assertEquals(expected, result.getString("result"))
    }
}
