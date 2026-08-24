package com.xiaozhi.android.media

import org.json.JSONObject

object NeteaseMusicSelector {
    fun prioritize(songs: List<MusicSong>): List<MusicSong> {
        return songs.sortedWith(
            compareByDescending<MusicSong> { it.onlinePlayable && it.likelyFullPlayback }
                .thenByDescending { it.onlinePlayable }
        )
    }

    fun fromSearchItem(item: JSONObject): MusicSong? {
        val songId = item.optLong("id").takeIf { it > 0 }?.toString() ?: return null
        val artists = item.optJSONArray("ar")?.let { list ->
            (0 until list.length())
                .mapNotNull { index -> list.optJSONObject(index)?.optString("name") }
                .filter { it.isNotBlank() }
                .joinToString("/")
        }.orEmpty()

        val privilege = item.optJSONObject("privilege")
        val noCopyright = item.optJSONObject("noCopyrightRcmd") != null
        val payed = privilege?.optInt("payed", 0) ?: 0
        val fee = item.optInt("fee", 0)
        val playable = !noCopyright && privilege?.optInt("st", 0) != -200

        return MusicSong(
            songId = songId,
            title = item.optString("name").ifBlank { "未知歌曲" },
            artist = artists,
            album = item.optJSONObject("al")?.optString("name").orEmpty(),
            durationSeconds = item.optLong("dt", 0L).toInt() / 1000,
            onlinePlayable = playable,
            likelyFullPlayback = playable && (payed > 0 || fee == 0 || fee == 8)
        )
    }
}
