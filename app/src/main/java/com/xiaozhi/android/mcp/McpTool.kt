package com.xiaozhi.android.mcp

import org.json.JSONArray
import org.json.JSONObject

data class McpToolDefinition(
    val name: String,
    val description: String,
    val properties: JSONObject = JSONObject(),
    val required: List<String> = emptyList()
) {
    fun toJson(): JSONObject {
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", properties)
        if (required.isNotEmpty()) {
            schema.put("required", JSONArray(required))
        }
        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put("inputSchema", schema)
    }
}

interface McpTool {
    val definition: McpToolDefinition

    fun call(arguments: JSONObject): Any?
}

fun Any?.toJsonValue(): Any? {
    return when (this) {
        null -> null
        is Boolean, is Int, is Long, is Double, is Float, is String,
        is JSONObject, is JSONArray -> this
        is Map<*, *> -> JSONObject(this)
        is Iterable<*> -> JSONArray(toList())
        else -> toString()
    }
}
