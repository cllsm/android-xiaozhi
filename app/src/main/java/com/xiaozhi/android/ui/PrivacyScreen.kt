package com.xiaozhi.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun PrivacyScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "隐私说明",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            privacyItems().forEach { item ->
                PrivacyCard(icon = item.first, title = item.second, body = item.third)
            }
        }
    }
}

@Composable
private fun PrivacyCard(
    icon: ImageVector,
    title: String,
    body: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun privacyItems(): List<Triple<ImageVector, String, String>> {
    return listOf(
        Triple(
            Icons.Filled.PrivacyTip,
            "陪学模式",
            "开启固定机位观察后，陪做作业和陪读会在学习期间保持相机会话；按设定间隔或孩子说话完成时取单帧识别。全程不录制视频、不保存画面，仅上传当次需要识别的 JPEG 单帧。跟读转写仅用于当次评测，报告保存在本地。"
        ),
        Triple(
            Icons.Filled.PrivacyTip,
            "语音与文字",
            "仅在你主动对话、唤醒词触发或发送文字时，将对应内容发送到配置的小智服务器以生成回复。"
        ),
        Triple(
            Icons.Filled.PrivacyTip,
            "麦克风与相机",
            "麦克风用于语音输入和唤醒词监听；相机和屏幕识别只在对应指令触发并完成授权后使用。"
        ),
        Triple(
            Icons.Filled.PrivacyTip,
            "设备标识",
            "OTA 会使用 Device-Id、Client-Id 和本地密钥完成设备连接与激活校验，用于维持服务会话。"
        ),
        Triple(
            Icons.Filled.PrivacyTip,
            "本地数据",
            "访问令牌、设备密钥、设置和聊天记录保存在应用私有目录；应用未开启系统云备份。"
        ),
        Triple(
            Icons.Filled.PrivacyTip,
            "诊断导出",
            "诊断报告只用于排障，导出前会过滤常见令牌字段；你可以在分享前再次确认内容。"
        )
    )
}
