package com.xiaozhi.android.media

import com.xiaozhi.android.core.SettingsState
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class MusicSong(
    val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Int,
    val onlinePlayable: Boolean,
    val likelyFullPlayback: Boolean,
    val resolvedPlaybackUrl: String? = null
) {
    val displayName: String
        get() = listOf(title, artist, album)
            .filter { it.isNotBlank() }
            .joinToString(" - ")
}

data class MusicPlayback(
    val url: String,
    val isPreview: Boolean
)

data class MusicSelection(
    val sourceId: String,
    val sourceName: String,
    val song: MusicSong,
    val playback: MusicPlayback
)

interface MusicSource {
    val id: String
    val displayName: String
    val playbackLookupLimit: Int get() = 5
    fun searchSongs(songName: String): List<MusicSong>
    fun resolvePlayback(song: MusicSong): MusicPlayback?
    fun lyrics(songId: String): String
}

object MusicSourcePlan {
    const val SOURCE_KUWO = "kuwo"
    const val SOURCE_NETEASE_LOSSLESS = "netease_lossless"
    const val SOURCE_NETEASE = "netease"
    const val SOURCE_AUDIUS = "audius"
    const val SOURCE_ITUNES = "itunes"

    fun orderedSourceIds(settings: SettingsState): List<String> {
        val neteaseConfigured = isHttpsUrl(settings.musicNeteaseApiUrl)
        val losslessConfigured = NeteaseLosslessGateway.isConfigured(settings)
        return when (settings.musicSourceMode) {
            SOURCE_KUWO -> listOf(SOURCE_KUWO)
            SOURCE_NETEASE_LOSSLESS ->
                if (losslessConfigured) listOf(SOURCE_NETEASE_LOSSLESS) else emptyList()
            SOURCE_NETEASE -> if (neteaseConfigured) listOf(SOURCE_NETEASE) else emptyList()
            else -> buildList {
                if (losslessConfigured) add(SOURCE_NETEASE_LOSSLESS)
                add(SOURCE_KUWO)
                if (neteaseConfigured) add(SOURCE_NETEASE)
                add(SOURCE_AUDIUS)
                add(SOURCE_ITUNES)
            }
        }
    }

    fun unavailableReason(settings: SettingsState): String? {
        return when {
            settings.musicSourceMode == SOURCE_NETEASE &&
                settings.musicNeteaseApiUrl.isBlank() ->
                "选择网易云 API 时需要填写 API 地址"
            settings.musicSourceMode == SOURCE_NETEASE &&
                !isHttpsUrl(settings.musicNeteaseApiUrl) ->
                "网易云 API 地址必须是 HTTPS"
            settings.musicSourceMode == SOURCE_NETEASE_LOSSLESS &&
                !NeteaseLosslessGateway.isConfigured(settings) ->
                "选择网易云无损时需要填写 HTTPS 网关地址和 AppKey"
            else -> null
        }
    }

    private fun isHttpsUrl(value: String): Boolean {
        val url = value.trim().toHttpUrlOrNull() ?: return false
        return url.isHttps && url.host.isNotEmpty()
    }
}

object NeteaseLosslessGateway {
    fun isConfigured(settings: SettingsState): Boolean {
        return settings.musicNeteaseLosslessAppKey.isNotBlank() &&
            settings.musicNeteaseLosslessApiUrl.trim().toHttpUrlOrNull()
                ?.let { it.isHttps && it.host.isNotEmpty() } == true
    }

    fun searchUrl(settings: SettingsState, keyword: String): HttpUrl? {
        return requestUrl(settings, "search")
            ?.newBuilder()
            ?.addQueryParameter("keyword", keyword)
            ?.addQueryParameter("limit", "20")
            ?.build()
    }

    fun songUrl(settings: SettingsState, songId: String, level: String): HttpUrl? {
        return requestUrl(settings, "song")
            ?.newBuilder()
            ?.addQueryParameter("id", songId)
            ?.addQueryParameter("level", level)
            ?.build()
    }

    fun playbackLevels(requested: String): List<String> {
        val preferred = requested.takeIf {
            it in setOf("standard", "exhigh", "lossless", "hires", "jyeffect", "sky", "jymaster")
        } ?: "lossless"
        return when (preferred) {
            "standard" -> listOf("standard")
            "exhigh" -> listOf("exhigh", "standard")
            else -> listOf(preferred, "lossless", "exhigh", "standard")
        }.distinct()
    }

    fun requestBuilder(settings: SettingsState, url: HttpUrl): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-App-Key", settings.musicNeteaseLosslessAppKey.trim())
    }

    private fun requestUrl(settings: SettingsState, action: String): HttpUrl? {
        val base = settings.musicNeteaseLosslessApiUrl
            .trim()
            .toHttpUrlOrNull()
            ?.takeIf { it.isHttps && it.host.isNotEmpty() }
            ?: return null
        return base.newBuilder()
            .setQueryParameter("api_path", "wy_music")
            .addQueryParameter("action", action)
            .build()
    }
}

class NeteaseLosslessMusicSource(
    private val client: OkHttpClient,
    private val settings: SettingsState
) : MusicSource {
    override val id = MusicSourcePlan.SOURCE_NETEASE_LOSSLESS
    override val displayName = "网易云无损"
    override val playbackLookupLimit = 1

    override fun searchSongs(songName: String): List<MusicSong> {
        val url = NeteaseLosslessGateway.searchUrl(settings, songName)
            ?: throw IllegalStateException("网易云无损网关地址无效")
        val json = executeJson(NeteaseLosslessGateway.requestBuilder(settings, url).build())
        return NeteaseLosslessMusicSelector.parseSearch(json)
    }

    override fun resolvePlayback(song: MusicSong): MusicPlayback? {
        val levels = NeteaseLosslessGateway.playbackLevels(settings.musicDefaultQuality)
        for (level in levels) {
            val url = NeteaseLosslessGateway.songUrl(settings, song.songId, level)
                ?: throw IllegalStateException("网易云无损网关地址无效")
            val json = executeJson(NeteaseLosslessGateway.requestBuilder(settings, url).build())
            val playbackUrl = NeteaseLosslessMusicSelector.findPlaybackUrl(json) ?: continue
            if (playbackUrl.startsWith("https://") || playbackUrl.startsWith("http://")) {
                return MusicPlayback(playbackUrl, isPreview = false)
            }
        }
        return null
    }

    override fun lyrics(songId: String): String = "网易云无损接口暂不支持歌词"

    private fun executeJson(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("${displayName}网关返回 HTTP ${response.code}")
            }
            val json = JSONObject(response.body?.string().orEmpty())
            validateGatewayResponse(json)
            return json
        }
    }

    private fun validateGatewayResponse(json: JSONObject) {
        val payload = json.optJSONObject("data") ?: return
        val innerCode = payload.optInt("code", 200)
        if (innerCode == 200) return

        val error = payload.optString("error")
            .ifBlank { payload.optString("msg") }
            .ifBlank { "请求失败" }
        throw IllegalStateException("网易云无损网关返回 $innerCode：$error")
    }
}

class AudiusMusicSource(
    private val client: OkHttpClient
) : MusicSource {
    override val id = MusicSourcePlan.SOURCE_AUDIUS
    override val displayName = "Audius"

    override fun searchSongs(songName: String): List<MusicSong> {
        val url = SEARCH_URL.toHttpUrlOrNull()?.newBuilder()?.apply {
            addQueryParameter("query", songName)
            addQueryParameter("app_name", APP_NAME)
        }?.build() ?: throw IllegalStateException("Audius 搜索地址无效")

        val json = executeJson(
            Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()
        )
        val songs = json.optJSONArray("data")?.let { list ->
            (0 until list.length()).mapNotNull { index ->
                list.optJSONObject(index)?.let(AudiusMusicSelector::fromSearchItem)
            }
        }.orEmpty()
        return AudiusMusicSelector.prioritize(songs)
    }

    override fun resolvePlayback(song: MusicSong): MusicPlayback? {
        if (!song.onlinePlayable) return null
        return MusicPlayback(
            url = "$STREAM_BASE_URL${song.songId}/stream?app_name=$APP_NAME",
            isPreview = false
        )
    }

    override fun lyrics(songId: String): String = "Audius 暂不支持歌词"

    private fun executeJson(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("${displayName}接口返回 HTTP ${response.code}")
            }
            return JSONObject(response.body?.string().orEmpty())
        }
    }

    private companion object {
        const val SEARCH_URL = "https://api.audius.co/v1/tracks/search"
        const val STREAM_BASE_URL = "https://api.audius.co/v1/tracks/"
        const val APP_NAME = "xiaozhi-android"
    }
}

class ItunesMusicSource(
    private val client: OkHttpClient
) : MusicSource {
    override val id = MusicSourcePlan.SOURCE_ITUNES
    override val displayName = "iTunes 试听"

    override fun searchSongs(songName: String): List<MusicSong> {
        val url = SEARCH_URL.toHttpUrlOrNull()?.newBuilder()?.apply {
            addQueryParameter("term", songName)
            addQueryParameter("media", "music")
            addQueryParameter("limit", "20")
            addQueryParameter("country", "CN")
        }?.build() ?: throw IllegalStateException("iTunes 搜索地址无效")

        val json = executeJson(
            Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()
        )
        val songs = json.optJSONArray("results")?.let { list ->
            (0 until list.length()).mapNotNull { index ->
                list.optJSONObject(index)?.let(ItunesMusicSelector::fromSearchItem)
            }
        }.orEmpty()
        return ItunesMusicSelector.prioritize(songs)
    }

    override fun resolvePlayback(song: MusicSong): MusicPlayback? {
        val playbackUrl = song.resolvedPlaybackUrl ?: return null
        return MusicPlayback(playbackUrl, isPreview = true)
    }

    override fun lyrics(songId: String): String = "试听源暂不支持歌词"

    private fun executeJson(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("${displayName}接口返回 HTTP ${response.code}")
            }
            return JSONObject(response.body?.string().orEmpty())
        }
    }

    private companion object {
        const val SEARCH_URL = "https://itunes.apple.com/search"
    }
}

class KuwoMusicSource(
    private val client: OkHttpClient,
    private val settings: SettingsState
) : MusicSource {
    override val id = MusicSourcePlan.SOURCE_KUWO
    override val displayName = "酷我音乐"

    override fun searchSongs(songName: String): List<MusicSong> {
        val searchUrl = settings.musicSearchUrl.ifBlank { DEFAULT_SEARCH_URL }
        val url = searchUrl.toHttpUrlOrNull()?.newBuilder()?.apply {
            addQueryParameter("client", "kt")
            addQueryParameter("all", songName)
            addQueryParameter("pn", "0")
            addQueryParameter("rn", "20")
            addQueryParameter("uid", "794762570")
            addQueryParameter("ver", "kwplayer_ar_9.2.2.1")
            addQueryParameter("vipver", "1")
            addQueryParameter("show_copyright_off", "1")
            addQueryParameter("newver", "1")
            addQueryParameter("ft", "music")
            addQueryParameter("cluster", "0")
            addQueryParameter("strategy", "2012")
            addQueryParameter("encoding", "utf8")
            addQueryParameter("rformat", "json")
            addQueryParameter("vermerge", "1")
            addQueryParameter("mobi", "1")
            addQueryParameter("issubtitle", "1")
        }?.build() ?: throw IllegalStateException("酷我搜索地址无效")

        val json = executeJson(
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .build()
        )
        val songs = json.optJSONArray("abslist")?.let { list ->
            (0 until list.length()).mapNotNull { index ->
                list.optJSONObject(index)?.let(KuwoMusicSelector::fromSearchItem)
            }
        }.orEmpty()
        return KuwoMusicSelector.prioritize(songs)
    }

    override fun resolvePlayback(song: MusicSong): MusicPlayback? {
        val quality = if (settings.musicDefaultQuality == "128k") "128kmp3" else "320kmp3"
        val url = PLAYBACK_URL.toHttpUrlOrNull()?.newBuilder()?.apply {
            addQueryParameter("user", USER_ID)
            addQueryParameter("source", APP_SOURCE)
            addQueryParameter("type", "convert_url_with_sign")
            addQueryParameter("br", quality)
            addQueryParameter("format", "mp3")
            addQueryParameter("sig", "0")
            addQueryParameter("rid", song.songId)
            addQueryParameter("network", "WIFI")
            addQueryParameter("f", "web")
        }?.build() ?: throw IllegalStateException("酷我直链地址无效")

        val json = executeJson(
            Request.Builder()
                .url(url)
                .header("User-Agent", APP_USER_AGENT)
                .build()
        )
        if (json.optInt("code") != 200) {
            throw IllegalStateException(json.optString("msg", "酷我直链服务返回异常"))
        }
        val data = json.optJSONObject("data") ?: return null
        val directUrl = data.optString("url")
        if (directUrl.isBlank()) return null

        val previewDuration = data.optInt("duration")
        val isPreview = !song.likelyFullPlayback ||
            data.optInt("type") == 1 ||
            previewDuration in 1 until PREVIEW_DURATION_LIMIT_SECONDS
        return MusicPlayback(directUrl, isPreview)
    }

    override fun lyrics(songId: String): String {
        val json = executeJson(
            Request.Builder()
                .url("$LYRICS_URL?musicId=$songId")
                .header("User-Agent", USER_AGENT)
                .build()
        )
        val lines = json.optJSONObject("data")?.optJSONArray("lrclist")
            ?: return "该歌曲暂无歌词"
        return buildString {
            for (index in 0 until lines.length()) {
                val line = lines.optJSONObject(index) ?: continue
                val text = line.optString("lineLyric").trim()
                if (text.isNotEmpty()) appendLine(text)
            }
        }.ifBlank { "该歌曲暂无歌词" }
    }

    private fun executeJson(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("${displayName}接口返回 HTTP ${response.code}")
            }
            return JSONObject(response.body?.string().orEmpty())
        }
    }

    private companion object {
        const val DEFAULT_SEARCH_URL = "http://search.kuwo.cn/r.s"
        const val PLAYBACK_URL = "https://mobi.kuwo.cn/mobi.s"
        const val LYRICS_URL = "https://m.kuwo.cn/newh5/singles/songinfoandlrc"
        const val USER_ID = "359307055300426"
        const val APP_SOURCE = "kw_ar_9.2.2.1_android_apk_71486.apk"
        const val APP_USER_AGENT = "okhttp/3.10.0"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        const val PREVIEW_DURATION_LIMIT_SECONDS = 30
    }
}

class NeteaseMusicSource(
    private val client: OkHttpClient,
    private val settings: SettingsState
) : MusicSource {
    override val id = MusicSourcePlan.SOURCE_NETEASE
    override val displayName = "网易云音乐"

    override fun searchSongs(songName: String): List<MusicSong> {
        val url = apiUrl("cloudsearch")?.newBuilder()?.apply {
            addQueryParameter("keywords", songName)
            addQueryParameter("limit", "20")
            addQueryParameter("type", "1")
        }?.build() ?: throw IllegalStateException("网易云 API 地址无效")

        val json = executeJson(Request.Builder().url(url).build())
        val songs = json.optJSONObject("result")?.optJSONArray("songs")?.let { list ->
            (0 until list.length()).mapNotNull { index ->
                list.optJSONObject(index)?.let(NeteaseMusicSelector::fromSearchItem)
            }
        }.orEmpty()
        return NeteaseMusicSelector.prioritize(songs)
    }

    override fun resolvePlayback(song: MusicSong): MusicPlayback? {
        val level = if (settings.musicDefaultQuality == "128k") "standard" else "exhigh"
        val url = apiUrl("song/url/v1")?.newBuilder()?.apply {
            addQueryParameter("id", song.songId)
            addQueryParameter("level", level)
        }?.build() ?: throw IllegalStateException("网易云 API 地址无效")

        val json = executeJson(Request.Builder().url(url).build())
        if (json.optInt("code") != 200) {
            throw IllegalStateException(json.optString("message", "网易云 API 返回异常"))
        }
        val data = json.optJSONArray("data")?.optJSONObject(0) ?: return null
        val directUrl = data.optString("url")
        if (directUrl.isBlank()) return null

        val freeTrial = data.optJSONObject("freeTrialInfo")
        return MusicPlayback(
            url = directUrl,
            isPreview = !song.likelyFullPlayback || freeTrial != null
        )
    }

    override fun lyrics(songId: String): String {
        val url = apiUrl("lyric")?.newBuilder()
            ?.addQueryParameter("id", songId)
            ?.build() ?: throw IllegalStateException("网易云 API 地址无效")
        val json = executeJson(Request.Builder().url(url).build())
        val rawLyrics = json.optJSONObject("lrc")?.optString("lyric").orEmpty()
        return rawLyrics.lineSequence()
            .map(::stripLrcTimestamp)
            .filter { it.isNotBlank() }
            .joinToString(System.lineSeparator())
            .ifBlank { "该歌曲暂无歌词" }
    }

    private fun apiUrl(path: String): HttpUrl? {
        return settings.musicNeteaseApiUrl
            .trim()
            .trimEnd('/')
            .toHttpUrlOrNull()
            ?.takeIf { it.isHttps }
            ?.newBuilder()
            ?.addPathSegments(path)
            ?.build()
    }

    private fun executeJson(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("${displayName}API 返回 HTTP ${response.code}")
            }
            return JSONObject(response.body?.string().orEmpty())
        }
    }

    private fun stripLrcTimestamp(line: String): String {
        return line.replace(Regex("^((\\[[0-9:.]+])+)?"), "").trim()
    }
}
