package com.xiaozhi.android.mcp

import android.content.Context
import android.util.Log
import com.xiaozhi.android.core.SettingsState
import com.xiaozhi.android.media.NativeMusicController
import org.json.JSONArray
import org.json.JSONObject

class McpDispatcher(
    context: Context,
    settings: SettingsState,
    private val send: (JSONObject) -> Unit
) {
    private val volumeTool = VolumeTool(context)
    private val tools: List<McpTool> = buildList {
        add(volumeTool)
        add(GetVolumeTool(volumeTool))
        add(VolumeStatusTool(volumeTool))
        add(AppLauncherTool(context))
        add(InstalledAppsTool(context))
        add(ScreenshotTool(context))
        add(CameraTool(context))
        add(StudyStartTool())
        add(StudyStatusTool())
        add(HomeworkCapturePageTool())
        add(HomeworkGetContextTool())
        add(ReadingCapturePageTool())
        add(ReadingGetContextTool())
        add(WeatherTool())
        add(ForecastTool())
        if (settings.musicEnabled) {
            NativeMusicController.configure(settings)
            add(MusicSearchTool())
            add(MusicPauseTool())
            add(MusicResumeTool())
            add(MusicStopTool())
            add(MusicSeekTool())
            add(MusicPreviousTool())
            add(MusicNextTool())
            add(MusicLyricsTool())
            add(LocalPlaylistTool(context))
        }
    }

    fun handle(payload: JSONObject) {
        val method = payload.optString("method")
        if (method.startsWith("notifications/")) {
            Log.i(TAG, "MCP notification received, method=$method")
            return
        }
        if (payload.optString("jsonrpc") != JSONRPC_VERSION) return
        val id = payload.opt("id") ?: return
        val params = payload.optJSONObject("params") ?: JSONObject()
        Log.i(
            TAG,
            "MCP request received, method=$method, id=$id, " +
                "protocolVersion=${params.optString("protocolVersion")}"
        )

        when (method) {
            METHOD_INITIALIZE -> {
                VisionService.configure(params.optJSONObject("capabilities") ?: JSONObject())
                val requestedProtocol = params.optString("protocolVersion")
                    .takeIf { it.isNotBlank() } ?: PROTOCOL_VERSION
                val result = JSONObject()
                    .put("protocolVersion", requestedProtocol)
                    .put(
                        "capabilities",
                        JSONObject().put("tools", JSONObject())
                    )
                    .put(
                        "serverInfo",
                        JSONObject()
                            .put("name", "android-xiaozhi")
                            .put("version", "0.1.0")
                    )
                replyResult(id, result)
            }

            METHOD_TOOLS_LIST -> {
                val page = toolsPage(payload.optJSONObject("params") ?: JSONObject())
                Log.i(
                    TAG,
                    "MCP tools page: count=${page.optJSONArray("tools")?.length() ?: 0}, " +
                        "nextCursor=${page.optString("nextCursor")}"
                )
                replyResult(id, page)
            }

            METHOD_TOOLS_CALL -> {
                val name = params.optString("name")
                val arguments = params.optJSONObject("arguments") ?: JSONObject()
                val tool = tools.firstOrNull { it.definition.name == name }
                if (tool == null) {
                    Log.w(TAG, "Unknown MCP tool request: $name")
                    replyError(id, "Unknown tool: $name")
                    return
                }
                Log.i(TAG, "Calling MCP tool $name, arguments=$arguments")
                runCatching { tool.call(arguments) }
                    .onSuccess { result ->
                        val formattedResult = toolCallResult(result)
                        Log.i(TAG, "MCP tool $name succeeded: $formattedResult")
                        replyResult(id, formattedResult)
                    }
                    .onFailure { error ->
                        Log.w(TAG, "MCP tool $name failed", error)
                        replyError(id, error.message ?: "Tool execution failed")
                    }
            }

            else -> if (!method.startsWith("notifications/")) {
                replyError(id, "Method not implemented: $method")
            }
        }
    }

    private fun replyResult(id: Any, result: Any?) {
        Log.i(TAG, "MCP result sending, id=$id, result=$result")
        send(
            JSONObject()
                .put("type", "mcp")
                .put(
                    "payload",
                    JSONObject()
                        .put("jsonrpc", JSONRPC_VERSION)
                        .put("id", id)
                        .put("result", result)
                )
        )
    }

    private fun toolsPage(params: JSONObject): JSONObject {
        val cursor = params.optString("cursor")
        val definitions = mutableListOf<JSONObject>()
        var totalSize = 0
        var nextCursor = ""
        var found = cursor.isBlank()

        for (tool in tools) {
            if (!found) {
                if (tool.definition.name != cursor) continue
                found = true
            }
            val definition = tool.definition.toJson()
            val size = definition.toString().length + JSON_OVERHEAD
            if (definitions.isNotEmpty() && totalSize + size > MAX_PAGE_SIZE) {
                nextCursor = tool.definition.name
                break
            }
            definitions.add(definition)
            totalSize += size
        }

        return JSONObject()
            .put("tools", JSONArray(definitions))
            .putOpt("nextCursor", nextCursor.ifBlank { null })
    }

    private fun toolCallResult(result: Any?): JSONObject {
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
                        .put("text", text)
                )
            )
            .put("isError", false)
    }

    private fun replyError(id: Any, message: String) {
        send(
            JSONObject()
                .put("type", "mcp")
                .put(
                    "payload",
                    JSONObject()
                        .put("jsonrpc", JSONRPC_VERSION)
                        .put("id", id)
                        .put(
                            "error",
                            JSONObject()
                                .put("code", INTERNAL_ERROR)
                                .put("message", message)
                        )
                )
        )
    }

    private companion object {
        private const val TAG = "XiaozhiMcp"
        const val JSONRPC_VERSION = "2.0"
        const val PROTOCOL_VERSION = "2024-11-05"
        const val METHOD_INITIALIZE = "initialize"
        const val METHOD_TOOLS_LIST = "tools/list"
        const val METHOD_TOOLS_CALL = "tools/call"
        const val INTERNAL_ERROR = -32603
        const val JSON_OVERHEAD = 100
        const val MAX_PAGE_SIZE = 8_000
    }
}
