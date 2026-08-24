package com.xiaozhi.android.media

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiusMusicSelectorTest {
    @Test
    fun readsSearchMetadata() {
        val item = JSONObject()
            .put("id", "D123")
            .put("title", "Summer Night")
            .put("duration", 215)
            .put("album", "Open Sessions")
            .put("is_streamable", true)
            .put(
                "user",
                JSONObject()
                    .put("display_name", "Alice")
                    .put("name", "alice")
            )

        val song = AudiusMusicSelector.fromSearchItem(item)!!

        assertEquals("D123", song.songId)
        assertEquals("Summer Night - Alice - Open Sessions", song.displayName)
        assertEquals(215, song.durationSeconds)
        assertTrue(song.onlinePlayable)
        assertTrue(song.likelyFullPlayback)
    }

    @Test
    fun fallsBackToUserNameAndSkipsUnstreamableTrack() {
        val item = JSONObject()
            .put("id", "D456")
            .put("title", "Late Drive")
            .put("is_streamable", false)
            .put("user", JSONObject().put("name", "bob"))

        val song = AudiusMusicSelector.fromSearchItem(item)!!

        assertEquals("Late Drive - bob", song.displayName)
        assertFalse(song.onlinePlayable)
        assertFalse(song.likelyFullPlayback)
    }
}
