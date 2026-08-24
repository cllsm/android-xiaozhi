package com.xiaozhi.android.media

import org.json.JSONObject
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
}
