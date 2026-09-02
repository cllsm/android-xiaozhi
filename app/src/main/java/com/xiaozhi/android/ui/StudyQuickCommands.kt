package com.xiaozhi.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xiaozhi.android.study.QuickCommand

/**
 * 陪学快捷指令芯片行：让孩子不用猜语音指令，点芯片即触发。
 * 芯片目录在 study 包（纯 JVM 可测），这里只负责展示与回调。
 */

/** 芯片行：普通页与全屏页共用，横向滑动 */
@Composable
fun StudyQuickCommandRow(
    commands: List<QuickCommand>,
    enabled: Boolean,
    darkStyle: Boolean = false,
    onCommand: (QuickCommand) -> Unit
) {
    LazyRow(
        modifier = Modifier.height(40.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(commands, key = { it.label }) { command ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        if (darkStyle) {
                            Color.White.copy(alpha = if (enabled) 0.20f else 0.10f)
                        } else {
                            MaterialTheme.colorScheme.primaryContainer.copy(
                                alpha = if (enabled) 1f else 0.5f
                            )
                        }
                    )
                    .clickable(enabled = enabled) { onCommand(command) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = command.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (darkStyle) {
                        Color.White
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    maxLines = 1
                )
            }
        }
    }
}
