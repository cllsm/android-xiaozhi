package com.xiaozhi.android.media

import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import com.xiaozhi.android.core.SettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseLosslessMusicSelectorTest {
    @Test
    fun readsWrappedSearchResponse() {
        val song = JSONObject()
            .put("id", 186016)
            .put("name", "晴天")
            .put(
                "artists",
                org.json.JSONArray().put(JSONObject().put("name", "周杰伦"))
            )
            .put("album", "叶惠美")
            .put("duration", 269000)
        val root = JSONObject()
            .put("code", 200)
            .put("data", JSONObject().put("result", JSONObject().put("songs", org.json.JSONArray().put(song))))

        val parsed = NeteaseLosslessMusicSelector.parseSearch(root).single()

        assertEquals("186016", parsed.songId)
        assertEquals("晴天 - 周杰伦 - 叶惠美", parsed.displayName)
        assertEquals(269, parsed.durationSeconds)
        assertTrue(parsed.onlinePlayable)
        assertTrue(parsed.likelyFullPlayback)
    }

    @Test
    fun findsNestedPlaybackUrl() {
        val root = JSONObject()
            .put(
                "data",
                JSONObject()
                    .put("level", "lossless")
                    .put("url", "https://audio.example.com/song.flac")
            )

        assertEquals(
            "https://audio.example.com/song.flac",
            NeteaseLosslessMusicSelector.findPlaybackUrl(root)
        )
    }

    @Test
    fun readsGatewaySingerMetadata() {
        val root = JSONObject()
            .put(
                "data",
                JSONObject().put(
                    "data",
                    JSONObject().put(
                        "songs",
                        org.json.JSONArray().put(
                            JSONObject()
                                .put("id", 2668397359)
                                .put("name", "晴天 (原唱 周杰伦)")
                                .put("singer", "RyaVocal")
                                .put("album", "晴天")
                                .put("duration", "4:31")
                        )
                    )
                )
            )

        val song = NeteaseLosslessMusicSelector.parseSearch(root).single()

        assertEquals("RyaVocal", song.artist)
        assertEquals("晴天 (原唱 周杰伦) - RyaVocal - 晴天", song.displayName)
    }

    @Test
    fun findsPlaybackUrlInsideUrlObject() {
        val root = JSONObject()
            .put(
                "data",
                JSONObject().put(
                    "data",
                    JSONObject().put(
                        "url",
                        JSONObject()
                            .put("level", "lossless")
                            .put("url", "https://audio.example.com/song.flac")
                            .put("size", 21883781L)
                    )
                )
            )

        assertEquals(
            "https://audio.example.com/song.flac",
            NeteaseLosslessMusicSelector.findPlaybackUrl(root)
        )
    }

    @Test
    fun losslessGatewayRetriesTransientHttpError() {
        var calls = 0
        var playbackRequestUrl = ""
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                calls += 1
                if (calls == 1) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(502)
                        .message("Bad Gateway")
                        .body("".toResponseBody(null))
                        .build()
                } else {
                    playbackRequestUrl = chain.request().url.toString()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"data":{"url":"https://audio.example.com/song.flac"}}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
            }
            .build()
        val source = NeteaseLosslessMusicSource(
            client = client,
            settings = SettingsState(
                musicNeteaseLosslessApiUrl = "https://gateway.example.com/api",
                musicNeteaseLosslessAppKey = "test-key"
            )
        )

        val playback = source.resolvePlayback(
            MusicSong(
                songId = "186016",
                title = "晴天",
                artist = "周杰伦",
                album = "叶惠美",
                durationSeconds = 269,
                onlinePlayable = true,
                likelyFullPlayback = true
            )
        )

        assertEquals("https://audio.example.com/song.flac", playback?.url)
        assertEquals(2, calls)
        assertTrue(playbackRequestUrl.contains("id=186016"))
        assertTrue(playbackRequestUrl.contains("level=lossless"))
    }
}
