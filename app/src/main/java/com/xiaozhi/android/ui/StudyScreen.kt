package com.xiaozhi.android.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.os.Build
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import com.xiaozhi.android.study.StudyObservationEngine
import com.xiaozhi.android.study.AnswerPolicy
import com.xiaozhi.android.study.StudyMode
import com.xiaozhi.android.study.StudyRuntimeState
import com.xiaozhi.android.study.StudySessionRecord
import com.xiaozhi.android.study.StudySessionState
import com.xiaozhi.android.study.StudySettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import org.json.JSONObject

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

        if (state.mode != StudyMode.None) {
            item {
                StudyPreviewCard(
                    modeLabel = if (state.mode == StudyMode.Homework) {
                        "陪做作业"
                    } else {
                        "陪读"
                    },
                    observationEnabled = state.settings.observationEnabled,
                    observationRunning = state.observationRunning,
                    observationFrames = state.observationFrames,
                    remainingLabel = formatSeconds(
                        if (state.phase == com.xiaozhi.android.study.StudyPhase.Break) {
                            state.breakRemainingSeconds
                        } else {
                            state.focusRemainingSeconds
                        }
                    ),
                    onOpenFullscreen = { fullscreenPreview = true }
                )
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
                        text = "固定手机，让孩子和学习资料同时进入画面。相机只在陪学期间保持会话，不录制视频、不保存画面，取帧时仅上传当前 JPEG 单帧用于识别。",
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
                            onClick = { showParentDialog = true },
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
                    mode = state.mode.name,
                    phase = if (state.phase == com.xiaozhi.android.study.StudyPhase.Break) {
                        "休息中"
                    } else {
                        "进行中"
                    },
                    remaining = if (state.phase == com.xiaozhi.android.study.StudyPhase.Break) {
                        state.breakRemainingSeconds
                    } else {
                        state.focusRemainingSeconds
                    },
                    observationEnabled = state.settings.observationEnabled,
                    observationRunning = state.observationRunning,
                    observationFrames = state.observationFrames,
                    lastObservationAt = state.lastObservationAt,
                    lastObservationTrigger = state.lastObservationTrigger,
                    statusMessage = state.statusMessage,
                    captureRunning = state.captureRunning,
                    onStop = viewModel::stopStudy
                )
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
                Text("开启后，小智在给足思路提示后可以说出最终答案。请家长确认孩子正在陪伴下使用。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        draft = draft.copy(answerPolicy = AnswerPolicy.GuidanceThenAnswer)
                        showParentDialog = false
                    }
                ) { Text("确认开启") }
            },
            dismissButton = {
                TextButton(onClick = { showParentDialog = false }) { Text("取消") }
            }
        )
    }

    if (fullscreenPreview && state.mode != StudyMode.None) {
        StudyFullscreenPreview(
            state = state,
            chat = chat,
            currentText = runtimeState.currentText,
            listening = runtimeState.deviceState == DeviceState.Listening,
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
    remainingLabel: String,
    onOpenFullscreen: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .aspectRatio(16f / 9f)
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
                text = "LIVE",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White
            )
        }

        Text(
            text = "$modeLabel · $remainingLabel · 取帧 $observationFrames",
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

        Icon(
            imageVector = Icons.Filled.Fullscreen,
            contentDescription = "全屏预览",
            tint = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(4.dp)
        )
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
        var attachedSurface: Surface? = null
        var previewInfo: StudyPreviewInfo? = null

        fun applyTransform(viewWidth: Int, viewHeight: Int) {
            val info = previewInfo ?: return
            textureView.setTransform(
                fillCropTransform(
                    info = info,
                    viewWidth = viewWidth,
                    viewHeight = viewHeight,
                    displayRotation = currentDisplayRotation(context)
                )
            )
        }

        val listener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: android.graphics.SurfaceTexture,
                width: Int,
                height: Int
            ) {
                val cameraSurface = Surface(surface)
                attachedSurface = cameraSurface
                StudyObservationEngine.attachPreview(surface, width, height) { info ->
                    previewInfo = info
                    applyTransform(width, height)
                    onPreviewStateChanged(info != null)
                }
            }

            override fun onSurfaceTextureSizeChanged(
                surface: android.graphics.SurfaceTexture,
                width: Int,
                height: Int
            ) {
                applyTransform(width, height)
            }

            override fun onSurfaceTextureDestroyed(
                surface: android.graphics.SurfaceTexture
            ): Boolean {
                attachedSurface?.let(StudyObservationEngine::detachPreview)
                attachedSurface = null
                previewInfo = null
                onPreviewStateChanged(false)
                return true
            }

            override fun onSurfaceTextureUpdated(
                surface: android.graphics.SurfaceTexture
            ) = Unit
        }
        textureView.surfaceTextureListener = listener

        onDispose {
            attachedSurface?.let(StudyObservationEngine::detachPreview)
            attachedSurface = null
            textureView.surfaceTextureListener = null
            onPreviewStateChanged(false)
        }
    }

    AndroidView(
        factory = { textureView },
        modifier = modifier
    )
}

@Composable
private fun StudyFullscreenPreview(
    state: StudyRuntimeState,
    chat: List<ChatMessage>,
    currentText: String,
    listening: Boolean,
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
                    text = "不录像 · 不保存画面",
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

private fun fillCropTransform(
    info: StudyPreviewInfo,
    viewWidth: Int,
    viewHeight: Int,
    displayRotation: Int
): Matrix {
    val rotation = ((info.rotationDegrees - displayRotation) % 360 + 360) % 360
    val rotatedWidth = if (rotation == 90 || rotation == 270) info.height else info.width
    val rotatedHeight = if (rotation == 90 || rotation == 270) info.width else info.height
    val scale = maxOf(
        viewWidth.toFloat() / rotatedWidth,
        viewHeight.toFloat() / rotatedHeight
    )
    val centerX = info.width / 2f
    val centerY = info.height / 2f
    val dx = (viewWidth - rotatedWidth * scale) / 2f
    val dy = (viewHeight - rotatedHeight * scale) / 2f
    return Matrix().apply {
        setRotate(rotation.toFloat(), centerX, centerY)
        postScale(scale, scale, centerX, centerY)
        postTranslate(dx, dy)
    }
}

private fun currentDisplayRotation(context: Context): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.display?.rotation ?: Surface.ROTATION_0
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(WindowManager::class.java)?.defaultDisplay?.rotation
            ?: Surface.ROTATION_0
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
    mode: String,
    phase: String,
    remaining: Int,
    observationEnabled: Boolean,
    observationRunning: Boolean,
    observationFrames: Int,
    lastObservationAt: Long,
    lastObservationTrigger: String,
    statusMessage: String,
    captureRunning: Boolean,
    onStop: () -> Unit
) {
    StudyPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (mode == "Homework") "陪做作业" else "陪读",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "$phase · 剩余 ${formatSeconds(remaining)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when {
                        !observationEnabled -> "固定机位观察未开启"
                        observationRunning -> buildString {
                            append("观察中 · 已取帧 $observationFrames 次")
                            if (lastObservationAt > 0) {
                                append(
                                    " · 上次${
                                        if (lastObservationTrigger == "speech") "说话触发" else "自动"
                                    } ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(lastObservationAt))}"
                                )
                            }
                        }
                        else -> "固定机位相机未运行"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (captureRunning) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                IconButton(onClick = onStop) {
                    Icon(Icons.Filled.Stop, contentDescription = "结束陪学")
                }
            }
        }
        if (statusMessage.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
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
            text = recordSummary(record),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun recordSummary(record: StudySessionRecord): String {
    val json = runCatching { JSONObject(record.summary) }.getOrNull()
        ?: return record.summary
    return if (record.mode == StudyMode.Homework) {
        "学习 ${json.optInt("duration_minutes")} 分钟；题目 ${json.optJSONArray("items")?.length() ?: 0} 道"
    } else {
        "阅读 ${json.optInt("duration_minutes")} 分钟；通过 " +
            "${json.optInt("sentences_passed")}/${json.optInt("sentences_total")} 句"
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
