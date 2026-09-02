package com.xiaozhi.android

import android.app.Application
import com.xiaozhi.android.data.ChatHistoryRepository
import com.xiaozhi.android.data.MusicHistoryRepository
import com.xiaozhi.android.data.SettingsRepository
import com.xiaozhi.android.study.StudySessionManager

class XiaozhiApplication : Application() {
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var chatHistoryRepository: ChatHistoryRepository
        private set
    lateinit var musicHistoryRepository: MusicHistoryRepository
        private set

    override fun onCreate() {
        super.onCreate()
        chatHistoryRepository = ChatHistoryRepository.initialize(this)
        musicHistoryRepository = MusicHistoryRepository.initialize(this)
        settingsRepository = SettingsRepository(this)
        StudySessionManager.initialize(this)
    }
}
