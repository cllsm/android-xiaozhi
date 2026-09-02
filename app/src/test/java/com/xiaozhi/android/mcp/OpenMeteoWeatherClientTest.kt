package com.xiaozhi.android.mcp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class OpenMeteoWeatherClientTest {

    @Test
    fun parsesGeocodeResult() {
        val body = JSONObject()
            .put(
                "results",
                JSONArray().put(
                    JSONObject()
                        .put("name", "北京市")
                        .put("latitude", 39.9)
                        .put("longitude", 116.4)
                )
            )
            .toString()

        val place = OpenMeteoWeatherClient.parseGeocode(body)!!

        assertEquals("北京市", place.name)
        assertEquals(39.9, place.latitude, 0.001)
        assertEquals(116.4, place.longitude, 0.001)
    }

    @Test
    fun parsesGeocodeWithoutResultAsNull() {
        assertNull(OpenMeteoWeatherClient.parseGeocode("""{"results":[]}"""))
        assertNull(OpenMeteoWeatherClient.parseGeocode("""{"generationtime_ms":1}"""))
        assertNull(OpenMeteoWeatherClient.parseGeocode("not json"))
    }

    @Test
    fun parsesCurrentWeatherFields() {
        val body = JSONObject()
            .put(
                "current",
                JSONObject()
                    .put("temperature_2m", 26.6)
                    .put("relative_humidity_2m", 45)
                    .put("weather_code", 61)
                    .put("wind_speed_10m", 14.0)
                    .put("wind_direction_10m", 225.0)
            )
            .toString()

        val current = OpenMeteoWeatherClient.parseCurrent(body)!!

        assertEquals(27, current.getInt("temperature"))
        assertEquals("小雨", current.getString("condition"))
        assertEquals(45, current.getInt("humidity"))
        assertEquals("西南风 3级", current.getString("wind"))
    }

    @Test
    fun parsesForecastWithRelativeDayLabels() {
        val today = LocalDate.now()
        val body = JSONObject()
            .put(
                "daily",
                JSONObject()
                    .put(
                        "time",
                        JSONArray()
                            .put(today.toString())
                            .put(today.plusDays(1).toString())
                            .put(today.plusDays(5).toString())
                    )
                    .put("weather_code", JSONArray().put(0).put(2).put(95))
                    .put("temperature_2m_max", JSONArray().put(28.4).put(26.1).put(24.0))
                    .put("temperature_2m_min", JSONArray().put(18.2).put(17.0).put(15.5))
            )
            .toString()

        val forecast = OpenMeteoWeatherClient.parseForecast(body)!!

        assertEquals(3, forecast.length())
        assertEquals("今天", forecast.getJSONObject(0).getString("date"))
        assertEquals("晴", forecast.getJSONObject(0).getString("condition"))
        assertEquals(28, forecast.getJSONObject(0).getInt("high"))
        assertEquals(18, forecast.getJSONObject(0).getInt("low"))
        assertEquals("明天", forecast.getJSONObject(1).getString("date"))
        assertEquals("多云", forecast.getJSONObject(1).getString("condition"))
        assertEquals(
            "${today.plusDays(5).monthValue}月${today.plusDays(5).dayOfMonth}日",
            forecast.getJSONObject(2).getString("date")
        )
        assertEquals("雷阵雨", forecast.getJSONObject(2).getString("condition"))
    }

    @Test
    fun mapsWindDirectionToEightCompassPoints() {
        assertEquals("北风 1级", OpenMeteoWeatherClient.windText(5.0, 350.0))
        assertEquals("东北风 0级", OpenMeteoWeatherClient.windText(0.5, 45.0))
        assertEquals("东风 2级", OpenMeteoWeatherClient.windText(10.0, 90.0))
        assertEquals("南风 4级", OpenMeteoWeatherClient.windText(25.0, 180.0))
    }

    @Test
    fun labelsDistantDatesAsMonthDay() {
        val today = LocalDate.of(2026, 9, 2)
        assertEquals("今天", OpenMeteoWeatherClient.dayLabel("2026-09-02", today))
        assertEquals("明天", OpenMeteoWeatherClient.dayLabel("2026-09-03", today))
        assertEquals("后天", OpenMeteoWeatherClient.dayLabel("2026-09-04", today))
        assertEquals("9月7日", OpenMeteoWeatherClient.dayLabel("2026-09-07", today))
        assertEquals("10月1日", OpenMeteoWeatherClient.dayLabel("2026-10-01", today))
    }

    @Test
    fun mapsWeatherCodesToChineseText() {
        assertEquals("晴", OpenMeteoWeatherClient.weatherText(0))
        assertEquals("阴", OpenMeteoWeatherClient.weatherText(3))
        assertEquals("雾", OpenMeteoWeatherClient.weatherText(45))
        assertEquals("中雨", OpenMeteoWeatherClient.weatherText(63))
        assertEquals("大雪", OpenMeteoWeatherClient.weatherText(75))
        assertEquals("阵雪", OpenMeteoWeatherClient.weatherText(85))
        assertEquals("未知天气", OpenMeteoWeatherClient.weatherText(42))
    }
}
