package com.xiaozhi.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

data class MusicSelectionPreference(
    val query: String,
    val title: String,
    val sourceId: String,
    val sourceName: String,
    val updatedAt: Long = System.currentTimeMillis()
)

class MusicHistoryRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val restored = CompletableDeferred<Unit>()
    private val writeMutex = Mutex()
    private val recordsFlow = MutableStateFlow<List<RecentMusicRecord>>(emptyList())
    @Volatile
    private var selectionPreferences: Map<String, MusicSelectionPreference> = emptyMap()
    private var playbackQueue: List<String> = emptyList()

    val records: StateFlow<List<RecentMusicRecord>> = recordsFlow.asStateFlow()

    init {
        scope.launch {
            try {
                restore()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            } finally {
                restored.complete(Unit)
            }
        }
    }

    fun record(title: String, sourceName: String) {
        if (title.isBlank()) return
        scope.launch {
            restored.await()
            writeMutex.withLock {
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
                persist(next, selectionPreferences)
            }
        }
    }

    fun clear() {
        scope.launch {
            restored.await()
            writeMutex.withLock {
                recordsFlow.value = emptyList()
                selectionPreferences = emptyMap()
                playbackQueue = emptyList()
                persist(emptyList(), emptyMap())
            }
        }
    }

    fun rememberSelection(preference: MusicSelectionPreference) {
        if (preference.query.isBlank() || preference.title.isBlank()) return
        waitForRestore()
        val normalizedQuery = normalizeQuery(preference.query)
        synchronized(selectionPreferences) {
            if (selectionPreferences[normalizedQuery] == preference) return
            val next = selectionPreferences + (normalizedQuery to preference)
            selectionPreferences = next.entries
                .sortedByDescending { it.value.updatedAt }
                .take(MAX_SELECTION_PREFERENCES)
                .associate { it.key to it.value }
        }
        scope.launch {
            restored.await()
            writeMutex.withLock {
                persist(recordsFlow.value, selectionPreferences)
            }
        }
    }

    fun selectionPreference(query: String): MusicSelectionPreference? =
        waitForRestore().let { selectionPreferences[normalizeQuery(query)] }

    @Synchronized
    fun preparePlaybackQueue(currentTitle: String) {
        waitForRestore()
        val snapshot = recordsFlow.value.map { it.title }
        playbackQueue = if (snapshot.any { it == currentTitle }) {
            snapshot
        } else {
            listOf(currentTitle) + snapshot
        }
    }

    @Synchronized
    fun adjacentTitle(currentTitle: String, offset: Int): String? {
        waitForRestore()
        val index = playbackQueue.indexOf(currentTitle)
        if (index < 0 || playbackQueue.size < 2) return null
        return playbackQueue[(index + offset).mod(playbackQueue.size)]
    }

    @Synchronized
    fun queueHasMultipleTracks(): Boolean {
        waitForRestore()
        return playbackQueue.size > 1
    }

    @Synchronized
    fun isInPlaybackQueue(title: String): Boolean {
        waitForRestore()
        return playbackQueue.contains(title)
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun restore() {
        val serialized = appContext.musicHistoryStore.data
            .catch { error ->
                if (error !is IOException) throw error
            }
            .firstOrNull() ?: return
        val raw = serialized[Keys.Records]
        if (raw != null) {
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
                                    sourceName = item.optString("source_name")
                                        .ifBlank { "未知来源" },
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

        selectionPreferences = runCatching {
            val source = JSONArray(serialized[Keys.SelectionPreferences] ?: "[]")
            buildMap {
                for (index in 0 until source.length()) {
                    val item = source.optJSONObject(index) ?: continue
                    val query = item.optString("query").trim()
                    val title = item.optString("title").trim()
                    if (query.isNotBlank() && title.isNotBlank()) {
                        put(
                            normalizeQuery(query),
                            MusicSelectionPreference(
                                query = query,
                                title = title,
                                sourceId = item.optString("source_id"),
                                sourceName = item.optString("source_name")
                                    .ifBlank { "未知来源" },
                                updatedAt = item.optLong(
                                    "updated_at",
                                    System.currentTimeMillis()
                                )
                            )
                        )
                    }
                }
            }
        }.getOrNull() ?: emptyMap()
    }

    private suspend fun persist(
        records: List<RecentMusicRecord>,
        preferences: Map<String, MusicSelectionPreference>
    ) {
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
        val preferenceArray = JSONArray()
        preferences.values.forEach { preference ->
            preferenceArray.put(
                JSONObject()
                    .put("query", preference.query)
                    .put("title", preference.title)
                    .put("source_id", preference.sourceId)
                    .put("source_name", preference.sourceName)
                    .put("updated_at", System.currentTimeMillis())
            )
        }
        runCatching {
            appContext.musicHistoryStore.edit { prefs ->
                prefs[Keys.Records] = array.toString()
                prefs[Keys.SelectionPreferences] = preferenceArray.toString()
            }
        }
    }

    private fun normalizeQuery(query: String) = query.trim().lowercase()

    private fun waitForRestore() {
        if (restored.isCompleted) return
        runBlocking { restored.await() }
    }

    private object Keys {
        val Records = stringPreferencesKey("records")
        val SelectionPreferences = stringPreferencesKey("selection_preferences")
    }

    companion object {
        private const val MAX_RECORDS = 50
        private const val MAX_SELECTION_PREFERENCES = 30

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

        fun rememberSelection(
            query: String,
            title: String,
            sourceId: String,
            sourceName: String
        ) {
            instance?.rememberSelection(
                MusicSelectionPreference(query, title, sourceId, sourceName)
            )
        }

        fun selectionPreference(query: String): MusicSelectionPreference? =
            instance?.selectionPreference(query)
    }
}
