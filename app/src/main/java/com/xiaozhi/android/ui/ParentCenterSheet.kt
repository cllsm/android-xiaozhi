package com.xiaozhi.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xiaozhi.android.study.AnswerPolicy
import com.xiaozhi.android.study.ProactivityLevel
import com.xiaozhi.android.study.StudyCameraFacing
import com.xiaozhi.android.study.StudyMode
import com.xiaozhi.android.study.StudySessionManager
import com.xiaozhi.android.study.StudySessionRecord
import com.xiaozhi.android.study.StudySettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 家长中心（ModalBottomSheet）：收纳陪学设置 + 最近报告。
 * 孩子视角的主界面只留选模式与成长入口，配置细节全部收在这里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentCenterSheet(
    settings: StudySettings,
    records: List<StudySessionRecord>,
    onSaveSettings: (StudySettings) -> Unit,
    onClearRecords: () -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(settings) { mutableStateOf(settings) }
    var showGate by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Text(
                text = "家长中心",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "陪学的设置与报告都在这里，调整后记得保存",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            ParentSectionTitle("孩子")
            OutlinedTextField(
                value = draft.childNickname,
                onValueChange = { draft = draft.copy(childNickname = it.take(12)) },
                label = { Text("孩子昵称（小智会这样称呼）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = draft.childGrade,
                onValueChange = { draft = draft.copy(childGrade = it.take(12)) },
                label = { Text("孩子年级") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))

            ParentSectionTitle("AI 主动性")
            Text(
                text = "决定小智巡查、鼓励和闲置提醒的频率",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = draft.proactivityLevel == ProactivityLevel.Quiet,
                    onClick = { draft = draft.copy(proactivityLevel = ProactivityLevel.Quiet) },
                    label = { Text("安静") }
                )
                FilterChip(
                    selected = draft.proactivityLevel == ProactivityLevel.Moderate,
                    onClick = { draft = draft.copy(proactivityLevel = ProactivityLevel.Moderate) },
                    label = { Text("适中") }
                )
                FilterChip(
                    selected = draft.proactivityLevel == ProactivityLevel.Enthusiastic,
                    onClick = {
                        draft = draft.copy(proactivityLevel = ProactivityLevel.Enthusiastic)
                    },
                    label = { Text("热情") }
                )
            }
            Text(
                text = when (draft.proactivityLevel) {
                    ProactivityLevel.Quiet -> "只在开始、休息与结束时说话，其余时间安静陪伴"
                    ProactivityLevel.Moderate -> "约 5 分钟看一眼画面，10 分钟没动静会轻声问候"
                    ProactivityLevel.Enthusiastic -> "约 3 分钟看一眼画面，5 分钟没动静就关心一下"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            ParentSectionTitle("观察与节奏")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("固定机位观察", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "自动取帧，或孩子说话时取一帧",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = draft.observationEnabled,
                    onCheckedChange = { draft = draft.copy(observationEnabled = it) }
                )
            }
            if (draft.observationEnabled) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "自动取帧间隔 ${draft.observationIntervalSeconds} 秒",
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = draft.observationIntervalSeconds.toFloat(),
                    onValueChange = {
                        draft = draft.copy(observationIntervalSeconds = it.roundToInt())
                    },
                    valueRange = 3f..30f
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "专注 ${draft.focusMinutes} 分钟",
                style = MaterialTheme.typography.labelLarge
            )
            Slider(
                value = draft.focusMinutes.toFloat(),
                onValueChange = { draft = draft.copy(focusMinutes = it.roundToInt()) },
                valueRange = 5f..60f
            )
            Text(
                text = "休息 ${draft.breakMinutes} 分钟",
                style = MaterialTheme.typography.labelLarge
            )
            Slider(
                value = draft.breakMinutes.toFloat(),
                onValueChange = { draft = draft.copy(breakMinutes = it.roundToInt()) },
                valueRange = 2f..20f
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text("相机默认方向", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = draft.cameraFacing == StudyCameraFacing.Back,
                    onClick = { draft = draft.copy(cameraFacing = StudyCameraFacing.Back) },
                    label = { Text("后摄（拍作业本）") }
                )
                FilterChip(
                    selected = draft.cameraFacing == StudyCameraFacing.Front,
                    onClick = { draft = draft.copy(cameraFacing = StudyCameraFacing.Front) },
                    label = { Text("前摄（画面巡查）") }
                )
            }
            Text(
                text = "固定手机，让孩子和学习资料同时进入画面。相机只在陪学期间保持会话，" +
                    "不录制视频；取当前帧用于识别，识别帧保存在本机并显示在对话中。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            ParentSectionTitle("答案策略")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = draft.answerPolicy == AnswerPolicy.GuidanceOnly,
                    onClick = { draft = draft.copy(answerPolicy = AnswerPolicy.GuidanceOnly) },
                    label = { Text("只引导") }
                )
                FilterChip(
                    selected = draft.answerPolicy == AnswerPolicy.GuidanceThenAnswer,
                    onClick = { showGate = true },
                    label = { Text("家长解锁答案") }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    onSaveSettings(draft)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存设置") }

            Spacer(modifier = Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ParentSectionTitle("最近报告", modifier = Modifier.weight(1f))
                TextButton(onClick = onClearRecords) { Text("清空") }
            }
            if (records.isEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "还没有陪学报告",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(modifier = Modifier.height(10.dp))
                records.take(10).forEach { record ->
                    ParentRecordRow(record)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }

    if (showGate) {
        ParentGateDialog(
            description = "开启后，小智在给足思路提示后可以说出最终答案。请家长确认孩子正在陪伴下使用。",
            onVerified = { draft = draft.copy(answerPolicy = AnswerPolicy.GuidanceThenAnswer) },
            onDismiss = { showGate = false }
        )
    }
}

@Composable
private fun ParentSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun ParentRecordRow(record: StudySessionRecord) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (record.mode == StudyMode.Homework) {
                    Icons.Filled.School
                } else {
                    Icons.AutoMirrored.Filled.MenuBook
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (record.mode == StudyMode.Homework) "作业" else "阅读",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
                    .format(Date(record.startedAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = StudySessionManager.friendlySummary(record),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
