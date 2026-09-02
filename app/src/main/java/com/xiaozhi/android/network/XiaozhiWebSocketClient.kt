package com.xiaozhi.android.network

import android.util.Log
import com.xiaozhi.android.core.DeviceIdentity
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class XiaozhiWebSocketClient(
    private val url: String,
    private val token: String,
    private val identity: DeviceIdentity,
    private val listener: Listener,
    private val client: OkHttpClient = defaultClient()
) {

    interface Listener {
        fun onOpen()
        fun onServerHello(sessionId: String?)
        fun onJson(message: JSONObject)
        fun onAudio(audio: ByteArray)
        fun onClosed(code: Int, reason: String)
        fun onError(message: String)
    }

    private var webSocket: WebSocket? = null
    private var helloReceived = false
    private var sessionId: String? = null

    fun connect() {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Protocol-Version", PROTOCOL_VERSION)
            .header("Device-Id", identity.deviceId)
            .header("Client-Id", identity.clientId)
            .build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sendHello()
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = try {
                    JSONObject(text)
                } catch (_: Exception) {
                    listener.onError("收到无效 JSON 消息")
                    return
                }
                if (json.optString("type") == TYPE_HELLO) {
                    sessionId = json.optString("session_id").takeIf { it.isNotBlank() }
                    helloReceived = true
                    Log.i(TAG, "WebSocket server hello received, session=${sessionId.orEmpty()}")
                    listener.onServerHello(sessionId)
                } else {
                    val messageType = json.optString("type")
                    if (messageType == "alert") {
                        val alertText = json.optString("message")
                            .ifBlank { json.optString("error") }
                            .ifBlank { json.optString("reason") }
                        Log.w(TAG, "WebSocket alert received: $alertText")
                    }
                    Log.i(TAG, "WebSocket JSON received, type=$messageType")
                    listener.onJson(json)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                listener.onAudio(bytes.toByteArray())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onError(t.message ?: "WebSocket 连接失败")
            }
        })
    }

    fun sendStartListening(mode: String = LISTENING_MODE_MANUAL): Boolean {
        val delivered = sendJson(
            JSONObject()
                .putOpt("session_id", sessionId)
                .put("type", TYPE_LISTEN)
                .put("state", "start")
                .put("mode", mode)
        )
        Log.i(TAG, "WebSocket start listening sent, delivered=$delivered, mode=$mode")
        return delivered
    }

    fun sendWakeWordDetected(wakeWord: String): Boolean {
        return sendText(wakeWord)
    }

    fun sendText(text: String): Boolean {
        val delivered = sendJson(
            JSONObject()
                .putOpt("session_id", sessionId)
                .put("type", TYPE_LISTEN)
                .put("state", "detect")
                .put("text", text)
        )
        Log.i(TAG, "WebSocket text sent, delivered=$delivered, length=${text.length}")
        return delivered
    }

    fun sendStopListening(): Boolean {
        return sendJson(
            JSONObject()
                .putOpt("session_id", sessionId)
                .put("type", TYPE_LISTEN)
                .put("state", "stop")
        )
    }

    fun sendAbortSpeaking(): Boolean {
        return sendJson(JSONObject().putOpt("session_id", sessionId).put("type", TYPE_ABORT))
    }

    fun sendAudio(audio: ByteArray): Boolean {
        val socket = webSocket ?: return false
        if (!helloReceived) return false
        return socket.send(audio.toByteString())
    }

    fun sendMcpPayload(payload: JSONObject): Boolean {
        val delivered = sendJson(
            JSONObject()
                .putOpt("session_id", sessionId)
                .put("type", "mcp")
                .put("payload", payload)
        )
        Log.i(TAG, "WebSocket MCP payload delivered=$delivered, method=${payload.optString("method")}")
        return delivered
    }

    fun close() {
        webSocket?.close(NORMAL_CLOSE_CODE, "Client stopped")
        webSocket = null
    }

    private fun sendHello() {
        val audioParams = JSONObject()
            .put("format", AUDIO_FORMAT)
            .put("sample_rate", INPUT_SAMPLE_RATE)
            .put("channels", CHANNELS)
            .put("frame_duration", FRAME_DURATION_MS)
        val hello = JSONObject()
            .put("type", TYPE_HELLO)
            .put("version", 1)
            .put("features", JSONObject().put("mcp", true))
            .put("transport", TRANSPORT)
            .put("audio_params", audioParams)
        webSocket?.send(hello.toString())
        Log.i(TAG, "WebSocket client hello sent, mcp=true")
    }

    private fun sendJson(message: JSONObject): Boolean {
        val socket = webSocket ?: return false
        if (!helloReceived) return false
        return socket.send(message.toString())
    }

    companion object {
        private const val TAG = "XiaozhiWebSocket"
        private const val PROTOCOL_VERSION = "1"
        private const val TYPE_HELLO = "hello"
        private const val TYPE_LISTEN = "listen"
        private const val TYPE_ABORT = "abort"
        private const val TRANSPORT = "websocket"
        private const val AUDIO_FORMAT = "opus"
        private const val INPUT_SAMPLE_RATE = 16_000
        private const val CHANNELS = 1
        private const val FRAME_DURATION_MS = 20
        private const val LISTENING_MODE_MANUAL = "manual"
        private const val NORMAL_CLOSE_CODE = 1000

        private val sharedClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        fun defaultClient(): OkHttpClient = sharedClient
    }
}
