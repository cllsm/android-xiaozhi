package com.xiaozhi.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.app.PendingIntent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.xiaozhi.android.MainActivity
import com.xiaozhi.android.audio.AudioInputEngine
import com.xiaozhi.android.audio.AudioOutputEngine
import com.xiaozhi.android.core.ConnectionStatus
import com.xiaozhi.android.core.ConnectionRecoveryPolicy
import com.xiaozhi.android.core.DeviceState
import com.xiaozhi.android.core.SettingsState
import com.xiaozhi.android.core.VoiceSessionState
import com.xiaozhi.android.core.UserErrorMessages
import com.xiaozhi.android.data.DeviceIdentityRepository
import com.xiaozhi.android.data.SettingsRepository
import com.xiaozhi.android.media.MusicIntentParser
import com.xiaozhi.android.media.NativeMusicController
import com.xiaozhi.android.media.VoiceMusicInterruptionPolicy
import com.xiaozhi.android.mcp.McpDispatcher
import com.xiaozhi.android.network.OtaClient
import com.xiaozhi.android.network.XiaozhiWebSocketClient
import com.xiaozhi.android.study.ReadingEvaluator
import com.xiaozhi.android.study.StudyMode
import com.xiaozhi.android.study.StudyObservationEngine
import com.xiaozhi.android.study.StudySessionManager
import com.xiaozhi.android.study.StudySessionState
import com.xiaozhi.android.wake.SherpaWakeWordEngine
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
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentLinkedQueue

class VoiceForegroundService : LifecycleService() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val active = AtomicBoolean(false)
    private var connectionJob: kotlinx.coroutines.Job? = null
    private val recoveryRequests = Channel<RecoveryTrigger>(Channel.CONFLATED)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var identityRepository: DeviceIdentityRepository
    private val otaClient = OtaClient()

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
        if (active.compareAndSet(false, true) && connectionJob == null) {
            startConnectionLoop()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        active.set(false)
        companionActive.set(false)
        activeService.set(null)
        activeWebSocket.set(null)
        pendingTexts.clear()
        conversationCommands.clear()
        connectionJob?.cancel()
        connectionJob = null
        unregisterNetworkRecoveryListener()
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
        NativeMusicController.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    private fun startConnectionLoop() {
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
                val trigger = timeoutMillis?.let { timeout ->
                    withTimeoutOrNull(timeout) { recoveryRequests.receive() }
                } ?: recoveryRequests.receive()
                return trigger ?: RecoveryTrigger.TIMER
            }

            suspend fun recoverAfterFailure(
                reason: String,
                retrySettings: SettingsState
            ) {
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
                        recoverAfterFailure("OTA 未返回完整 WebSocket 配置", settings)
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
                        recoverAfterFailure(
                            VoiceSessionState.state.value.statusText,
                            settings
                        )
                        continue
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val retrySettings = settingsRepository.settings.first()
                    recoverAfterFailure(
                        UserErrorMessages.from(error.message ?: "网络连接失败"),
                        retrySettings
                    )
                }
            }
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
        val mcpDispatcher = McpDispatcher(this, settings) { payload ->
            socket.sendMcpPayload(payload)
        }

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
                    drainPendingTexts(socket)
                    updateNotification(VoiceSessionState.state.value.statusText)
                    hello.complete(true)
                }

                    override fun onJson(message: org.json.JSONObject) {
                        if (message.optString("type") == "mcp") {
                            val payload = message.optJSONObject("payload")
                            if (payload != null) {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    runCatching { mcpDispatcher.handle(payload) }
                                        .onFailure { error ->
                                            VoiceSessionState.update(
                                                status = ConnectionStatus.Connected,
                                                statusText = "MCP 工具执行失败：" +
                                                    (error.message ?: error.javaClass.simpleName)
                                            )
                                        }
                                }
                            }
                        }
                    if (message.optString("type") == "tts") {
                        val text = message.optString("text")
                        VoiceSessionState.updateConversation(currentText = text)
                        if (text.isNotBlank() && message.optString("state") in FINAL_TEXT_STATES) {
                            VoiceSessionState.appendChat(text, fromUser = false)
                        }
                        val stateText = when (message.optString("state")) {
                            "start" -> "正在播放语音"
                            "stop" -> "语音播放完成"
                            else -> return
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
                        if (message.optString("state") == "start") {
                            NativeMusicController.pauseForVoiceInteraction()
                        }
                        if (message.optString("state") == "stop") {
                            startContinuousListening("继续聆听")
                            VoiceSessionState.updateConversation(currentText = "")
                        }
                        if (message.optString("state") == "stop" &&
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
                        if (text.isNotBlank() && (state.isBlank() || state == "final")) {
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
                    audioOutput?.enqueue(audio)
                }

                override fun onClosed(code: Int, reason: String) {
                    stopAudio()
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

        val connected = withTimeoutOrNull(HELLO_TIMEOUT_MS) { hello.await() } ?: false
        if (!connected) {
            socket.close()
            stopAudio()
            wakeModeJob?.cancel()
            clearActiveSocket(socket)
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
        wakeModeJob?.cancel()
        stopAudio()
        socket.close()
        clearActiveSocket(socket)
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
        if (active.get() && connectionJob == null) {
            startConnectionLoop()
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

    private fun buildNotification(text: String = "正在启动语音服务"): Notification {
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
            .setContentTitle("小智语音服务")
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
                "立即重连",
                reconnectIntent
            )
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
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
        }
    }

    private fun playMusicSelectionLocally(index: Int) {
        lifecycleScope.launch {
            NativeMusicController.configure(settingsRepository.settings.first())
            val result = withContext(Dispatchers.IO) {
                NativeMusicController.selectPendingCandidate(index)
            }
            VoiceSessionState.appendChat(result, fromUser = false)
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
        private const val LISTENING_MODE_AUTO = "auto"
        private const val LISTENING_MODE_REALTIME = "realtime"
        private val FINAL_TEXT_STATES = setOf("stop", "sentence_end")

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
            if (companionActive.get()) {
                conversationCommands.add(COMMAND_START_LISTENING)
                return
            }
            context.startService(
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

        fun isRunning(): Boolean {
            return companionActive.get()
        }

        fun sendText(text: String, callerContext: Context? = null): Boolean {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return false

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
                    statusText = "文本已排队，等待语音链路就绪"
                )
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
