package com.xiaozhi.android.media

import org.json.JSONArray
import org.json.JSONObject

object NeteaseLosslessMusicSelector {
    fun prioritize(songs: List<MusicSong>): List<MusicSong> {
        return songs.sortedWith(
            compareByDescending<MusicSong> { it.onlinePlayable && it.likelyFullPlayback }
                .thenByDescending { it.onlinePlayable }
        )
    }

    fun parseSearch(root: JSONObject): List<MusicSong> {
        val songs = findArray(root, "songs") ?: return emptyList()
        return (0 until songs.length())
            .mapNotNull { index -> songs.optJSONObject(index)?.let(::fromSearchItem) }
            .let(::prioritize)
    }

    fun fromSearchItem(item: JSONObject): MusicSong? {
        val songId = readSongId(item) ?: return null
        return MusicSong(
            songId = songId,
            title = readString(item, "name", "title", "song_name")?.ifBlank { "未知歌曲" } ?: "未知歌曲",
            artist = readArtist(item),
            album = readAlbum(item),
            durationSeconds = readDuration(item),
            onlinePlayable = true,
            likelyFullPlayback = true
        )
    }

    fun findPlaybackUrl(root: JSONObject): String? {
        return findString(root, setOf("url", "music_url", "play_url", "data_url", "link"))?.takeIf {
            it.startsWith("http://") || it.startsWith("https://")
        }
    }

    private fun readSongId(item: JSONObject): String? {
        val numericId = item.optLong("id", -1L).takeIf { it > 0 }
        return numericId?.toString()
            ?: readString(item, "song_id", "songId", "id")?.takeIf { it.isNotBlank() }
    }

    private fun readArtist(item: JSONObject): String {
        val array = item.optJSONArray("artists") ?: item.optJSONArray("ar")
        if (array != null) {
            return (0 until array.length()).mapNotNull { index ->
                val value = array.opt(index)
                when (value) {
                    is JSONObject -> value.optString("name")
                    else -> value?.toString().orEmpty()
                }
                }.filter { it.isNotBlank() }.joinToString("/")
        }
        return readString(item, "artist", "artists", "singer") ?: ""
    }

    private fun readAlbum(item: JSONObject): String {
        return item.optJSONObject("album")?.optString("name")
            ?: item.optJSONObject("al")?.optString("name")
            ?: readString(item, "album")
            ?: ""
    }

    private fun readDuration(item: JSONObject): Int {
        val duration = item.optLong("duration", item.optLong("dt", 0L))
        if (duration <= 0L) return 0
        return if (duration > 10_000L) (duration / 1000L).toInt() else duration.toInt()
    }

    private fun findArray(root: JSONObject, name: String): JSONArray? {
        root.optJSONArray(name)?.let { return it }
        for (key in root.keys()) {
            val value = root.opt(key) ?: continue
            if (value is JSONObject) {
                findArray(value, name)?.let { return it }
            }
        }
        return null
    }

    private fun findString(root: JSONObject, names: Set<String>): String? {
        for (name in names) {
            val value = root.opt(name)
            if (value is String && value.isNotBlank() && value != "null") return value
        }
        for (key in root.keys()) {
            val value = root.opt(key) ?: continue
            if (value is JSONObject) {
                findString(value, names)?.let { return it }
            }
        }
        return null
    }

    private fun readString(item: JSONObject, vararg names: String): String? {
        for (name in names) {
            val value = item.optString(name)
            if (value.isNotBlank() && value != "null") return value
        }
        return null
    }
}
