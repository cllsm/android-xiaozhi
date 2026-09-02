package com.xiaozhi.android.core

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolReplySpeechFormatterTest {

    @Test
    fun formatsHumanReadableString() {
        assertEquals("正在播放歌曲", ToolReplySpeechFormatter.format("  正在播放歌曲\n"))
    }

    @Test
    fun prefersMessageFieldInJsonObject() {
        val result = JSONObject()
            .put("success", false)
            .put("message", "没有找到可播放的歌曲")

        assertEquals("没有找到可播放的歌曲", ToolReplySpeechFormatter.format(result))
    }

    @Test
    fun formatsBooleanResult() {
        assertEquals("操作已完成", ToolReplySpeechFormatter.format(true))
        assertEquals("操作失败，请稍后再试", ToolReplySpeechFormatter.format(false))
    }

    @Test
    fun usesResponseFieldForVisionResult() {
        val result = JSONObject()
            .put("success", true)
            .put("response", "图片里是一页数学作业")

        assertEquals("图片里是一页数学作业", ToolReplySpeechFormatter.format(result))
    }

    @Test
    fun formatsStructuredWeatherResult() {
        val result = JSONObject()
            .put("city", "北京")
            .put("temperature", 25)
            .put("condition", "晴朗")
            .put("humidity", 45)
            .put("wind", "东北风 3级")
            .put("aqi", 52)

        assertEquals(
            "北京现在25度，晴朗，湿度45%，东北风 3级，空气质量指数52",
            ToolReplySpeechFormatter.format(result)
        )
    }

    @Test
    fun formatsStructuredForecastResult() {
        val forecast = JSONArray()
            .put(
                JSONObject()
                    .put("date", "今天")
                    .put("high", 28)
                    .put("low", 18)
                    .put("condition", "晴")
            )
        val result = JSONObject()
            .put("city", "北京")
            .put("forecast", forecast)

        assertEquals(
            "北京：今天18到28度，晴。",
            ToolReplySpeechFormatter.format(result)
        )
    }

    @Test
    fun formatsInstalledAppsResultWithoutReadingWholeList() {
        val apps = JSONArray()
            .put(JSONObject().put("label", "设置").put("package_name", "com.android.settings"))
            .put(JSONObject().put("label", "相机").put("package_name", "com.android.camera"))
        val result = JSONObject()
            .put("success", true)
            .put("total_count", 12)
            .put("apps", apps)

        assertEquals(
            "共找到12个应用：设置、相机等12个应用",
            ToolReplySpeechFormatter.format(result)
        )
    }

    @Test
    fun limitsSpeechAndHidesUrls() {
        val result = "https://example.com/audio.mp3 " + "很".repeat(200)
        val formatted = ToolReplySpeechFormatter.format(result)

        assertEquals(true, formatted!!.startsWith("链接 很"))
        assertEquals(true, formatted.endsWith("详细内容请看屏幕"))
    }

    @Test
    fun keepsLongReadingContentInsteadOfSummarizingIt() {
        val content = "春天来了。".repeat(80)
        val formatted = ToolReplySpeechFormatter.formatForReading(content)

        assertEquals(content, formatted)
    }

    @Test
    fun marksVeryLongReadingContentAsTruncated() {
        val content = "春天来了。".repeat(500)
        val formatted = ToolReplySpeechFormatter.formatForReading(content)

        assertEquals(true, formatted!!.startsWith("春天来了。"))
        assertEquals(true, formatted.endsWith("剩余内容请看屏幕"))
    }

    @Test
    fun readingFormatKeepsCompleteShortContent() {
        assertEquals(
            "屏幕上是：三加五等于八",
            ToolReplySpeechFormatter.formatForReading(" 屏幕上是：三加五等于八 ")
        )
    }
}
