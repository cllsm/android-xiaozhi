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
    private var overlayEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XiaozhiApp()
        }
        lifecycleScope.launch {
            (applicationContext as XiaozhiApplication).settingsRepository.settings.collect { settings ->
                overlayEnabled = settings.overlayEnabled
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            overlayEnabled = (applicationContext as XiaozhiApplication)
                .settingsRepository.settings.first().overlayEnabled
            if (overlayEnabled && !Settings.canDrawOverlays(this@MainActivity)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else if (overlayEnabled) {
                SystemOverlayService.setAppForeground(this@MainActivity, foreground = true)
                SystemOverlayService.start(this@MainActivity)
            }
        }
    }

    override fun onPause() {
        if (overlayEnabled) {
            SystemOverlayService.setAppForeground(this, foreground = false)
            SystemOverlayService.start(this)
        }
        super.onPause()
    }
}
