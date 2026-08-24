package com.xiaozhi.android.media

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItunesMusicSelectorTest {
    @Test
    fun readsSearchMetadataAndPreviewUrl() {
        val item = JSONObject()
            .put("trackId", 123456L)
            .put("trackName", "晴天")
            .put("artistName", "周杰伦")
            .put("collectionName", "叶惠美")
            .put("trackTimeMillis", 269000L)
            .put("previewUrl", "https://audio.example.com/preview.m4a")

        val song = ItunesMusicSelector.fromSearchItem(item)!!

        assertEquals("123456", song.songId)
        assertEquals("晴天 - 周杰伦 - 叶惠美", song.displayName)
        assertEquals(269, song.durationSeconds)
        assertEquals("https://audio.example.com/preview.m4a", song.resolvedPlaybackUrl)
        assertTrue(song.onlinePlayable)
        assertFalse(song.likelyFullPlayback)
    }

    @Test
    fun skipsTrackWithoutPreview() {
        val item = JSONObject()
            .put("trackId", 123457L)
            .put("trackName", "无试听")

        assertNull(ItunesMusicSelector.fromSearchItem(item))
    }
}
