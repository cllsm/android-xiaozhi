package com.xiaozhi.android.media

import org.json.JSONObject

object AudiusMusicSelector {
    fun prioritize(songs: List<MusicSong>): List<MusicSong> {
        return songs.sortedWith(
            compareByDescending<MusicSong> { it.onlinePlayable && it.likelyFullPlayback }
                .thenByDescending { it.onlinePlayable }
        )
    }

    fun fromSearchItem(item: JSONObject): MusicSong? {
        val songId = item.optString("id")
        if (songId.isBlank()) return null

        val user = item.optJSONObject("user")
        val artist = user?.optString("display_name")?.takeIf { it.isNotBlank() }
            ?: user?.optString("name")?.takeIf { it.isNotBlank() }
            ?: "Audius Artist"
        val streamable = item.optBoolean("is_streamable", true)

        return MusicSong(
            songId = songId,
            title = item.optString("title").ifBlank { "未知歌曲" },
            artist = artist,
            album = item.optString("album"),
            durationSeconds = item.optLong("duration", 0L).toInt(),
            onlinePlayable = streamable,
            likelyFullPlayback = streamable
        )
    }
}
