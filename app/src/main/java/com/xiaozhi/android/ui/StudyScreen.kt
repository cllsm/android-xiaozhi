package com.xiaozhi.android.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.ViewTreeObserver
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaozhi.android.core.ChatMessage
import com.xiaozhi.android.core.DeviceState
import com.xiaozhi.android.service.VoiceForegroundService
import com.xiaozhi.android.media.StudyPreviewInfo
import com.xiaozhi.android.media.StudyPreviewOrientation
import com.xiaozhi.android.study.StudyObservationEngine
import com.xiaozhi.android.study.AnswerPolicy
import com.xiaozhi.android.study.StudyCameraFacing
import com.xiaozhi.android.study.StudyMode
import com.xiaozhi.android.study.StudyPhase
import com.xiaozhi.android.study.StudyRuntimeState
import com.xiaozhi.android.study.StudySessionRecord
import com.xiaozhi.android.study.StudySessionManager
import com.xiaozhi.android.study.StudySessionState
import com.xiaozhi.android.study.StudySettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun StudyScreen(
    viewModel: com.xiaozhi.android.ui.XiaozhiViewModel,
    onFullscreenChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val state by StudySessionState.state.collectAsStateWithLifecycle()
    val settings by viewModel.studySettings.collectAsStateWithLifecycle(initialValue = StudySettings())
    val records by viewModel.studyRecords.collectAsStateWithLifecycle(initialValue = emptyList())
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val runtimeState by viewModel.runtimeState.collectAsStateWithLifecycle()
    var fullscreenPreview by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showParentDialog by remember { mutableStateOf(false) }
    var parentGate by remember { mutableStateOf(0 to 0) }
    var parentGateAnswer by remember { mutableStateOf("") }
    var finishedRecord by remember { mutableStateOf<StudySessionRecord?>(null) }
    var draft by remember(settings) { mutableStateOf(settings) }

    BackHandler(enabled = fullscreenPreview) {
        fullscreenPreview = false
    }

    LaunchedEffect(fullscreenPreview) {
        onFullscreenChange(fullscreenPreview)
    }

    LaunchedEffect(state.mode) {
        if (state.mode == StudyMode.None) fullscreenPreview = false
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val ready = grants[Manifest.permission.RECORD_AUDIO] == true &&
            grants[Manifest.permission.CAMERA] == true
        if (ready) {
            pendingAction?.invoke()
        }
        pendingAction = null
    }

    fun withStudyPermissions(action: () -> Unit) {
        val ready = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (ready) {
            VoiceForegroundService.start(context)
            action()
        } else {
            pendingAction = action
            permissionLauncher.launch(
                buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    add(Manifest.permission.CAMERA)
                }.toTypedArray()
            )
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "陪学",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        if (state.mode != StudyMode.None && !fullscreenPreview) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    StudyPreviewCard(
                        modeLabel = if (state.mode == StudyMode.Homework) {
                            "陪做作业"
                        } else {
                            "陪读"
                        },
                        observationEnabled = state.settings.observationEnabled,
                        observationRunning = state.observationRunning,
                        observationFrames = state.observationFrames,
                        cameraFacing = state.settings.cameraFacing,
                        showSetupGuide = state.phase == StudyPhase.Prepare,
                        remainingLabel = formatSeconds(
                            if (state.phase == com.xiaozhi.android.study.StudyPhase.Break) {
                                state.breakRemainingSeconds
                            } else {
                                state.focusRemainingSeconds
                            }
                        ),
                        onSwitchCamera = {
                            viewModel.updateStudySettings(
                                state.settings.copy(
                                    cameraFacing = if (state.settings.cameraFacing ==
                                        StudyCameraFacing.Back
                                    ) {
                                        StudyCameraFacing.Front
                                    } else {
                                        StudyCameraFacing.Back
                                    }
                                )
                            )
                        },
                        onOpenFullscreen = { fullscreenPreview = true }
                    )
                }
            }
        }

        if (state.mode == StudyMode.None) {
            item {
                StudyPanel {
                    Text(
                        text = "选择模式",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    StudyEntry(
                        icon = Icons.Filled.School,
                        title = "陪做作业",
                        subtitle = "拍题、分步引导、检查订正",
                        enabled = !state.captureRunning
                    ) {
                        withStudyPermissions { viewModel.startStudy(StudyMode.Homework) }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    StudyEntry(
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        title = "陪读",
                        subtitle = "逐句领读、跟读评测、理解提问",
                        enabled = !state.captureRunning
                    ) {
                        withStudyPermissions { viewModel.startStudy(StudyMode.Reading) }
                    }
                }
            }
            item {
                StudyPanel {
                    Text(
                        text = "陪学设置",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = draft.childGrade,
                        onValueChange = { draft = draft.copy(childGrade = it.take(12)) },
                        label = { Text("孩子年级") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "固定机位观察",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                text = "自动取帧，或孩子说话时取一帧",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = draft.observationEnabled,
                            onCheckedChange = {
                                draft = draft.copy(observationEnabled = it)
                            }
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
                                draft = draft.copy(
                                    observationIntervalSeconds = it.roundToInt()
                                )
                            },
                            valueRange = 3f..30f
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "固定手机，让孩子和学习资料同时进入画面。相机只在陪学期间保持会话，不录制视频；取当前 JPEG 帧用于识别，识别帧会保存在本机并显示在对话中。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "专注 ${draft.focusMinutes} 分钟",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Slider(
                        value = draft.focusMinutes.toFloat(),
                        onValueChange = {
                            draft = draft.copy(focusMinutes = it.roundToInt())
                        },
                        valueRange = 5f..60f
                    )
                    Text(
                        text = "休息 ${draft.breakMinutes} 分钟",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Slider(
                        value = draft.breakMinutes.toFloat(),
                        onValueChange = {
                            draft = draft.copy(breakMinutes = it.roundToInt())
                        },
                        valueRange = 2f..20f
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("答案策略", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = draft.answerPolicy == AnswerPolicy.GuidanceOnly,
                            onClick = { draft = draft.copy(answerPolicy = AnswerPolicy.GuidanceOnly) },
                            label = { Text("只引导") }
                        )
                        FilterChip(
                            selected = draft.answerPolicy == AnswerPolicy.GuidanceThenAnswer,
                            onClick = {
                                parentGate = (
                                    (System.currentTimeMillis() % 37L).toInt() + 11
                                    ) to ((System.currentTimeMillis() / 7L % 29L).toInt() + 7)
                                parentGateAnswer = ""
                                showParentDialog = true
                            },
                            label = { Text("家长解锁答案") }
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.updateStudySettings(draft) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("保存陪学设置") }
                }
            }
            item {
                StudyPanel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "最近报告",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = viewModel::clearStudyRecords) {
                            Text("清空")
                        }
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
                        records.forEach { record ->
                            StudyRecordRow(record)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        } else {
            item {
                StudySessionHeader(
                    state = state,
                    onStop = { finishedRecord = viewModel.stopStudy() }
                )
            }

            if (state.phase == StudyPhase.Prepare) {
                item {
                    StudyCameraSetupPanel(
                        observationRunning = state.observationRunning,
                        observationIssue = state.observationIssue,
                        onConfirm = { viewModel.confirmStudySetup() }
                    )
                }
            }

            if (state.mode == StudyMode.Homework) {
                item {
                    StudyPanel {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    withStudyPermissions {
                                        viewModel.captureHomeworkPage(
                                            com.xiaozhi.android.study.HomeworkPromptBuilder.INTENT_EXPLAIN
                                        )
                                    }
                                },
                                enabled = !state.captureRunning,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.CameraAlt, contentDescription = null)
                                Text("拍当前页")
                            }
                            OutlinedButton(
                                onClick = {
                                    withStudyPermissions {
                                        viewModel.captureHomeworkPage(
                                            com.xiaozhi.android.study.HomeworkPromptBuilder.INTENT_CHECK
                                        )
                                    }
                                },
                                enabled = !state.captureRunning,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.FactCheck, contentDescription = null)
                                Text("检查")
                            }
                            OutlinedButton(
                                onClick = {
                                    withStudyPermissions {
                                        viewModel.captureHomeworkPage(
                                            com.xiaozhi.android.study.HomeworkPromptBuilder.INTENT_REFRESH
                                        )
                                    }
                                },
                                enabled = !state.captureRunning,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null)
                                Text("重看")
                            }
                        }
                    }
                }
                val page = state.homeworkPage
                if (page == null || page.items.isEmpty()) {
                    item {
                        StudyPanel {
                            Text(
                                text = "把作业本放在摄像头前，点“拍当前页”，或者说“看第 3 题”。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    itemsIndexed(page.items, key = { _, item -> item.index }) { _, item ->
                        StudyPanel {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "第 ${item.index} 题",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = homeworkStateLabel(item.checkState),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = homeworkStateColor(item.checkState)
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    text = "提示 ${item.hintLevel}/3",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = item.question.ifBlank { "题干看不清，请让孩子念一遍。" },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (!item.answerReadable) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "作答区域不可读",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = { viewModel.requestHomeworkHint(item.index) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("给我一点提示") }
                        }
                    }
                    page.unreadableRegions.forEach { region ->
                        item {
                            Text(
                                text = region,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                item {
                    StudyPanel {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    withStudyPermissions { viewModel.captureReadingPage() }
                                },
                                enabled = !state.captureRunning,
                                modifier = Modifier.weight(2f)
                            ) {
                                Icon(Icons.Filled.CameraAlt, contentDescription = null)
                                Text("拍书页")
                            }
                            IconButton(
                                onClick = { viewModel.moveReadingSentence(-1) },
                                enabled = !state.captureRunning
                            ) {
                                Icon(Icons.Filled.ChevronLeft, contentDescription = "上一句")
                            }
                            IconButton(
                                onClick = { viewModel.moveReadingSentence(1) },
                                enabled = !state.captureRunning
                            ) {
                                Icon(Icons.Filled.ChevronRight, contentDescription = "下一句")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = viewModel::repeatReadingSentence,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Text("领读")
                            }
                            OutlinedButton(
                                onClick = viewModel::askReadingComprehension,
                                modifier = Modifier.weight(1f)
                            ) { Text("理解提问") }
                        }
                    }
                }
                val page = state.readingPage
                if (page == null || page.sentences.isEmpty()) {
                    item {
                        StudyPanel {
                            Text(
                                text = "把书页放平，光线充足后点“拍书页”。识别后小智会逐句领读。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    itemsIndexed(page.sentences, key = { _, sentence -> sentence.index }) { index, sentence ->
                        val selected = index == page.currentIndex
                        StudyPanel(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = readingStateLabel(sentence.status),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = readingStateColor(sentence.status)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(sentence.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    state.readingPage?.lastEvaluation?.let { evaluation ->
                        item {
                            StudyPanel {
                                Text(
                                    text = "上次跟读准确率 ${(evaluation.accuracy * 100).roundToInt()}%",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (evaluation.passed) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                                )
                                if (!evaluation.passed) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = listOf(
                                            evaluation.missingText.takeIf { it.isNotBlank() }
                                                ?.let { "漏读：$it" },
                                            evaluation.extraText.takeIf { it.isNotBlank() }
                                                ?.let { "多读：$it" },
                                            evaluation.substitutions.joinToString("、") {
                                                "${it.expected}→${it.actual}"
                                            }.takeIf { it.isNotBlank() }
                                        ).filterNotNull().joinToString("；")
                                            .ifBlank { "再试一次" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }
    }

    if (showParentDialog) {
        AlertDialog(
            onDismissRequest = { showParentDialog = false },
            title = { Text("家长确认") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("开启后，小智在给足思路提示后可以说出最终答案。请家长确认孩子正在陪伴下使用。")
                    OutlinedTextField(
                        value = parentGateAnswer,
                        onValueChange = { value ->
                            parentGateAnswer = value.filter(Char::isDigit).take(3)
                        },
                        label = { Text("家长验证：${parentGate.first} + ${parentGate.second} = ?") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        draft = draft.copy(answerPolicy = AnswerPolicy.GuidanceThenAnswer)
                        showParentDialog = false
                    },
                    enabled = parentGateAnswer.toIntOrNull() ==
                        parentGate.first + parentGate.second
                ) { Text("确认开启") }
            },
            dismissButton = {
                TextButton(onClick = { showParentDialog = false }) { Text("取消") }
            }
        )
    }

    finishedRecord?.let { record ->
        StudySummaryDialog(
            record = record,
            onClose = { finishedRecord = null }
        )
    }

    if (fullscreenPreview && state.mode != StudyMode.None) {
        StudyFullscreenPreview(
            state = state,
            chat = chat,
            currentText = runtimeState.currentText,
            listening = runtimeState.deviceState == DeviceState.Listening,
            cameraFacing = state.settings.cameraFacing,
            showSetupGuide = state.phase == StudyPhase.Prepare,
            onSwitchCamera = {
                viewModel.updateStudySettings(
                    state.settings.copy(
                        cameraFacing = if (state.settings.cameraFacing ==
                            StudyCameraFacing.Back
                        ) {
                            StudyCameraFacing.Front
                        } else {
                            StudyCameraFacing.Back
                        }
                    )
                )
            },
            onClose = { fullscreenPreview = false }
        )
    }
}

@Composable
private fun StudyPreviewCard(
    modeLabel: String,
    observationEnabled: Boolean,
    observationRunning: Boolean,
    observationFrames: Int,
    cameraFacing: StudyCameraFacing,
    showSetupGuide: Boolean,
    remainingLabel: String,
    onSwitchCamera: () -> Unit,
    onOpenFullscreen: () -> Unit
) {
    Box(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            .clickable(enabled = observationRunning, onClick = onOpenFullscreen)
    ) {
        if (observationEnabled && observationRunning) {
            StudyPreviewSurface(
                modifier = Modifier.fillMaxSize(),
                onPreviewStateChanged = { }
            )
        } else {
            Text(
                text = if (observationEnabled) {
                    "固定机位相机准备中..."
                } else {
                    "固定机位观察未开启"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.82f),
                modifier = Modifier.align(Alignment.Center)
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE5484D))
            )
            Text(
                text = "相机预览 · 不录视频",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White
            )
        }

        Text(
            text = "$modeLabel · $remainingLabel · 仅上传识别帧 $observationFrames",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.60f)),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.CameraAlt,
                contentDescription = if (cameraFacing == StudyCameraFacing.Back) {
                    "切换到前置摄像头"
                } else {
                    "切换到后置摄像头"
                },
                tint = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = observationRunning, onClick = onSwitchCamera)
                    .padding(8.dp)
            )
            Icon(
                imageVector = Icons.Filled.Fullscreen,
                contentDescription = "全屏预览",
                tint = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onOpenFullscreen)
                    .padding(8.dp)
            )
        }

        if (showSetupGuide) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.76f)
                    .fillMaxHeight(0.66f)
                    .border(
                        width = 1.5.dp,
                        color = Color.White.copy(alpha = 0.78f),
                        shape = RoundedCornerShape(6.dp)
                    )
            )
            Text(
                text = "孩子 + 学习资料",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.58f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }

}

@Composable
private fun StudyPreviewSurface(
    modifier: Modifier = Modifier,
    onPreviewStateChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val textureView = remember(context) { TextureView(context) }

    DisposableEffect(textureView) {
        var attachedSurfaceTexture: android.graphics.SurfaceTexture? = null
        var previewInfo: StudyPreviewInfo? = null
        var observedContainer: FrameLayout? = null
        var layoutObserver: ViewTreeObserver.OnGlobalLayoutListener? = null
        val display = currentDisplay(context)
        var lastDisplayRotation = display?.rotation ?: currentDisplayRotation(context)

        fun applyLayout() {
            val info = previewInfo ?: return
            applyCameraViewLayout(textureView, info, currentDisplayRotation(context))
        }

        val listener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: android.graphics.SurfaceTexture,
                width: Int,
                height: Int
            ) {
                attachedSurfaceTexture = surface
                StudyObservationEngine.attachPreview(
                    surface,
                    width,
                    height,
                    currentDisplayRotation(context)
                ) { info ->
                    previewInfo = info
                    applyLayout()
                    onPreviewStateChanged(info != null)
                }
            }

            override fun onSurfaceTextureSizeChanged(
                surface: android.graphics.SurfaceTexture,
                width: Int,
                height: Int
            ) {
                applyLayout()
            }

            override fun onSurfaceTextureDestroyed(
                surface: android.graphics.SurfaceTexture
            ): Boolean {
                attachedSurfaceTexture?.let(StudyObservationEngine::detachPreview)
                attachedSurfaceTexture = null
                previewInfo = null
                onPreviewStateChanged(false)
                return true
            }

            override fun onSurfaceTextureUpdated(
                surface: android.graphics.SurfaceTexture
            ) = Unit
        }
        textureView.surfaceTextureListener = listener

        val attachListener = object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: android.view.View) {
                val container = view.parent as? FrameLayout ?: return
                val listener = ViewTreeObserver.OnGlobalLayoutListener { applyLayout() }
                observedContainer = container
                layoutObserver = listener
                container.viewTreeObserver.addOnGlobalLayoutListener(listener)
                applyLayout()
            }

            override fun onViewDetachedFromWindow(view: android.view.View) {
                val container = observedContainer
                val listener = layoutObserver
                if (container != null && listener != null) {
                    container.viewTreeObserver.removeOnGlobalLayoutListener(listener)
                }
                observedContainer = null
                layoutObserver = null
            }
        }
        textureView.addOnAttachStateChangeListener(attachListener)

        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                if (display?.displayId != displayId) return
                val nextRotation = display.rotation
                if (nextRotation == lastDisplayRotation) return

                lastDisplayRotation = nextRotation
                Log.i("StudyPreview", "display_changed:rotation=$nextRotation")
                textureView.post { applyLayout() }
            }
        }
        display
            ?.let { context.getSystemService(DisplayManager::class.java) }
            ?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))

        onDispose {
            textureView.removeOnAttachStateChangeListener(attachListener)
            layoutObserver?.let { listener ->
                observedContainer?.viewTreeObserver?.removeOnGlobalLayoutListener(listener)
            }
            observedContainer = null
            layoutObserver = null
            display
                ?.let { context.getSystemService(DisplayManager::class.java) }
                ?.unregisterDisplayListener(displayListener)
            attachedSurfaceTexture?.let(StudyObservationEngine::detachPreview)
            attachedSurfaceTexture = null
            textureView.surfaceTextureListener = null
            onPreviewStateChanged(false)
        }
    }

    AndroidView(
        factory = {
            FrameLayout(it).apply {
                clipChildren = true
                addView(
                    textureView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                    )
                )
            }
        },
        modifier = modifier
    )
}

private fun applyCameraViewLayout(
    textureView: TextureView,
    info: StudyPreviewInfo,
    displayRotation: Int
) {
    val container = textureView.parent as? FrameLayout ?: return
    if (container.width <= 0 || container.height <= 0) {
        container.post { applyCameraViewLayout(textureView, info, displayRotation) }
        return
    }

    val rotation = StudyPreviewOrientation.previewRotationDegrees(
        sensorOrientationDegrees = info.rotationDegrees,
        isFrontCamera = info.isFrontCamera,
        displayRotation = displayRotation
    )
    val uprightWidth = if (rotation == 90 || rotation == 270) info.height else info.width
    val uprightHeight = if (rotation == 90 || rotation == 270) info.width else info.height
    val scale = maxOf(
        container.width.toFloat() / uprightWidth,
        container.height.toFloat() / uprightHeight
    )
    val width = (info.width * scale).roundToInt().coerceAtLeast(1)
    val height = (info.height * scale).roundToInt().coerceAtLeast(1)

    val params = textureView.layoutParams as? FrameLayout.LayoutParams
        ?: FrameLayout.LayoutParams(width, height, Gravity.CENTER)
    if (params.width != width || params.height != height || params.gravity != Gravity.CENTER) {
        params.width = width
        params.height = height
        params.gravity = Gravity.CENTER
        textureView.layoutParams = params
    }
    textureView.rotation = rotation.toFloat()
    // Mirroring happens in the buffer's local axes, before the view rotation.
    textureView.scaleX = if (info.mirror && rotation % 180 == 0) -1f else 1f
    textureView.scaleY = if (info.mirror && rotation % 180 == 90) -1f else 1f
}

@Composable
private fun StudyFullscreenPreview(
    state: StudyRuntimeState,
    chat: List<ChatMessage>,
    currentText: String,
    listening: Boolean,
    cameraFacing: StudyCameraFacing,
    showSetupGuide: Boolean,
    onSwitchCamera: () -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        StudyPreviewSurface(
            modifier = Modifier.fillMaxSize(),
            onPreviewStateChanged = { }
        )

        if (showSetupGuide) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.72f)
                    .fillMaxHeight(0.68f)
                    .border(
                        width = 1.5.dp,
                        color = Color.White.copy(alpha = 0.76f),
                        shape = RoundedCornerShape(6.dp)
                    )
            )
            Text(
                text = "孩子 + 学习资料",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 128.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.58f))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }

        Row(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "退出全屏",
                    tint = Color.White
                )
            }
            Text(
                text = if (state.mode == StudyMode.Homework) "陪做作业" else "陪读",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
            Spacer(modifier = Modifier.width(10.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFE5484D))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LIVE",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "取帧 ${state.observationFrames}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White
            )
            IconButton(onClick = onSwitchCamera) {
                Icon(
                    Icons.Filled.CameraAlt,
                    contentDescription = if (cameraFacing == StudyCameraFacing.Back) {
                        "切换到前置摄像头"
                    } else {
                        "切换到后置摄像头"
                    },
                    tint = Color.White
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.68f))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "实时陪学对话",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "不录像 · 识别帧存本机",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.72f)
                )
            }

            if (currentText.isNotBlank()) {
                LiveChatBubble(
                    message = ChatMessage(
                        id = Long.MAX_VALUE,
                        text = currentText,
                        fromUser = listening,
                        timestamp = System.currentTimeMillis()
                    ),
                    emphasize = true
                )
            }

            val sessionChat = chat
                .filter { it.timestamp >= state.startedAt }
                .takeLast(5)
            if (sessionChat.isEmpty() && currentText.isBlank()) {
                Text(
                    text = if (listening) "孩子正在说话..." else "孩子开口后，对话会显示在这里",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f)
                )
            } else {
                sessionChat.forEach { message ->
                    LiveChatBubble(message = message, emphasize = false)
                }
            }
        }
    }
}

@Composable
private fun LiveChatBubble(
    message: ChatMessage,
    emphasize: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            horizontalAlignment = if (message.fromUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 310.dp)
        ) {
            Text(
                text = if (message.fromUser) "孩子" else "小智",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.68f)
            )
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (message.fromUser) {
                            Color.White.copy(alpha = if (emphasize) 0.30f else 0.18f)
                        } else {
                            Color.White.copy(alpha = if (emphasize) 0.16f else 0.10f)
                        }
                    )
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            )
        }
    }
}

private fun currentDisplayRotation(context: Context): Int {
    return currentDisplay(context)?.rotation ?: Surface.ROTATION_0
}

private fun currentDisplay(context: Context): Display? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.display
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(WindowManager::class.java)?.defaultDisplay
    }
}

@Composable
private fun StudyPanel(
    containerColor: androidx.compose.ui.graphics.Color =
        MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        content()
    }
}

@Composable
private fun StudyEntry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StudySessionHeader(
    state: StudyRuntimeState,
    onStop: () -> Unit
) {
    val issue = state.observationIssue.ifBlank { state.lastCaptureFailure }
    val status = when {
        state.phase == StudyPhase.Prepare -> "摆位中"
        !state.settings.observationEnabled -> "语音陪学中"
        !state.observationRunning -> "相机不可用"
        issue.isNotBlank() -> "识别不稳定"
        state.phase == StudyPhase.Break -> "休息中"
        else -> "专注中"
    }
    val remaining = if (state.phase == StudyPhase.Break) {
        state.breakRemainingSeconds
    } else {
        state.focusRemainingSeconds
    }

    StudyPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (state.mode == StudyMode.Homework) "陪做作业" else "陪读",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "$status · 剩余 ${formatSeconds(remaining)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (state.captureRunning) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
            }
            IconButton(onClick = onStop) {
                Icon(Icons.Filled.Stop, contentDescription = "结束陪学")
            }
        }
        if (issue.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (state.consecutiveCaptureFailures >= 2) {
                    "$issue；语音和文字陪学仍可用，稍后自动重试"
                } else {
                    issue
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StudyCameraSetupPanel(
    observationRunning: Boolean,
    observationIssue: String,
    onConfirm: () -> Unit
) {
    val cameraUnavailable = !observationRunning && observationIssue.isNotBlank()

    StudyPanel {
        Text(
            text = "固定手机",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(10.dp))
        listOf(
            "孩子和学习资料同框",
            "手机稳定，画面不晃动",
            "光线充足，避免反光"
        ).forEach { tip ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(text = tip, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (cameraUnavailable) {
            Text(
                text = "$observationIssue。可以先继续语音和文字陪学，相机恢复后会自动重试。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Text("继续语音陪学")
            }
        } else {
            Text(
                text = if (observationRunning) {
                    "确认画面后开始，本次陪学不录制视频。"
                } else {
                    "相机准备中，准备好后点击开始。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onConfirm,
                enabled = observationRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Text("开始专注")
            }
        }
    }
}

@Composable
private fun StudySummaryDialog(
    record: StudySessionRecord,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("本次陪学完成") },
        text = {
            Text(StudySessionManager.friendlySummary(record))
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Text("完成")
            }
        }
    )
}

@Composable
private fun StudyRecordRow(record: StudySessionRecord) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
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

private fun formatSeconds(seconds: Int): String {
    return "%02d:%02d".format(Locale.getDefault(), seconds / 60, seconds % 60)
}

private fun homeworkStateLabel(state: String): String {
    return when (state) {
        "correct" -> "已做对"
        "corrected" -> "已订正"
        "wrong" -> "需订正"
        "blank" -> "未作答"
        "unreadable" -> "看不清"
        else -> "未检查"
    }
}

@Composable
private fun homeworkStateColor(state: String): androidx.compose.ui.graphics.Color {
    return when (state) {
        "correct", "corrected" -> MaterialTheme.colorScheme.primary
        "wrong", "unreadable" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun readingStateLabel(state: String): String {
    return when (state) {
        "passed" -> "已通过"
        "needs_retry" -> "再读一遍"
        else -> "待跟读"
    }
}

@Composable
private fun readingStateColor(state: String): androidx.compose.ui.graphics.Color {
    return when (state) {
        "passed" -> MaterialTheme.colorScheme.primary
        "needs_retry" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
