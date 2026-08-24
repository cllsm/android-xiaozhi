package com.xiaozhi.android.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xiaozhi.android.media.ScreenCaptureController
import com.xiaozhi.android.service.MediaProjectionForegroundService

@Composable
internal fun OnboardingScreen(
    onFinish: () -> Unit,
    onOpenPrivacy: () -> Unit
) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun permissionGranted(permission: String) =
        context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            notice = null
            if (step < ONBOARDING_STEP_COUNT - 1) step += 1
        } else {
            notice = "未完成授权，此功能可能会受限"
        }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureController.savePermissionResult(result.resultCode, result.data)
            MediaProjectionForegroundService.start(context)
            if (step < ONBOARDING_STEP_COUNT - 1) step += 1
        } else {
            notice = "未完成屏幕授权，屏幕理解会稍后再次询问"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        LinearProgressIndicator(
            progress = { (step + 1) / ONBOARDING_STEP_COUNT.toFloat() },
            modifier = Modifier.fillMaxWidth()
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = onboardingTitle(step),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = onboardingBody(step),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            notice?.let { message ->
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step > 0) {
                OutlinedButton(
                    onClick = { step -= 1 },
                    modifier = Modifier.weight(1f)
                ) { Text("上一步") }
            }
            Button(
                onClick = {
                    when (step) {
                        0 -> step = 1
                        1 -> {
                            if (permissionGranted(Manifest.permission.RECORD_AUDIO)) {
                                step = 2
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                        2 -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                step = 3
                            }
                        }
                        3 -> permissionLauncher.launch(Manifest.permission.CAMERA)
                        4 -> context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                        5 -> projectionLauncher.launch(
                            (context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                                as MediaProjectionManager).createScreenCaptureIntent()
                        )
                        6 -> context.startActivity(
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        )
                        else -> onFinish()
                    }
                },
                enabled = true,
                modifier = Modifier.weight(1f)
            ) { Text(onboardingAction(step)) }
        }

        if (step in 2..6) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    if (step < ONBOARDING_STEP_COUNT - 1) step += 1 else onFinish()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("暂时跳过") }
        }

        if (step == 0) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenPrivacy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("查看隐私说明") }
        }
    }
}

private const val ONBOARDING_STEP_COUNT = 7

private fun onboardingTitle(step: Int): String = when (step) {
    0 -> "欢迎使用小智"
    1 -> "开启麦克风"
    2 -> "开启服务通知"
    3 -> "开启相机"
    4 -> "启用悬浮窗"
    5 -> "屏幕识别授权"
    else -> "后台运行建议"
}

private fun onboardingBody(step: Int): String = when (step) {
    0 -> "语音与文字内容会发送到已配置的小智服务器，用于生成回复。令牌和设备标识仅保存在本机。"
    1 -> "麦克风只在语音对话、唤醒词监听或需要听写时采集音频。"
    2 -> "通知用于展示语音服务状态，不包含聊天内容。"
    3 -> "相机仅在你主动要求拍照或识别面前内容时使用，可以稍后再授权。"
    4 -> "悬浮窗提供后台快捷球，可在其他应用中聆听、打断或停止语音服务。开启后请允许小智显示在其他应用上层。"
    5 -> "屏幕理解需要你在首次使用时授权屏幕采集；系统提示会出现，这是正常流程。"
    else -> "如果系统频繁清理后台，可在系统设置中把小智加入电池优化白名单。此项可选。"
}

private fun onboardingAction(step: Int): String = when (step) {
    0 -> "开始配置"
    1 -> "授权麦克风"
    2 -> "授权通知"
    3 -> "授权相机"
    4 -> "前往授权"
    5 -> "立即授权"
    6 -> "前往设置"
    else -> "完成"
}
