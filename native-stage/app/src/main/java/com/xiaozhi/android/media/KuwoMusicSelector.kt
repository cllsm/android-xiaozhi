package com.xiaozhi.android.media

import org.json.JSONObject

object KuwoMusicSelector {
    fun prioritize(songs: List<MusicSong>): List<MusicSong> {
        return songs.sortedWith(
            compareByDescending<MusicSong> { it.onlinePlayable && it.likelyFullPlayback }
                .thenByDescending { it.onlinePlayable }
        )
    }

    fun fromSearchItem(item: JSONObject): MusicSong? {
        val songId = item.optString("MUSICRID").removePrefix("MUSIC_")
        if (songId.isBlank()) return null

        val payInfo = item.optJSONObject("payInfo")
        val cannotOnlinePlay = payInfo?.optString("cannotOnlinePlay", "0") ?: "0"
        val listenFragment = payInfo?.optString("listen_fragment", "0") ?: "0"
        val online = item.optString("ONLINE", "1") != "0"

        return MusicSong(
            songId = songId,
            title = item.optString("SONGNAME").ifBlank { "未知歌曲" },
            artist = item.optString("ARTIST"),
            album = item.optString("ALBUM"),
            durationSeconds = item.optString("DURATION").toIntOrNull() ?: 0,
            onlinePlayable = online && cannotOnlinePlay == "0",
            likelyFullPlayback = listenFragment != "1"
        )
    }
}
