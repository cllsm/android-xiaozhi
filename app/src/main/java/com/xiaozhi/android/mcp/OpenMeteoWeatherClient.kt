package com.xiaozhi.android.mcp

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * open-meteo 天气客户端（免费、无需 API Key）：
 * 城市名经 geocoding 接口换算经纬度，再查询实时天气或逐日预报。
 * 网络与解析分离，解析函数为纯函数，便于单元测试。
 */
object OpenMeteoWeatherClient {
    data class GeoPlace(
        val name: String,
        val latitude: Double,
        val longitude: Double
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    // 城市名 → 坐标缓存，避免每次查询天气都要多跑一跳 geocoding 请求
    private val placeCache = ConcurrentHashMap<String, GeoPlace>()

    /** 按城市名查询坐标；未找到或网络失败返回 null */
    fun lookupCity(city: String): GeoPlace? {
        val trimmed = city.trim()
        if (trimmed.isEmpty()) return null
        val cacheKey = trimmed.lowercase()
        placeCache[cacheKey]?.let { return it }
        val url = "https://geocoding-api.open-meteo.com/v1/search" +
            "?name=${urlEncoded(trimmed)}&count=1&language=zh&format=json"
        val place = runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                parseGeocode(response.body?.string().orEmpty())
            }
        }.onFailure { error ->
            Log.w(TAG, "geocode failed: ${error.message ?: error.javaClass.simpleName}")
        }.getOrNull() ?: return null
        placeCache[cacheKey] = place
        return place
    }

    /** 查询实时天气，字段与 ToolReplySpeechFormatter 约定保持一致 */
    fun loadCurrentWeather(place: GeoPlace): JSONObject? {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${place.latitude}&longitude=${place.longitude}" +
            "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m" +
            "&timezone=auto"
        return runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "forecast failed, http=${response.code}")
                    return@runCatching null
                }
                parseCurrent(response.body?.string().orEmpty())
            }
        }.onFailure { error ->
            Log.w(TAG, "forecast request failed: ${error.message ?: error.javaClass.simpleName}")
        }.getOrNull()
    }

    /** 查询未来 days 天（1-7）的逐日预报 */
    fun loadForecast(place: GeoPlace, days: Int): JSONArray? {
        val boundedDays = days.coerceIn(1, 7)
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${place.latitude}&longitude=${place.longitude}" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&forecast_days=$boundedDays&timezone=auto"
        return runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "daily forecast failed, http=${response.code}")
                    return@runCatching null
                }
                parseForecast(response.body?.string().orEmpty())
            }
        }.onFailure { error ->
            Log.w(TAG, "daily forecast request failed: ${error.message ?: error.javaClass.simpleName}")
        }.getOrNull()
    }

    /** 解析 geocoding 响应，取第一个结果 */
    fun parseGeocode(body: String): GeoPlace? {
        val result = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val first = result.optJSONArray("results")?.optJSONObject(0) ?: return null
        val latitude = first.optDouble("latitude", Double.NaN)
        val longitude = first.optDouble("longitude", Double.NaN)
        if (latitude.isNaN() || longitude.isNaN()) return null
        return GeoPlace(
            name = first.optString("name").ifBlank { "该城市" },
            latitude = latitude,
            longitude = longitude
        )
    }

    /** 解析实时天气响应为播报友好的字段结构 */
    fun parseCurrent(body: String): JSONObject? {
        val result = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val current = result.optJSONObject("current") ?: return null
        val temperature = current.optDouble("temperature_2m", Double.NaN)
        if (temperature.isNaN()) return null
        val windSpeed = current.optDouble("wind_speed_10m", 0.0)
        val windDeg = current.optDouble("wind_direction_10m", 0.0)
        val weather = JSONObject()
            .put("temperature", Math.round(temperature).toInt())
            .put("condition", weatherText(current.optInt("weather_code")))
        if (current.has("relative_humidity_2m")) {
            weather.put("humidity", current.optInt("relative_humidity_2m"))
        }
        weather.put("wind", windText(windSpeed, windDeg))
        return weather
    }

    /** 解析逐日预报响应为 [{date, high, low, condition}] */
    fun parseForecast(body: String): JSONArray? {
        val result = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val daily = result.optJSONObject("daily") ?: return null
        val times = daily.optJSONArray("time") ?: return null
        val codes = daily.optJSONArray("weather_code")
        val highs = daily.optJSONArray("temperature_2m_max")
        val lows = daily.optJSONArray("temperature_2m_min")
        val today = LocalDate.now()
        val forecast = JSONArray()
        for (index in 0 until times.length()) {
            val dateIso = times.optString(index)
            val condition = codes?.optInt(index, -1) ?: -1
            if (dateIso.isBlank() || condition < 0) continue
            val day = JSONObject()
                .put("date", dayLabel(dateIso, today))
                .put("condition", weatherText(condition))
            highs?.takeIf { index < it.length() }?.let {
                day.put("high", Math.round(it.optDouble(index)).toInt())
            }
            lows?.takeIf { index < it.length() }?.let {
                day.put("low", Math.round(it.optDouble(index)).toInt())
            }
            forecast.put(day)
        }
        return if (forecast.length() > 0) forecast else null
    }

    /** WMO 天气代码转中文描述 */
    fun weatherText(code: Int): String = when (code) {
        0 -> "晴"
        1 -> "大部晴"
        2 -> "多云"
        3 -> "阴"
        45, 48 -> "雾"
        51, 53 -> "毛毛雨"
        55, 56, 57 -> "浓毛毛雨"
        61 -> "小雨"
        63 -> "中雨"
        65, 66, 67 -> "大雨"
        71 -> "小雪"
        73 -> "中雪"
        75, 77 -> "大雪"
        80 -> "小阵雨"
        81 -> "阵雨"
        82 -> "强阵雨"
        85, 86 -> "阵雪"
        95, 96, 99 -> "雷阵雨"
        else -> "未知天气"
    }

    /** 风速（km/h）与风向（角度）转“东北风 3级”样式 */
    fun windText(speedKmh: Double, directionDeg: Double): String {
        val directions = arrayOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
        val index = (((directionDeg + 22.5) % 360.0) / 45.0).toInt().let { it % 8 }
        return "${directions[index]}风 ${beaufortLevel(speedKmh)}级"
    }

    /** 蒲福风级（按 km/h 简化分档） */
    fun beaufortLevel(speedKmh: Double): Int = when {
        speedKmh < 1 -> 0
        speedKmh < 6 -> 1
        speedKmh < 12 -> 2
        speedKmh < 20 -> 3
        speedKmh < 29 -> 4
        speedKmh < 39 -> 5
        speedKmh < 50 -> 6
        speedKmh < 62 -> 7
        speedKmh < 75 -> 8
        speedKmh < 89 -> 9
        speedKmh < 103 -> 10
        speedKmh < 118 -> 11
        else -> 12
    }

    /** ISO 日期转口语化标签：近三天用今天/明天/后天，更远用“9月5日” */
    fun dayLabel(dateIso: String, today: LocalDate): String {
        val date = runCatching { LocalDate.parse(dateIso) }.getOrNull() ?: return dateIso
        return when (ChronoUnit.DAYS.between(today, date)) {
            0L -> "今天"
            1L -> "明天"
            2L -> "后天"
            else -> "${date.monthValue}月${date.dayOfMonth}日"
        }
    }

    private fun urlEncoded(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private const val TAG = "OpenMeteoWeather"
}
