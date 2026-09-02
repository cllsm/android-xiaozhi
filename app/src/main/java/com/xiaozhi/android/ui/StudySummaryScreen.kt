package com.xiaozhi.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xiaozhi.android.study.STUDY_ACHIEVEMENTS
import com.xiaozhi.android.study.StudySettlement
import kotlinx.coroutines.delay

/** 勋章 iconKey → 图标的统一映射（勋章墙与总结页共用） */
fun achievementIcon(iconKey: String): ImageVector {
    return when (iconKey) {
        "flag" -> Icons.Filled.Flag
        "fire" -> Icons.Filled.LocalFireDepartment
        "trophy" -> Icons.Filled.EmojiEvents
        "timer" -> Icons.Filled.Timer
        "book" -> Icons.AutoMirrored.Filled.MenuBook
        "check" -> Icons.Filled.TaskAlt
        "sun" -> Icons.Filled.WbSunny
        else -> Icons.Filled.Star
    }
}

/**
 * 结束总结页（覆盖整屏）：星星结算动画 + 本次数据 + AI 表扬语 +
 * 解锁勋章 + 连续打卡，"收下星星"回到未开始界面。
 */
@Composable
fun StudySummaryScreen(
    settlement: StudySettlement,
    nickname: String,
    onCollect: () -> Unit
) {
    BackHandler(onBack = onCollect)

    // 星星逐颗弹出：最多演示 12 颗，其余用计数表达
    val shownStars = settlement.starsTotal.coerceAtMost(12)
    val starScales = List(shownStars) { remember { Animatable(0f) } }
    var counter by remember { mutableStateOf(0) }
    LaunchedEffect(settlement.starsTotal) {
        starScales.forEach { scale ->
            scale.animateTo(1f, animationSpec = tween(durationMillis = 260))
            counter += 1
            delay(110)
        }
        counter = settlement.starsTotal
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 64.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "本次陪学完成",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        val name = nickname.ifBlank { "小朋友" }
        Text(
            text = "$name 今天表现得特别棒",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))

        // 大星星计数
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = Color(0xFFFFB300),
                modifier = Modifier.size(44.dp)
            )
            Text(
                text = "$counter",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFFFFB300)
            )
            Text(
                text = "颗星",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        // 星星逐颗弹出
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            starScales.forEach { scale ->
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = Color(0xFFFFB300),
                    modifier = Modifier
                        .size(22.dp)
                        .scale(scale.value)
                )
            }
        }
        if (settlement.starsTotal > shownStars) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "还有 ${settlement.starsTotal - shownStars} 颗已存进成长册",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(24.dp))

        // 本次数据卡
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SummaryDataRow("专注时长", "${settlement.detail.focusSeconds / 60} 分钟")
            if (settlement.detail.completedItems > 0) {
                SummaryDataRow("完成题目", "${settlement.detail.completedItems} 道")
            }
            if (settlement.detail.passedSentences > 0) {
                SummaryDataRow("通过跟读", "${settlement.detail.passedSentences} 句")
            }
            if (settlement.detail.uploadedFrames > 0) {
                SummaryDataRow("识别帧数", "${settlement.detail.uploadedFrames} 帧 · 不录视频")
            }
            settlement.completedTasks.forEach { task ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "完成每日任务 +${task.starReward} 星",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // 连续打卡
        if (settlement.streakDays >= 1) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFFFF7043).copy(alpha = 0.14f))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.LocalFireDepartment,
                    contentDescription = null,
                    tint = Color(0xFFFF7043)
                )
                Text(
                    text = "已连续学习 ${settlement.streakDays} 天",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFFF7043)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 新解锁勋章
        if (settlement.newlyUnlocked.isNotEmpty()) {
            Text(
                text = "解锁新勋章",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                settlement.newlyUnlocked.take(3).forEach { id ->
                    val definition = STUDY_ACHIEVEMENTS.firstOrNull { it.id == id }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = achievementIcon(definition?.iconKey ?: "star"),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = definition?.title ?: id.name,
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Spacer(modifier = Modifier.weight(1f))
        Button(
            onClick = onCollect,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Icon(Icons.Filled.Star, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("收下星星", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SummaryDataRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
