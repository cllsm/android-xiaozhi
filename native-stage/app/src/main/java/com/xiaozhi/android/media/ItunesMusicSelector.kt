package com.xiaozhi.android.media

import org.json.JSONObject

object ItunesMusicSelector {
    fun prioritize(songs: List<MusicSong>): List<MusicSong> {
        return songs.sortedWith(
            compareByDescending<MusicSong> { it.onlinePlayable }
        )
    }

    fun fromSearchItem(item: JSONObject): MusicSong? {
        val songId = item.optLong("trackId").takeIf { it > 0 }?.toString() ?: return null
        val previewUrl = item.optString("previewUrl")
        if (previewUrl.isBlank()) return null

        return MusicSong(
            songId = songId,
            title = item.optString("trackName").ifBlank { "未知歌曲" },
            artist = item.optString("artistName"),
            album = item.optString("collectionName"),
            durationSeconds = (item.optLong("trackTimeMillis", 0L) / 1000).toInt(),
            onlinePlayable = true,
            likelyFullPlayback = false,
            resolvedPlaybackUrl = previewUrl
        )
    }
}
