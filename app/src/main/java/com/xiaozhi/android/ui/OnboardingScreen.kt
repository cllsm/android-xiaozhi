package com.xiaozhi.android.ui

import android.Manifest
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
                        2 -> onFinish()
                        else -> onFinish()
                    }
                },
                enabled = true,
                modifier = Modifier.weight(1f)
            ) { Text(onboardingAction(step)) }
        }

        if (step == 2) {
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

private const val ONBOARDING_STEP_COUNT = 3

private fun onboardingTitle(step: Int): String = when (step) {
    0 -> "欢迎使用小智"
    1 -> "开启麦克风"
    else -> "可选能力稍后再开"
}

private fun onboardingBody(step: Int): String = when (step) {
    0 -> "语音与文字内容会发送到已配置的小智服务器，用于生成回复。令牌和设备标识仅保存在本机。"
    1 -> "麦克风只在语音对话、唤醒词监听或需要听写时采集音频。"
    else -> "通知、相机、屏幕识别、悬浮窗和电池白名单会在首次使用对应功能时按需申请。你可以先回到首页，直接开始说话或打字。"
}

private fun onboardingAction(step: Int): String = when (step) {
    0 -> "开始配置"
    1 -> "授权麦克风"
    else -> "进入小智"
}
