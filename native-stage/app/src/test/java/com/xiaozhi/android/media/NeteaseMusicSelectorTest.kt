package com.xiaozhi.android.media

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseMusicSelectorTest {
    @Test
    fun readsSongMetadataAndPrioritizesFreeSongs() {
        val vip = searchItem(id = 1L, name = "VIP 歌曲", fee = 1)
        val free = searchItem(id = 2L, name = "免费歌曲", fee = 0)

        val selected = NeteaseMusicSelector.prioritize(
            listOf(
                NeteaseMusicSelector.fromSearchItem(vip)!!,
                NeteaseMusicSelector.fromSearchItem(free)!!
            )
        ).first()

        assertEquals("2", selected.songId)
        assertTrue(selected.likelyFullPlayback)
    }

    @Test
    fun marksNoCopyrightSongsUnplayable() {
        val item = searchItem(id = 1L)
            .put("noCopyrightRcmd", JSONObject())

        val song = NeteaseMusicSelector.fromSearchItem(item)!!

        assertFalse(song.onlinePlayable)
        assertFalse(song.likelyFullPlayback)
    }

    private fun searchItem(id: Long, name: String = "测试歌曲", fee: Int = 0): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("fee", fee)
            .put("dt", 180000)
            .put(
                "ar",
                JSONArray().put(JSONObject().put("name", "测试歌手"))
            )
            .put("al", JSONObject().put("name", "测试专辑"))
    }
}
