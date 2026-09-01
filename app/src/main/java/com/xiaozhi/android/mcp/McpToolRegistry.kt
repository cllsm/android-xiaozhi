package com.xiaozhi.android.mcp

import android.content.Context
import com.xiaozhi.android.core.SettingsState
import com.xiaozhi.android.media.NativeMusicController

object McpToolRegistry {
    fun create(context: Context, settings: SettingsState): List<McpTool> {
        val volumeTool = VolumeTool(context)
        return buildList {
            add(volumeTool)
            add(GetVolumeTool(volumeTool))
            add(VolumeStatusTool(volumeTool))
            add(AppLauncherTool(context))
            add(InstalledAppsTool(context))
            add(LatestVisionResultTool())
            add(ScreenshotTool(context))
            add(CameraTool(context))
            add(StudyStartTool())
            add(StudyStatusTool())
            add(HomeworkCapturePageTool())
            add(HomeworkGetContextTool())
            add(ReadingCapturePageTool())
            add(ReadingGetContextTool())
            add(ForecastTool())
            if (settings.musicEnabled) {
                NativeMusicController.configure(settings)
                add(MusicSearchTool())
                add(MusicPauseTool())
                add(MusicResumeTool())
                add(MusicStopTool())
                add(MusicSeekTool())
                add(MusicPreviousTool())
                add(MusicNextTool())
                add(MusicLyricsTool())
                add(LocalPlaylistTool(context))
            }
        }.filterNot { it.definition.name in CLOUD_NATIVE_TOOL_NAMES }
    }

    private val CLOUD_NATIVE_TOOL_NAMES = setOf("get_weather")
}
