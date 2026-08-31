package com.xiaozhi.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.xiaozhi.android.service.SystemOverlayService
import com.xiaozhi.android.ui.XiaozhiApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var overlayServiceEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XiaozhiApp()
        }
        lifecycleScope.launch {
            (applicationContext as XiaozhiApplication).settingsRepository.settings.collect { settings ->
                overlayServiceEnabled = settings.overlayEnabled || settings.musicIslandEnabled
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val settings = (applicationContext as XiaozhiApplication)
                .settingsRepository.settings.first()
            overlayServiceEnabled = settings.overlayEnabled || settings.musicIslandEnabled
            if (overlayServiceEnabled && !Settings.canDrawOverlays(this@MainActivity)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else if (overlayServiceEnabled) {
                SystemOverlayService.setAppForeground(this@MainActivity, foreground = true)
                SystemOverlayService.start(this@MainActivity)
            }
        }
    }

    override fun onPause() {
        if (overlayServiceEnabled) {
            SystemOverlayService.setAppForeground(this, foreground = false)
            SystemOverlayService.start(this)
        }
        super.onPause()
    }
}
