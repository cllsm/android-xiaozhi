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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    val autoSelectAtMillis: Long,
    val pageSize: Int = MUSIC_SELECTION_PAGE_SIZE
) {
    val pageCount: Int
        get() = if (options.isEmpty()) {
            1
        } else {
            (options.size + pageSize - 1) / pageSize
        }
}

const val MUSIC_SELECTION_PAGE_SIZE = 5

object NativeMusicController {
    private val client = OkHttpClient.Builder()
        .callTimeout(8, TimeUnit.SECONDS)
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
    private var pausedForVoiceInteraction = false

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
        val rememberedPreference = if (
            activeSettings.musicRememberSelection && !fromPlaybackQueue
        ) {
            MusicHistoryRepository.selectionPreference(songName)
        } else {
            null
        }
        val preferredByHistory = rememberedPreference?.let { preference ->
            options.firstOrNull { candidate ->
                candidate.song.displayName == preference.title &&
                    candidate.sourceId == preference.sourceId
            } ?: options.firstOrNull { candidate ->
                candidate.sourceId == preference.sourceId
            }
        }

        if (preferredByHistory != null) {
            val selection = resolvePreferredSelection(activeSettings, preferredByHistory)
                ?: selectPlayableSong(
                    settings = activeSettings,
                    query = songName,
                    preferredCandidate = preferredByHistory
                )
            if (selection != null) {
                return playSelection(
                    selection,
                    switchedFromSourceName = preferredByHistory.sourceName.takeIf {
                        selection.sourceId != preferredByHistory.sourceId
                    }
                )
            }
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
                query = songName,
                preferredCandidate = preferred
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

        return playSelection(
            selection,
            switchedFromSourceName = if (
                preferred != null &&
                selection.sourceId != preferred.sourceId
            ) {
                preferred.sourceName
            } else {
                null
            }
        )
    }

    fun hasPendingSelection(): Boolean {
        return selectionPromptFlow.value != null
    }

    fun postponeSelectionAutoPlay() {
        synchronized(selectionLock) {
            val prompt = selectionPromptFlow.value ?: return
            val nextPrompt = prompt.copy(
                autoSelectAtMillis = System.currentTimeMillis() + AUTO_SELECTION_DELAY_MS
            )
            selectionPromptFlow.value = nextPrompt
            autoSelectionJob?.cancel()
            autoSelectionJob = playbackScope.launch {
                delay(AUTO_SELECTION_DELAY_MS)
                if (selectionPromptFlow.value == nextPrompt) {
                    val result = selectPendingCandidate(1, rememberSelection = false)
                    VoiceSessionState.appendChat(result, fromUser = false)
                }
            }
        }
    }

    fun pendingSelectionIndex(text: String): Int? {
        val index = MusicSelectionParser.extractSelection(text) ?: return null
        return selectionPromptFlow.value?.options
            ?.any { it.number == index }?.takeIf { it }?.let { index }
    }

    fun selectPendingCandidate(index: Int, rememberSelection: Boolean = true): String {
        val activeSettings = settings ?: return "音乐服务尚未初始化"
        if (!activeSettings.musicEnabled) return "音乐工具未启用，请在设置中开启"

        val promptQuery = selectionPromptFlow.value?.query
        val candidate = takePendingCandidate(index) ?: return "请输入有效的序号"
        Log.i(
            "NativeMusic",
            "选择歌曲:id=${candidate.song.songId},title=${candidate.song.title}," +
                "artist=${candidate.song.artist},source=${candidate.sourceId}"
        )
        val query = promptQuery ?: candidate.song.title
        MusicPlaybackState.update { it.copy(loading = true) }

        val selection = resolvePreferredSelection(activeSettings, candidate)
            ?: selectPlayableSong(
                settings = activeSettings,
                query = query,
                preferredCandidate = candidate
            )
            ?: return "所选歌曲暂无法播放：${candidate.song.displayName}".also {
                MusicPlaybackState.update { current -> current.copy(loading = false) }
            }
        if (selection.sourceId != candidate.sourceId) {
            Log.w(
                "NativeMusic",
                "同曲跨源匹配:query=$query,title=${selection.song.title}," +
                    "artist=${selection.song.artist},from=${candidate.sourceId}," +
                    "to=${selection.sourceId},songId=${selection.song.songId}"
            )
        }

        val switchedFromSourceName = candidate.sourceName.takeIf {
            selection.sourceId != candidate.sourceId
        }
        val playbackResult = playSelection(selection, switchedFromSourceName)
        if (
            rememberSelection &&
            activeSettings.musicRememberSelection &&
            playbackResult.startsWith("正在")
        ) {
            MusicHistoryRepository.rememberSelection(
                query = query,
                title = selection.song.displayName,
                sourceId = selection.sourceId,
                sourceName = selection.sourceName
            )
        }
        return playbackResult
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
                        pausedForVoiceInteraction = source != "manual"
                        MusicPlaybackState.update { it.copy(paused = true) }
                        "已暂停"
                    } else {
                        "没有正在播放的歌曲"
                    }
                } else {
                    if (source == "manual") pausedForVoiceInteraction = false
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
                    pausedForVoiceInteraction = false
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

    fun isPausedForVoiceInteraction(): Boolean = pausedForVoiceInteraction
    fun pauseForVoiceInteraction() {
        if (!hasTrack || paused) return
        if (pause(source = "voice") == "已暂停") {
            pausedForVoiceInteraction = true
        }
    }

    fun resumeAfterVoiceInteraction() {
        if (pausedForVoiceInteraction) resume()
    }

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
            Log.i(
                "NativeMusic",
                "播放地址解析成功:source=${source.id},songId=${candidate.song.songId}"
            )
            if (!MusicPlaybackProbe.isPlayableUrl(client, playback.url)) {
                Log.w(
                    "NativeMusic",
                    "${source.displayName}直链不可播放:host=" +
                        playback.url.toHttpUrlOrNull()?.host.orEmpty()
                )
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
                val result = selectPendingCandidate(1, rememberSelection = false)
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
        val visibleOptions = prompt.options.take(prompt.pageSize)
        return buildString {
            appendLine("找到 ${prompt.options.size} 个版本，5 秒内回复序号选择：")
            visibleOptions.forEach { option ->
                appendLine(
                    "${option.number}. ${option.title}" +
                        option.artist.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty() +
                        "（${option.sourceName}）"
                )
            }
            if (prompt.options.size > visibleOptions.size) {
                appendLine("其余版本可在选择弹窗中翻页查看。")
            }
        }.trimEnd()
    }

    private fun playSelection(
        selection: MusicSelection,
        switchedFromSourceName: String? = null
    ): String {
        currentSong = selection.song.displayName
        currentSongId = selection.song.songId
        currentSourceId = selection.sourceId
        if (!MusicHistoryRepository.isInPlaybackQueue(currentSong)) {
            MusicHistoryRepository.preparePlaybackQueue(currentSong)
        }
        autoAdvanceEnabled = true
        val hasAdjacentTracks = MusicHistoryRepository.queueHasMultipleTracks()
        val sourceName = selection.sourceName
        MusicPlaybackState.update {
            MusicRuntimeState(
                loading = true,
                title = selection.song.displayName,
                sourceName = sourceName,
                hasPrevious = hasAdjacentTracks,
                hasNext = hasAdjacentTracks
            )
        }
        return startPlayback(
            url = selection.playback.url,
            isPreview = selection.playback.isPreview,
            hasAdjacentTracks = hasAdjacentTracks,
            sourceName = sourceName,
            switchedFromSourceName = switchedFromSourceName
        )
    }

    private fun selectPlayableSong(
        settings: SettingsState,
        query: String,
        preferredCandidate: PendingMusicCandidate?
    ): MusicSelection? {
        val errors = mutableListOf<String>()
        var previewSelection: MusicSelection? = null
        for (source in createSources(settings)) {
            if (preferredCandidate != null && source.id == preferredCandidate.sourceId) continue
            try {
                val songs = if (preferredCandidate == null) {
                    source.searchSongs(query)
                } else {
                    MusicSelectionPolicy.prioritizeFallbackSongs(
                        songs = source.searchSongs(query),
                        preferred = preferredCandidate.song
                    )
                }
                if (songs.isEmpty()) {
                    errors.add("${source.displayName}未返回匹配歌曲")
                    continue
                }
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

    private fun startPlayback(
        url: String,
        isPreview: Boolean,
        hasAdjacentTracks: Boolean,
        sourceName: String,
        switchedFromSourceName: String? = null
    ): String {
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
                pausedForVoiceInteraction = false
                MusicPlaybackState.update {
                    MusicRuntimeState(
                        hasTrack = true,
                        paused = false,
                        title = currentSong,
                        sourceName = sourceName,
                        hasPrevious = hasAdjacentTracks,
                        hasNext = hasAdjacentTracks
                    )
                }
                MusicHistoryRepository.recordPlayback(
                    title = currentSong,
                    sourceName = sourceName
                )
                val sourceMessage = if (switchedFromSourceName == null) {
                    "$currentSong（$sourceName）"
                } else {
                    "$currentSong（$sourceName，原${switchedFromSourceName}不可播已切换）"
                }
                message = if (isPreview) {
                    "正在试听：$sourceMessage"
                } else {
                    "正在播放：$sourceMessage"
                }
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
        pausedForVoiceInteraction = false
        MusicPlaybackState.clear()
    }

    private const val ACTION_TIMEOUT_SECONDS = 3L
    private const val PREPARE_TIMEOUT_SECONDS = 20L
    private const val MAX_CANDIDATES = 5
    private const val MAX_SELECTION_OPTIONS = 20
    private const val AUTO_SELECTION_DELAY_MS = 5_000L

    private fun currentSourceName(): String {
        val activeSettings = settings ?: return ""
        return createSources(activeSettings).firstOrNull { it.id == currentSourceId }
            ?.displayName.orEmpty()
    }
}
