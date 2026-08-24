package com.xiaozhi.android.ui

import android.content.Intent
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xiaozhi.android.core.DiagnosticReport
import com.xiaozhi.android.core.DiagnosticState

@Composable
internal fun DiagnosticsScreen(
    report: DiagnosticReport?,
    checking: Boolean,
    onBack: () -> Unit,
    onRun: (includeServerProbe: Boolean) -> Unit,
    onBuildReportText: () -> String
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var includeServerProbe by remember { mutableStateOf(true) }

    fun copyReport() {
        val text = onBuildReportText()
        if (text.isNotBlank()) clipboard.setText(AnnotatedString(text))
    }

    fun shareReport() {
        val text = onBuildReportText()
        if (text.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(intent, "分享诊断报告"))
    }

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
                text = "诊断",
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("探测 OTA 服务", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "会向当前 OTA 地址发起一次请求，用于定位连接问题",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = includeServerProbe,
                    onCheckedChange = { includeServerProbe = it }
                )
            }

            Button(
                onClick = { onRun(includeServerProbe) },
                enabled = !checking,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) {
                if (checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (checking) "检测中..." else if (report == null) "开始检测" else "重新检测")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = ::copyReport,
                    enabled = report != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("复制")
                }
                OutlinedButton(
                    onClick = ::shareReport,
                    enabled = report != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("分享")
                }
            }

            if (report == null) {
                Text(
                    text = "检测权限、网络、配置、语音链路和后台运行状态。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = report.deviceSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                report.items.forEach { item ->
                    DiagnosticRow(
                        name = item.name,
                        state = item.state,
                        detail = item.detail
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(
    name: String,
    state: DiagnosticState,
    detail: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(diagnosticColor(state))
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = diagnosticLabel(state),
            style = MaterialTheme.typography.labelSmall,
            color = diagnosticColor(state)
        )
    }
}

@Composable
private fun diagnosticColor(state: DiagnosticState): Color = when (state) {
    DiagnosticState.Ok -> MaterialTheme.colorScheme.tertiary
    DiagnosticState.Warning -> Color(0xFFF9A825)
    DiagnosticState.Error -> MaterialTheme.colorScheme.error
    DiagnosticState.Checking -> MaterialTheme.colorScheme.secondary
}

private fun diagnosticLabel(state: DiagnosticState): String = when (state) {
    DiagnosticState.Ok -> "正常"
    DiagnosticState.Warning -> "注意"
    DiagnosticState.Error -> "异常"
    DiagnosticState.Checking -> "检测中"
}
