package com.xiaozhi.android.mcp

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerProtocolLargeResultTest {

    @Test
    fun usesToolSpecificResultBudget() = runBlocking {
        val longText = "长".repeat(600)
        val tool = object : McpTool {
            override val definition = McpToolDefinition(
                name = "large_text",
                description = "large text",
                resultTextLimitBytes = 4_096
            )
            override fun call(arguments: JSONObject): Any? = longText
        }

        val response = McpServerProtocol(listOf(tool)).handle(
            JSONObject()
                .put("jsonrpc", McpServerProtocol.JSONRPC_VERSION)
                .put("id", 1)
                .put("method", "tools/call")
                .put(
                    "params",
                    JSONObject().put("name", "large_text").put("arguments", JSONObject())
                )
        )!!
        val text = response.getJSONObject("result")
            .getJSONArray("content")
            .getJSONObject(0)
            .getString("text")

        assertFalse(text.contains("结果过长已截断"))
        assertTrue(McpServerProtocol.responseByteSize(response) <= 4_096)
    }
}
