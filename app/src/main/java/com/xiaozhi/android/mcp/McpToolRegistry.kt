package com.xiaozhi.android.mcp

import android.content.Context
import com.xiaozhi.android.core.SettingsState
import com.xiaozhi.android.media.NativeMusicController

object McpToolRegistry {
    fun create(context: Context, settings: SettingsState): List<McpTool> {
        val volumeTool = VolumeTool(context)
        return buildList {
            add(LatestVisionResultTool())
            add(volumeTool)
            add(GetVolumeTool(volumeTool))
            add(VolumeStatusTool(volumeTool))
            add(AppLauncherTool(context))
            add(InstalledAppsTool(context))
            add(LatestUserTextTool())
            add(ScreenshotTool(context))
            add(CameraTool(context))
            add(StudyStartTool())
            add(StudyStatusTool())
            add(StudyCompanionCheckTool())
            add(HomeworkCapturePageTool())
            add(HomeworkGetContextTool())
            add(ReadingCapturePageTool())
            add(ReadingGetContextTool())
            add(WeatherTool())
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
        }
    }
}
