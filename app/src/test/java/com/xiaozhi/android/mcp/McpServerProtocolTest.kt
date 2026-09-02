package com.xiaozhi.android.mcp

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerProtocolTest {

    @Test
    fun initializesWithCloudProtocolAndVisionCapabilities() {
        var configured: JSONObject? = null
        val protocol = McpServerProtocol(
            tools = emptyList(),
            onInitialize = { configured = it }
        )
        val capabilities = JSONObject().put(
            "vision",
            JSONObject().put("url", "https://vision.example.com")
        )
        val request = request(
            method = "initialize",
            params = JSONObject()
                .put("protocolVersion", "2025-06-18")
                .put("capabilities", capabilities)
        )

        val response = runBlocking { protocol.handle(request) }!!

        assertEquals("2025-06-18", response.getJSONObject("result").getString("protocolVersion"))
        assertEquals(capabilities.toString(), configured!!.toString())
    }

    @Test
    fun fallsBackToLocalProtocolVersionWhenUnsupported() = runBlocking {
        val protocol = McpServerProtocol(emptyList())
        val request = request(
            method = "initialize",
            params = JSONObject().put("protocolVersion", "1999-01-01")
        )

        val response = protocol.handle(request)!!

        assertEquals(
            McpServerProtocol.PROTOCOL_VERSION,
            response.getJSONObject("result").getString("protocolVersion")
        )
    }

    @Test
    fun ignoresNotifications() = runBlocking {
        val response = McpServerProtocol(emptyList()).handle(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("method", "notifications/initialized")
        )

        assertNull(response)
    }

    @Test
    fun pagesToolsByUtf8BudgetAndCursor() = runBlocking {
        val tools = listOf(
            tool("one", "一".repeat(80)),
            tool("two", "二".repeat(80))
        )
        val protocol = McpServerProtocol(
            tools = tools,
            toolsPageSizeBytes = 400
        )

        val firstPage = protocol.handle(request("tools/list"))!!
            .getJSONObject("result")
        val secondPage = protocol.handle(
            request("tools/list", JSONObject().put("cursor", "two"))
        )!!.getJSONObject("result")

        assertEquals("two", firstPage.optString("nextCursor"))
        assertEquals(1, firstPage.getJSONArray("tools").length())
        assertEquals("one", firstPage.getJSONArray("tools").getJSONObject(0).getString("name"))
        assertFalse(secondPage.has("nextCursor"))
        assertEquals(1, secondPage.getJSONArray("tools").length())
    }

    @Test
    fun boundsToolResultAfterJsonEscaping() {
        val result = McpServerProtocol.toolCallResult("\"\\\u0001".repeat(300))

        val text = result.getJSONArray("content")
            .getJSONObject(0)
            .getString("text")

        assertTrue(text.endsWith("；结果过长已截断，完整内容请看手机"))
        assertTrue(McpServerProtocol.responseByteSize(result) <= 1024)
    }

    @Test
    fun returnsToolExecutionFailureAsCallToolResult() = runBlocking {
        val protocol = McpServerProtocol(
            tools = listOf(tool("broken", description = "broken", result = Exception("boom")))
                .map { brokenTool ->
                    object : McpTool by brokenTool {
                        override fun call(arguments: JSONObject): Any? =
                            throw IllegalArgumentException("boom")
                    }
                }
        )

        val response = protocol.handle(
            request(
                "tools/call",
                JSONObject().put("name", "broken").put("arguments", JSONObject())
            )
        )!!

        assertTrue(response.has("result"))
        assertFalse(response.has("error"))
        assertTrue(response.getJSONObject("result").getBoolean("isError"))
        assertTrue(
            response.getJSONObject("result").getJSONArray("content")
                .getJSONObject(0).getString("text").contains("boom")
        )
    }

    @Test
    fun returnsTimeoutAsCallToolResult() = runBlocking {
        val protocol = McpServerProtocol(
            tools = listOf(
                tool(
                    "slow",
                    description = "slow",
                    result = Unit,
                    call = { "done" }
                ).let { slowTool ->
                    object : McpTool by slowTool {
                        override fun call(arguments: JSONObject): Any? {
                            Thread.sleep(80)
                            return "done"
                        }
                    }
                }
            ),
            toolTimeoutMillis = 10
        )

        val response = protocol.handle(
            request("tools/call", JSONObject().put("name", "slow"))
        )!!
        val result = response.getJSONObject("result")

        assertTrue(result.getBoolean("isError"))
        assertTrue(
            result.getJSONArray("content").getJSONObject(0).getString("text")
                .contains("timed out")
        )
    }

    @Test
    fun rejectsUnknownToolAsJsonRpcError() = runBlocking {
        val response = McpServerProtocol(emptyList()).handle(
            request("tools/call", JSONObject().put("name", "missing"))
        )!!

        assertEquals(-32602, response.getJSONObject("error").getInt("code"))
    }

    @Test
    fun rejectsUnknownCursorAsInvalidParams() = runBlocking {
        val protocol = McpServerProtocol(tools = listOf(tool("one", "第一")))
        val response = protocol.handle(
            request("tools/list", JSONObject().put("cursor", "missing"))
        )!!

        assertEquals(-32602, response.getJSONObject("error").getInt("code"))
    }

    private fun request(method: String, params: JSONObject = JSONObject()): JSONObject {
        return JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 17)
            .put("method", method)
            .put("params", params)
    }

    private fun tool(
        name: String,
        description: String,
        result: Any? = "ok",
        call: (JSONObject) -> Any? = { result }
    ): McpTool {
        return object : McpTool {
            override val definition = McpToolDefinition(
                name = name,
                description = description,
                properties = JSONObject().put(
                    "value",
                    JSONObject().put("type", JSONArray(listOf("string", "number")))
                )
            )

            override fun call(arguments: JSONObject): Any? = call.invoke(arguments)
        }
    }
}
