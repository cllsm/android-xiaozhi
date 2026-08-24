package com.xiaozhi.android.media

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.xiaozhi.android.core.SettingsState
import com.xiaozhi.android.core.VoiceSessionState
import com.xiaozhi.android.data.MusicHistoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class MusicSelectionOption(
    val number: Int,
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Int,
    val sourceName: String
)

data class MusicSelectionPrompt(
    val query: String,
    val options: List<MusicSelectionOption>,
    val autoSelectAtMillis: Long
)

object NativeMusicController {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val selectionPromptFlow = MutableStateFlow<MusicSelectionPrompt?>(null)
    private val selectionLock = Any()
    private var autoSelectionJob: Job? = null

    val selectionPrompt: StateFlow<MusicSelectionPrompt?> = selectionPromptFlow.asStateFlow()

    @Volatile
    private var pendingCandidateOptions: List<PendingMusicCandidate> = emptyList()

    @Volatile
    private var settings: SettingsState? = null

    @Volatile
    private var currentSong: String = ""

    @Volatile
    private var currentSongId: String = ""

    @Volatile
    private var currentSourceId: String = ""

    @Volatile
    private var hasTrack = false

    @Volatile
    private var paused = false

    @Volatile
    private var pausedByTts = false

    @Volatile
    private var autoAdvanceEnabled = false

    fun configure(newSettings: SettingsState) {
        settings = newSettings
    }

    fun searchAndPlay(songName: String, fromPlaybackQueue: Boolean = false): String {
        val activeSettings = settings ?: return "音乐服务尚未初始化"
        if (!activeSettings.musicEnabled) return "音乐工具未启用，请在设置中开启"
        if (songName.isBlank()) return "歌曲名不能为空"
        MusicSourcePlan.unavailableReason(activeSettings)?.let { return it }
        clearPendingSelection()
        MusicPlaybackState.update { it.copy(loading = true) }

        val searchResult = findSearchResult(activeSettings, songName)
        if (searchResult == null) {
            MusicPlaybackState.update { it.copy(loading = false) }
            return "未找到歌曲：$songName"
        }

        val options = searchResult.songs
            .distinctBy { it.displayName }
            .take(MAX_SELECTION_OPTIONS)
            .mapIndexed { index, song ->
                PendingMusicCandidate(
                    number = index + 1,
                    song = song,
                    sourceId = searchResult.source.id,
                    sourceName = searchResult.source.displayName
                )
            }
        if (options.size > 1 && !fromPlaybackQueue) {
            showSelectionPrompt(songName, options)
            MusicPlaybackState.update { it.copy(loading = false) }
            return formatSelectionPrompt(selectionPromptFlow.value ?: return "未找到歌曲")
        }

        val preferred = options.firstOrNull()
        val selection = resolvePreferredSelection(activeSettings, preferred)
            ?: selectPlayableSong(
                settings = activeSettings,
                songName = preferred?.song?.displayName ?: songName,
                skipSourceId = preferred?.sourceId
            )
            ?: return buildString {
                MusicPlaybackState.update { it.copy(loading = false) }
                append("未找到可播放歌曲：$songName")
                if (activeSettings.musicSourceMode == MusicSourcePlan.SOURCE_NETEASE) {
                    append("，请检查网易云 API 或版权限制")
                } else if (activeSettings.musicSourceMode == "auto") {
                    append("，多源均未返回可播放音频")
                }
            }

        return playSelection(selection)
    }

    fun hasPendingSelection(): Boolean {
        return selectionPromptFlow.value != null
    }

    fun pendingSelectionIndex(text: String): Int? {
        val index = MusicSelectionParser.extractSelection(text) ?: return null
        return selectionPromptFlow.value?.options
            ?.any { it.number == index }?.takeIf { it }?.let { index }
    }

    fun selectPendingCandidate(index: Int): String {
        val activeSettings = settings ?: return "音乐服务尚未初始化"
        if (!activeSettings.musicEnabled) return "音乐工具未启用，请在设置中开启"

        val candidate = takePendingCandidate(index) ?: return "请输入有效的序号"
        MusicPlaybackState.update { it.copy(loading = true) }

        val selection = resolvePreferredSelection(activeSettings, candidate)
            ?: selectPlayableSong(
                settings = activeSettings,
                songName = candidate.song.displayName,
                skipSourceId = candidate.sourceId
            )
            ?: return "所选歌曲暂无法播放：${candidate.song.displayName}".also {
                MusicPlaybackState.update { current -> current.copy(loading = false) }
            }

        return playSelection(selection)
    }

    fun clearPendingSelection() {
        synchronized(selectionLock) {
            autoSelectionJob?.cancel()
            autoSelectionJob = null
            pendingCandidateOptions = emptyList()
            selectionPromptFlow.value = null
        }
    }

    fun playAdjacent(offset: Int): String {
        if (currentSong.isBlank()) return "当前没有播放歌曲"
        val nextSong = MusicHistoryRepository.adjacentTitle(currentSong, offset)
            ?: return if (MusicHistoryRepository.queueHasMultipleTracks()) {
                "播放队列状态异常，请重新从最近播放中选择"
            } else {
                "最近播放列表里只有当前一首歌"
            }
        return searchAndPlay(nextSong, fromPlaybackQueue = true)
    }

    fun pause(source: String = "manual"): String {
        val latch = CountDownLatch(1)
        var message = ""
        mainHandler.post {
            message = try {
                if (!hasTrack || !paused) {
                    val player = playerField
                    if (player?.isPlaying == true) {
                        player.pause()
                        paused = true
                        pausedByTts = source == "tts"
                        MusicPlaybackState.update { it.copy(paused = true) }
                        "已暂停"
                    } else {
                        "没有正在播放的歌曲"
                    }
                } else {
                    "已经处于暂停状态"
                }
            } catch (_: Exception) {
                "暂停失败"
            }
            latch.countDown()
        }
        latch.await(ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return message.ifBlank { "暂停超时" }
    }

    fun resume(): String {
        val latch = CountDownLatch(1)
        var message = ""
        mainHandler.post {
            message = try {
                if (!hasTrack) {
                    "没有正在播放的歌曲"
                } else if (!paused) {
                    "当前未暂停"
                } else {
                    playerField?.start()
                    paused = false
                    pausedByTts = false
                    MusicPlaybackState.update { it.copy(paused = false) }
                    "已恢复播放"
                }
            } catch (_: Exception) {
                "恢复失败"
            }
            latch.countDown()
        }
        latch.await(ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return message.ifBlank { "恢复超时" }
    }

    fun stop(): String {
        autoAdvanceEnabled = false
        val latch = CountDownLatch(1)
        var message = ""
        mainHandler.post {
            message = try {
                if (!hasTrack) {
                    "没有正在播放的歌曲"
                } else {
                    releasePlayer()
                    "已停止"
                }
            } catch (_: Exception) {
                "停止失败"
            }
            latch.countDown()
        }
        latch.await(ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return message.ifBlank { "停止超时" }
    }

    fun seek(positionSeconds: Int): String {
        if (!hasTrack) return "没有正在播放的歌曲"
        val latch = CountDownLatch(1)
        var message = ""
        mainHandler.post {
            message = try {
                val player = playerField
                if (player == null) {
                    "跳转失败"
                } else {
                    player.seekTo(positionSeconds.coerceAtLeast(0) * 1000)
                    "跳转完成"
                }
            } catch (_: Exception) {
                "跳转失败"
            }
            latch.countDown()
        }
        latch.await(ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return message
    }

    fun currentPositionSeconds(): Int? {
        val latch = CountDownLatch(1)
        var position: Int? = null
        mainHandler.post {
            position = playerField?.currentPosition?.div(1000)
            latch.countDown()
        }
        latch.await(ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return position
    }

    fun isPausedByTts(): Boolean = pausedByTts

    fun getLyrics(): String {
        val activeSettings = settings ?: return "音乐服务尚未初始化"
        if (currentSong.isBlank()) return "当前没有播放歌曲"
        val source = createSources(activeSettings).firstOrNull { it.id == currentSourceId }
        return try {
            source?.lyrics(currentSongId) ?: "当前音乐源不可用"
        } catch (_: Exception) {
            "获取歌词失败"
        }
    }

    fun getLocalPlaylist(context: Context): String {
        val directory = File(context.cacheDir, "music")
        val files = directory.listFiles { file -> file.isFile }.orEmpty()
            .sortedBy { it.nameWithoutExtension.lowercase() }
        if (files.isEmpty()) return "本地缓存中没有音乐文件"
        return buildString {
            appendLine("本地音乐歌单 (共${files.size}首):")
            files.forEach { appendLine(it.nameWithoutExtension) }
        }
    }

    private var playerField: MediaPlayer? = null

    private data class SourceSearchResult(
        val source: MusicSource,
        val songs: List<MusicSong>
    )

    private data class PendingMusicCandidate(
        val number: Int,
        val song: MusicSong,
        val sourceId: String,
        val sourceName: String
    )

    private fun findSearchResult(
        settings: SettingsState,
        songName: String
    ): SourceSearchResult? {
        val errors = mutableListOf<String>()
        for (source in createSources(settings)) {
            try {
                val songs = source.searchSongs(songName)
                if (songs.isNotEmpty()) return SourceSearchResult(source, songs)
            } catch (error: Exception) {
                errors.add("${source.displayName}:${error.message ?: "请求失败"}")
            }
        }
        if (errors.isNotEmpty()) {
            Log.w("NativeMusic", "音乐搜索失败: ${errors.joinToString(" | ")}")
        }
        return null
    }

    private fun resolvePreferredSelection(
        settings: SettingsState,
        candidate: PendingMusicCandidate?
    ): MusicSelection? {
        candidate ?: return null
        val source = createSources(settings).firstOrNull { it.id == candidate.sourceId }
            ?: return null
        return try {
            val playback = source.resolvePlayback(candidate.song) ?: return null
            if (!MusicPlaybackProbe.isPlayableUrl(client, playback.url)) {
                Log.w("NativeMusic", "${source.displayName}直链不可播放")
                null
            } else {
                MusicSelection(source.id, source.displayName, candidate.song, playback)
            }
        } catch (error: Exception) {
            Log.w(
                "NativeMusic",
                "解析用户选择失败:${error.message ?: error.javaClass.simpleName}"
            )
            null
        }
    }

    private fun showSelectionPrompt(query: String, candidates: List<PendingMusicCandidate>) {
        val prompt = MusicSelectionPrompt(
            query = query,
            options = candidates.map { candidate ->
                MusicSelectionOption(
                    number = candidate.number,
                    title = candidate.song.title,
                    artist = candidate.song.artist,
                    album = candidate.song.album,
                    durationSeconds = candidate.song.durationSeconds,
                    sourceName = candidate.sourceName
                )
            },
            autoSelectAtMillis = System.currentTimeMillis() + AUTO_SELECTION_DELAY_MS
        )
        synchronized(selectionLock) {
            pendingCandidateOptions = candidates
            selectionPromptFlow.value = prompt
        }

        val timeoutJob = playbackScope.launch {
            delay(AUTO_SELECTION_DELAY_MS)
            if (selectionPromptFlow.value == prompt) {
                val result = selectPendingCandidate(1)
                VoiceSessionState.appendChat(result, fromUser = false)
            }
        }
        synchronized(selectionLock) {
            autoSelectionJob?.cancel()
            autoSelectionJob = timeoutJob
        }
    }

    private fun takePendingCandidate(index: Int): PendingMusicCandidate? {
        synchronized(selectionLock) {
            autoSelectionJob?.cancel()
            autoSelectionJob = null
            val candidate = pendingCandidateOptions.firstOrNull { it.number == index }
            if (candidate != null) {
                pendingCandidateOptions = emptyList()
                selectionPromptFlow.value = null
            }
            return candidate
        }
    }

    private fun formatSelectionPrompt(prompt: MusicSelectionPrompt): String {
        return buildString {
            appendLine("找到 ${prompt.options.size} 个版本，5 秒内回复序号选择：")
            prompt.options.forEach { option ->
                appendLine(
                    "${option.number}. ${option.title}" +
                        option.artist.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty() +
                        "（${option.sourceName}）"
                )
            }
        }.trimEnd()
    }

    private fun playSelection(selection: MusicSelection): String {
        currentSong = selection.song.displayName
        currentSongId = selection.song.songId
        currentSourceId = selection.sourceId
        if (!MusicHistoryRepository.isInPlaybackQueue(currentSong)) {
            MusicHistoryRepository.preparePlaybackQueue(currentSong)
        }
        autoAdvanceEnabled = true
        val hasAdjacentTracks = MusicHistoryRepository.queueHasMultipleTracks()
        MusicPlaybackState.update {
            MusicRuntimeState(
                loading = true,
                title = selection.song.displayName,
                sourceName = selection.sourceName,
                hasPrevious = hasAdjacentTracks,
                hasNext = hasAdjacentTracks
            )
        }
        return startPlayback(selection.playback.url, selection.playback.isPreview)
    }

    private fun selectPlayableSong(
        settings: SettingsState,
        songName: String,
        skipSourceId: String? = null
    ): MusicSelection? {
        val errors = mutableListOf<String>()
        var previewSelection: MusicSelection? = null
        for (source in createSources(settings)) {
            if (source.id == skipSourceId) continue
            try {
                val songs = source.searchSongs(songName)
                var playbackLookups = 0
                for (song in songs.take(MAX_CANDIDATES)) {
                    if (playbackLookups >= source.playbackLookupLimit) break
                    playbackLookups += 1
                    val playback = source.resolvePlayback(song) ?: continue
                    if (!MusicPlaybackProbe.isPlayableUrl(client, playback.url)) {
                        errors.add("${source.displayName}直链不可播放")
                        continue
                    }

                    val selection = MusicSelection(source.id, source.displayName, song, playback)
                    if (!playback.isPreview) return selection
                    if (previewSelection == null) previewSelection = selection
                }
                errors.add("${source.displayName}未返回可播放直链")
            } catch (error: Exception) {
                errors.add("${source.displayName}:${error.message ?: "请求失败"}")
            }
        }
        previewSelection?.let { return it }
        if (errors.isNotEmpty()) {
            Log.w("NativeMusic", "音乐多源选择失败: ${errors.joinToString(" | ")}")
        }
        return null
    }

    private fun createSources(settings: SettingsState): List<MusicSource> {
        return MusicSourcePlan.orderedSourceIds(settings).mapNotNull { sourceId ->
            when (sourceId) {
                MusicSourcePlan.SOURCE_KUWO -> KuwoMusicSource(client, settings)
                MusicSourcePlan.SOURCE_NETEASE_LOSSLESS ->
                    if (NeteaseLosslessGateway.isConfigured(settings)) {
                        NeteaseLosslessMusicSource(client, settings)
                    } else {
                        null
                    }
                MusicSourcePlan.SOURCE_NETEASE ->
                    if (settings.musicNeteaseApiUrl.isBlank()) null
                    else NeteaseMusicSource(client, settings)
                MusicSourcePlan.SOURCE_AUDIUS -> AudiusMusicSource(client)
                MusicSourcePlan.SOURCE_ITUNES -> ItunesMusicSource(client)
                else -> null
            }
        }
    }

    private fun startPlayback(url: String, isPreview: Boolean): String {
        val prepared = CountDownLatch(1)
        var message = ""
        mainHandler.post {
            releasePlayer()
            val player = MediaPlayer()
            playerField = player
            player.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC)
            MusicPlaybackState.update { it.copy(loading = true, paused = false) }
            player.setOnPreparedListener {
                it.start()
                hasTrack = true
                paused = false
                pausedByTts = false
                MusicPlaybackState.update {
                    MusicRuntimeState(
                        hasTrack = true,
                        paused = false,
                        title = currentSong,
                        sourceName = currentSourceName(),
                        hasPrevious = MusicHistoryRepository.queueHasMultipleTracks(),
                        hasNext = MusicHistoryRepository.queueHasMultipleTracks()
                    )
                }
                MusicHistoryRepository.recordPlayback(
                    title = currentSong,
                    sourceName = currentSourceName()
                )
                message = if (isPreview) "正在试听：$currentSong" else "正在播放：$currentSong"
                prepared.countDown()
            }
            player.setOnCompletionListener {
                hasTrack = false
                paused = false
                val shouldAdvance = autoAdvanceEnabled
                val finishedTitle = currentSong
                MusicPlaybackState.clear()
                if (shouldAdvance) {
                    playbackScope.launch {
                        MusicHistoryRepository.adjacentTitle(finishedTitle, 1)?.let { next ->
                            searchAndPlay(next, fromPlaybackQueue = true)
                        }
                    }
                }
            }
            player.setOnErrorListener { _, _, _ ->
                message = "播放失败"
                autoAdvanceEnabled = false
                releasePlayer()
                prepared.countDown()
                true
            }
            try {
                player.setDataSource(url)
                player.prepareAsync()
            } catch (error: Exception) {
                message = "播放失败：${error.message ?: error.javaClass.simpleName}"
                releasePlayer()
                prepared.countDown()
            }
        }
        return if (prepared.await(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            message
        } else {
            "播放超时"
        }
    }

    private fun releasePlayer() {
        playerField?.runCatching {
            stop()
            release()
        }
        playerField = null
        hasTrack = false
        paused = false
        pausedByTts = false
        MusicPlaybackState.clear()
    }

    private const val ACTION_TIMEOUT_SECONDS = 3L
    private const val PREPARE_TIMEOUT_SECONDS = 20L
    private const val MAX_CANDIDATES = 5
    private const val MAX_SELECTION_OPTIONS = 5
    private const val AUTO_SELECTION_DELAY_MS = 5_000L

    private fun currentSourceName(): String {
        val activeSettings = settings ?: return ""
        return createSources(activeSettings).firstOrNull { it.id == currentSourceId }
            ?.displayName.orEmpty()
    }
}
