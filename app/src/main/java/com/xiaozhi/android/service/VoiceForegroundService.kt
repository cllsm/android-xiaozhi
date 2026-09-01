package com.xiaozhi.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.app.PendingIntent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.xiaozhi.android.MainActivity
import com.xiaozhi.android.audio.AudioInputEngine
import com.xiaozhi.android.audio.AudioOutputEngine
import com.xiaozhi.android.core.ConnectionStatus
import com.xiaozhi.android.core.ConnectionRecoveryPolicy
import com.xiaozhi.android.core.DeviceState
import com.xiaozhi.android.core.SettingsState
import com.xiaozhi.android.core.ToolReplySpeechFormatter
import com.xiaozhi.android.core.VoiceSessionState
import com.xiaozhi.android.core.UserErrorMessages
import com.xiaozhi.android.data.DeviceIdentityRepository
import com.xiaozhi.android.data.SettingsRepository
import com.xiaozhi.android.media.MusicIntentParser
import com.xiaozhi.android.media.NativeMusicController
import com.xiaozhi.android.media.VoiceMusicInterruptionPolicy
import com.xiaozhi.android.mcp.AppLauncherTool
import com.xiaozhi.android.mcp.McpEndpointManager
import com.xiaozhi.android.mcp.McpServerProtocol
import com.xiaozhi.android.mcp.VisionResultStore
import com.xiaozhi.android.mcp.VisionService
import com.xiaozhi.android.network.OtaClient
import com.xiaozhi.android.network.XiaozhiWebSocketClient
import com.xiaozhi.android.study.ReadingEvaluator
import com.xiaozhi.android.study.StudyMode
import com.xiaozhi.android.study.StudyObservationEngine
import com.xiaozhi.android.study.StudySessionManager
import com.xiaozhi.android.study.StudySessionState
import com.xiaozhi.android.wake.SherpaWakeWordEngine
import org.json.JSONObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentLinkedQueue

class VoiceForegroundService : LifecycleService() {

    private data class HiddenSpeechRequest(
        val text: String,
        val expiresAt: Long
    )

    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val localTtsSpeaker by lazy { LocalTtsSpeaker(this) }
    private val activeMcpToolResultHandler = AtomicReference<(Any?, Boolean) -> Unit>()
    private val active = AtomicBoolean(false)
    private var connectionJob: kotlinx.coroutines.Job? = null
    private val recoveryRequests = Channel<RecoveryTrigger>(Channel.CONFLATED)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val connectionRequests = ConcurrentLinkedQueue<Unit>()
    private val pendingWakeWords = ConcurrentLinkedQueue<String>()
    private val standbyListenerLock = Any()
    private var standbyAudioInput: AudioInputEngine? = null
    private var standbyWakeWordEngine: SherpaWakeWordEngine? = null
    private val deviceWakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON ||
                intent?.action == Intent.ACTION_USER_PRESENT
            ) {
                requestConnection()
            }
            }
        }
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var identityRepository: DeviceIdentityRepository
    private val otaClient = OtaClient()

    private val mcpEndpointManager by lazy {
        McpEndpointManager(
            context = this,
            scope = lifecycleScope,
            settingsRepository = settingsRepository
        ) { result, isError ->
            activeMcpToolResultHandler.get()?.invoke(result, isError)
        }
    }

    override fun onCreate() {
        super.onCreate()
        companionActive.set(true)
        activeService.set(this)
        applicationContextHolder.set(applicationContext)
        settingsRepository = SettingsRepository(this)
        identityRepository = DeviceIdentityRepository(this)
        createChannel()
        registerNetworkRecoveryListener()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                if (checkSelfPermission(android.Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                } else {
                    0
                }
            startForeground(NOTIFICATION_ID, buildNotification(), serviceTypes)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        acquireWakeLock()
        val wakeIntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                deviceWakeReceiver,
                wakeIntentFilter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(deviceWakeReceiver, wakeIntentFilter)
        }
        localTtsSpeaker.warmUp()
        mcpEndpointManager.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopVoiceService()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RECONNECT_NOW) {
            requestRecovery(RecoveryTrigger.MANUAL)
        }
        if (intent?.action == ACTION_SET_WAKE_WORD) {
            val enabled = intent.getBooleanExtra(EXTRA_WAKE_WORD_ENABLED, true)
            lifecycleScope.launch {
                val current = settingsRepository.settings.first()
                settingsRepository.update(current.copy(wakeWordEnabled = enabled))
                requestedWakeWordMode.set(enabled)
            }
        }
        if (intent?.action == ACTION_START_LISTENING) {
            conversationCommands.add(COMMAND_START_LISTENING)
        }
        if (intent?.action == ACTION_STOP_LISTENING) {
            conversationCommands.add(COMMAND_STOP_LISTENING)
        }
        if (intent?.action == ACTION_RELOAD_WAKE_WORD) {
            conversationCommands.add(COMMAND_RELOAD_WAKE_WORD)
        }
        requestConnection()
        return START_STICKY
    }

    override fun onDestroy() {
        active.set(false)
        companionActive.set(false)
        activeService.set(null)
        activeWebSocket.set(null)
        pendingTexts.clear()
        conversationCommands.clear()
        pendingWakeWords.clear()
        connectionRequests.clear()
        connectionJob?.cancel()
        connectionJob = null
        unregisterNetworkRecoveryListener()
        stopStandbyWakeListener()
        runCatching { unregisterReceiver(deviceWakeReceiver) }
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
        mcpEndpointManager.stop()
        activeMcpToolResultHandler.set(null)
        localTtsSpeaker.shutdown()
        NativeMusicController.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    private fun requestConnection() {
        synchronized(standbyListenerLock) {
            stopStandbyWakeListener()
            connectionRequests.add(Unit)
            if (!active.compareAndSet(false, true)) return
        }

        connectionJob?.cancel()
        connectionJob = lifecycleScope.launch {
            var identity = identityRepository.ensureIdentity()
            VoiceSessionState.updateRecovery(
                waitingForNetwork = false,
                autoRecoveryEnabled = true,
                recoveryAttempt = 0,
                recoveryLimit = 0
            )
            VoiceSessionState.update(
                status = ConnectionStatus.Connecting,
                statusText = "正在获取连接配置",
                activationCode = "",
                deviceId = identity.deviceId,
                clientId = identity.clientId,
                deviceState = DeviceState.Connecting
            )
            var reconnectDelay = INITIAL_RECONNECT_DELAY_MS
            var consecutiveFailures = 0

            suspend fun waitForRecovery(timeoutMillis: Long? = reconnectDelay): RecoveryTrigger {
                val deadline = timeoutMillis?.let { SystemClock.elapsedRealtime() + it }
                while (true) {
                    recoveryRequests.tryReceive().getOrNull()?.let { return it }
                    if (connectionRequests.poll() != null) return RecoveryTrigger.MANUAL
                    deadline?.let { end ->
                        if (SystemClock.elapsedRealtime() >= end) return RecoveryTrigger.TIMER
                    }
                    delay(NETWORK_RECOVERY_POLL_MS.coerceAtMost(RECONNECT_POLL_MS))
                }
            }

            suspend fun recoverAfterFailure(
                reason: String,
                retrySettings: SettingsState
            ): Boolean {
                consecutiveFailures += 1
                val retryLimit = ConnectionRecoveryPolicy.normalizeRetryLimit(
                    retrySettings.connectRetryCount
                )
                val rapidRetry = retrySettings.connectRetryEnabled &&
                    consecutiveFailures < retryLimit
                val delayMillis = when {
                    !retrySettings.connectRetryEnabled -> null
                    rapidRetry -> reconnectDelay
                    else -> MAX_RECONNECT_DELAY_MS
                }
                val message = ConnectionRecoveryPolicy.recoveryMessage(
                    reason = UserErrorMessages.from(reason),
                    attempt = consecutiveFailures,
                    retryLimit = retryLimit,
                    autoRetryEnabled = retrySettings.connectRetryEnabled,
                    nextDelayMillis = delayMillis
                )
                VoiceSessionState.update(
                    status = if (retrySettings.connectRetryEnabled) {
                        ConnectionStatus.Connecting
                    } else {
                        ConnectionStatus.Error
                    },
                    statusText = message,
                    deviceState = if (retrySettings.connectRetryEnabled) {
                        DeviceState.Connecting
                    } else {
                        VoiceSessionState.state.value.deviceState
                    }
                )
                VoiceSessionState.updateRecovery(
                    waitingForNetwork = false,
                    autoRecoveryEnabled = retrySettings.connectRetryEnabled,
                    recoveryAttempt = consecutiveFailures,
                    recoveryLimit = retryLimit
                )
                updateNotification(message)

                val trigger = waitForRecovery(delayMillis)
                if (trigger == RecoveryTrigger.MANUAL || trigger == RecoveryTrigger.NETWORK) {
                    consecutiveFailures = 0
                    reconnectDelay = INITIAL_RECONNECT_DELAY_MS
                } else {
                    reconnectDelay = ConnectionRecoveryPolicy.nextDelay(
                        reconnectDelay,
                        MAX_RECONNECT_DELAY_MS
                    )
                }
                val shouldContinue = trigger == RecoveryTrigger.MANUAL ||
                    trigger == RecoveryTrigger.NETWORK ||
                    (
                        retrySettings.connectRetryEnabled &&
                            consecutiveFailures < retryLimit
                        )
                if (!shouldContinue) enterStandby()
                return shouldContinue
            }

            while (isActive && active.get()) {
                try {
                    if (!isNetworkAvailable()) {
                        consecutiveFailures = 0
                        reconnectDelay = INITIAL_RECONNECT_DELAY_MS
                        val waitingMessage = ConnectionRecoveryPolicy.waitingForNetworkMessage()
                        VoiceSessionState.update(
                            status = ConnectionStatus.Error,
                            statusText = waitingMessage
                        )
                        VoiceSessionState.updateRecovery(
                            waitingForNetwork = true,
                            autoRecoveryEnabled = true,
                            recoveryAttempt = 0
                        )
                        updateNotification(waitingMessage)
                        waitForRecovery(NETWORK_RECOVERY_POLL_MS)
                        continue
                    }

                    val settings = settingsRepository.settings.first()
                    VoiceSessionState.updateRecovery(
                        waitingForNetwork = false,
                        autoRecoveryEnabled = settings.connectRetryEnabled,
                        recoveryAttempt = 0,
                        recoveryLimit = ConnectionRecoveryPolicy.normalizeRetryLimit(
                            settings.connectRetryCount
                        )
                    )
                    val config = otaClient.fetch(
                        settings = settings,
                        identity = identity,
                        localIpAddress = localIpAddress()
                    )

                    val code = config.activationCode
                    if (code != null) {
                        settingsRepository.update(
                            settings.copy(websocketUrl = "", websocketToken = "")
                        )
                            VoiceSessionState.update(
                            status = ConnectionStatus.ActivationRequired,
                            statusText = config.activationMessage ?: "需要在控制台完成设备激活",
                            activationCode = code,
                            deviceState = DeviceState.Connecting
                            )
                            updateNotification("等待设备激活，验证码 $code")

                        if (config.activationChallenge.isNullOrBlank()) {
                            delay(ACTIVATION_RETRY_DELAY_MS)
                            continue
                        }

                        val activated = otaClient.activate(
                            settings = settings,
                            identity = identity,
                            config = config
                        )
                        if (!activated) {
                            VoiceSessionState.update(
                                status = ConnectionStatus.Error,
                                statusText = "设备激活未完成，稍后重试"
                            )
                            delay(ACTIVATION_RETRY_DELAY_MS)
                            continue
                        }

                        identityRepository.setActivated(true)
                        identity = identityRepository.ensureIdentity()
                        continue
                    }

                    identityRepository.setActivated(true)
                    identity = identityRepository.ensureIdentity()
                    VoiceSessionState.update(
                        status = ConnectionStatus.Connecting,
                        statusText = "设备已激活，正在连接",
                        activationCode = "",
                        deviceState = DeviceState.Connecting
                    )
                    if (config.websocketUrl.isBlank() || config.websocketToken.isBlank()) {
                        if (!recoverAfterFailure("OTA 未返回完整 WebSocket 配置", settings)) break
                        continue
                    }
                    settingsRepository.update(
                        settings.copy(
                            websocketUrl = config.websocketUrl,
                            websocketToken = config.websocketToken
                        )
                    )
                    val connected = connectOnce(identity)
                    if (connected) {
                        reconnectDelay = INITIAL_RECONNECT_DELAY_MS
                        consecutiveFailures = 0
                        VoiceSessionState.updateRecovery(
                            waitingForNetwork = false,
                            autoRecoveryEnabled = settings.connectRetryEnabled,
                            recoveryAttempt = 0,
                            recoveryLimit = ConnectionRecoveryPolicy.normalizeRetryLimit(
                                settings.connectRetryCount
                            )
                        )
                    } else {
                        if (!recoverAfterFailure(
                            VoiceSessionState.state.value.statusText,
                            settings
                        )) break
                        continue
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val retrySettings = settingsRepository.settings.first()
                    if (!recoverAfterFailure(
                        UserErrorMessages.from(error.message ?: "网络连接失败"),
                        retrySettings
                    )) break
                }
            }
        }
    }

    private suspend fun enterStandby() {
        active.set(false)
        val settings = settingsRepository.settings.first()
        VoiceSessionState.update(
            status = ConnectionStatus.Disconnected,
            statusText = "小智待命",
            deviceState = DeviceState.Idle
        )
        updateNotification(VoiceSessionState.state.value.statusText)
        startStandbyWakeListener(settings)
    }

    private fun startStandbyWakeListener(settings: SettingsState) {
        if (!settings.wakeWordEnabled ||
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        synchronized(standbyListenerLock) {
            if (active.get()) return
            if (standbyAudioInput != null || standbyWakeWordEngine != null) return

            standbyWakeWordEngine = SherpaWakeWordEngine(
                context = this,
                settings = settings,
                onDetected = { keyword ->
                    pendingWakeWords.add(keyword)
                    conversationCommands.add(COMMAND_START_LISTENING)
                    requestConnection()
                },
                onError = { message ->
                    VoiceSessionState.update(
                        status = ConnectionStatus.Disconnected,
                        statusText = "唤醒词待命：$message"
                    )
                }
            )
            standbyAudioInput = AudioInputEngine(
                onPacket = {},
                onSamples = { samples ->
                    standbyWakeWordEngine?.process(samples)
                },
                initialSendingEnabled = false,
                aecEnabled = settings.aecEnabled
            )
            standbyAudioInput?.start()
        }
    }

    private fun stopStandbyWakeListener() {
        synchronized(standbyListenerLock) {
            standbyAudioInput?.stop()
            standbyWakeWordEngine?.close()
            standbyAudioInput = null
            standbyWakeWordEngine = null
        }
    }

    private suspend fun waitBeforeReconnect(timeoutMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (connectionRequests.poll() != null) return
            val remaining = deadline - SystemClock.elapsedRealtime()
            delay(remaining.coerceAtMost(RECONNECT_POLL_MS))
        }
    }

    private suspend fun connectOnce(identity: com.xiaozhi.android.core.DeviceIdentity): Boolean {
        val settings = settingsRepository.settings.first()
        if (settings.websocketUrl.isBlank() || settings.websocketToken.isBlank()) {
            VoiceSessionState.update(
                status = ConnectionStatus.Error,
                statusText = "OTA 未返回完整 WebSocket 配置"
            )
            return false
        }

        val hello = CompletableDeferred<Boolean>()
        val closed = CompletableDeferred<Unit>()
                    VoiceSessionState.update(
                        status = ConnectionStatus.Connecting,
                        statusText = "正在连接小智服务",
                        deviceState = DeviceState.Connecting
        )

        var audioInput: AudioInputEngine? = null
        var audioOutput: AudioOutputEngine? = null
        var wakeWordEngine: SherpaWakeWordEngine? = null
        lateinit var socket: XiaozhiWebSocketClient
        val initialWakeMode = requestedWakeWordMode.getAndSet(null) ?: settings.wakeWordEnabled
        val wakeMode = AtomicBoolean(initialWakeMode)
        var wakeModeJob: Job? = null
        val serverSpeechGeneration = AtomicLong(0L)
        val mcpSpeechFallbacks = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val cloudSpeechActive = AtomicBoolean(false)
        val cloudSpeechAudioReceived = AtomicBoolean(false)
        val cloudSpeechLastText = AtomicReference("")

        fun startContinuousListening(statusText: String) {
            val listeningMode = if (settings.aecEnabled) {
                LISTENING_MODE_REALTIME
            } else {
                LISTENING_MODE_AUTO
            }
            socket.sendStartListening(listeningMode)
            audioInput?.startSending()
            VoiceSessionState.update(
                status = ConnectionStatus.Connected,
                statusText = statusText,
                deviceState = DeviceState.Listening
            )
        }

        fun cancelMcpSpeechFallback() {
            serverSpeechGeneration.incrementAndGet()
            localTtsSpeaker.stop()
            while (true) {
                val runnable = mcpSpeechFallbacks.poll() ?: break
                mainHandler.removeCallbacks(runnable)
            }
        }

        fun scheduleMcpSpeechFallback(result: Any?, isError: Boolean) {
            val speech = ToolReplySpeechFormatter.format(
                if (isError) {
                    mapOf("success" to false, "message" to result.toString())
                } else {
                    result
                }
            ) ?: return
            Log.i(
                TAG,
                "MCP speech fallback scheduled, length=${speech.length}, " +
                    "localTtsReady=${localTtsSpeaker.isReady()}"
            )
            val generation = serverSpeechGeneration.incrementAndGet()
            val runnable = Runnable {
                if (generation != serverSpeechGeneration.get()) return@Runnable
                socket.sendAbortSpeaking()
                VoiceSessionState.appendChat(speech, fromUser = false)

                NativeMusicController.pauseForVoiceInteraction()
                VoiceSessionState.update(
                    status = ConnectionStatus.Connected,
                    statusText = "正在播报功能结果",
                    deviceState = DeviceState.Speaking
                )
                updateNotification("正在播报功能结果")
                Log.i(TAG, "MCP local speech requested, length=${speech.length}")
                localTtsSpeaker.speak(speech) { spoken ->
                    if (generation != serverSpeechGeneration.get()) return@speak
                    Log.i(TAG, "MCP local speech finished, spoken=$spoken")
                    if (spoken) {
                        if (NativeMusicController.isPausedForVoiceInteraction()) {
                            NativeMusicController.resumeAfterVoiceInteraction()
                        }
                        VoiceSessionState.update(
                            status = ConnectionStatus.Connected,
                            statusText = "功能结果播报完成",
                            deviceState = DeviceState.Idle
                        )
                        updateNotification(VoiceSessionState.state.value.statusText)
                        startContinuousListening("继续聆听")
                    } else {
                        val delivered = sendDirectSpeechFallback(socket, speech, "MCP")
                        VoiceSessionState.update(
                            status = ConnectionStatus.Connected,
                            statusText = if (delivered) {
                                "正在尝试云端播报功能结果"
                            } else {
                                "本机与云端语音合成暂不可用"
                            },
                            deviceState = DeviceState.Idle
                        )
                        updateNotification(VoiceSessionState.state.value.statusText)
                        if (!delivered) startContinuousListening("继续聆听")
                    }
                }
            }
            mcpSpeechFallbacks.add(runnable)
            mainHandler.postDelayed(runnable, MCP_TOOL_SPEECH_DELAY_MS)
        }

        val mcpToolResultHandler: (Any?, Boolean) -> Unit = { result, isError ->
            scheduleMcpSpeechFallback(result, isError)
        }
        activeMcpToolResultHandler.set(mcpToolResultHandler)

        fun handleBootstrapMcp(payload: JSONObject): Boolean {
            if (payload.optString("jsonrpc") != McpServerProtocol.JSONRPC_VERSION) return false
            val method = payload.optString("method")
            val id = payload.opt("id") ?: return true
            val response = when (method) {
                "initialize" -> {
                    VisionService.configure(
                        payload.optJSONObject("params")
                            ?.optJSONObject("capabilities")
                            ?: JSONObject()
                    )
                    McpServerProtocol.resultResponse(
                        id,
                        JSONObject()
                            .put("protocolVersion", McpServerProtocol.PROTOCOL_VERSION)
                            .put(
                                "capabilities",
                                JSONObject().put("tools", JSONObject())
                            )
                            .put(
                                "serverInfo",
                                JSONObject()
                                    .put("name", "android-xiaozhi-bootstrap")
                                    .put("version", "1.0.0")
                            )
                    )
                }
                "tools/list" -> McpServerProtocol.resultResponse(
                    id,
                    McpServerProtocol.emptyToolsList()
                )
                "tools/call" -> McpServerProtocol.errorResponse(
                    id,
                    -32601,
                    "Tools are served by the official MCP endpoint"
                )
                else -> McpServerProtocol.errorResponse(
                    id,
                    -32601,
                    "Method not found: $method"
                )
            }
            return socket.sendMcpPayload(response)
        }

        fun stopAudio() {
            audioInput?.stop()
            audioOutput?.stop()
            wakeWordEngine?.close()
            VoiceSessionState.updateLevels(inputLevel = 0f, outputLevel = 0f)
            audioInput = null
            audioOutput = null
            wakeWordEngine = null
        }

        socket = XiaozhiWebSocketClient(
            url = settings.websocketUrl,
            token = settings.websocketToken,
            identity = identity,
            listener = object : XiaozhiWebSocketClient.Listener {
                override fun onOpen() {
                    updateNotification("WebSocket 已打开，等待服务端握手")
                }

                override fun onServerHello(sessionId: String?) {
                    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        VoiceSessionState.update(
                            status = ConnectionStatus.Error,
                            statusText = "缺少麦克风权限"
                        )
                        socket.close()
                        return
                    }
                    VoiceSessionState.update(
                        status = ConnectionStatus.Connected,
                        statusText = if (sessionId == null) {
                            "语音链路已就绪"
                        } else {
                            "语音链路已就绪：$sessionId"
                        },
                        deviceState = if (wakeMode.get()) {
                            DeviceState.Idle
                        } else {
                            DeviceState.Listening
                        },
                        wakeWordEnabled = wakeMode.get()
                    )
                    audioOutput = AudioOutputEngine(
                        sampleRate = settings.outputSampleRate,
                        onError = { message ->
                            VoiceSessionState.update(
                                status = ConnectionStatus.Error,
                                statusText = UserErrorMessages.from(message)
                            )
                        },
                        onLevel = { level ->
                            VoiceSessionState.updateLevels(outputLevel = level)
                        }
                    )
                    audioInput = AudioInputEngine(
                        onPacket = { packet -> socket.sendAudio(packet) },
                        onSamples = { samples -> wakeWordEngine?.process(samples) },
                        initialSendingEnabled = false,
                        aecEnabled = settings.aecEnabled,
                        onError = { message ->
                            VoiceSessionState.update(
                                status = ConnectionStatus.Error,
                                statusText = UserErrorMessages.from(message)
                            )
                        },
                        onLevel = { level ->
                            VoiceSessionState.updateLevels(inputLevel = level)
                        }
                    )
                    if (wakeMode.get()) {
                        wakeWordEngine = SherpaWakeWordEngine(
                            context = this@VoiceForegroundService,
                            settings = settings,
                            onDetected = { keyword ->
                                socket.sendWakeWordDetected(keyword)
                                startContinuousListening("已唤醒：$keyword")
                                updateNotification("已唤醒：$keyword")
                            },
                            onError = { message ->
                                VoiceSessionState.update(
                                    status = ConnectionStatus.Error,
                                    statusText = "唤醒词错误：$message"
                                )
                            }
                        )
                        VoiceSessionState.update(
                            status = ConnectionStatus.Connected,
                            statusText = "等待唤醒词：${settings.wakeWordText}",
                            deviceState = DeviceState.Idle,
                            wakeWordEnabled = true
                        )
                    } else {
                        VoiceSessionState.update(
                            status = ConnectionStatus.Connected,
                            statusText = "语音链路已就绪",
                            deviceState = DeviceState.Idle,
                            wakeWordEnabled = false
                        )
                    }
                    audioOutput?.start()
                    audioInput?.start()
                    drainPendingWakeWords(socket)
                    drainPendingTexts(socket)
                    updateNotification(VoiceSessionState.state.value.statusText)
                    hello.complete(true)
                }

                    override fun onJson(message: org.json.JSONObject) {
                        if (message.optString("type") == "mcp") {
                            val payload = message.optJSONObject("payload")
                            if (payload != null) {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val delivered = runCatching { handleBootstrapMcp(payload) }
                                        .getOrDefault(false)
                                    Log.i(
                                        TAG,
                                        "MCP bootstrap response delivered=$delivered, " +
                                            "method=${payload.optString("method")}"
                                    )
                                }
                            }
                        }
                    if (message.optString("type") == "tts") {
                        val text = message.optString("text")
                        Log.i(
                            TAG,
                            "Cloud TTS state=${message.optString("state")}, " +
                                "length=${text.length}"
                        )
                        VoiceSessionState.updateConversation(currentText = text)
                        val ttsState = message.optString("state")
                        if (text.isNotBlank()) {
                            cloudSpeechLastText.set(text)
                        }
                        if (ttsState == "start" && activeHiddenSpeechRequest.get() == null) {
                            claimHiddenSpeechRequest()
                        }
                        if (text.isNotBlank() &&
                            ttsState in FINAL_TEXT_STATES &&
                            !shouldSuppressAssistantChat()
                        ) {
                            VoiceSessionState.appendChat(text, fromUser = false)
                        }
                        val stateText = when (ttsState) {
                            "start" -> "正在播放语音"
                            "stop" -> "语音播放完成"
                            else -> return
                        }
                        cloudSpeechActive.set(ttsState == "start")
                        if (ttsState == "start") {
                            cloudSpeechAudioReceived.set(false)
                            cancelMcpSpeechFallback()
                            cancelVisionSpeechFallbacks()
                        }
                        VoiceSessionState.update(
                            status = ConnectionStatus.Connected,
                            statusText = stateText,
                            deviceState = if (message.optString("state") == "start") {
                                DeviceState.Speaking
                            } else {
                                DeviceState.Idle
                            }
                        )
                        updateNotification(stateText)
                        if (ttsState == "start") {
                            NativeMusicController.pauseForVoiceInteraction()
                        }
                        if (ttsState == "stop") {
                            activeHiddenSpeechRequest.set(null)
                            val fallbackSpeech = cloudSpeechLastText.get().trim()
                            if (!cloudSpeechAudioReceived.get() && fallbackSpeech.isNotBlank()) {
                                Log.w(
                                    TAG,
                                    "Cloud TTS ended without audio; local fallback, " +
                                        "length=${fallbackSpeech.length}"
                                )
                                localTtsSpeaker.speak(fallbackSpeech) { spoken ->
                                    Log.i(
                                        TAG,
                                        "Cloud TTS no-audio local fallback spoken=$spoken"
                                    )
                                }
                            }
                            cloudSpeechLastText.set("")
                            startContinuousListening("继续聆听")
                            VoiceSessionState.updateConversation(currentText = "")
                        }
                        if (ttsState == "stop" &&
                            NativeMusicController.isPausedForVoiceInteraction()
                        ) {
                            NativeMusicController.resumeAfterVoiceInteraction()
                        }
                    }
                    if (message.optString("type") == "stt") {
                        val text = message.optString("text")
                        val state = message.optString("state")
                        if (VoiceMusicInterruptionPolicy.shouldPauseForUserSpeech(state, text)) {
                            NativeMusicController.pauseForVoiceInteraction()
                        }
                        VoiceSessionState.updateConversation(currentText = text)
                        val hiddenSpeechTrigger = hiddenSpeechRequests.isNotEmpty() &&
                            hiddenSpeechRequests.any { it.text == text }
                        if (hiddenSpeechTrigger && activeHiddenSpeechRequest.get() == null) {
                            claimHiddenSpeechRequest()
                        }
                        if (text.isNotBlank() &&
                            (state.isBlank() || state == "final") &&
                            !hiddenSpeechTrigger
                        ) {
                            val studyState = StudySessionState.state.value
                            if (studyState.mode != StudyMode.None) {
                                StudyObservationEngine.requestSpeechFrame()
                            }
                            if (studyState.mode == StudyMode.Reading) {
                                val evaluation = StudySessionManager.evaluateTranscript(text)
                                if (evaluation != null) {
                                    socket.sendAbortSpeaking()
                                    VoiceSessionState.appendChat(text, fromUser = true)
                                    socket.sendText(
                                        "陪读评测结果：${ReadingEvaluator.feedbackFor(evaluation)}" +
                                            "请用温和的儿童阅读导师口吻给出一句简短反馈。"
                                    )
                                    return
                                }
                            }
                            NativeMusicController.pendingSelectionIndex(text)?.let { index ->
                                socket.sendAbortSpeaking()
                                VoiceSessionState.appendChat(text, fromUser = true)
                                playMusicSelectionLocally(index)
                                return
                            }
                            MusicIntentParser.extractSongName(text)?.let { songName ->
                                socket.sendAbortSpeaking()
                                VoiceSessionState.appendChat(text, fromUser = true)
                                playMusicLocally(songName)
                                return
                            }
                            VoiceSessionState.appendChat(text, fromUser = true)
                        }
                    }
                    message.optString("emotion").takeIf { it.isNotBlank() }?.let { emotion ->
                        VoiceSessionState.updateConversation(emotion = emotion)
                    }
                }

                override fun onAudio(audio: ByteArray) {
                    if (cloudSpeechActive.get()) {
                        cloudSpeechAudioReceived.set(true)
                        cancelMcpSpeechFallback()
                        cancelVisionSpeechFallbacks()
                    }
                    audioOutput?.enqueue(audio)
                }

                override fun onClosed(code: Int, reason: String) {
                    stopAudio()
                    cancelMcpSpeechFallback()
                    cancelVisionSpeechFallbacks()
                    activeMcpToolResultHandler.compareAndSet(mcpToolResultHandler, null)
                    if (active.get()) {
                        VoiceSessionState.update(
                            status = ConnectionStatus.Disconnected,
                            statusText = "连接关闭：$code ${reason.ifBlank { "无原因" }}"
                        )
                    }
                    hello.complete(false)
                    closed.complete(Unit)
                }

                override fun onError(message: String) {
                    stopAudio()
                    cancelMcpSpeechFallback()
                    cancelVisionSpeechFallbacks()
                    activeMcpToolResultHandler.compareAndSet(mcpToolResultHandler, null)
                    if (active.get()) {
                        VoiceSessionState.update(
                            status = ConnectionStatus.Connecting,
                            statusText = UserErrorMessages.from(message),
                            deviceState = DeviceState.Connecting
                        )
                    }
                    hello.complete(false)
                    closed.complete(Unit)
                }
            }
        )
        activeWebSocket.set(socket)
        socket.connect()

        suspend fun setWakeMode(enabled: Boolean) {
            if (!wakeMode.compareAndSet(!enabled, enabled)) return
            if (enabled) {
                socket.sendStopListening()
                audioInput?.stopSending()
                val latestSettings = settingsRepository.settings.first()
                if (wakeWordEngine == null) {
                    wakeWordEngine = SherpaWakeWordEngine(
                        context = this@VoiceForegroundService,
                            settings = latestSettings,
                            onDetected = { keyword ->
                                socket.sendWakeWordDetected(keyword)
                                startContinuousListening("已唤醒：$keyword")
                                updateNotification("已唤醒：$keyword")
                            },
                        onError = { message ->
                            VoiceSessionState.update(
                                status = ConnectionStatus.Error,
                                statusText = "唤醒词错误：$message"
                            )
                        }
                    )
                }
                VoiceSessionState.update(
                    status = ConnectionStatus.Connected,
                    statusText = "等待唤醒词：${latestSettings.wakeWordText}",
                    deviceState = DeviceState.Idle,
                    wakeWordEnabled = true
                )
            } else {
                wakeWordEngine?.close()
                wakeWordEngine = null
                socket.sendStopListening()
                audioInput?.stopSending()
                VoiceSessionState.update(
                    status = ConnectionStatus.Connected,
                    statusText = "语音链路已就绪",
                    deviceState = DeviceState.Idle,
                    wakeWordEnabled = false
                )
            }
            updateNotification(VoiceSessionState.state.value.statusText)
        }

        try {
            val connected = withTimeoutOrNull(HELLO_TIMEOUT_MS) { hello.await() } ?: false
            if (!connected) {
                VoiceSessionState.update(
                    status = ConnectionStatus.Error,
                    statusText = "等待服务端 hello 超时"
                )
                return false
            }

        wakeModeJob = lifecycleScope.launch {
            while (isActive) {
                    requestedWakeWordMode.getAndSet(null)?.let { enabled ->
                        setWakeMode(enabled)
                    }
                when (conversationCommands.poll()) {
                    COMMAND_START_LISTENING -> {
                        startContinuousListening("正在聆听")
                    }
                    COMMAND_STOP_LISTENING -> {
                        socket.sendStopListening()
                        audioInput?.stopSending()
                        VoiceSessionState.update(
                            status = ConnectionStatus.Connected,
                            statusText = "语音链路已就绪",
                            deviceState = DeviceState.Idle
                        )
                    }
                    COMMAND_RELOAD_WAKE_WORD -> {
                        wakeWordEngine?.close()
                        wakeWordEngine = null
                        if (wakeMode.get()) {
                            val latestSettings = settingsRepository.settings.first()
                            wakeWordEngine = SherpaWakeWordEngine(
                                context = this@VoiceForegroundService,
                                    settings = latestSettings,
                                    onDetected = { keyword ->
                                        socket.sendWakeWordDetected(keyword)
                                        startContinuousListening("已唤醒：$keyword")
                                        updateNotification("已唤醒：$keyword")
                                    },
                                onError = { message ->
                                    VoiceSessionState.update(
                                        status = ConnectionStatus.Error,
                                        statusText = "唤醒词错误：$message"
                                    )
                                }
                            )
                            VoiceSessionState.update(
                                status = ConnectionStatus.Connected,
                                statusText = "等待唤醒词：${latestSettings.wakeWordText}",
                                deviceState = DeviceState.Idle
                            )
                        }
                    }
                }
                delay(WAKE_MODE_POLL_MS)
            }
        }

            closed.await()
        } finally {
            wakeModeJob?.cancel()
            stopAudio()
            socket.close()
            clearActiveSocket(socket)
            cancelMcpSpeechFallback()
            cancelVisionSpeechFallbacks()
            activeMcpToolResultHandler.compareAndSet(mcpToolResultHandler, null)
        }
        return false
    }

    private fun clearActiveSocket(socket: XiaozhiWebSocketClient) {
        activeWebSocket.compareAndSet(socket, null)
    }

    private fun requestRecovery(trigger: RecoveryTrigger) {
        recoveryRequests.trySend(trigger)
        if (trigger == RecoveryTrigger.MANUAL && active.get()) {
            if (VoiceSessionState.state.value.status == ConnectionStatus.Error) {
                activeWebSocket.get()?.close()
            }
            VoiceSessionState.update(
                status = ConnectionStatus.Connecting,
                statusText = "正在重新连接小智服务"
            )
            updateNotification(VoiceSessionState.state.value.statusText)
        }
        if (!active.get()) {
            requestConnection()
        }
    }

    private fun registerNetworkRecoveryListener() {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                recoveryRequests.trySend(RecoveryTrigger.NETWORK)
            }
        }
        networkCallback = callback
        runCatching { manager.registerDefaultNetworkCallback(callback) }
    }

    private fun unregisterNetworkRecoveryListener() {
        val callback = networkCallback ?: return
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching { manager.unregisterNetworkCallback(callback) }
        networkCallback = null
    }

    private fun isNetworkAvailable(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun drainPendingWakeWords(socket: XiaozhiWebSocketClient) {
        while (true) {
            val keyword = pendingWakeWords.poll() ?: break
            if (!socket.sendWakeWordDetected(keyword)) {
                pendingWakeWords.add(keyword)
                break
            }
        }
    }

    private fun drainPendingTexts(socket: XiaozhiWebSocketClient) {
        while (true) {
            val text = pendingTexts.poll() ?: break
            if (!socket.sendText(text)) {
                VoiceSessionState.update(
                    status = ConnectionStatus.Error,
                    statusText = "文本发送失败：语音链路未就绪"
                )
                break
            }
        }
    }

    private fun stopVoiceService() {
        active.set(false)
        stopStandbyWakeListener()
        connectionJob?.cancel()
        connectionJob = null
        VoiceSessionState.update(
            status = ConnectionStatus.Disconnected,
            statusText = "服务已停止",
            activationCode = ""
        )
        VoiceSessionState.updateRecovery(
            waitingForNetwork = false,
            autoRecoveryEnabled = true,
            recoveryAttempt = 0,
            recoveryLimit = 0
        )
        stopSelf()
    }

    private fun acquireWakeLock() {
        val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "语音服务",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildNotification(text: String = "小智准备中"): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val immutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            immutableFlag
        )
        val startListeningIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, VoiceForegroundService::class.java).setAction(ACTION_START_LISTENING),
            immutableFlag
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, VoiceForegroundService::class.java).setAction(ACTION_STOP),
            immutableFlag
        )
        val reconnectIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, VoiceForegroundService::class.java).setAction(ACTION_RECONNECT_NOW),
            immutableFlag
        )
        return builder
            .setContentTitle("小智")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_play,
                "开始聆听",
                startListeningIntent
            )
            .addAction(
                android.R.drawable.ic_delete,
                "停止服务",
                stopIntent
            )
            .addAction(
                android.R.drawable.ic_popup_sync,
                "重试",
                reconnectIntent
            )
            .build()
    }

    private fun updateNotification(text: String) {
        val state = VoiceSessionState.state.value
        val content = when {
            state.deviceState == DeviceState.Listening -> "正在聆听"
            state.deviceState == DeviceState.Speaking &&
                text in setOf("正在播报功能结果", "正在朗读识别内容") -> text
            state.status == ConnectionStatus.Connecting ||
                state.deviceState == DeviceState.Connecting -> "正在准备，马上就好"
            state.status == ConnectionStatus.ActivationRequired -> "需要完成设备激活"
            state.status == ConnectionStatus.Error && state.waitingForNetwork ->
                "网络恢复后会自动继续"
            state.status == ConnectionStatus.Error && state.autoRecoveryEnabled ->
                "正在自动恢复，消息会先排队"
            state.status == ConnectionStatus.Error -> "暂时不可用，可点重试"
            state.status == ConnectionStatus.Connected -> "随时可对话"
            else -> "小智准备中"
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun localIpAddress(): String {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull() ?: "127.0.0.1"
    }

    private fun playMusicLocally(songName: String) {
        lifecycleScope.launch {
            NativeMusicController.configure(settingsRepository.settings.first())
            val result = withContext(Dispatchers.IO) {
                NativeMusicController.searchAndPlay(songName)
            }
            VoiceSessionState.appendChat(result, fromUser = false)
            speakLocalFunctionReply(result)
        }
    }

    private fun claimHiddenSpeechRequest() {
        val now = System.currentTimeMillis()
        while (true) {
            val request = hiddenSpeechRequests.peek() ?: break
            if (request.expiresAt > now) {
                activeHiddenSpeechRequest.set(request)
                hiddenSpeechRequests.poll()
                return
            }
            hiddenSpeechRequests.poll()
        }
    }

    private fun shouldSuppressAssistantChat(): Boolean {
        val request = activeHiddenSpeechRequest.get() ?: return false
        return request.expiresAt > System.currentTimeMillis()
    }

    private fun playMusicSelectionLocally(index: Int) {
        lifecycleScope.launch {
            NativeMusicController.configure(settingsRepository.settings.first())
            val result = withContext(Dispatchers.IO) {
                NativeMusicController.selectPendingCandidate(index)
            }
            VoiceSessionState.appendChat(result, fromUser = false)
            speakLocalFunctionReply(result)
        }
    }

    private fun speakLocalFunctionReply(text: String) {
        val speech = ToolReplySpeechFormatter.format(text) ?: return
        mainHandler.post {
            NativeMusicController.pauseForVoiceInteraction()
            VoiceSessionState.update(
                status = VoiceSessionState.state.value.status,
                statusText = "正在播报功能结果",
                deviceState = DeviceState.Speaking
            )
            updateNotification("正在播报功能结果")
            localTtsSpeaker.speak(speech) {
                if (NativeMusicController.isPausedForVoiceInteraction()) {
                    NativeMusicController.resumeAfterVoiceInteraction()
                }
                VoiceSessionState.update(
                    status = VoiceSessionState.state.value.status,
                    statusText = if (it) "功能结果播报完成" else "本机语音合成不可用",
                    deviceState = DeviceState.Idle
                )
                updateNotification(VoiceSessionState.state.value.statusText)
            }
        }
    }

    private fun speakVisionResult(text: String, onFinished: (Boolean) -> Unit) {
        mainHandler.post {
            activeWebSocket.get()?.sendAbortSpeaking()
            NativeMusicController.pauseForVoiceInteraction()
            VoiceSessionState.update(
                status = VoiceSessionState.state.value.status,
                statusText = "正在朗读识别内容",
                deviceState = DeviceState.Speaking
            )
            updateNotification("正在朗读识别内容")
            localTtsSpeaker.speak(text) { spoken ->
                if (NativeMusicController.isPausedForVoiceInteraction()) {
                    NativeMusicController.resumeAfterVoiceInteraction()
                }
                VoiceSessionState.update(
                    status = VoiceSessionState.state.value.status,
                    statusText = if (spoken) {
                        "识别内容朗读完成"
                    } else {
                        "本机语音合成不可用，正在尝试云端朗读"
                    },
                    deviceState = DeviceState.Idle
                )
                updateNotification(VoiceSessionState.state.value.statusText)
                onFinished(spoken)
            }
        }
    }

    private fun speakLocalVisionFallback(text: String) {
        mainHandler.post {
            activeWebSocket.get()?.sendAbortSpeaking()
            NativeMusicController.pauseForVoiceInteraction()
            VoiceSessionState.appendChat(text, fromUser = false)
            VoiceSessionState.update(
                status = VoiceSessionState.state.value.status,
                statusText = "正在朗读识别内容",
                deviceState = DeviceState.Speaking
            )
            updateNotification("正在朗读识别内容")
            localTtsSpeaker.speak(text) { spoken ->
                if (NativeMusicController.isPausedForVoiceInteraction()) {
                    NativeMusicController.resumeAfterVoiceInteraction()
                }
                VoiceSessionState.update(
                    status = VoiceSessionState.state.value.status,
                    statusText = if (spoken) {
                        "识别内容朗读完成"
                    } else {
                        "本机语音合成不可用"
                    },
                    deviceState = DeviceState.Idle
                )
                updateNotification(VoiceSessionState.state.value.statusText)
            }
        }
    }

    private enum class RecoveryTrigger {
        MANUAL,
        NETWORK,
        TIMER
    }

    companion object {
        private val applicationContextHolder = AtomicReference<Context?>(null)
        private val activeWebSocket = AtomicReference<XiaozhiWebSocketClient?>(null)
        private val activeService = AtomicReference<VoiceForegroundService?>(null)
        private val companionActive = AtomicBoolean(false)
        private val requestedWakeWordMode = AtomicReference<Boolean?>(null)
        private val pendingTexts = ConcurrentLinkedQueue<String>()
        private val conversationCommands = ConcurrentLinkedQueue<String>()
        private val hiddenSpeechRequests =
            ConcurrentLinkedQueue<HiddenSpeechRequest>()
        private val activeHiddenSpeechRequest = AtomicReference<HiddenSpeechRequest?>(null)
        private val visionSpeechFallbacks = ConcurrentLinkedQueue<Runnable>()
        private val visionSpeechGeneration = AtomicLong(0L)
        private const val CHANNEL_ID = "xiaozhi_voice"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "xiaozhi:voice"
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        private const val ACTION_STOP = "com.xiaozhi.android.action.STOP_VOICE"
        private const val ACTION_SET_WAKE_WORD = "com.xiaozhi.android.action.SET_WAKE_WORD"
        private const val EXTRA_WAKE_WORD_ENABLED = "enabled"
        private const val ACTION_START_LISTENING =
            "com.xiaozhi.android.action.START_LISTENING"
        private const val ACTION_STOP_LISTENING =
            "com.xiaozhi.android.action.STOP_LISTENING"
        private const val ACTION_RELOAD_WAKE_WORD =
            "com.xiaozhi.android.action.RELOAD_WAKE_WORD"
        private const val ACTION_RECONNECT_NOW =
            "com.xiaozhi.android.action.RECONNECT_NOW"
        private const val COMMAND_START_LISTENING = "start"
        private const val COMMAND_STOP_LISTENING = "stop"
        private const val COMMAND_RELOAD_WAKE_WORD = "reload_wake_word"
        private const val HELLO_TIMEOUT_MS = 10_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 500L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val NETWORK_RECOVERY_POLL_MS = 3_000L
        private const val ACTIVATION_RETRY_DELAY_MS = 15_000L
        private const val WAKE_MODE_POLL_MS = 200L
        private const val RECONNECT_POLL_MS = 200L
        private const val MCP_TOOL_SPEECH_DELAY_MS = 250L
        private const val VISION_TTS_FALLBACK_DELAY_MS = 1_500L
        private const val LISTENING_MODE_AUTO = "auto"
        private const val LISTENING_MODE_REALTIME = "realtime"
        private val FINAL_TEXT_STATES = setOf("stop", "sentence_end")
        private const val TAG = "VoiceForegroundService"
        private const val HIDDEN_SPEECH_TIMEOUT_MS = 30_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, VoiceForegroundService::class.java))
        }

        fun reconnectNow(context: Context) {
            context.startForegroundService(
                Intent(context, VoiceForegroundService::class.java)
                    .setAction(ACTION_RECONNECT_NOW)
            )
        }

        fun stop(context: Context) {
            VoiceSessionState.update(
                status = ConnectionStatus.Disconnected,
                statusText = "服务已停止",
                activationCode = ""
            )
            VoiceSessionState.updateRecovery(
                waitingForNetwork = false,
                autoRecoveryEnabled = true,
                recoveryAttempt = 0,
                recoveryLimit = 0
            )
            context.stopService(Intent(context, VoiceForegroundService::class.java))
        }

        fun setWakeWordEnabled(context: Context, enabled: Boolean) {
            val current = VoiceSessionState.state.value
            VoiceSessionState.update(
                status = current.status,
                statusText = if (enabled) "正在开启唤醒词" else "正在关闭唤醒词",
                wakeWordEnabled = enabled
            )
            context.startService(
                Intent(context, VoiceForegroundService::class.java)
                    .setAction(ACTION_SET_WAKE_WORD)
                    .putExtra(EXTRA_WAKE_WORD_ENABLED, enabled)
            )
        }

        fun reloadWakeWord(context: Context) {
            context.startService(
                Intent(context, VoiceForegroundService::class.java)
                    .setAction(ACTION_RELOAD_WAKE_WORD)
            )
        }

        fun startListening(context: Context) {
            conversationCommands.add(COMMAND_START_LISTENING)
            context.startForegroundService(
                Intent(context, VoiceForegroundService::class.java)
                    .setAction(ACTION_START_LISTENING)
            )
        }

        fun stopListening(context: Context) {
            if (companionActive.get()) {
                conversationCommands.add(COMMAND_STOP_LISTENING)
                return
            }
            context.startService(
                Intent(context, VoiceForegroundService::class.java)
                    .setAction(ACTION_STOP_LISTENING)
            )
        }

        fun abortSpeaking(): Boolean {
            return activeWebSocket.get()?.sendAbortSpeaking() ?: false
        }

        fun requestCloudSpeech(text: String): Boolean {
            val speech = ToolReplySpeechFormatter.formatForReading(text) ?: return false
            VisionResultStore.update(text)
            VisionResultStore.markPendingSpeech()

            val service = activeService.get()
            val socket = activeWebSocket.get()
            if (socket != null) {
                val delivered = sendDirectSpeechFallback(socket, speech, "Vision")
                if (delivered) {
                    service?.let { scheduleVisionSpeechFallback(it, speech) }
                    return true
                }
            }
            if (service != null) {
                service.speakVisionResult(speech) { spoken ->
                    if (!spoken) requestCloudSpeechFallback(speech)
                }
                Log.i(TAG, "Local vision speech requested, length=${speech.length}, cloudAvailable=false")
                return true
            }
            return requestCloudSpeechFallback(speech)
        }

        private fun scheduleVisionSpeechFallback(service: VoiceForegroundService, speech: String) {
            val generation = visionSpeechGeneration.incrementAndGet()
            val runnable = Runnable {
                if (generation != visionSpeechGeneration.get()) return@Runnable
                Log.i(TAG, "Vision local speech fallback started, length=${speech.length}")
                service.speakLocalVisionFallback(speech)
            }
            visionSpeechFallbacks.add(runnable)
            service.mainHandler.postDelayed(runnable, VISION_TTS_FALLBACK_DELAY_MS)
        }

        private fun cancelVisionSpeechFallbacks() {
            visionSpeechGeneration.incrementAndGet()
            while (true) {
                val runnable = visionSpeechFallbacks.poll() ?: break
                activeService.get()?.mainHandler?.removeCallbacks(runnable)
            }
        }

        private fun requestCloudSpeechFallback(speech: String): Boolean {
            val socket = activeWebSocket.get()
            return socket?.let { sendDirectSpeechFallback(it, speech, "Vision") } ?: false
        }

        private fun sendDirectSpeechFallback(
            socket: XiaozhiWebSocketClient,
            speech: String,
            source: String
        ): Boolean {
            socket.sendAbortSpeaking()
            val delivered = socket.sendDeviceCallSpeech(speech)
            if (delivered) {
                hiddenSpeechRequests.add(
                    HiddenSpeechRequest(
                        text = speech,
                        expiresAt = System.currentTimeMillis() + HIDDEN_SPEECH_TIMEOUT_MS
                    )
                )
            }
            Log.i(TAG, "Cloud $source speech fallback delivered=$delivered")
            return delivered
        }

        fun isRunning(): Boolean {
            return companionActive.get()
        }

        fun sendText(text: String, callerContext: Context? = null): Boolean {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return false

            if (AppLauncherTool.isLaunchRequest(trimmed)) {
                val service = activeService.get()
                val launchContext = service?.applicationContext ?:
                    callerContext?.applicationContext ?:
                    applicationContextHolder.get()?.applicationContext
                if (service != null && launchContext != null) {
                    VoiceSessionState.appendChat(trimmed, fromUser = true)
                    service.lifecycleScope.launch(Dispatchers.IO) {
                        val result = AppLauncherTool.launch(launchContext, trimmed)
                        val message = result.optString("message")
                        VoiceSessionState.appendChat(message, fromUser = false)
                        service.speakLocalFunctionReply(message)
                    }
                    return true
                }
                if (launchContext != null) {
                    val result = AppLauncherTool.launch(launchContext, trimmed)
                    val message = result.optString("message")
                    VoiceSessionState.appendChat(trimmed, fromUser = true)
                    VoiceSessionState.appendChat(message, fromUser = false)
                    activeService.get()?.speakLocalFunctionReply(message)
                    return true
                }
            }

            NativeMusicController.pendingSelectionIndex(trimmed)?.let { index ->
                VoiceSessionState.appendChat(trimmed, fromUser = true)
                activeService.get()?.let { service ->
                    service.playMusicSelectionLocally(index)
                    return true
                }
            }

            activeService.get()?.let { service ->
                MusicIntentParser.extractSongName(trimmed)?.let { songName ->
                    VoiceSessionState.appendChat(trimmed, fromUser = true)
                    service.playMusicLocally(songName)
                    return true
                }
            }

            activeWebSocket.get()?.let { socket ->
                if (socket.sendText(trimmed)) {
                    VoiceSessionState.appendChat(trimmed, fromUser = true)
                    return true
                }
            }

            if (companionActive.get()) {
                pendingTexts.add(trimmed)
                VoiceSessionState.appendChat(trimmed, fromUser = true)
                VoiceSessionState.update(
                    status = VoiceSessionState.state.value.status,
                    statusText = "文本已发送"
                )
                activeService.get()?.requestConnection()
                return true
            }

            val context = callerContext ?: applicationContextHolder.get()
            if (context?.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                pendingTexts.add(trimmed)
                context.startForegroundService(
                    Intent(context, VoiceForegroundService::class.java)
                )
                VoiceSessionState.appendChat(trimmed, fromUser = true)
                return true
            }

            VoiceSessionState.update(
                status = VoiceSessionState.state.value.status,
                statusText = "文本发送失败：缺少麦克风权限"
            )
            return false
        }
    }
}
