package com.xiaozhi.android.mcp

import org.json.JSONArray
import org.json.JSONObject

class WeatherTool : McpTool {
    override val definition = McpToolDefinition(
        name = "get_weather",
        description = "Get current weather for a city. Defaults to Beijing.",
        properties = JSONObject().put(
            "city",
            JSONObject().put("type", "string")
        )
    )

    override fun call(arguments: JSONObject): Any? {
        val city = arguments.optString("city").ifBlank { DEFAULT_CITY }
        return JSONObject()
            .put("city", city)
            .put("temperature", 25)
            .put("condition", "晴朗")
            .put("humidity", 45)
            .put("wind", "东北风 3级")
            .put("aqi", 52)
    }

    private companion object {
        private const val DEFAULT_CITY = "北京"
    }
}

class ForecastTool : McpTool {
    override val definition = McpToolDefinition(
        name = "get_forecast",
        description = "Get a 1-7 day weather forecast for a city.",
        properties = JSONObject()
            .put(
                "city",
                JSONObject().put("type", "string")
            )
            .put(
                "days",
                JSONObject()
                    .put("type", "integer")
                    .put("minimum", 1)
                    .put("maximum", 7)
            )
    )

    override fun call(arguments: JSONObject): Any? {
        val city = arguments.optString("city").ifBlank { "北京" }
        val days = arguments.optInt("days", 3).coerceIn(1, forecast.size)
        return JSONObject()
            .put("city", city)
            .put("forecast", JSONArray(forecast.take(days)))
    }

    private companion object {
        private val forecast = listOf(
            JSONObject().put("date", "今天").put("high", 28).put("low", 18).put("condition", "晴"),
            JSONObject().put("date", "明天").put("high", 26).put("low", 17).put("condition", "多云"),
            JSONObject().put("date", "后天").put("high", 24).put("low", 15).put("condition", "小雨")
        )
    }
}
