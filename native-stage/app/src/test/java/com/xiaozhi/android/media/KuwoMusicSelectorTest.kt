package com.xiaozhi.android.media

import com.xiaozhi.android.core.SettingsState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KuwoMusicSelectorTest {
    @Test
    fun prefersFullPlaybackOverPaidPreview() {
        val selected = KuwoMusicSelector.prioritize(
            listOf(
                song(id = "1", online = true, full = false),
                song(id = "2", online = true, full = true)
            )
        ).first()

        assertEquals("2", selected.songId)
    }

    @Test
    fun fallsBackToPreviewWhenNoFullSongExists() {
        val selected = KuwoMusicSelector.prioritize(
            listOf(
                song(id = "1", online = true, full = false),
                song(id = "2", online = false, full = true)
            )
        ).first()

        assertEquals("1", selected.songId)
        assertFalse(selected.likelyFullPlayback)
    }

    @Test
    fun readsSearchMetadata() {
        val item = JSONObject()
            .put("MUSICRID", "MUSIC_228908")
            .put("SONGNAME", "晴天")
            .put("ARTIST", "周杰伦")
            .put("ALBUM", "叶惠美")
            .put("DURATION", "269")
            .put(
                "payInfo",
                JSONObject()
                    .put("cannotOnlinePlay", "0")
                    .put("listen_fragment", "1")
            )

        val song = KuwoMusicSelector.fromSearchItem(item)!!

        assertEquals("228908", song.songId)
        assertEquals("晴天 - 周杰伦 - 叶惠美", song.displayName)
        assertTrue(song.onlinePlayable)
        assertFalse(song.likelyFullPlayback)
    }

    @Test
    fun ordersAutoSourcesWithDefaultLosslessFirst() {
        val ids = MusicSourcePlan.orderedSourceIds(
            SettingsState(
                musicNeteaseLosslessAppKey = "ak_test",
                musicNeteaseApiUrl = "https://music-api.example.com:3000"
            )
        )

        assertEquals(
            listOf("netease_lossless", "kuwo", "netease", "audius", "itunes"),
            ids
        )
    }

    @Test
    fun ordersConfiguredLosslessSourceFirst() {
        val ids = MusicSourcePlan.orderedSourceIds(
            SettingsState(
                musicNeteaseLosslessApiUrl = "https://gateway.example.com/api/gateway.php",
                musicNeteaseLosslessAppKey = "ak_test",
                musicNeteaseApiUrl = "https://music-api.example.com:3000"
            )
        )

        assertEquals(
            listOf("netease_lossless", "kuwo", "netease", "audius", "itunes"),
            ids
        )
    }

    @Test
    fun skipsIncompleteLosslessSourceInAutoMode() {
        val ids = MusicSourcePlan.orderedSourceIds(
            SettingsState(
                musicNeteaseLosslessApiUrl = "https://gateway.example.com/api/gateway.php",
                musicNeteaseLosslessAppKey = ""
            )
        )

        assertEquals(listOf("kuwo", "audius", "itunes"), ids)
    }

    @Test
    fun normalizesLosslessGatewayParameters() {
        val url = NeteaseLosslessGateway.searchUrl(
            SettingsState(
                musicNeteaseLosslessApiUrl = "https://gateway.example.com/api/gateway.php?api_path=other",
                musicNeteaseLosslessAppKey = "ak_test"
            ),
            "晴天"
        )

        requireNotNull(url)
        assertEquals("wy_music", url.queryParameter("api_path"))
        assertEquals("search", url.queryParameter("action"))
        assertEquals("晴天", url.queryParameter("keyword"))
        assertEquals("20", url.queryParameter("limit"))
    }

    @Test
    fun skipsHttpNeteaseSourceInAutoMode() {
        val ids = MusicSourcePlan.orderedSourceIds(
            SettingsState(
                musicNeteaseLosslessAppKey = "ak_test",
                musicNeteaseApiUrl = "http://192.168.1.10:3000"
            )
        )

        assertEquals(listOf("netease_lossless", "kuwo", "audius", "itunes"), ids)
    }

    @Test
    fun losslessSourceLimitsPlaybackLookupToAvoidRateLimit() {
        val source = NeteaseLosslessMusicSource(
            client = okhttp3.OkHttpClient(),
            settings = SettingsState(musicNeteaseLosslessAppKey = "ak_test")
        )

        assertEquals(1, source.playbackLookupLimit)
    }

    private fun song(
        id: String,
        title: String = "测试歌曲",
        artist: String = "测试歌手",
        album: String = "测试专辑",
        online: Boolean = true,
        full: Boolean = true
    ) = MusicSong(
        songId = id,
        title = title,
        artist = artist,
        album = album,
        durationSeconds = 180,
        onlinePlayable = online,
        likelyFullPlayback = full
    )
}
