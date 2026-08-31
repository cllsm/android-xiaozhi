package com.xiaozhi.android.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object VoiceSessionState {
    private val stateFlow = MutableStateFlow(VoiceRuntimeState())
    private val chatFlow = MutableStateFlow<List<ChatMessage>>(emptyList())

    val state: StateFlow<VoiceRuntimeState> = stateFlow.asStateFlow()
    val chat: StateFlow<List<ChatMessage>> = chatFlow.asStateFlow()

    @Volatile
    var chatHistoryLimit: Int = DEFAULT_CHAT_HISTORY_LIMIT
        private set

    fun setChatHistoryLimit(limit: Int) {
        val normalized = limit.coerceIn(MIN_CHAT_HISTORY_LIMIT, MAX_CHAT_HISTORY_LIMIT)
        chatHistoryLimit = normalized
        val current = stateFlow.value
        if (current.chat.size > normalized) {
            val retained = current.chat.takeLast(normalized)
            stateFlow.value = current.copy(chat = retained)
            chatFlow.value = retained
        }
    }

    fun restoreChat(messages: List<ChatMessage>) {
        val restored = messages.takeLast(chatHistoryLimit)
        stateFlow.value = stateFlow.value.copy(chat = restored)
        chatFlow.value = restored
    }

    fun update(
        status: ConnectionStatus,
        statusText: String,
        activationCode: String = stateFlow.value.activationCode,
        deviceId: String = stateFlow.value.deviceId,
        clientId: String = stateFlow.value.clientId,
         deviceState: DeviceState = stateFlow.value.deviceState,
         wakeWordEnabled: Boolean = stateFlow.value.wakeWordEnabled
    ) {
        stateFlow.value = stateFlow.value.copy(
            status = status,
            statusText = statusText,
            activationCode = activationCode,
            deviceId = deviceId,
            clientId = clientId,
            deviceState = deviceState,
             wakeWordEnabled = wakeWordEnabled
        )
    }

    fun updateRecovery(
        waitingForNetwork: Boolean? = null,
        autoRecoveryEnabled: Boolean? = null,
        recoveryAttempt: Int? = null,
        recoveryLimit: Int? = null
    ) {
        val current = stateFlow.value
        val newState = current.copy(
            waitingForNetwork = waitingForNetwork ?: current.waitingForNetwork,
            autoRecoveryEnabled = autoRecoveryEnabled ?: current.autoRecoveryEnabled,
            recoveryAttempt = (recoveryAttempt ?: current.recoveryAttempt).coerceAtLeast(0),
            recoveryLimit = (recoveryLimit ?: current.recoveryLimit).coerceAtLeast(0)
        )
        if (newState != current) stateFlow.value = newState
    }

    fun updateConversation(
        currentText: String? = null,
        emotion: String? = null
    ) {
        stateFlow.value = stateFlow.value.copy(
            currentText = currentText ?: stateFlow.value.currentText,
            emotion = emotion ?: stateFlow.value.emotion
        )
    }

    fun appendChat(
        text: String,
        fromUser: Boolean,
        imagePath: String? = null,
        thumbnailPath: String? = null
    ) {
        if (text.isBlank()) return
        val current = stateFlow.value
        val lastMessage = current.chat.lastOrNull()
        if (
            fromUser &&
            lastMessage?.fromUser == true &&
            lastMessage.text == text &&
            lastMessage.imagePath == imagePath
        ) return
        stateFlow.value = current.copy(
            chat = (
                current.chat +
                    ChatMessage(
                        id = (current.chat.lastOrNull()?.id ?: 0L) + 1L,
                        text = text,
                        fromUser = fromUser,
                        timestamp = System.currentTimeMillis(),
                        imagePath = imagePath,
                        thumbnailPath = thumbnailPath
                    )
                ).takeLast(chatHistoryLimit)
        )
        chatFlow.value = stateFlow.value.chat
    }

    fun updateLevels(inputLevel: Float? = null, outputLevel: Float? = null) {
        val current = stateFlow.value
        val newInput = inputLevel ?: current.inputLevel
        val newOutput = outputLevel ?: current.outputLevel
        if (newInput == current.inputLevel && newOutput == current.outputLevel) return
        stateFlow.value = current.copy(inputLevel = newInput, outputLevel = newOutput)
    }

    fun clearChat() {
        stateFlow.value = stateFlow.value.copy(chat = emptyList())
        chatFlow.value = emptyList()
    }

    const val DEFAULT_CHAT_HISTORY_LIMIT = 200
    const val MIN_CHAT_HISTORY_LIMIT = 50
    const val MAX_CHAT_HISTORY_LIMIT = 500
}
