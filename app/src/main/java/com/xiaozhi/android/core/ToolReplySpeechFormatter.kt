package com.xiaozhi.android.core

import org.json.JSONObject
import org.json.JSONArray

object ToolReplySpeechFormatter {

    fun format(value: Any?): String? {
        val text = readableText(value) ?: return null
        val normalized = text
            .replace(URL_PATTERN, "链接")
            .replace(WHITESPACE_PATTERN, " ")
            .trim()
        if (normalized.isEmpty()) return null
        return if (normalized.length <= MAX_LENGTH) {
            normalized
        } else {
            normalized.take(MAX_LENGTH) + "。详细内容请看屏幕"
        }
    }

    fun formatForReading(value: Any?): String? {
        val text = readableText(value) ?: return null
        val normalized = text
            .replace(WHITESPACE_PATTERN, " ")
            .trim()
        if (normalized.isEmpty()) return null
        return if (normalized.length <= MAX_READING_LENGTH) {
            normalized
        } else {
            normalized.take(MAX_READING_LENGTH) + "。剩余内容请看屏幕"
        }
    }

    private fun readableText(value: Any?): String? {
        return when (value) {
            null -> null
            is String -> value.takeIf { it.isNotBlank() }
            is Boolean -> if (value) "操作已完成" else "操作失败，请稍后再试"
            is Number -> "操作结果是 ${value}"
            is JSONObject -> jsonObjectText(value)
            is JSONArray -> jsonArrayText(value)
            else -> value.toString().takeIf { it.isNotBlank() }
        }
    }

    private fun jsonObjectText(value: JSONObject): String? {
        PREFERRED_FIELDS.forEach { field ->
            val text = value.optString(field)
            if (text.isNotBlank()) return text
        }

        if (value.has("success")) {
            return if (value.optBoolean("success")) {
                weatherText(value) ?: installedAppsText(value) ?: jsonObjectEntries(value) ?: "操作已完成"
            } else {
                value.optString("error").ifBlank { "操作失败，请稍后再试" }
            }
        }
        return weatherText(value) ?: installedAppsText(value) ?: jsonObjectEntries(value)
    }

    private fun jsonArrayText(value: JSONArray): String? {
        if (value.length() == 0) return "结果为空"
        val items = buildList {
            for (index in 0 until minOf(value.length(), 5)) {
                when (val item = value.opt(index)) {
                    is String -> add(item)
                    is Number -> add(item.toString())
                    is Boolean -> add(if (item) "是" else "否")
                    is JSONObject -> item.optString("name")
                        .ifBlank { item.optString("label") }
                        .ifBlank { item.optString("title") }
                        .takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.filter { it.isNotBlank() }
        val suffix = if (value.length() > items.size) "等${value.length()}项" else ""
        return if (items.isEmpty()) {
            "共${value.length()}项结果，详细内容请看屏幕"
        } else {
            items.joinToString("、") + suffix
        }
    }

    private fun weatherText(value: JSONObject): String? {
        if (value.has("temperature") && value.has("condition")) {
            val city = value.optString("city").ifBlank { "当前城市" }
            return buildString {
                append(city)
                append("现在")
                append(value.opt("temperature"))
                append("度，")
                append(value.optString("condition"))
                if (value.has("humidity")) {
                    append("，湿度")
                    append(value.opt("humidity"))
                    append("%")
                }
                value.optString("wind").takeIf { it.isNotBlank() }?.let {
                    append("，")
                    append(it)
                }
                if (value.has("aqi")) {
                    append("，空气质量指数")
                    append(value.opt("aqi"))
                }
            }
        }

        val forecast = value.optJSONArray("forecast") ?: return null
        if (forecast.length() == 0) return null
        val city = value.optString("city").ifBlank { "未来天气" }
        val days = buildList {
            for (index in 0 until forecast.length()) {
                val day = forecast.optJSONObject(index) ?: continue
                val date = day.optString("date")
                val condition = day.optString("condition")
                if (date.isBlank() || condition.isBlank()) continue
                add("${date}${day.opt("low")}到${day.opt("high")}度，$condition")
            }
        }
        if (days.isEmpty()) return null
        return "$city：${days.joinToString("；")}。"
    }

    private fun installedAppsText(value: JSONObject): String? {
        val apps = value.optJSONArray("apps") ?: return null
        val count = value.optInt("total_count", apps.length()).coerceAtLeast(1)
        val names = buildList {
            for (index in 0 until minOf(apps.length(), 5)) {
                val app = apps.optJSONObject(index) ?: continue
                app.optString("label")
                    .ifBlank { app.optString("name") }
                    .ifBlank { app.optString("package_name") }
                    .takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        val suffix = if (count > names.size) "等${count}个应用" else ""
        return if (names.isEmpty()) {
            "共找到${count}个应用，详细列表请看屏幕"
        } else {
            "共找到${count}个应用：" + names.joinToString("、") + suffix
        }
    }

    private fun jsonObjectEntries(value: JSONObject): String? {
        val entries = buildList {
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key in IGNORED_FIELDS) continue
                val label = FIELD_LABELS[key] ?: key
                val item = value.opt(key) ?: continue
                val itemText = when (item) {
                    is String -> item.takeIf { it.isNotBlank() } ?: continue
                    is Boolean -> if (item) "可用" else "不可用"
                    is JSONObject, is JSONArray -> continue
                    else -> item.toString()
                }
                add("$label$itemText")
            }
        }
        return entries.joinToString("，").takeIf { it.isNotBlank() }
    }

    private val PREFERRED_FIELDS = listOf(
        "message",
        "status_message",
        "response",
        "text",
        "result"
    )
    private val IGNORED_FIELDS = setOf("success", "capabilities", "serverInfo")
    private val FIELD_LABELS = mapOf(
        "city" to "城市",
        "temperature" to "温度",
        "condition" to "天气",
        "humidity" to "湿度",
        "wind" to "风力",
        "aqi" to "空气质量指数",
        "volume" to "音量",
        "muted" to "静音",
        "available" to "状态",
        "count" to "数量",
        "status" to "状态",
        "mode" to "模式",
        "payload" to "内容"
    )
    private const val MAX_LENGTH = 180
    private const val MAX_READING_LENGTH = 2_000
    private val URL_PATTERN = Regex("https?://\\S+")
    private val WHITESPACE_PATTERN = Regex("\\s+")
}
