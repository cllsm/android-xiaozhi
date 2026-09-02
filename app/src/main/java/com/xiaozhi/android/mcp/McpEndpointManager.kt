package com.xiaozhi.android.mcp

import android.content.Context
import android.util.Log
import com.xiaozhi.android.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class McpEndpointManager(
    context: Context,
    scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val onToolResult: (
        result: Any?,
        isError: Boolean,
        toolName: String
    ) -> Unit
) {
    private val appContext = context.applicationContext
    private val managerScope = CoroutineScope(
        SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Default
    )
    private val started = AtomicBoolean(false)
    private var watcherJob: Job? = null

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val requestDispatcher = Dispatchers.IO.limitedParallelism(1)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        watcherJob = managerScope.launch {
            settingsRepository.settings
                .map { it.mcpEndpointUrl.trim() }
                .distinctUntilChanged()
                .collectLatest { endpointUrl ->
                    if (endpointUrl.isNotBlank()) runConnectionLoop(endpointUrl)
                }
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        watcherJob?.cancel()
        watcherJob = null
        managerScope.cancel()
    }

    private suspend fun runConnectionLoop(endpointUrl: String) {
        var reconnectDelay = INITIAL_RECONNECT_DELAY_MS
        while (true) {
            val stayedOpen = try {
                connectOnce(endpointUrl)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "MCP endpoint error: ${error.javaClass.simpleName}")
                false
            }

            reconnectDelay = if (stayedOpen) {
                INITIAL_RECONNECT_DELAY_MS
            } else {
                (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            }
            Log.i(TAG, "MCP endpoint reconnect scheduled, delayMs=$reconnectDelay")
            delay(reconnectDelay)
        }
    }

    private suspend fun connectOnce(endpointUrl: String): Boolean {
        val closed = CompletableDeferred<Unit>()
        val settings = settingsRepository.settings.first()
        val parentJob = currentCoroutineContext().job
        val requestScope = CoroutineScope(SupervisorJob(parentJob) + requestDispatcher)
        val protocol = McpServerProtocol(
            tools = McpToolRegistry.create(appContext, settings),
            onInitialize = { capabilities -> VisionService.configure(capabilities) },
            onToolResult = onToolResult
        )
        var client: McpEndpointClient? = null

        try {
            client = McpEndpointClient(
                endpointUrl = endpointUrl,
                listener = object : McpEndpointClient.Listener {
                    override fun onOpen() {
                        Log.i(TAG, "MCP endpoint opened")
                    }

                    override fun onJson(message: JSONObject) {
                        val bytes = message.toString().toByteArray(Charsets.UTF_8).size
                        val method = message.optString("method")
                        val id = message.opt("id")
                        val toolName = message.optJSONObject("params")?.optString("name").orEmpty()
                        Log.i(
                            TAG,
                            "MCP request, method=$method, id=$id, " +
                                "tool=${if (toolName.isBlank()) "-" else toolName}, bytes=$bytes"
                        )
                        requestScope.launch {
                            val startedAt = System.currentTimeMillis()
                            val response = runCatching { protocol.handle(message) }.getOrNull()
                            if (response == null) {
                                Log.w(TAG, "MCP request ignored, method=$method")
                                return@launch
                            }
                            val responseBytes = McpServerProtocol.responseByteSize(response)
                            val delivered = client?.send(response) ?: false
                            Log.i(
                                TAG,
                                "MCP response, method=$method, id=$id, delivered=$delivered, " +
                                    "bytes=$responseBytes, " +
                                    "elapsedMs=${System.currentTimeMillis() - startedAt}"
                            )
                        }
                    }

                    override fun onClosed(code: Int, reason: String) {
                        Log.i(TAG, "MCP endpoint closed, code=$code")
                        closed.complete(Unit)
                    }

                    override fun onError(message: String) {
                        Log.w(TAG, "MCP endpoint error: $message")
                        closed.complete(Unit)
                    }
                }
            )
            client.connect()

            val openedAt = System.currentTimeMillis()
            closed.await()
            return System.currentTimeMillis() - openedAt >= MIN_CONNECTED_DURATION_MS
        } finally {
            requestScope.cancel()
            client?.close()
        }
    }

    private companion object {
        private const val TAG = "XiaozhiMcpEndpoint"
        private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val MIN_CONNECTED_DURATION_MS = 30_000L
    }
}
