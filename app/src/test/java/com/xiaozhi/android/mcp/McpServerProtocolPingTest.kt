package com.xiaozhi.android.mcp

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class McpServerProtocolPingTest {

    @Test
    fun answersJsonRpcPingWithEmptyResult() = runBlocking {
        val request = JSONObject()
            .put("jsonrpc", McpServerProtocol.JSONRPC_VERSION)
            .put("id", 2)
            .put("method", "ping")

        val response = McpServerProtocol(emptyList()).handle(request)!!

        assertEquals(McpServerProtocol.JSONRPC_VERSION, response.getString("jsonrpc"))
        assertEquals(2, response.getInt("id"))
        assertEquals("{}", response.getJSONObject("result").toString())
        assertFalse(response.has("error"))
    }
}
