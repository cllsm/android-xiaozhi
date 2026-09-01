package com.xiaozhi.android.mcp

import org.junit.Assert.assertEquals
import org.junit.Test

class VisionResultStoreTest {

    @Test
    fun keepsLatestReadableResult() {
        VisionResultStore.update("  第一张图片的识别结果  ")
        VisionResultStore.update("第二张图片的识别结果")

        assertEquals("第二张图片的识别结果", VisionResultStore.latest())
    }
}
