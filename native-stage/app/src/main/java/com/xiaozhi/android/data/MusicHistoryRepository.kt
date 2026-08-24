package com.xiaozhi.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private val Context.musicHistoryStore by preferencesDataStore(name = "xiaozhi_music_history")

data class RecentMusicRecord(
    val title: String,
    val sourceName: String,
    val playedAt: Long,
    val playCount: Int
)

class MusicHistoryRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recordsFlow = MutableStateFlow<List<RecentMusicRecord>>(emptyList())
    private var playbackQueue: List<String> = emptyList()

    val records: StateFlow<List<RecentMusicRecord>> = recordsFlow.asStateFlow()

    init {
        scope.launch { restore() }
    }

    fun record(title: String, sourceName: String) {
        if (title.isBlank()) return
        scope.launch {
            val current = recordsFlow.value
            val next = buildList {
                val existing = current.firstOrNull { it.title == title }
                add(
                    RecentMusicRecord(
                        title = title,
                        sourceName = sourceName.ifBlank { "未知来源" },
                        playedAt = System.currentTimeMillis(),
                        playCount = (existing?.playCount ?: 0) + 1
                    )
                )
                addAll(current.filter { it.title != title })
            }.take(MAX_RECORDS)
            recordsFlow.value = next
            persist(next)
        }
    }

    fun clear() {
        scope.launch {
            recordsFlow.value = emptyList()
            playbackQueue = emptyList()
            persist(emptyList())
        }
    }

    @Synchronized
    fun preparePlaybackQueue(currentTitle: String) {
        val snapshot = recordsFlow.value.map { it.title }
        playbackQueue = if (snapshot.any { it == currentTitle }) {
            snapshot
        } else {
            listOf(currentTitle) + snapshot
        }
    }

    @Synchronized
    fun adjacentTitle(currentTitle: String, offset: Int): String? {
        val index = playbackQueue.indexOf(currentTitle)
        if (index < 0 || playbackQueue.size < 2) return null
        return playbackQueue[(index + offset).mod(playbackQueue.size)]
    }

    @Synchronized
    fun queueHasMultipleTracks(): Boolean = playbackQueue.size > 1

    @Synchronized
    fun isInPlaybackQueue(title: String): Boolean = playbackQueue.contains(title)

    fun close() {
        scope.cancel()
    }

    private suspend fun restore() {
        val serialized = appContext.musicHistoryStore.data
            .catch { error ->
                if (error !is IOException) throw error
            }
            .firstOrNull() ?: return
        val raw = serialized[Keys.Records] ?: return
        val restored = runCatching {
            val source = JSONArray(raw)
            buildList {
                for (index in 0 until source.length()) {
                    val item = source.optJSONObject(index) ?: continue
                    val title = item.optString("title").trim()
                    if (title.isNotBlank()) {
                        add(
                            RecentMusicRecord(
                                title = title,
                                sourceName = item.optString("source_name").ifBlank { "未知来源" },
                                playedAt = item.optLong("played_at"),
                                playCount = item.optInt("play_count", 1).coerceAtLeast(1)
                            )
                        )
                    }
                }
            }
        }.getOrNull() ?: emptyList()
        recordsFlow.value = restored.take(MAX_RECORDS)
    }

    private suspend fun persist(records: List<RecentMusicRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("title", record.title)
                    .put("source_name", record.sourceName)
                    .put("played_at", record.playedAt)
                    .put("play_count", record.playCount)
            )
        }
        runCatching {
            appContext.musicHistoryStore.edit { prefs ->
                prefs[Keys.Records] = array.toString()
            }
        }
    }

    private object Keys {
        val Records = stringPreferencesKey("records")
    }

    companion object {
        private const val MAX_RECORDS = 50

        @Volatile
        private var instance: MusicHistoryRepository? = null

        fun initialize(context: Context): MusicHistoryRepository {
            return instance ?: synchronized(this) {
                instance ?: MusicHistoryRepository(context).also { instance = it }
            }
        }

        fun recordPlayback(title: String, sourceName: String) {
            instance?.record(title, sourceName)
        }

        fun preparePlaybackQueue(currentTitle: String) {
            instance?.preparePlaybackQueue(currentTitle)
        }

        fun adjacentTitle(currentTitle: String, offset: Int): String? =
            instance?.adjacentTitle(currentTitle, offset)

        fun queueHasMultipleTracks(): Boolean = instance?.queueHasMultipleTracks() == true

        fun isInPlaybackQueue(title: String): Boolean =
            instance?.isInPlaybackQueue(title) == true
    }
}
