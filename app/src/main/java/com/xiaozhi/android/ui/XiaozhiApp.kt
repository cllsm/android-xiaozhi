package com.xiaozhi.android.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaozhi.android.BuildConfig
import com.xiaozhi.android.R
import com.xiaozhi.android.core.ChatMessage
import com.xiaozhi.android.core.ConnectionStatus
import com.xiaozhi.android.core.DeviceState
import com.xiaozhi.android.core.SettingsState
import com.xiaozhi.android.core.SettingsValidator
import com.xiaozhi.android.core.ThemeMode
import com.xiaozhi.android.core.UserErrorMessages
import com.xiaozhi.android.core.VoiceRuntimeState
import com.xiaozhi.android.core.WakeWordTestState
import com.xiaozhi.android.data.RecentMusicRecord
import com.xiaozhi.android.media.MusicSelectionPrompt
import com.xiaozhi.android.media.MusicRuntimeState
import com.xiaozhi.android.media.ScreenCaptureController
import com.xiaozhi.android.service.MediaProjectionForegroundService
import com.xiaozhi.android.service.VoiceForegroundService
import com.xiaozhi.android.ui.theme.XiaozhiTheme
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class Screen {
    Onboarding,
    Home,
    Study,
    Settings,
    RecentMusic,
    Developer,
    Diagnostics,
    Privacy
}

private enum class DirectVisionAction {
    Screen,
    Camera,
    Image
}

private const val DEVELOPER_UNLOCK_TAPS = 7

@Composable
fun XiaozhiApp(viewModel: XiaozhiViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val settingsReady by viewModel.settingsReady.collectAsStateWithLifecycle()

    val systemDarkTheme = isSystemInDarkTheme()
    XiaozhiTheme(
        darkTheme = when (settings.themeMode) {
            ThemeMode.System -> systemDarkTheme
            ThemeMode.Dark -> true
            ThemeMode.Light -> false
        }
    ) {
        AppContent(viewModel, settingsReady)
    }
}

@Composable
private fun AppContent(
    viewModel: XiaozhiViewModel,
    settingsReady: Boolean
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val runtimeState by viewModel.runtimeState.collectAsStateWithLifecycle()
    val diagnosticReport by viewModel.diagnosticReport.collectAsStateWithLifecycle()
    val diagnosticRunning by viewModel.diagnosticRunning.collectAsStateWithLifecycle()
    val operationMessage by viewModel.operationMessage.collectAsStateWithLifecycle()
    val recentMusic by viewModel.recentMusic.collectAsStateWithLifecycle()
    val musicPlaybackState by viewModel.musicPlaybackState.collectAsStateWithLifecycle()
    val musicOperationMessage by viewModel.musicOperationMessage.collectAsStateWithLifecycle()
    val musicSelectionPrompt by viewModel.musicSelectionPrompt.collectAsStateWithLifecycle()
    val wakeWordTest by viewModel.wakeWordTest.collectAsStateWithLifecycle()
    var showSplash by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(900)
        showSplash = false
    }

    var screen by remember(settings.onboardingCompleted) {
        mutableStateOf(
            if (settings.onboardingCompleted) Screen.Home else Screen.Onboarding
        )
    }
    var privacyReturnScreen by remember { mutableStateOf(Screen.Home) }
    var studyPreviewFullscreen by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                if (!settingsReady) return@Box

                when (screen) {
                    Screen.Onboarding -> OnboardingScreen(
                        onFinish = viewModel::completeOnboarding,
                        onOpenPrivacy = {
                            privacyReturnScreen = Screen.Onboarding
                            screen = Screen.Privacy
                        }
                    )
                    Screen.Home -> HomeScreen(
                        settings = settings,
                        chat = chat,
                        runtimeState = runtimeState,
                        onSendText = viewModel::sendText,
                        onAnalyzeScreen = viewModel::analyzeScreen,
                        onAnalyzeCamera = viewModel::analyzeCamera,
                        onAnalyzeImage = viewModel::analyzeImage,
                        onOpenRecentMusic = { screen = Screen.RecentMusic },
                        onOpenStudy = { screen = Screen.Study }
                    )
                    Screen.Study -> StudyScreen(
                        viewModel = viewModel,
                        onFullscreenChange = { studyPreviewFullscreen = it }
                    )
                    Screen.RecentMusic -> RecentMusicScreen(
                        records = recentMusic,
                        playbackState = musicPlaybackState,
                        operationMessage = musicOperationMessage,
                        onBack = { screen = Screen.Home },
                        onReplay = viewModel::replayMusic,
                        onTogglePlayback = viewModel::toggleMusicPlayback,
                        onClear = viewModel::clearMusicHistory,
                        onClearMessage = viewModel::clearMusicOperationMessage
                    )
                    Screen.Settings -> SettingsScreen(
                        settings = settings,
                        onUpdateSettings = viewModel::updateSettings,
                        onClearChat = viewModel::clearChat,
                        onResetSettings = viewModel::resetSettings,
                        onExportChat = viewModel::exportChat,
                        onImportChat = viewModel::importChat,
                        onExportCredential = viewModel::exportCredential,
                        onImportCredential = viewModel::importCredential,
                        wakeWordTest = wakeWordTest,
                        onStartWakeWordTest = viewModel::startWakeWordTest,
                        operationMessage = operationMessage,
                        onClearOperationMessage = viewModel::clearOperationMessage,
                        onOpenDiagnostics = { screen = Screen.Diagnostics },
                        onOpenPrivacy = {
                            privacyReturnScreen = Screen.Settings
                            screen = Screen.Privacy
                        },
                        onOpenDeveloper = { screen = Screen.Developer }
                    )
                    Screen.Developer -> DeveloperSettingsScreen(
                        settings = settings,
                        onUpdateSettings = viewModel::updateSettings,
                        operationMessage = operationMessage,
                        onClearOperationMessage = viewModel::clearOperationMessage,
                        onBack = { screen = Screen.Settings }
                    )
                    Screen.Diagnostics -> DiagnosticsScreen(
                        report = diagnosticReport,
                        checking = diagnosticRunning,
                        onBack = { screen = Screen.Settings },
                        onRun = viewModel::runDiagnostics,
                        onBuildReportText = viewModel::diagnosticText
                    )
                    Screen.Privacy -> PrivacyScreen(
                        onBack = { screen = privacyReturnScreen }
                    )
                }
            }

            if (screen in mainScreens &&
                !(screen == Screen.Study && studyPreviewFullscreen)
            ) {
                MainBottomNavigation(
                    current = screen,
                    onSelect = { selected -> screen = selected }
                )
            }
        }

        musicSelectionPrompt?.let { prompt ->
            MusicSelectionDialog(
                prompt = prompt,
                onSelect = viewModel::selectMusicCandidate,
                onPostponeAutoPlay = viewModel::postponeMusicSelectionAutoPlay,
                onDismiss = viewModel::dismissMusicSelection
            )
        }

        AnimatedVisibility(
            visible = showSplash,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(240)),
            modifier = Modifier.fillMaxSize()
        ) {
            Image(
                painter = painterResource(R.drawable.splash),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )
        }
    }
}

private val mainScreens = listOf(Screen.Home, Screen.Study, Screen.Settings)

@Composable
private fun MainBottomNavigation(
    current: Screen,
    onSelect: (Screen) -> Unit
) {
    NavigationBar {
        NavigationBarItem(
            selected = current == Screen.Home,
            onClick = { onSelect(Screen.Home) },
            icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
            label = { Text("对话") }
        )
        NavigationBarItem(
            selected = current == Screen.Study,
            onClick = { onSelect(Screen.Study) },
            icon = { Icon(Icons.Filled.School, contentDescription = null) },
            label = { Text("陪学") }
        )
        NavigationBarItem(
            selected = current == Screen.Settings,
            onClick = { onSelect(Screen.Settings) },
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            label = { Text("设置") }
        )
    }
}

@Composable
private fun MusicSelectionDialog(
    prompt: MusicSelectionPrompt,
    onSelect: (Int) -> Unit,
    onPostponeAutoPlay: () -> Unit,
    onDismiss: () -> Unit
) {
    val rememberSelectionHint = if (prompt.options.size > 1) {
        "打开“记住歌曲版本选择”后，相同搜索会优先使用上次选择的版本。"
    } else {
        null
    }

    var remainingMillis by remember(prompt.autoSelectAtMillis) {
        mutableStateOf(
            (prompt.autoSelectAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        )
    }

    LaunchedEffect(prompt.autoSelectAtMillis) {
        while (remainingMillis > 0L) {
            delay(100L)
            remainingMillis = (
                prompt.autoSelectAtMillis - System.currentTimeMillis()
                ).coerceAtLeast(0L)
        }
    }

    var currentPage by remember(prompt.query, prompt.options) { mutableStateOf(0) }
    val pageCount = prompt.pageCount
    val pageStart = currentPage * prompt.pageSize
    val visibleOptions = prompt.options.drop(pageStart).take(prompt.pageSize)

    fun changePage(delta: Int) {
        val nextPage = (currentPage + delta).coerceIn(0, pageCount - 1)
        if (nextPage != currentPage) {
            currentPage = nextPage
            onPostponeAutoPlay()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择歌曲") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "“${prompt.query}”找到 ${prompt.options.size} 个版本，可以直接说或输入序号。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (pageCount > 1) {
                        "第 ${currentPage + 1}/${pageCount} 页，" +
                            "${(remainingMillis + 999L) / 1000L} 秒后自动播放第 1 首"
                    } else {
                        "${(remainingMillis + 999L) / 1000L} 秒后自动播放第 1 首"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                rememberSelectionHint?.let { hint ->
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                visibleOptions.forEach { option ->
                    TextButton(
                        onClick = { onSelect(option.number) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = option.number.toString(),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(24.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = option.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                listOf(option.artist, option.album)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · ")
                                    .takeIf { it.isNotBlank() }
                                    ?.let { metadata ->
                                        Text(
                                            text = metadata,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                Text(
                                    text = option.sourceName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                if (pageCount > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { changePage(-1) },
                            enabled = currentPage > 0
                        ) {
                            Icon(
                                Icons.Filled.ChevronLeft,
                                contentDescription = "上一页"
                            )
                        }
                        Text(
                            text = "${pageStart + 1}-${
                                minOf(pageStart + prompt.pageSize, prompt.options.size)
                            } / ${prompt.options.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center
                        )
                        IconButton(
                            onClick = { changePage(1) },
                            enabled = currentPage < pageCount - 1
                        ) {
                            Icon(
                                Icons.Filled.ChevronRight,
                                contentDescription = "下一页"
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun HomeScreen(
    settings: SettingsState,
    chat: List<ChatMessage>,
    runtimeState: VoiceRuntimeState,
    onSendText: (String) -> Boolean,
    onAnalyzeScreen: (String) -> Unit,
    onAnalyzeCamera: (String) -> Unit,
    onAnalyzeImage: (String, Uri) -> Unit,
    onOpenRecentMusic: () -> Unit,
    onOpenStudy: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var startRequested by remember { mutableStateOf(false) }
    var textDraft by remember { mutableStateOf("") }
    var dismissedActivationCode by remember { mutableStateOf("") }
    var pendingScreenPrompt by remember { mutableStateOf<String?>(null) }
    var pendingText by remember { mutableStateOf<String?>(null) }
    var pendingTextNeedsCamera by remember { mutableStateOf(false) }
    var pendingVisionAction by remember { mutableStateOf<DirectVisionAction?>(null) }
    var pendingVisionPrompt by remember { mutableStateOf<String?>(null) }
    var pendingVisionImage by remember { mutableStateOf<Uri?>(null) }
    var draftImage by remember { mutableStateOf<Uri?>(null) }
    var viewingImage by remember { mutableStateOf<String?>(null) }
    var toolsExpanded by remember { mutableStateOf(false) }
    val chatListState = rememberLazyListState()

    LaunchedEffect(chat.lastOrNull()?.id, runtimeState.currentText) {
        if (chat.isNotEmpty()) chatListState.animateScrollToItem(chat.lastIndex)
    }

    LaunchedEffect(Unit) {
        val microphoneReady = context.checkSelfPermission(
            Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (microphoneReady && !VoiceForegroundService.isRunning()) {
            VoiceForegroundService.start(context)
            startRequested = true
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val visionAction = pendingVisionAction
        val visionPrompt = pendingVisionPrompt
        val visionImage = pendingVisionImage
        pendingVisionAction = null
        pendingVisionPrompt = null
        pendingVisionImage = null

        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            VoiceForegroundService.start(context)
            startRequested = true
            val regularCameraReady = !pendingTextNeedsCamera ||
                grants[Manifest.permission.CAMERA] == true

            when (visionAction) {
                DirectVisionAction.Screen ->
                    visionPrompt?.let(onAnalyzeScreen)
                DirectVisionAction.Camera ->
                    visionPrompt?.let(onAnalyzeCamera)
                DirectVisionAction.Image ->
                    visionImage?.let { image -> onAnalyzeImage(visionPrompt.orEmpty(), image) }
                null -> if (regularCameraReady) {
                    pendingText?.let(onSendText) ?: VoiceForegroundService.startListening(context)
                }
            }
        } else {
            when (visionAction) {
                DirectVisionAction.Screen ->
                    visionPrompt?.let(onAnalyzeScreen)
                DirectVisionAction.Camera ->
                    visionPrompt?.let(onAnalyzeCamera)
                DirectVisionAction.Image ->
                    visionImage?.let { image -> onAnalyzeImage(visionPrompt.orEmpty(), image) }
                null -> Unit
            }
        }
        pendingText = null
        pendingTextNeedsCamera = false
    }

    fun sendFromHome(text: String, needsCamera: Boolean = false): Boolean {
        val microphoneReady = context.checkSelfPermission(
            Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val cameraReady = !needsCamera || context.checkSelfPermission(
            Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (microphoneReady && cameraReady) {
            return onSendText(text)
        }

        pendingText = text
        pendingTextNeedsCamera = needsCamera
        permissionLauncher.launch(
            buildList {
                add(Manifest.permission.RECORD_AUDIO)
                add(Manifest.permission.CAMERA)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.toTypedArray()
        )
        return true
    }

    fun requestDirectVision(prompt: String, action: DirectVisionAction) {
        val microphoneReady = context.checkSelfPermission(
            Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val actionPermissionReady = when (action) {
            DirectVisionAction.Screen -> ScreenCaptureController.hasPermission()
            DirectVisionAction.Camera -> context.checkSelfPermission(
                Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            DirectVisionAction.Image -> true
        }

        if (microphoneReady && actionPermissionReady) {
            VoiceForegroundService.start(context)
            startRequested = true
            when (action) {
                DirectVisionAction.Screen -> onAnalyzeScreen(prompt)
                DirectVisionAction.Camera -> onAnalyzeCamera(prompt)
                DirectVisionAction.Image -> Unit
            }
            return
        }

        pendingVisionAction = action
        pendingVisionPrompt = prompt
        pendingText = null
        pendingTextNeedsCamera = false
        permissionLauncher.launch(
            buildList {
                add(Manifest.permission.RECORD_AUDIO)
                add(Manifest.permission.CAMERA)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.toTypedArray()
        )
    }

    fun requestImagePrompt(prompt: String, image: Uri) {
        val microphoneReady = context.checkSelfPermission(
            Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (microphoneReady) {
            VoiceForegroundService.start(context)
            startRequested = true
            onAnalyzeImage(prompt, image)
            return
        }

        pendingVisionAction = DirectVisionAction.Image
        pendingVisionPrompt = prompt
        pendingVisionImage = image
        pendingText = null
        pendingTextNeedsCamera = false
        permissionLauncher.launch(
            buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.toTypedArray()
        )
    }

    fun submitHomeInput() {
        val text = textDraft.trim()
        val image = draftImage
        if (image == null) {
            if (text.isNotEmpty() && sendFromHome(text)) {
                textDraft = ""
                toolsExpanded = false
            }
            return
        }

        requestImagePrompt(
            prompt = text.ifBlank { "描述这张图片的内容" },
            image = image
        )
        textDraft = ""
        draftImage = null
        toolsExpanded = false
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { image ->
        if (image != null) {
            draftImage = image
            toolsExpanded = false
        }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureController.savePermissionResult(result.resultCode, result.data)
            MediaProjectionForegroundService.start(context)
            pendingScreenPrompt?.let { requestDirectVision(it, DirectVisionAction.Screen) }
        }
        pendingScreenPrompt = null
    }

    val serviceRunning = VoiceForegroundService.isRunning() ||
        startRequested ||
        runtimeState.status != ConnectionStatus.Disconnected
    val showActivationDialog = runtimeState.activationCode.isNotBlank() &&
        runtimeState.activationCode != dismissedActivationCode

    fun requestScreenPrompt(prompt: String) {
        if (ScreenCaptureController.hasPermission()) {
            requestDirectVision(prompt, DirectVisionAction.Screen)
            return
        }
        pendingScreenPrompt = prompt
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    fun requestCameraPrompt(prompt: String) {
        requestDirectVision(prompt, DirectVisionAction.Camera)
    }

    fun startVoiceAction() {
        if (!serviceRunning) {
            permissionLauncher.launch(
                buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    add(Manifest.permission.CAMERA)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }.toTypedArray()
            )
        } else if (
            runtimeState.status == ConnectionStatus.Error &&
            !runtimeState.waitingForNetwork
        ) {
            VoiceForegroundService.reconnectNow(context)
        } else if (serviceRunning &&
            runtimeState.status == ConnectionStatus.Connected &&
            runtimeState.deviceState == DeviceState.Idle
        ) {
            if (settings.wakeWordEnabled) {
                VoiceForegroundService.setWakeWordEnabled(context, false)
            }
            VoiceForegroundService.startListening(context)
        } else {
            VoiceForegroundService.startListening(context)
        }
    }

    fun stopVoiceAction() {
        when {
            runtimeState.deviceState == DeviceState.Listening ->
                VoiceForegroundService.stopListening(context)
            serviceRunning &&
                runtimeState.status == ConnectionStatus.Connected &&
                runtimeState.deviceState == DeviceState.Idle &&
                settings.wakeWordEnabled ->
                VoiceForegroundService.setWakeWordEnabled(context, false)
            else -> {
                VoiceForegroundService.stop(context)
                startRequested = false
            }
        }
    }

    if (showActivationDialog) {
        AlertDialog(
            onDismissRequest = { dismissedActivationCode = runtimeState.activationCode },
            title = { Text("设备激活") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("请在 xiaozhi.me 控制台输入验证码完成绑定")
                    Text(
                        text = formatActivationCode(runtimeState.activationCode),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (runtimeState.deviceId.isNotBlank()) {
                        Text(
                            text = "Device-Id：${runtimeState.deviceId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "绑定完成后会自动继续连接，无需重启 App。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(runtimeState.activationCode))
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://xiaozhi.me/"))
                        )
                    }
                ) { Text("复制并打开") }
            },
            dismissButton = {
                TextButton(
                    onClick = { dismissedActivationCode = runtimeState.activationCode }
                ) { Text("稍后") }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
        HomeTopBar()

        if (settings.studyCompanionEnabled) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .clip(RoundedCornerShape(8.dp))
                     .clickable(onClick = onOpenStudy)
            ) {
                StudyCameraPreview(modifier = Modifier.fillMaxSize())
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error)
                    )
                    Text(
                        text = "陪学中",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
                Icon(
                    imageVector = Icons.Filled.Fullscreen,
                    contentDescription = "全屏",
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(6.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            if (chat.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Spacer(modifier = Modifier.height(48.dp))
                    Text(
                        text = "小智",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "直接说话、打字，或试试下面这些",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    listOf(
                        "今天天气怎么样？",
                        "帮我看看屏幕上是什么",
                        "帮我看看面前有什么"
                    ).forEach { prompt ->
                        Text(
                            text = prompt,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    if (prompt.contains("屏幕")) {
                                        requestScreenPrompt(prompt)
                                    } else {
                                        requestCameraPrompt(prompt)
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 11.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = chatListState,
                    contentPadding = PaddingValues(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(
                        chat,
                        key = { _, message -> message.id }
                    ) { index, message ->
                        val previous = chat.getOrNull(index - 1)
                        if (previous == null || !isSameDay(previous.timestamp, message.timestamp)) {
                            DateSeparator(message.timestamp)
                        }
                        ChatBubble(
                            message = message,
                            onCopy = {
                                clipboard.setText(AnnotatedString(message.text))
                            },
                            onResend = { onSendText(message.text) },
                            onImageClick = { path -> viewingImage = path }
                        )
                    }
                }
            }
        }

        if (runtimeState.currentText.isNotBlank() ||
            runtimeState.deviceState == DeviceState.Listening ||
            runtimeState.deviceState == DeviceState.Speaking
        ) {
            AssistantLivePanel(
                runtimeState = runtimeState,
                onAbort = { VoiceForegroundService.abortSpeaking() },
                onStopListening = { VoiceForegroundService.stopListening(context) }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            AnimatedVisibility(
                visible = toolsExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                QuickToolsPanel(
                    onScreenLook = { requestScreenPrompt("帮我看看屏幕上是什么") },
                    onScreenRead = { requestScreenPrompt("帮我读一下屏幕上的文字") },
                    onFrontLook = {
                        requestCameraPrompt("帮我看看面前有什么")
                    },
                    onObjectLook = {
                        requestCameraPrompt("帮我拍张照看看这是什么东西")
                    },
                    onCamera = { sendFromHome("帮我打开相机") },
                    onMusic = { sendFromHome("播放一段音乐") },
                    onRecentMusic = onOpenRecentMusic,
                    onWeather = { sendFromHome("今天天气怎么样") },
                    onAction = { toolsExpanded = false }
                )
            }

            draftImage?.let { image ->
                PendingImagePreview(
                    image = image,
                    onRemove = { draftImage = null }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RoundIconButton(
                    icon = {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = if (toolsExpanded) "收起工具" else "更多工具"
                        )
                    },
                    onClick = { toolsExpanded = !toolsExpanded },
                    emphasized = toolsExpanded
                )

                BasicTextField(
                    value = textDraft,
                    onValueChange = { textDraft = it },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 46.dp, max = 132.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    minLines = 1,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = { submitHomeInput() }
                    ),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (textDraft.isEmpty()) {
                                Text(
                                    if (draftImage == null) "给小智发消息" else "补充图片要求",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                RoundIconButton(
                    icon = {
                        Icon(Icons.Filled.Image, contentDescription = "发送图片")
                    },
                    onClick = { imagePicker.launch("image/*") }
                )

                RoundIconButton(
                    icon = {
                        Icon(
                            if (
                                runtimeState.deviceState == DeviceState.Listening ||
                                runtimeState.deviceState == DeviceState.Speaking
                            ) {
                                Icons.Filled.Stop
                            } else {
                                Icons.Filled.Mic
                            },
                            contentDescription = when (runtimeState.deviceState) {
                                DeviceState.Listening -> "停止聆听"
                                DeviceState.Speaking -> "停止回复"
                                else -> "开始语音对话"
                            }
                        )
                    },
                    onClick = {
                        when (runtimeState.deviceState) {
                            DeviceState.Listening -> stopVoiceAction()
                            DeviceState.Speaking -> VoiceForegroundService.abortSpeaking()
                            else -> startVoiceAction()
                        }
                    },
                    emphasized = runtimeState.deviceState == DeviceState.Listening ||
                        runtimeState.deviceState == DeviceState.Speaking
                )

                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            if (textDraft.isBlank() && draftImage == null) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                        .clickable {
                            if (textDraft.isNotBlank() || draftImage != null) {
                                submitHomeInput()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        tint = if (textDraft.isBlank() && draftImage == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        }
                    )
                }
            }
        }
    }

    viewingImage?.let { path ->
        ImageViewerDialog(
            path = path,
            onDismiss = { viewingImage = null }
        )
    }
    }
}

@Composable
private fun StudyCompanionScreen(
    chat: List<ChatMessage>,
    runtimeState: VoiceRuntimeState,
    onSendText: (String) -> Boolean,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val view = LocalView.current
    var textDraft by remember { mutableStateOf("") }
    var startRequested by remember { mutableStateOf(false) }
    var pendingText by remember { mutableStateOf<String?>(null) }
    var pendingVoice by remember { mutableStateOf(false) }
    val chatListState = rememberLazyListState()
    val visibleChat = remember(chat) { chat.takeLast(30) }
    val serviceRunning = startRequested ||
        runtimeState.status != ConnectionStatus.Disconnected

    DisposableEffect(Unit) {
        val window = (view.context as Activity).window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, view)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            WindowCompat.setDecorFitsSystemWindows(window, true)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            VoiceForegroundService.start(context)
            startRequested = true
            pendingText?.let(onSendText)
            if (pendingVoice) VoiceForegroundService.startListening(context)
        }
        pendingText = null
        pendingVoice = false
    }

    fun sendFromLive(text: String) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            VoiceForegroundService.start(context)
            startRequested = true
            onSendText(text)
        } else {
            pendingText = text
            pendingVoice = false
            permissionLauncher.launch(
                buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    add(Manifest.permission.CAMERA)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }.toTypedArray()
            )
        }
    }

    fun startVoice() {
        if (serviceRunning &&
            runtimeState.status == ConnectionStatus.Connected &&
            runtimeState.deviceState == DeviceState.Idle
        ) {
            VoiceForegroundService.startListening(context)
        } else {
            pendingText = null
            pendingVoice = true
            permissionLauncher.launch(
                buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    add(Manifest.permission.CAMERA)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }.toTypedArray()
            )
        }
    }

    LaunchedEffect(visibleChat.lastOrNull()?.id, runtimeState.currentText) {
        if (visibleChat.isNotEmpty()) chatListState.animateScrollToItem(visibleChat.lastIndex)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        StudyCameraPreview(modifier = Modifier.fillMaxSize())
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.45f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.15f),
                        Color.Black.copy(alpha = 0.82f)
                    )
                )
            )
        )
        StudyLiveTopBar(onBack)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                state = chatListState,
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                items(visibleChat, key = { it.id }) { message ->
                    StudyLiveChatItem(message)
                }
                if (runtimeState.currentText.isNotBlank()) {
                    item(key = "live-reply") {
                        StudyLiveText(
                            speaker = "小智",
                            text = runtimeState.currentText,
                            fromUser = false
                        )
                    }
                }
            }
            StudyLiveInput(
                value = textDraft,
                runtimeState = runtimeState,
                onValueChange = { textDraft = it },
                onSend = {
                    val text = textDraft.trim()
                    if (text.isNotEmpty()) {
                        sendFromLive(text)
                        textDraft = ""
                    }
                },
                onVoiceClick = {
                    if (runtimeState.deviceState == DeviceState.Listening) {
                        VoiceForegroundService.stopListening(context)
                    } else if (runtimeState.deviceState == DeviceState.Speaking) {
                        VoiceForegroundService.abortSpeaking()
                    } else {
                        startVoice()
                    }
                }
            )
        }
    }
}

@Composable
private fun StudyLiveTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RoundIconButton(
            icon = {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "退出全屏",
                    tint = Color.White
                )
            },
            onClick = onBack
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.58f))
                .padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.LiveTv,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = "陪学直播",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White
            )
        }
    }
}

@Composable
private fun StudyLiveChatItem(message: ChatMessage) {
    StudyLiveText(
        speaker = if (message.fromUser) "主人翁" else "小智",
        text = message.text,
        fromUser = message.fromUser
    )
}

@Composable
private fun StudyLiveText(
    speaker: String,
    text: String,
    fromUser: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start
    ) {
        Text(
            text = speaker,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.72f)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (fromUser) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)
                    } else {
                        Color.Black.copy(alpha = 0.58f)
                    }
                )
                .padding(horizontal = 11.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun StudyLiveInput(
    value: String,
    runtimeState: VoiceRuntimeState,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoiceClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 46.dp, max = 112.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.58f))
                .padding(horizontal = 13.dp, vertical = 10.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
            minLines = 1,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            "给小智发消息",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.65f)
                        )
                    }
                    field()
                }
            }
        )
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.18f))
                .clickable(onClick = onVoiceClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (
                    runtimeState.deviceState == DeviceState.Listening ||
                    runtimeState.deviceState == DeviceState.Speaking
                ) {
                    Icons.Filled.Stop
                } else {
                    Icons.Filled.Mic
                },
                contentDescription = if (
                    runtimeState.deviceState == DeviceState.Listening ||
                    runtimeState.deviceState == DeviceState.Speaking
                ) {
                    "停止语音"
                } else {
                    "开始语音"
                },
                tint = Color.White
            )
        }
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(
                    if (value.isBlank()) Color.White.copy(alpha = 0.18f)
                    else MaterialTheme.colorScheme.primary
                )
                .clickable(onClick = onSend),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "发送",
                tint = Color.White
            )
        }
    }
}

@Composable
private fun FloatingBall(
    runtimeState: VoiceRuntimeState,
    serviceRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAbort: () -> Unit,
    onToggleWake: (Boolean) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current

    LaunchedEffect(menuOpen) {
        if (menuOpen) {
            kotlinx.coroutines.delay(5_000)
            menuOpen = false
        }
    }

    val actions = buildList {
        when {
            !serviceRunning -> add(Triple("🎤", "开始对话", onStart))
            runtimeState.deviceState == DeviceState.Listening ->
                add(Triple("⏹", "停止聆听", onStop))
            runtimeState.deviceState == DeviceState.Speaking ->
                add(Triple("✋", "打断回复", onAbort))
            else -> add(Triple("🎤", "开始对话", onStart))
        }
        if (serviceRunning &&
            runtimeState.status == ConnectionStatus.Connected &&
            runtimeState.deviceState == DeviceState.Idle
        ) {
            add(Triple("👂", "唤醒词", { onToggleWake(true) }))
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val ballSize = 48.dp
        val maxX = with(density) { (maxWidth - ballSize).toPx() }
        val maxY = with(density) { (maxHeight - ballSize - 12.dp).toPx() }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
                .padding(end = 12.dp, bottom = 16.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, amount ->
                        change.consume()
                        dragOffset = Offset(
                            (dragOffset.x + amount.x).coerceIn(-maxX, 0f),
                            (dragOffset.y + amount.y).coerceIn(-maxY, 0f)
                        )
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { menuOpen = !menuOpen }
                }
        ) {
            if (menuOpen) {
                actions.forEach { (icon, label, action) ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(18.dp)
                            )
                            .clickable {
                                menuOpen = false
                                action()
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(icon)
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .size(ballSize)
                    .clip(CircleShape)
                    .background(
                        when (runtimeState.deviceState) {
                            DeviceState.Listening -> MaterialTheme.colorScheme.tertiary
                            DeviceState.Speaking -> MaterialTheme.colorScheme.secondary
                            DeviceState.Connecting -> MaterialTheme.colorScheme.primary
                            DeviceState.Idle -> MaterialTheme.colorScheme.primary
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (runtimeState.deviceState) {
                        DeviceState.Listening -> "🎙"
                        DeviceState.Speaking -> "🔊"
                        DeviceState.Connecting -> "💬"
                        DeviceState.Idle -> "💬"
                    },
                    fontSize = 20.sp
                )
            }
        }
    }
}

@Composable
private fun AssistantLivePanel(
    runtimeState: VoiceRuntimeState,
    onAbort: () -> Unit,
    onStopListening: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
           .padding(bottom = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            Text(
                text = runtimeState.currentText.ifBlank {
                    if (runtimeState.deviceState == DeviceState.Listening) "正在聆听..." else "正在回复..."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            emotionEmoji(runtimeState.emotion)?.let { emoji ->
                Text(
                    text = emoji,
                    fontSize = 18.sp
                )
            }
            AudioWaveform(
                inputLevel = runtimeState.inputLevel,
                outputLevel = runtimeState.outputLevel,
                active = runtimeState.deviceState == DeviceState.Listening ||
                    runtimeState.deviceState == DeviceState.Speaking
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = if (runtimeState.deviceState == DeviceState.Listening) "停止" else "打断",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable {
                    if (runtimeState.deviceState == DeviceState.Listening) {
                        onStopListening()
                    } else {
                        onAbort()
                    }
                }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun QuickToolsPanel(
    onScreenLook: () -> Unit,
    onScreenRead: () -> Unit,
    onFrontLook: () -> Unit,
    onObjectLook: () -> Unit,
    onCamera: () -> Unit,
    onMusic: () -> Unit,
    onRecentMusic: () -> Unit,
    onWeather: () -> Unit,
    onAction: () -> Unit
) {
    val tools = listOf(
        Triple("看看屏幕", "识别当前屏幕") { onScreenLook() },
        Triple("读屏幕文字", "朗读屏幕内容") { onScreenRead() },
        Triple("看看面前", "调用前置相机") { onFrontLook() },
        Triple("识别物体", "拍照后识别") { onObjectLook() },
        Triple("打开相机", "启动系统相机") { onCamera() },
        Triple("播放音乐", "播放默认音乐") { onMusic() },
        Triple("最近播放", "重播歌曲") { onRecentMusic() },
        Triple("查天气", "查询今日天气") { onWeather() }
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tools.chunked(2).forEach { rowTools ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowTools.forEach { (title, subtitle, action) ->
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable {
                                action()
                                onAction()
                            }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = when (title) {
                                "看看屏幕" -> Icons.Filled.Visibility
                                "读屏幕文字" -> Icons.Filled.Description
                                "看看面前", "识别物体" -> Icons.Filled.PhotoCamera
                                "打开相机" -> Icons.Filled.CameraAlt
                                "播放音乐" -> Icons.Filled.MusicNote
                                else -> Icons.Filled.WbSunny
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (rowTools.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HomeTopBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "小智",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

@Composable
private fun StatusChip(
    label: String,
    color: Color,
    emphasized: Boolean = false
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (emphasized) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun QuickPromptGrid(
    prompts: List<Pair<String, String>>,
    onPromptClick: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        prompts.chunked(2).forEach { rowPrompts ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowPrompts.forEach { (label, prompt) ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(20.dp)
                            )
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onPromptClick(prompt) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
                if (rowPrompts.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PrimaryVoiceButton(
    settings: SettingsState,
    runtimeState: VoiceRuntimeState,
    serviceRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAbort: () -> Unit
) {
    val label: String
    val containerColor: Color
    val contentColor: Color
    var outlineColor: Color? = null
    val onClick: () -> Unit

    when {
        runtimeState.deviceState == DeviceState.Speaking -> {
            label = "正在回复 (点击打断)"
            containerColor = MaterialTheme.colorScheme.secondary
            contentColor = MaterialTheme.colorScheme.onSecondary
            onClick = onAbort
        }
        runtimeState.deviceState == DeviceState.Listening -> {
            label = "正在聆听..."
            containerColor = MaterialTheme.colorScheme.tertiary
            contentColor = MaterialTheme.colorScheme.onTertiary
            onClick = onStop
        }
        runtimeState.status == ConnectionStatus.ActivationRequired -> {
            label = "等待设备激活"
            containerColor = MaterialTheme.colorScheme.surfaceVariant
            contentColor = MaterialTheme.colorScheme.onSurface
            outlineColor = MaterialTheme.colorScheme.outline
            onClick = {}
        }
        !serviceRunning -> {
            label = "开始对话"
            containerColor = MaterialTheme.colorScheme.primary
            contentColor = MaterialTheme.colorScheme.onPrimary
            onClick = onStart
        }
        settings.wakeWordEnabled -> {
            label = "唤醒词待命"
            containerColor = MaterialTheme.colorScheme.primary
            contentColor = MaterialTheme.colorScheme.onPrimary
            onClick = onStart
        }
        else -> {
            label = "开始对话"
            containerColor = MaterialTheme.colorScheme.primary
            contentColor = MaterialTheme.colorScheme.onPrimary
            onClick = onStart
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(containerColor)
            .then(
                if (outlineColor == null) {
                    Modifier
                } else {
                    Modifier.border(1.dp, outlineColor, RoundedCornerShape(24.dp))
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor
        )
    }
}

@Composable
private fun RoundIconButton(
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    emphasized: Boolean = false
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(
                if (emphasized) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (emphasized) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        ) {
            icon()
        }
    }
}

@Composable
private fun PendingImagePreview(
    image: Uri,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ContentUriImage(
            uri = image,
            contentDescription = "待发送图片",
            modifier = Modifier.size(54.dp)
        )
        Text(
            text = "已选择图片，可补充要求后发送",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        RoundIconButton(
            icon = {
                Icon(Icons.Filled.Close, contentDescription = "移除图片")
            },
            onClick = onRemove
        )
    }
}

@Composable
private fun ContentUriImage(
    uri: Uri,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var bitmap by remember(uri) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }

    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            var sampleSize = 1
            while (
                bounds.outWidth / (sampleSize * 2) >= 512 &&
                bounds.outHeight / (sampleSize * 2) >= 512
            ) {
                sampleSize *= 2
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(
                    input,
                    null,
                    BitmapFactory.Options().apply { inSampleSize = sampleSize }
                )
            }
        }
    }

    val loadedBitmap = bitmap
    if (loadedBitmap != null) {
        Image(
            bitmap = loadedBitmap.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Image,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ChatBubble(
    message: ChatMessage,
    onCopy: () -> Unit,
    onResend: () -> Unit,
    onImageClick: (String) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (message.fromUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 310.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (message.fromUser) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
                .border(
                    width = if (message.fromUser) 0.dp else 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(12.dp)
                )
                .combinedClickable(
                    onClick = {},
                    onLongClick = { menuOpen = true }
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false }
            ) {
                DropdownMenuItem(
                    text = { Text("复制内容") },
                    onClick = {
                        menuOpen = false
                        onCopy()
                    }
                )
                if (message.fromUser) {
                    DropdownMenuItem(
                        text = { Text("重新发送") },
                        onClick = {
                            menuOpen = false
                            onResend()
                        }
                    )
                }
            }
            val imagePath = message.thumbnailPath ?: message.imagePath
            if (imagePath != null) {
                LocalFileImage(
                    path = imagePath,
                    contentDescription = "查看图片",
                    modifier = Modifier
                        .width(180.dp)
                        .height(120.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onImageClick(message.imagePath ?: imagePath) },
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (message.fromUser) MaterialTheme.colorScheme.onPrimary else {
                    MaterialTheme.colorScheme.onBackground
                }
            )
            Text(
                text = formatTime(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = if (message.fromUser) {
                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.72f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ImageViewerDialog(
    path: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            LocalFileImage(
                path = path,
                contentDescription = "图片预览",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
private fun LocalFileImage(
    path: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    var bitmap by remember(path) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }

    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sampleSize = 1
            while (
                bounds.outWidth / (sampleSize * 2) >= 720 &&
                bounds.outHeight / (sampleSize * 2) >= 720
            ) {
                sampleSize *= 2
            }
            BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply { inSampleSize = sampleSize }
            )
        }
    }

    val loadedBitmap = bitmap
    if (loadedBitmap != null) {
        Image(
            bitmap = loadedBitmap.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun ChatSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface
        ),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        decorationBox = { innerTextField ->
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                innerTextField()
            }
        }
    )
}

@Composable
private fun DateSeparator(timestamp: Long) {
    Text(
        text = SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(timestamp)),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        textAlign = TextAlign.Center
    )
}

@Composable
private fun AudioWaveform(
    inputLevel: Float,
    outputLevel: Float,
    active: Boolean
) {
    val transition = rememberInfiniteTransition(label = "wave")
    val waveColor = MaterialTheme.colorScheme.primary
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "phase"
    )

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(horizontal = 16.dp)
    ) {
        val level = (maxOf(inputLevel, outputLevel) * 2.4f).coerceIn(0.15f, 1f)
        val barCount = 20
        val gap = 3.dp.toPx()
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        for (index in 0 until barCount) {
            val center = size.height / 2f
            val wave = 0.35f + 0.65f * kotlin.math.sin(phase + index * 0.5f)
            val barHeight = if (active) {
                8.dp.toPx() + (28.dp.toPx() - 8.dp.toPx()) * level * wave
            } else {
                8.dp.toPx()
            }.coerceAtMost(size.height)
            drawRoundRect(
                color = waveColor.copy(alpha = if (active) 1f else 0.4f),
                topLeft = Offset(index * (barWidth + gap), center - barHeight / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
            )
        }
    }
}

@Composable
private fun RecentMusicScreen(
    records: List<RecentMusicRecord>,
    playbackState: MusicRuntimeState,
    operationMessage: String?,
    onBack: () -> Unit,
    onReplay: (RecentMusicRecord) -> Unit,
    onTogglePlayback: () -> Unit,
    onClear: () -> Unit,
    onClearMessage: () -> Unit
) {
    var clearDialogOpen by remember { mutableStateOf(false) }

    LaunchedEffect(operationMessage) {
        if (operationMessage != null) {
            delay(1800)
            onClearMessage()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundIconButton(
                    icon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") },
                    onClick = onBack
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "最近播放",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                if (records.isNotEmpty()) {
                    TextButton(onClick = { clearDialogOpen = true }) {
                        Text("清空")
                    }
                }
            }

            if (records.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(38.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "还没有播放记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        records,
                        key = { it.title }
                    ) { record ->
                        RecentMusicRow(
                            record = record,
                            playing = playbackState.hasTrack && playbackState.title == record.title,
                            paused = playbackState.paused,
                            onReplay = { onReplay(record) },
                            onTogglePlayback = onTogglePlayback
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = operationMessage != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = operationMessage.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            )
        }
    }

    if (clearDialogOpen) {
        AlertDialog(
            onDismissRequest = { clearDialogOpen = false },
            title = { Text("清空最近播放") },
            text = { Text("将删除全部 ${records.size} 条播放记录，此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearDialogOpen = false
                        onClear()
                    }
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { clearDialogOpen = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun RecentMusicRow(
    record: RecentMusicRecord,
    playing: Boolean,
    paused: Boolean,
    onReplay: () -> Unit,
    onTogglePlayback: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = if (playing) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                },
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onReplay)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (playing) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = if (playing) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = listOf(
                    record.sourceName,
                    "播放 ${record.playCount} 次",
                    formatRecentMusicTime(record.playedAt)
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }

        RoundIconButton(
            icon = {
                Icon(
                    imageVector = if (playing && !paused) {
                        Icons.Filled.Pause
                    } else {
                        Icons.Filled.PlayArrow
                    },
                    contentDescription = if (playing && !paused) "暂停" else "播放"
                )
            },
            onClick = if (playing) onTogglePlayback else onReplay,
            emphasized = playing
        )
    }
}

private fun formatRecentMusicTime(timestamp: Long): String {
    if (timestamp <= 0) return ""
    return SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(timestamp))
}

@Composable
private fun SettingsScreen(
    settings: SettingsState,
    onUpdateSettings: (SettingsState) -> Unit,
    onClearChat: () -> Unit,
    onResetSettings: () -> Unit,
    onExportChat: (android.net.Uri) -> Unit,
    onImportChat: (android.net.Uri) -> Unit,
    onExportCredential: (Uri, String) -> Unit,
    onImportCredential: (Uri, String) -> Unit,
    wakeWordTest: WakeWordTestState,
    onStartWakeWordTest: (SettingsState) -> Unit,
    operationMessage: String?,
    onClearOperationMessage: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenDeveloper: () -> Unit,
) {
    var draft by remember(settings) { mutableStateOf(settings) }
    val context = LocalContext.current
    var saveToast by remember { mutableStateOf<String?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var settingsQuery by remember { mutableStateOf("") }
    var developerUnlocked by remember { mutableStateOf(false) }
    var versionTaps by remember { mutableStateOf(0) }
    var pendingWakeTest by remember { mutableStateOf<SettingsState?>(null) }
    var pendingExportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    val validation = remember(draft) { SettingsValidator.validate(draft) }

    val exportChatLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(onExportChat) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onImportChat) }
    val exportCredentialLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> pendingExportUri = uri }
    val importCredentialLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> pendingImportUri = uri }
    val wakeTestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val draftToTest = pendingWakeTest
        pendingWakeTest = null
        if (granted) {
            onStartWakeWordTest(draftToTest ?: return@rememberLauncherForActivityResult)
        } else {
            saveToast = "未授权麦克风，无法测试唤醒词"
        }
    }

    fun launchWakeWordTest() {
        onUpdateSettings(draft)
        saveToast = "设置已保存"
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            onStartWakeWordTest(draft)
        } else {
            pendingWakeTest = draft
            wakeTestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(saveToast) {
        if (saveToast != null) {
            delay(2_000)
            saveToast = null
        }
    }
    LaunchedEffect(operationMessage) {
        if (operationMessage != null) {
            delay(2_000)
            onClearOperationMessage()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "设置",
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleLarge
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ChatSearchField(
                value = settingsQuery,
                onValueChange = { settingsQuery = it },
                placeholder = "搜索设置"
            )

            if (!validation.valid || validation.warnings.isNotEmpty()) {
                SettingsSection("配置校验") {
                    validation.errors.forEach { SettingsHint(it) }
                    validation.warnings.forEach { SettingsHint(it) }
                }
            }

            if (sectionVisible(settingsQuery, "唤醒词", "唤醒词 灵敏度")) {
                SettingsSection("唤醒词") {
                SwitchSettingRow("启用唤醒词", checked = draft.wakeWordEnabled) {
                    draft = draft.copy(wakeWordEnabled = it)
                }
                if (draft.wakeWordEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        WakeWordField(
                            value = draft.wakeWordText,
                            onChange = { draft = draft.copy(wakeWordText = it) }
                        )
                        if (!hasChinese(draft.wakeWordText)) {
                            SettingsHint("当前内置唤醒模型支持中文唤醒词，英文文本可能无法唤醒")
                        }
                        SettingsSlider(
                            label = "灵敏度",
                            value = draft.wakeWordSensitivity,
                            range = 0.05f..0.50f,
                            steps = 8,
                            valueLabel = String.format("%.2f", draft.wakeWordSensitivity)
                        ) {
                            draft = draft.copy(wakeWordSensitivity = it)
                        }
                        SettingsHint("数值越高越容易唤醒，也越可能误唤醒；改完可以立即测试")
                        OutlinedButton(
                            onClick = ::launchWakeWordTest,
                            enabled = validation.valid && !wakeWordTest.running,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (wakeWordTest.running) {
                                    "测试中 · 剩余 ${wakeWordTest.remainingSeconds} 秒"
                                } else {
                                    "测试唤醒词"
                                }
                            )
                        }
                        if (wakeWordTest.message.isNotBlank()) {
                            SettingsHint(
                                "命中 ${wakeWordTest.hits} 次 · ${wakeWordTest.message}"
                            )
                        }
                    }
                }
            }
            }

            if (sectionVisible(settingsQuery, "外观", "外观 主题 深色 浅色")) {
                SettingsSection("外观") {
                SettingsLabel("主题模式")
                SettingsRadioGroup(
                    options = listOf(
                        SettingsOption("跟随系统", "system"),
                        SettingsOption("深色", "dark"),
                        SettingsOption("浅色", "light")
                    ),
                    selected = draft.themeMode.name.lowercase()
                ) { value ->
                    val newValue = ThemeMode.values().firstOrNull { it.name.lowercase() == value }
                        ?: ThemeMode.System
                    draft = draft.copy(themeMode = newValue)
                    onUpdateSettings(draft)
                }
            }
            }

            if (sectionVisible(settingsQuery, "陪学", "陪学 视频 预览 全屏 直播")) {
                SettingsSection("陪学") {
                SwitchSettingRow(
                    label = "开启陪学模式",
                    hint = "首页显示视频预览，可进入全屏直播式对话",
                    checked = draft.studyCompanionEnabled
                ) {
                    draft = draft.copy(studyCompanionEnabled = it)
                }
            }
            }

            if (sectionVisible(
                    settingsQuery,
                    "激活凭证",
                    "激活 凭证 备份 恢复 重装 设备"
                )
            ) {
                SettingsSection("激活凭证") {
                SettingsActionRow(
                    title = "备份激活凭证",
                    hint = "导出加密文件；卸载重装后可恢复当前激活身份"
                ) {
                    exportCredentialLauncher.launch("xiaozhi-credential-${System.currentTimeMillis()}.bin")
                }
                SettingsActionRow(
                    title = "恢复激活凭证",
                    hint = "选择备份文件并输入恢复口令，恢复后重启语音服务"
                ) {
                    importCredentialLauncher.launch(arrayOf("*/*"))
                }
            }
            }

            if (sectionVisible(
                    settingsQuery,
                    "应用行为",
                    "应用 聊天 历史 清除 重试 连接 导出 导入"
                )
            ) {
                SettingsSection("应用行为") {
                SettingsSlider(
                    label = "聊天历史保留",
                    value = draft.chatHistoryLimit.toFloat(),
                    range = 50f..500f,
                    steps = 8,
                    valueLabel = "${draft.chatHistoryLimit} 条",
                    hint = "超出后自动删除最早的记录"
                ) {
                    draft = draft.copy(chatHistoryLimit = it.roundToInt())
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.error,
                            RoundedCornerShape(22.dp)
                        )
                        .clickable { showClearDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "清除聊天记录",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                SettingsActionRow(
                    title = "导出聊天记录",
                    hint = "保存为 JSON 文件，用于备份或迁移"
                ) {
                    exportChatLauncher.launch("xiaozhi-chat-${System.currentTimeMillis()}.json")
                }
                SettingsActionRow(
                    title = "导入聊天记录",
                    hint = "支持本 App 导出的 JSON，最多导入 2MB"
                ) {
                    importLauncher.launch(arrayOf("application/json"))
                }
                SwitchSettingRow(
                    label = "启动时自动重试连接",
                    hint = "连接未就绪时指数退避重试",
                    checked = draft.connectRetryEnabled
                ) {
                    draft = draft.copy(connectRetryEnabled = it)
                }
                SwitchSettingRow(
                    label = "记住歌曲版本选择",
                    hint = "同一首歌下次搜索时优先使用你上次选择的版本",
                    checked = draft.musicRememberSelection
                ) {
                    draft = draft.copy(musicRememberSelection = it)
                }
            }
            }

            if (sectionVisible(settingsQuery, "悬浮窗", "悬浮窗 快捷球 overlay 后台")) {
                SettingsSection("悬浮窗") {
                SwitchSettingRow(
                    label = "音乐灵动岛",
                    hint = "播放或搜索歌曲时，在顶部显示播放状态",
                    checked = draft.musicIslandEnabled
                ) {
                    draft = draft.copy(musicIslandEnabled = it)
                }
                SwitchSettingRow(
                    label = "语音快捷球",
                    hint = "最小化后可在其他应用中聆听、打断或停止服务",
                    checked = draft.overlayEnabled
                ) {
                    draft = draft.copy(overlayEnabled = it)
                }
            }
            }

            if (sectionVisible(
                    settingsQuery,
                    "诊断与隐私",
                    "帮助 反馈 诊断 检测 权限 网络 日志 隐私 数据"
                )
            ) {
                SettingsSection("诊断与隐私") {
                    SettingsActionRow(
                        title = "帮助与反馈",
                        hint = "常见问题、反馈入口和诊断报告说明"
                    ) { showHelpDialog = true }
                    SettingsActionRow(
                        title = "运行诊断",
                        hint = "检查权限、网络、配置、链路和后台运行"
                    ) { onOpenDiagnostics() }
                    SettingsActionRow(
                        title = "隐私说明",
                        hint = "查看语音、文字、相机和本地数据说明"
                    ) { onOpenPrivacy() }
                }
            }

            if (sectionVisible(settingsQuery, "关于", "关于 版本 默认 恢复")) {
                SettingsSection("关于") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                versionTaps += 1
                                if (versionTaps >= DEVELOPER_UNLOCK_TAPS) {
                                    developerUnlocked = true
                                    saveToast = "开发者配置已解锁"
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SettingsLabel("版本")
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = BuildConfig.VERSION_NAME,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    SettingsActionRow(
                        title = "恢复默认设置",
                        hint = "重置唤醒词、音频、音乐和外观配置"
                    ) {
                        showResetDialog = true
                    }
                }
            }

            if (developerUnlocked) {
                SettingsSection("开发者配置") {
                    SettingsActionRow(
                        title = "打开开发者配置",
                        hint = "网络、音频和音乐源；修改后请确认能恢复"
                    ) { onOpenDeveloper() }
                }
            } else if (sectionVisible(settingsQuery, "关于", "关于 版本")) {
                SettingsHint("连续点版本号 $DEVELOPER_UNLOCK_TAPS 次可解锁开发者配置")
            }

            Button(
                onClick = {
                    onUpdateSettings(draft)
                    saveToast = "设置已保存"
                    if (draft.overlayEnabled && !Settings.canDrawOverlays(context)) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                enabled = validation.valid
            ) { Text("保存设置") }
        }
        }

        val notice = saveToast ?: operationMessage
        notice?.let { message ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.92f))
                    .padding(horizontal = 32.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    message,
                    color = MaterialTheme.colorScheme.onTertiary,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = { showResetDialog = false },
                title = { Text("恢复默认设置") },
                text = { Text("将重置全部可配置项，聊天记录不会被删除。确定继续吗？") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showResetDialog = false
                            onResetSettings()
                        }
                    ) { Text("确定", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { showResetDialog = false }) { Text("取消") }
                }
            )
        }

        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text("确认清除") },
                text = { Text("清除后聊天记录将无法恢复，确定要清除吗？") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearDialog = false
                            onClearChat()
                            saveToast = "聊天记录已清除"
                        }
                    ) { Text("确定", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) { Text("取消") }
                }
            )
        }

        if (showHelpDialog) {
            AlertDialog(
                onDismissRequest = { showHelpDialog = false },
                title = { Text("帮助与反馈") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("叫不醒：先在设置的唤醒词里点“测试唤醒词”，再按提示调高灵敏度。")
                        Text("没声音或连接失败：运行诊断，并把诊断报告分享给反馈渠道。")
                        Text("相机、屏幕识别、悬浮窗：首次使用对应功能时会再次申请，不需要重新安装。")
                        Text("换机或重装：可先在设置里加密备份激活凭证和聊天记录。")
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showHelpDialog = false
                            val feedback = buildString {
                                appendLine("小智 Android 反馈")
                                appendLine("版本：${BuildConfig.VERSION_NAME}")
                                appendLine("问题描述：")
                            }
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND)
                                        .setType("text/plain")
                                        .putExtra(Intent.EXTRA_TEXT, feedback),
                                    "分享反馈"
                                )
                            )
                        }
                    ) { Text("去反馈") }
                },
                dismissButton = {
                    TextButton(onClick = { showHelpDialog = false }) { Text("知道了") }
                }
            )
        }

        pendingExportUri?.let { uri ->
            CredentialPasswordDialog(
                title = "备份激活凭证",
                description = "凭证包含设备身份和密钥，请使用至少 8 位口令加密，并把文件保存在可靠位置。",
                confirmText = "备份",
                requirePasswordConfirm = true,
                onDismiss = { pendingExportUri = null },
                onConfirm = { password ->
                    onExportCredential(uri, password)
                    pendingExportUri = null
                }
            )
        }

        pendingImportUri?.let { uri ->
            CredentialPasswordDialog(
                title = "恢复激活凭证",
                description = "输入当时设置的恢复口令。口令不正确时不会修改当前设备身份。",
                confirmText = "恢复",
                requirePasswordConfirm = false,
                onDismiss = { pendingImportUri = null },
                onConfirm = { password ->
                    onImportCredential(uri, password)
                    pendingImportUri = null
                }
            )
        }
    }
}

@Composable
private fun CredentialPasswordDialog(
    title: String,
    description: String,
    confirmText: String,
    requirePasswordConfirm: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(description, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMessage = null
                    },
                    label = { Text("恢复口令") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                if (requirePasswordConfirm) {
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = {
                            confirmPassword = it
                            errorMessage = null
                        },
                        label = { Text("确认口令") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
                errorMessage?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when {
                        password.length !in 8..64 ->
                            errorMessage = "口令需要 8-64 个字符"
                        requirePasswordConfirm && password != confirmPassword ->
                            errorMessage = "两次输入的口令不一致"
                        else -> onConfirm(password)
                    }
                }
            ) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun DeveloperSettingsScreen(
    settings: SettingsState,
    onUpdateSettings: (SettingsState) -> Unit,
    operationMessage: String?,
    onClearOperationMessage: () -> Unit,
    onBack: () -> Unit
) {
    var draft by remember(settings) { mutableStateOf(settings) }
    var saveToast by remember { mutableStateOf<String?>(null) }
    val validation = remember(draft) { SettingsValidator.validate(draft) }

    LaunchedEffect(saveToast) {
        if (saveToast != null) {
            delay(2_000)
            saveToast = null
        }
    }
    LaunchedEffect(operationMessage) {
        if (operationMessage != null) {
            delay(2_000)
            onClearOperationMessage()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundIconButton(
                    icon = {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回设置"
                        )
                    },
                    onClick = onBack
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    "开发者配置",
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.titleLarge
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (!validation.valid || validation.warnings.isNotEmpty()) {
                    SettingsSection("配置校验") {
                        validation.errors.forEach { SettingsHint(it) }
                        validation.warnings.forEach { SettingsHint(it) }
                    }
                }

                SettingsSection("网络配置") {
                    LabeledField("OTA 地址", draft.otaUrl, placeholder = "https://...") {
                        draft = draft.copy(otaUrl = it)
                    }
                    LabeledField(
                        "WebSocket 地址",
                        draft.websocketUrl,
                        placeholder = "留空后由 OTA 下发"
                    ) {
                        draft = draft.copy(websocketUrl = it)
                    }
                    LabeledField(
                        "访问令牌",
                        draft.websocketToken,
                        placeholder = "留空后由 OTA 下发",
                        secret = true
                    ) {
                        draft = draft.copy(websocketToken = it)
                    }
                    LabeledField(
                        "MCP 接入点",
                        draft.mcpEndpointUrl,
                        placeholder = "wss://...（智能体专属）",
                        secret = true
                    ) {
                        draft = draft.copy(mcpEndpointUrl = it)
                    }
                    SettingsHint("语音与 MCP 分开连接；默认接入点已内置，可本机覆盖")
                }

                SettingsSection("音频设置") {
                    SwitchSettingRow(
                        label = "回声消除 (AEC)",
                        hint = "建议在扬声器外放时开启",
                        checked = draft.aecEnabled
                    ) {
                        draft = draft.copy(aecEnabled = it)
                    }
                    SettingsLabel("Opus 输出采样率")
                    SettingsRadioGroup(
                        options = listOf(
                            SettingsOption("24000 Hz", "24000"),
                            SettingsOption("16000 Hz", "16000")
                        ),
                        selected = draft.outputSampleRate.toString()
                    ) {
                        draft = draft.copy(outputSampleRate = it.toInt())
                    }
                    SettingsHint("官方服务通常使用 24kHz，设备不支持时自动适配")
                }

                SettingsSection("音乐设置") {
                    SwitchSettingRow("启用音乐工具", checked = draft.musicEnabled) {
                        draft = draft.copy(musicEnabled = it)
                    }
                    SettingsLabel("音乐源")
                    SettingsRadioGroup(
                        options = listOf(
                            SettingsOption("自动多源", "auto"),
                            SettingsOption("酷我官方", "kuwo"),
                            SettingsOption("网易云无损", "netease_lossless"),
                            SettingsOption("网易云 API", "netease")
                        ),
                        selected = draft.musicSourceMode
                    ) {
                        draft = draft.copy(musicSourceMode = it)
                    }
                    if (draft.musicSourceMode != "kuwo") {
                        LabeledField(
                            "无损网关地址",
                            draft.musicNeteaseLosslessApiUrl,
                            placeholder = "https://.../api/gateway.php"
                        ) {
                            draft = draft.copy(musicNeteaseLosslessApiUrl = it)
                        }
                        LabeledField(
                            "无损网关 AppKey",
                            draft.musicNeteaseLosslessAppKey,
                            placeholder = "ak_...",
                            secret = true
                        ) {
                            draft = draft.copy(musicNeteaseLosslessAppKey = it)
                        }
                        LabeledField(
                            "网易云 API 地址",
                            draft.musicNeteaseApiUrl,
                            placeholder = "可选，HTTPS API"
                        ) {
                            draft = draft.copy(musicNeteaseApiUrl = it)
                        }
                        SettingsHint("自动顺序：网易云无损 → 酷我 → 网易云 → Audius → iTunes 试听")
                    }
                    SettingsLabel("默认音质")
                    SettingsRadioGroup(
                        options = listOf(
                            SettingsOption("128k", "128k"),
                            SettingsOption("320k", "320k"),
                            SettingsOption("无损", "lossless"),
                            SettingsOption("Hi-Res", "hires")
                        ),
                        selected = draft.musicDefaultQuality
                    ) {
                        draft = draft.copy(musicDefaultQuality = it)
                    }
                }

                Button(
                    onClick = {
                        onUpdateSettings(draft)
                        saveToast = "开发者配置已保存"
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    enabled = validation.valid
                ) { Text("保存开发者配置") }
            }
        }

        val notice = saveToast ?: operationMessage
        notice?.let { message ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.92f))
                    .padding(horizontal = 32.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    message,
                    color = MaterialTheme.colorScheme.onTertiary,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            title,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleSmall
        )
        content()
    }
}

@Composable
private fun SettingsLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SettingsHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SettingsValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    hint: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            SettingsHint(hint)
        }
        Text(
            text = ">",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    secret: Boolean = false,
    placeholder: String = "",
    onChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsLabel(label)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.outline)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            visualTransformation = if (secret) {
                PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
            decorationBox = { innerTextField ->
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    innerTextField()
                }
            }
        )
    }
}

@Composable
private fun WakeWordField(
    value: String,
    onChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingsLabel("唤醒词文本")
            if (value.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        if (hasChinese(value)) "中文" else "English",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.outline)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            decorationBox = { innerTextField ->
                if (value.isEmpty()) {
                    Text(
                        "输入唤醒词，如 你好小智",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    innerTextField()
                }
            }
        )
    }
}

@Composable
private fun SwitchSettingRow(
    label: String,
    checked: Boolean,
    hint: String? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (hint != null) SettingsHint(hint)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled
        )
    }
}

@Composable
private fun SettingsRadioGroup(
    options: List<SettingsOption>,
    selected: String,
    onSelect: (String) -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            val isSelected = option.value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                        } else {
                            MaterialTheme.colorScheme.outline
                        }
                    )
                    .then(
                        if (isSelected) {
                            Modifier.border(
                                1.dp,
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(8.dp)
                            )
                        } else {
                            Modifier
                        }
                    )
                    .clickable(enabled = option.enabled) { onSelect(option.value) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    option.label,
                    color = if (!option.enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    } else if (isSelected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun SettingsSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: String,
    hint: String? = null,
    onChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SettingsLabel("$label: $valueLabel")
        Slider(
            value = value,
            onValueChange = { rawValue ->
                onChange(snapToSteps(rawValue, range, steps))
            },
            valueRange = range,
            steps = steps
        )
        if (hint != null) SettingsHint(hint)
    }
}

private data class SettingsOption(
    val label: String,
    val value: String,
    val enabled: Boolean = true
)

private fun snapToSteps(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int
): Float {
    if (steps <= 0) return value
    val intervalCount = steps + 1
    val stepSize = (range.endInclusive - range.start) / intervalCount
    val stepped = ((value - range.start) / stepSize).roundToInt() * stepSize + range.start
    return stepped.coerceIn(range.start, range.endInclusive)
}

private fun stateLabel(state: DeviceState): String {
    return when (state) {
        DeviceState.Idle -> "待机"
        DeviceState.Connecting -> "连接中"
        DeviceState.Listening -> "聆听中"
        DeviceState.Speaking -> "回复中"
    }
}

private fun serverLabel(status: ConnectionStatus): String {
    return when (status) {
        ConnectionStatus.Connected -> "已连接"
        ConnectionStatus.Connecting -> "连接中"
        ConnectionStatus.ActivationRequired -> "待激活"
        ConnectionStatus.Disconnected -> "待连接"
        ConnectionStatus.Error -> "断开"
    }
}

@Composable
private fun serverColor(status: ConnectionStatus): Color {
    return when (status) {
        ConnectionStatus.Connected -> MaterialTheme.colorScheme.tertiary
        ConnectionStatus.Connecting,
        ConnectionStatus.ActivationRequired -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun emotionEmoji(emotion: String): String? {
    return when (emotion) {
        "happy" -> "😊"
        "sad" -> "😢"
        "thinking" -> "🤔"
        "surprised" -> "😮"
        "angry" -> "😠"
        "love" -> "❤️"
        "laugh" -> "😄"
        else -> null
    }
}

private fun formatTime(timestamp: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}

private fun formatActivationCode(code: String): String {
    return when (code.length) {
        8 -> code.chunked(4).joinToString("-")
        6 -> code.chunked(3).joinToString(" ")
        else -> code
    }
}

private fun formatSliderValue(value: Float): String {
    return if (value >= 1000f) "${value.toInt()} Hz" else String.format("%.2f", value)
}

private fun hasChinese(text: String): Boolean {
    return Regex("[\\u4e00-\\u9fff]").containsMatchIn(text)
}

private fun sectionVisible(query: String, title: String, keywords: String): Boolean {
    val keyword = query.trim()
    if (keyword.isBlank()) return true
    return title.contains(keyword, ignoreCase = true) ||
        keywords.contains(keyword, ignoreCase = true)
}

private fun isSameDay(left: Long, right: Long): Boolean {
    val leftCalendar = java.util.Calendar.getInstance()
    val rightCalendar = java.util.Calendar.getInstance()
    leftCalendar.timeInMillis = left
    rightCalendar.timeInMillis = right
    return leftCalendar.get(java.util.Calendar.YEAR) == rightCalendar.get(java.util.Calendar.YEAR) &&
        leftCalendar.get(java.util.Calendar.DAY_OF_YEAR) ==
        rightCalendar.get(java.util.Calendar.DAY_OF_YEAR)
}
