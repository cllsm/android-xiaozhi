package com.xiaozhi.android.mcp

import org.json.JSONObject

/** 实时天气工具：经 open-meteo 查询指定城市的真实天气，默认北京 */
class WeatherTool : McpTool {
    override val definition = McpToolDefinition(
        name = "get_current_weather",
        description = "Get real-time weather for a city. Defaults to Beijing.",
        properties = JSONObject().put(
            "city",
            JSONObject().put("type", "string")
        )
    )

    override fun call(arguments: JSONObject): Any? {
        val city = arguments.optString("city").ifBlank { DEFAULT_CITY }
        val place = OpenMeteoWeatherClient.lookupCity(city)
            ?: return failure("暂时查不到“$city”的天气，请稍后再试或换个城市名")
        val weather = OpenMeteoWeatherClient.loadCurrentWeather(place)
            ?: return failure("天气服务暂时不可用，请稍后再试")
        return weather.put("city", place.name)
    }

    private fun failure(message: String): JSONObject {
        return JSONObject().put("success", false).put("message", message)
    }

    private companion object {
        private const val DEFAULT_CITY = "北京"
    }
}

/** 逐日预报工具：经 open-meteo 查询未来 1-7 天的真实预报，默认北京 */
class ForecastTool : McpTool {
    override val definition = McpToolDefinition(
        name = "get_forecast",
        description = "Get a real 1-7 day weather forecast for a city.",
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
        val days = arguments.optInt("days", 3).coerceIn(1, 7)
        val place = OpenMeteoWeatherClient.lookupCity(city)
            ?: return failure("暂时查不到“$city”的天气，请稍后再试或换个城市名")
        val forecast = OpenMeteoWeatherClient.loadForecast(place, days)
            ?: return failure("天气服务暂时不可用，请稍后再试")
        return JSONObject()
            .put("city", place.name)
            .put("forecast", forecast)
    }

    private fun failure(message: String): JSONObject {
        return JSONObject().put("success", false).put("message", message)
    }
}
