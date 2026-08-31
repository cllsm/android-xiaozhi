package com.xiaozhi.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xiaozhi.android.core.ChatMessage
import com.xiaozhi.android.core.VoiceSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private val Context.chatHistoryStore by preferencesDataStore(name = "xiaozhi_chat_history")

class ChatHistoryRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            restore()
            scope.launch {
                VoiceSessionState.chat
                    .drop(1)
                    .debounce(SAVE_DEBOUNCE_MS)
                    .collectLatest { messages -> save(messages) }
            }
        }
    }

    private suspend fun restore() {
        val serialized = appContext.chatHistoryStore.data
            .catch { error ->
                if (error !is IOException) throw error
            }
            .firstOrNull() ?: return
        val raw = serialized[Keys.Messages] ?: return
        val messages = runCatching {
            val source = JSONArray(raw)
            buildList {
                for (index in 0 until source.length()) {
                    val item = source.optJSONObject(index) ?: continue
                    val text = item.optString("text")
                    if (text.isNotBlank()) {
                        add(
                            ChatMessage(
                                id = item.optLong("id"),
                                text = text,
                                fromUser = item.optBoolean("from_user"),
                                timestamp = item.optLong("timestamp"),
                                imagePath = item.optString("image_path").takeIf { it.isNotBlank() },
                                thumbnailPath = item.optString("thumbnail_path").takeIf { it.isNotBlank() }
                            )
                        )
                    }
                }
            }
        }.getOrNull() ?: emptyList()
        VoiceSessionState.restoreChat(messages)
    }

    private suspend fun save(messages: List<ChatMessage>) {
        val array = JSONArray()
        messages.takeLast(VoiceSessionState.chatHistoryLimit).forEach { message ->
            array.put(
                JSONObject()
                    .put("id", message.id)
                    .put("text", message.text)
                    .put("from_user", message.fromUser)
                    .put("timestamp", message.timestamp)
                    .apply {
                        message.imagePath?.let { put("image_path", it) }
                        message.thumbnailPath?.let { put("thumbnail_path", it) }
                    }
            )
        }
        runCatching {
            appContext.chatHistoryStore.edit { prefs ->
                prefs[Keys.Messages] = array.toString()
            }
        }
    }

    suspend fun replace(messages: List<ChatMessage>) {
        val retained = messages.takeLast(VoiceSessionState.chatHistoryLimit)
        VoiceSessionState.restoreChat(retained)
        save(retained)
    }

    fun close() {
        scope.cancel()
    }

    private object Keys {
        val Messages = stringPreferencesKey("messages")
    }

    companion object {
        private const val SAVE_DEBOUNCE_MS = 500L

        @Volatile
        private var instance: ChatHistoryRepository? = null

        fun initialize(context: Context): ChatHistoryRepository {
            return instance ?: synchronized(this) {
                instance ?: ChatHistoryRepository(context).also { instance = it }
            }
        }
    }
}
