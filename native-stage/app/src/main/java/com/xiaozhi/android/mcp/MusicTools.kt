package com.xiaozhi.android.mcp

import android.content.Context
import com.xiaozhi.android.media.NativeMusicController
import org.json.JSONObject

class MusicSearchTool : McpTool {
    override val definition = McpToolDefinition(
        name = "self.music_player.search_and_play",
        description =
            "Search a song online and start playing it. Always call this for requests such as play song, 播放歌曲, or 放音乐.",
        properties = JSONObject().put(
            "song_name",
            JSONObject()
                .put("type", "string")
                .put("description", "Song title. Use a concrete title when the user provides one.")
        ),
        required = listOf("song_name")
    )

    override fun call(arguments: JSONObject): Any? {
        return NativeMusicController.searchAndPlay(arguments.optString("song_name"))
    }
}

class MusicPauseTool : McpTool {
    override val definition = McpToolDefinition(
        name = "self.music_player.pause",
        description = "Pause the current song and keep its playback position."
    )

    override fun call(arguments: JSONObject): Any? {
        return NativeMusicController.pause()
    }
}

class MusicResumeTool : McpTool {
    override val definition = McpToolDefinition(
        name = "self.music_player.resume",
        description = "Resume a song previously paused by the user."
    )

    override fun call(arguments: JSONObject): Any? {
        return NativeMusicController.resume()
    }
}

class MusicStopTool : McpTool {
    override val definition = McpToolDefinition(
        name = "self.music_player.stop",
        description = "Stop music playback and reset the player."
    )

    override fun call(arguments: JSONObject): Any? {
        return NativeMusicController.stop()
    }
}

class MusicSeekTool : McpTool {
    override val definition = McpToolDefinition(
        name = "self.music_player.seek",
        description = "Seek to a position in seconds from the start of the song.",
        properties = JSONObject().put(
            "position",
            JSONObject()
                .put("type", "integer")
                .put("minimum", 0)
        ),
        required = listOf("position")
    )

    override fun call(arguments: JSONObject): Any? {
        return NativeMusicController.seek(arguments.optInt("position", -1))
    }
}

class MusicPreviousTool : McpTool {
    override val definition = McpToolDefinition(
        name = "self.music_player.previous",
        description = "Play the previous song in the recent-playback queue. Use for 上一首."
    )

    override fun call(arguments: JSONObject): Any? {
        return NativeMusicController.playAdjacent(-1)
    }
}

class MusicNextTool : McpTool {
    override val definition = McpToolDefinition(
        name = "self.music_player.next",
        description = "Play the next song in the recent-playback queue. Use for 下一首."
    )

    override fun call(arguments: JSONObject): Any? {
        return NativeMusicController.playAdjacent(1)
    }
}

class MusicLyricsTool : McpTool {
    override val definition = McpToolDefinition(
        name = "self.music_player.get_lyrics",
        description = "Get lyrics for the current song."
    )

    override fun call(arguments: JSONObject): Any? {
        return NativeMusicController.getLyrics()
    }
}

class LocalPlaylistTool(private val context: Context) : McpTool {
    override val definition = McpToolDefinition(
        name = "self.music_player.get_local_playlist",
        description = "List music files cached by the app.",
        properties = JSONObject().put(
            "force_refresh",
            JSONObject().put("type", "boolean")
        )
    )

    override fun call(arguments: JSONObject): Any? {
        return NativeMusicController.getLocalPlaylist(context)
    }
}
