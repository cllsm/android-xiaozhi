package com.xiaozhi.android.mcp

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class McpEndpointClient(
    private val endpointUrl: String,
    private val listener: Listener,
    private val client: OkHttpClient = defaultClient()
) {
    interface Listener {
        fun onOpen()
        fun onJson(message: JSONObject)
        fun onClosed(code: Int, reason: String)
        fun onError(message: String)
    }

    private var webSocket: WebSocket? = null

    fun connect() {
        val request = Request.Builder()
            .url(endpointUrl)
            .build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching { JSONObject(text) }.getOrNull()
                if (message == null) {
                    Log.w(TAG, "Invalid JSON received, bytes=${text.toByteArray().size}")
                    return
                }
                listener.onJson(message)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.w(TAG, "Binary frame received, bytes=${bytes.size}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
                listener.onError(
                    throwable.message
                        ?.replace(Regex("token=[^&\\s]+"), "token=***")
                        ?: "MCP connection failed"
                )
            }
        })
    }

    fun send(message: JSONObject): Boolean {
        return webSocket?.send(message.toString()) ?: false
    }

    fun close() {
        webSocket?.close(NORMAL_CLOSE_CODE, "Android MCP stopped")
        webSocket = null
    }

    companion object {
        private const val TAG = "XiaozhiMcpEndpoint"
        private const val NORMAL_CLOSE_CODE = 1000

        private val sharedClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        fun defaultClient(): OkHttpClient = sharedClient
    }
}
