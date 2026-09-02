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
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import kotlinx.coroutines.flow.map
import com.xiaozhi.android.core.ChatMessage
import com.xiaozhi.android.core.DeviceState
import com.xiaozhi.android.service.VoiceForegroundService
import com.xiaozhi.android.ui.theme.extendedColors
import com.xiaozhi.android.media.StudyPreviewInfo
import com.xiaozhi.android.media.StudyPreviewOrientation
import com.xiaozhi.android.study.StudyObservationEngine
import com.xiaozhi.android.study.StudyCameraFacing
import com.xiaozhi.android.study.QuickCommand
import com.xiaozhi.android.study.QuickCommandAction
import com.xiaozhi.android.study.StudyCommandCatalog
import com.xiaozhi.android.study.StudyMode
import com.xiaozhi.android.study.StudyPhase
import com.xiaozhi.android.study.StudyRewardEngine
import com.xiaozhi.android.study.StudyRuntimeState
import com.xiaozhi.android.study.StudySessionState
import com.xiaozhi.android.study.StudySettings
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
    // 家长中心：先过算式门禁再进入（与"家长解锁答案"共用门禁组件）
    var showParentCenter by remember { mutableStateOf(false) }
    var parentCenterUnlocked by remember { mutableStateOf(false) }
    // 全屏预览的底部输入草稿
    var liveInputDraft by remember { mutableStateOf("") }

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

    // 快捷指令分发：芯片直接复用现有能力，SendText 型与孩子说话同通道
    fun handleQuickCommand(command: QuickCommand) {
        when (command.action) {
            QuickCommandAction.SendText -> viewModel.sendStudyText(command.prompt)
            QuickCommandAction.CaptureExplain ->
                withStudyPermissions {
                    if (state.mode == StudyMode.Homework) {
                        viewModel.captureHomeworkPage(
                            com.xiaozhi.android.study.HomeworkPromptBuilder.INTENT_EXPLAIN
                        )
                    } else {
                        viewModel.captureReadingPage()
                    }
                }
            QuickCommandAction.CaptureCheck ->
                withStudyPermissions {
                    viewModel.captureHomeworkPage(
                        com.xiaozhi.android.study.HomeworkPromptBuilder.INTENT_CHECK
                    )
                }
            QuickCommandAction.HintCurrent -> viewModel.requestHomeworkHint(null)
            QuickCommandAction.RepeatReading -> viewModel.repeatReadingSentence()
            QuickCommandAction.AskComprehension -> viewModel.askReadingComprehension()
            QuickCommandAction.PrevSentence -> viewModel.moveReadingSentence(-1)
            QuickCommandAction.NextSentence -> viewModel.moveReadingSentence(1)
            QuickCommandAction.FinishSession -> viewModel.stopStudy()
        }
    }

    fun sendFromLive(text: String) {
        if (text.isBlank()) return
        liveInputDraft = ""
        viewModel.sendStudyText(text)
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "陪学",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showParentCenter = true }) {
                    Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = "家长中心",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "学习中可以说“看第 3 题”，也可以点屏幕上的快捷按钮和小智互动；右上角盾牌是家长中心。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            item {
                StudySessionHeader(
                    state = state,
                    liveStars = StudyRewardEngine.liveStars(state),
                    onStop = { viewModel.stopStudy() }
                )
            }

            item {
                StudyQuickCommandRow(
                    commands = StudyCommandCatalog.forContext(
                        mode = state.mode,
                        phase = state.phase,
                        hasPage = if (state.mode == StudyMode.Homework) {
                            !state.homeworkPage?.items.isNullOrEmpty()
                        } else {
                            !state.readingPage?.sentences.isNullOrEmpty()
                        }
                    ),
                    enabled = !state.captureRunning,
                    onCommand = ::handleQuickCommand
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
                            // 题内提示是次级动作,用低饱和的 tonal 层级避免全屏主按钮
                            FilledTonalButton(
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
                                MaterialTheme.colorScheme.surfaceContainerLow
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

    // 家长中心入口：先过算式门禁，验证通过后打开设置与报告
    if (showParentCenter && !parentCenterUnlocked) {
        ParentGateDialog(
            description = "家长中心包含陪学设置与学习报告，请家长完成验证。",
            onVerified = { parentCenterUnlocked = true },
            onDismiss = { showParentCenter = false }
        )
    }
    if (showParentCenter && parentCenterUnlocked) {
        ParentCenterSheet(
            settings = settings,
            records = records,
            onSaveSettings = viewModel::updateStudySettings,
            onClearRecords = {
                viewModel.clearStudyRecords()
            },
            onDismiss = {
                showParentCenter = false
                parentCenterUnlocked = false
            }
        )
    }

    // 会话结束：总结页覆盖整屏，"收下星星"复位
    state.summary?.let { settlement ->
        StudySummaryScreen(
            settlement = settlement,
            nickname = state.settings.childNickname,
            onCollect = StudySessionState::reset
        )
    }

    if (fullscreenPreview && state.mode != StudyMode.None && state.summary == null) {
        StudyFullscreenPreview(
            state = state,
            chat = chat,
            currentText = runtimeState.currentText,
            listening = runtimeState.deviceState == DeviceState.Listening,
            voiceActive = runtimeState.deviceState == DeviceState.Listening ||
                runtimeState.deviceState == DeviceState.Speaking,
            inputDraft = liveInputDraft,
            onInputDraftChange = { liveInputDraft = it },
            onSendFromLive = ::sendFromLive,
            onVoiceClick = {
                when (runtimeState.deviceState) {
                    DeviceState.Listening -> VoiceForegroundService.stopListening(context)
                    DeviceState.Speaking -> VoiceForegroundService.abortSpeaking()
                    else -> VoiceForegroundService.startListening(context)
                }
            },
            onQuickCommand = ::handleQuickCommand,
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
                imageVector = Icons.Filled.Refresh,
                contentDescription = "旋转画面",
                tint = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = observationRunning, onClick = StudySessionState::rotatePreview)
                    .padding(8.dp)
            )
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
    // 手动旋转校正（0/90/180/270）：变化时重新布局预览
    val rotationOffset by StudySessionState.state
        .map { it.previewRotationOffset }
        .collectAsStateWithLifecycle(initialValue = 0)
    val applyLayoutRef = remember { mutableStateOf<(() -> Unit)?>(null) }

    DisposableEffect(textureView) {
        var attachedSurfaceTexture: android.graphics.SurfaceTexture? = null
        var previewInfo: StudyPreviewInfo? = null
        var observedContainer: FrameLayout? = null
        var layoutObserver: ViewTreeObserver.OnGlobalLayoutListener? = null
        val display = currentDisplay(context)
        var lastDisplayRotation = display?.rotation ?: currentDisplayRotation(context)

        fun applyLayout() {
            val info = previewInfo ?: return
            applyCameraViewLayout(
                textureView,
                info,
                currentDisplayRotation(context),
                rotationOffset
            )
        }
        applyLayoutRef.value = ::applyLayout

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
            applyLayoutRef.value = null
            onPreviewStateChanged(false)
        }
    }

    // 手动旋转校正变化时立即重排预览
    LaunchedEffect(rotationOffset) {
        applyLayoutRef.value?.invoke()
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
    displayRotation: Int,
    manualRotationOffset: Int = 0
) {
    val container = textureView.parent as? FrameLayout ?: return
    if (container.width <= 0 || container.height <= 0) {
        container.post {
            applyCameraViewLayout(textureView, info, displayRotation, manualRotationOffset)
        }
        return
    }

    val rotation = (
        StudyPreviewOrientation.previewRotationDegrees(
            sensorOrientationDegrees = info.rotationDegrees,
            isFrontCamera = info.isFrontCamera,
            displayRotation = displayRotation
        ) + manualRotationOffset
        ) % 360
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
    voiceActive: Boolean,
    inputDraft: String,
    onInputDraftChange: (String) -> Unit,
    onSendFromLive: (String) -> Unit,
    onVoiceClick: () -> Unit,
    onQuickCommand: (QuickCommand) -> Unit,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = MaterialTheme.extendedColors.starGold,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "${StudyRewardEngine.liveStars(state)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.extendedColors.starGold
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "取帧 ${state.observationFrames}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White
            )
            IconButton(onClick = StudySessionState::rotatePreview) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "旋转画面",
                    tint = Color.White
                )
            }
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

            // 全屏沉浸时快捷指令同样可用，不用猜语音指令
            StudyQuickCommandRow(
                commands = StudyCommandCatalog.forContext(
                    mode = state.mode,
                    phase = state.phase,
                    hasPage = if (state.mode == StudyMode.Homework) {
                        !state.homeworkPage?.items.isNullOrEmpty()
                    } else {
                        !state.readingPage?.sentences.isNullOrEmpty()
                    }
                ),
                enabled = !state.captureRunning,
                darkStyle = true,
                onCommand = onQuickCommand
            )

            StudyLiveInputBar(
                value = inputDraft,
                onValueChange = onInputDraftChange,
                voiceActive = voiceActive,
                onSend = { onSendFromLive(inputDraft) },
                onVoiceClick = onVoiceClick
            )
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
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (message.fromUser) {
                            Color.White.copy(alpha = if (emphasize) 0.30f else 0.18f)
                        } else {
                            Color.White.copy(alpha = if (emphasize) 0.16f else 0.10f)
                        }
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
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
        MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(16.dp),
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
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 图标配主色圆托盘,让模式入口有明确的视觉重量
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
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
    liveStars: Int,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "本次星星",
                    tint = MaterialTheme.extendedColors.starGold,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "$liveStars",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.extendedColors.starGold
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
                // 勾选清单用成功色小托盘,比裸图标更有"就绪"的仪式感
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.extendedColors.successContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.extendedColors.success,
                        modifier = Modifier.size(14.dp)
                    )
                }
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
        // 做对/订正属于"成功"语义,与主色区分开
        "correct", "corrected" -> MaterialTheme.extendedColors.success
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
        "passed" -> MaterialTheme.extendedColors.success
        "needs_retry" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
