package com.xiaozhi.android.mcp

import android.content.Context
import android.media.AudioManager
import org.json.JSONObject

class VolumeTool(private val context: Context) : McpTool {
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override val definition = McpToolDefinition(
        name = "self.audio_speaker.set_volume",
        description = "Set the system speaker volume to an absolute value from 0 to 100.",
        properties = JSONObject().put(
            "volume",
            JSONObject()
                .put("type", "integer")
                .put("minimum", 0)
                .put("maximum", 100)
        ),
        required = listOf("volume")
    )

    fun getVolume(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return current * 100 / max
    }

    fun setVolume(volume: Int): Boolean {
        if (volume !in 0..100) return false
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            (volume * max / 100).coerceIn(0, max),
            0
        )
        return true
    }

    fun getStatus(): JSONObject {
        val volume = getVolume()
        return JSONObject()
            .put("volume", volume)
            .put("muted", volume == 0)
            .put("available", true)
            .put("mode", "native")
    }

    override fun call(arguments: JSONObject): Any? {
        return setVolume(arguments.optInt("volume", -1))
    }
}

class GetVolumeTool(private val volumeTool: VolumeTool) : McpTool {
    override val definition = McpToolDefinition(
        name = "self.audio_speaker.get_volume",
        description = "Get the current system speaker volume level from 0 to 100."
    )

    override fun call(arguments: JSONObject): Any? = volumeTool.getVolume()
}

class VolumeStatusTool(private val volumeTool: VolumeTool) : McpTool {
    override val definition = McpToolDefinition(
        name = "self.audio_speaker.get_volume_status",
        description = "Get speaker volume, mute state, and controller availability."
    )

    override fun call(arguments: JSONObject): Any? = volumeTool.getStatus()
}
