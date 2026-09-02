package com.xiaozhi.android.mcp

import org.json.JSONObject

class LatestUserTextTool : McpTool {
    override val definition = McpToolDefinition(
        name = "get_latest_user_text",
        description = "Read the latest long text submitted by the user. " +
            "Use this when the user says 读取用户长文本.",
        resultTextLimitBytes = MAX_RESULT_TEXT_BYTES
    )

    override fun call(arguments: JSONObject): Any? {
        val text = UserTextStore.latest()
            ?: return JSONObject()
                .put("success", false)
                .put("message", "还没有收到用户长文本")
        return JSONObject()
            .put("success", true)
            .put("message", text)
            .put("text", text)
    }

    private companion object {
        private const val MAX_RESULT_TEXT_BYTES = 32_768
    }
}
