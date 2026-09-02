package com.xiaozhi.android.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

class McpServerProtocol(
    private val tools: List<McpTool>,
    private val toolTimeoutMillis: Long = DEFAULT_TOOL_TIMEOUT_MS,
    private val toolsPageSizeBytes: Int = DEFAULT_TOOLS_PAGE_SIZE_BYTES,
    private val toolDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onInitialize: (capabilities: JSONObject) -> Unit = {},
    private val onToolResult: (
        result: Any?,
        isError: Boolean,
        toolName: String
    ) -> Unit = { _, _, _ -> }
) {
    suspend fun handle(payload: JSONObject): JSONObject? {
        if (payload.optString("jsonrpc") != JSONRPC_VERSION) return null
        val id = payload.opt("id") ?: return null
        val method = payload.optString("method")
        val params = payload.optJSONObject("params") ?: JSONObject()

        return when (method) {
            METHOD_INITIALIZE -> resultResponse(id, initializeResult(params))
            METHOD_PING -> resultResponse(id, JSONObject())
            METHOD_TOOLS_LIST -> resultResponse(id, toolsPage(params))
            METHOD_TOOLS_CALL -> callTool(id, params)
            else -> errorResponse(
                id = id,
                code = METHOD_NOT_FOUND,
                message = "Method not found: $method"
            )
        }
    }

    private fun initializeResult(params: JSONObject): JSONObject {
        val capabilities = params.optJSONObject("capabilities") ?: JSONObject()
        onInitialize(capabilities)
        val protocolVersion = params.optString("protocolVersion")
            .takeIf { it.isNotBlank() }
            ?: PROTOCOL_VERSION
        return JSONObject()
            .put("protocolVersion", protocolVersion)
            .put("capabilities", JSONObject().put("tools", JSONObject()))
            .put(
                "serverInfo",
                JSONObject()
                    .put("name", SERVER_NAME)
                    .put("version", SERVER_VERSION)
            )
    }

    private fun toolsPage(params: JSONObject): JSONObject {
        val cursor = params.optString("cursor")
        var found = cursor.isBlank()
        val definitions = JSONArray()
        var pageSizeBytes = 0
        var nextCursor: String? = null

        for (tool in tools) {
            if (!found) {
                if (tool.definition.name != cursor) continue
                found = true
            }
            val definition = tool.definition.toJson()
            val definitionBytes = definition.toString().toByteArray(Charsets.UTF_8).size
            if (definitions.length() > 0 &&
                pageSizeBytes + definitionBytes > toolsPageSizeBytes
            ) {
                nextCursor = tool.definition.name
                break
            }
            definitions.put(definition)
            pageSizeBytes += definitionBytes
        }

        return JSONObject()
            .put("tools", definitions)
            .putOpt("nextCursor", nextCursor)
    }

    private suspend fun callTool(id: Any, params: JSONObject): JSONObject {
        val name = params.optString("name")
        val tool = tools.firstOrNull { it.definition.name == name }
            ?: return errorResponse(id, INVALID_PARAMS, "Unknown tool: $name")
        val arguments = params.optJSONObject("arguments") ?: JSONObject()

        val result = withTimeoutOrNull(toolTimeoutMillis) {
            coroutineScope {
                async(toolDispatcher) {
                    try {
                        Result.success(tool.call(arguments))
                    } catch (error: CancellationException) {
                        if (!currentCoroutineContext().isActive) throw error
                        Result.failure(error)
                    } catch (error: Throwable) {
                        Result.failure(error)
                    }
                }.await()
            }
        }
        return when {
            result == null -> {
                val message = "Tool timed out after ${toolTimeoutMillis}ms"
                onToolResult(message, true, name)
                resultResponse(id, errorToolCallResult(message))
            }
            result.isFailure -> {
                val message = result.exceptionOrNull()
                    ?.message
                    ?.takeIf { it.isNotBlank() }
                    ?: "Tool execution failed"
                onToolResult(message, true, name)
                resultResponse(id, errorToolCallResult(message))
            }
            else -> {
                val rawResult = result.getOrThrow()
                onToolResult(rawResult, false, name)
                resultResponse(
                    id,
                    toolCallResult(rawResult, tool.definition.resultTextLimitBytes)
                )
            }
        }
    }

    companion object {
        const val JSONRPC_VERSION = "2.0"
        const val PROTOCOL_VERSION = "2024-11-05"

        fun resultResponse(id: Any, result: Any?): JSONObject {
            return JSONObject()
                .put("jsonrpc", JSONRPC_VERSION)
                .put("id", id)
                .put("result", result)
        }

        fun errorResponse(id: Any, code: Int, message: String): JSONObject {
            return JSONObject()
                .put("jsonrpc", JSONRPC_VERSION)
                .put("id", id)
                .put(
                    "error",
                    JSONObject()
                        .put("code", code)
                        .put("message", boundUtf8(message, MAX_ERROR_BYTES))
                )
        }

        fun emptyToolsList(): JSONObject {
            return JSONObject().put("tools", JSONArray())
        }

        fun toolCallResult(result: Any?): JSONObject {
            return toolCallResult(result, isError = false)
        }

        fun toolCallResult(result: Any?, maxTextBytes: Int): JSONObject {
            return toolCallResult(result, isError = false, maxTextBytes = maxTextBytes)
        }

        fun errorToolCallResult(message: String): JSONObject {
            return toolCallResult(message, isError = true)
        }

        private fun toolCallResult(
            result: Any?,
            isError: Boolean,
            maxTextBytes: Int = MAX_TOOL_TEXT_BYTES
        ): JSONObject {
            val value = result.toJsonValue()
            val text = when (value) {
                null -> ""
                is String -> value
                else -> value.toString()
            }
            return JSONObject()
                .put(
                    "content",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "text")
                            .put("text", boundToolText(text, maxTextBytes))
                    )
                )
                .put("isError", isError)
        }

        fun responseByteSize(response: JSONObject): Int {
            return response.toString().toByteArray(Charsets.UTF_8).size
        }

        private fun boundToolText(text: String, maxTextBytes: Int): String {
            return if (jsonEscapedUtf8Bytes(text) <= maxTextBytes) {
                text
            } else {
                boundTextForJsonBudget(text, maxTextBytes - TRUNCATION_NOTE_BYTES) +
                    TRUNCATION_NOTE
            }
        }

        private fun boundTextForJsonBudget(text: String, maxEscapedBytes: Int): String {
            if (maxEscapedBytes <= 0) return ""
            if (jsonEscapedUtf8Bytes(text) <= maxEscapedBytes) return text

            var end = 0
            while (end < text.length) {
                val next = text.offsetByCodePoints(end, 1)
                if (jsonEscapedUtf8Bytes(text.substring(0, next)) > maxEscapedBytes) break
                end = next
            }
            return text.substring(0, end)
        }

        private fun jsonEscapedUtf8Bytes(text: String): Int {
            var size = 0
            var index = 0
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                size += when {
                    codePoint == '"'.code || codePoint == '\\'.code -> 2
                    codePoint in 0x00..0x1F -> 6
                    codePoint <= 0x7F -> 1
                    codePoint <= 0x7FF -> 2
                    codePoint <= 0xFFFF -> 3
                    else -> 4
                }
                index += Character.charCount(codePoint)
            }
            return size
        }

        private fun boundUtf8(text: String, maxBytes: Int): String {
            if (maxBytes <= 0) return ""
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size <= maxBytes) return text
            var end = maxBytes
            while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
            return String(bytes.copyOf(end), Charsets.UTF_8)
        }

        private const val METHOD_INITIALIZE = "initialize"
        private const val METHOD_PING = "ping"
        private const val METHOD_TOOLS_LIST = "tools/list"
        private const val METHOD_TOOLS_CALL = "tools/call"
        private const val SERVER_NAME = "android-xiaozhi"
        private const val SERVER_VERSION = "1.0.0"
        private const val METHOD_NOT_FOUND = -32601
        private const val INVALID_PARAMS = -32602
        private const val INTERNAL_ERROR = -32603
        private const val DEFAULT_TOOL_TIMEOUT_MS = 8_500L
        private const val DEFAULT_TOOLS_PAGE_SIZE_BYTES = 3_500
        private const val MAX_TOOL_TEXT_BYTES = 760
        private const val MAX_ERROR_BYTES = 240
        private val TRUNCATION_NOTE = "；结果过长已截断，完整内容请看手机"
        private val TRUNCATION_NOTE_BYTES = jsonEscapedUtf8Bytes(TRUNCATION_NOTE)
    }
}
