package com.xiaozhi.android.service

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.xiaozhi.android.XiaozhiApplication
import com.xiaozhi.android.MainActivity
import com.xiaozhi.android.core.ConnectionStatus
import com.xiaozhi.android.core.DeviceState
import com.xiaozhi.android.core.VoiceRuntimeState
import com.xiaozhi.android.core.VoiceSessionState
import com.xiaozhi.android.media.MusicPlaybackState
import com.xiaozhi.android.media.MusicRuntimeState
import com.xiaozhi.android.media.NativeMusicController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class SystemOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val autoCollapse = Runnable { setPanelExpanded(false) }

    private lateinit var windowManager: WindowManager
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var musicLayoutParams: WindowManager.LayoutParams
    private lateinit var contentView: LinearLayout
    private lateinit var musicIslandView: LinearLayout
    private lateinit var ballView: TextView
    private lateinit var panelView: LinearLayout
    private lateinit var statusDot: View
    private lateinit var statusView: TextView
    private lateinit var primaryAction: TextView
    private lateinit var abortAction: TextView
    private lateinit var wakeAction: TextView
    private lateinit var stopAction: TextView
    private lateinit var openAction: TextView
    private lateinit var collapseAction: TextView
    private lateinit var musicPulseView: View
    private lateinit var musicTitleView: TextView
    private lateinit var musicSourceView: TextView
    private lateinit var musicPreviousAction: TextView
    private lateinit var musicPlayPauseAction: TextView
    private lateinit var musicNextAction: TextView
    private lateinit var musicStopAction: TextView

    private var panelExpanded = false
    private var contentHiddenByApp = false
    private var appInForeground = false
    private var controlOverlayEnabled = false
    private var latestState = VoiceSessionState.state.value
    private var latestMusicState = MusicPlaybackState.state.value
    private var musicIslandShown = false
    private var currentBallColor = COLOR_PRIMARY
    private var ballPulse: ValueAnimator? = null
    private var statusPulse: ObjectAnimator? = null
    private var snapAnimator: ValueAnimator? = null
    private var musicBreath: ValueAnimator? = null
    private var musicIslandSnapAnimator: ValueAnimator? = null
    private var musicIslandDragging = false
    private var musicIslandDownRawX = 0f
    private var musicIslandDownRawY = 0f
    private var musicIslandStartX = 0
    private var musicIslandStartY = 0
    private val restoreMusicIsland = Runnable { animateMusicIslandToCenter() }

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        contentView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        contentView.visibility = View.GONE
        musicIslandView = createMusicIsland().apply { visibility = View.GONE }
        ballView = createBall()
        panelView = createPanel()
        panelView.visibility = View.GONE
        contentView.addView(ballView)
        contentView.addView(panelView)

        val ballSize = dp(BALL_SIZE_DP)
        val margin = dp(16)
        layoutParams = WindowManager.LayoutParams(
            ballSize,
            ballSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - ballSize - margin
            y = (resources.displayMetrics.heightPixels * 0.42f).roundToInt()
        }
        layoutParams.width = 1
        layoutParams.height = 1
        musicLayoutParams = WindowManager.LayoutParams(
            dp(MUSIC_ISLAND_WIDTH_DP),
            dp(MUSIC_ISLAND_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val center = centeredMusicIslandPoint()
            x = center.first
            y = center.second
        }

        attachBallGestures()
        attachPanelActions()
        attachMusicIslandActions()

        runCatching {
            windowManager.addView(contentView, layoutParams)
        }.onFailure {
            stopSelf()
            return
        }
        runCatching {
            windowManager.addView(musicIslandView, musicLayoutParams)
        }

        val app = application as? XiaozhiApplication
        scope.launch {
            if (app == null) {
                stopSelf()
                return@launch
            }
            app.settingsRepository.settings.collect { settings ->
                if (!settings.overlayEnabled && !settings.musicIslandEnabled) {
                    stopSelf()
                    return@collect
                }
                controlOverlayEnabled = settings.overlayEnabled
                setControlContentHidden(appInForeground || !controlOverlayEnabled)
            }
        }

        scope.launch {
            VoiceSessionState.state.collect { state ->
                latestState = state
                renderState(state)
            }
        }
        scope.launch {
            MusicPlaybackState.state.collect { state ->
                latestMusicState = state
                renderMusicState(state)
            }
        }
        renderState(latestState)
        renderMusicState(latestMusicState)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_APP_FOREGROUND -> {
                appInForeground = true
                setControlContentHidden(true)
            }
            ACTION_APP_BACKGROUND -> {
                appInForeground = false
                setControlContentHidden(!controlOverlayEnabled)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoCollapse)
        ballPulse?.cancel()
        statusPulse?.cancel()
        snapAnimator?.cancel()
        musicBreath?.cancel()
        musicIslandSnapAnimator?.cancel()
        handler.removeCallbacks(restoreMusicIsland)
        scope.cancel()
        if (::contentView.isInitialized && contentView.isAttachedToWindow) {
            runCatching { windowManager.removeView(contentView) }
        }
        if (::musicIslandView.isInitialized && musicIslandView.isAttachedToWindow) {
            runCatching { windowManager.removeView(musicIslandView) }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createMusicIsland(): MusicIslandLayout {
        musicPulseView = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COLOR_PRIMARY)
            }
            layoutParams = LinearLayout.LayoutParams(dp(11), dp(11))
        }
        musicTitleView = TextView(this).apply {
            textSize = 13f
            setTextColor(COLOR_TEXT_PRIMARY)
            paint.isFakeBoldText = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        musicSourceView = TextView(this).apply {
            textSize = 10f
            setTextColor(COLOR_TEXT_SECONDARY)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        musicPreviousAction = musicActionButton("◀◀", "上一首")
        musicPlayPauseAction = musicActionButton("││", "暂停音乐")
        musicNextAction = musicActionButton("▶▶", "下一首")
        musicStopAction = musicActionButton("■", "停止音乐")

        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(musicTitleView)
            addView(musicSourceView)
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { marginStart = dp(9) }
        }

        return MusicIslandLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(COLOR_ISLAND)
                cornerRadius = dp(MUSIC_ISLAND_HEIGHT_DP / 2).toFloat()
                setStroke(dp(1), COLOR_ISLAND_STROKE)
            }
            elevation = dp(12).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                dp(MUSIC_ISLAND_WIDTH_DP),
                dp(MUSIC_ISLAND_HEIGHT_DP)
            )
            addView(musicPulseView)
            addView(textContainer)
            addView(musicPreviousAction)
            addView(musicPlayPauseAction)
            addView(musicNextAction)
            addView(musicStopAction)
        }
    }

    private inner class MusicIslandLayout(context: android.content.Context) :
        LinearLayout(context) {

        init {
            isClickable = true
        }

        override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
            if (event == null) return super.dispatchTouchEvent(null)

            val wasDragging = musicIslandDragging
            val handled = handleMusicIslandDrag(event)
            if (!handled) return super.dispatchTouchEvent(event)

            // A child button may already own this gesture. Cancel it when the
            // drag threshold is crossed so its pressed state resets cleanly.
            if (!wasDragging && event.actionMasked == MotionEvent.ACTION_MOVE) {
                MotionEvent.obtain(event).apply {
                    action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(this)
                    recycle()
                }
            }
            return true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun handleMusicIslandDrag(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                musicIslandSnapAnimator?.cancel()
                handler.removeCallbacks(restoreMusicIsland)
                musicIslandDragging = false
                musicIslandDownRawX = event.rawX
                musicIslandDownRawY = event.rawY
                musicIslandStartX = musicLayoutParams.x
                musicIslandStartY = musicLayoutParams.y
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - musicIslandDownRawX
                val dy = event.rawY - musicIslandDownRawY
                if (!musicIslandDragging &&
                    (
                        abs(dx) > ViewConfiguration.get(this).scaledTouchSlop ||
                            abs(dy) > ViewConfiguration.get(this).scaledTouchSlop
                        )
                ) {
                    musicIslandDragging = true
                }
                if (musicIslandDragging) {
                    musicLayoutParams.x = musicIslandStartX + dx.roundToInt()
                    musicLayoutParams.y = musicIslandStartY + dy.roundToInt()
                    clampMusicIslandPosition()
                    return true
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                val wasDragging = musicIslandDragging
                musicIslandDragging = false
                if (wasDragging) {
                    handler.postDelayed(restoreMusicIsland, MUSIC_ISLAND_RESTORE_DELAY_MS)
                    return true
                }
            }
        }
        return false
    }

    private fun musicActionButton(symbol: String, description: String): TextView {
        return TextView(this).apply {
            text = symbol
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_PRIMARY)
            contentDescription = description
            background = circleRipple()
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginStart = dp(2)
            }
            isClickable = true
        }
    }

    private fun createBall(): TextView {
        val size = dp(BALL_SIZE_DP)
        return TextView(this).apply {
            text = "小智"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            isAllCaps = false
            letterSpacing = 0f
            paint.isFakeBoldText = true
            contentDescription = "小智悬浮控制球"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COLOR_PRIMARY)
                setStroke(dp(2), COLOR_BALL_STROKE)
            }
            elevation = dp(9).toFloat()
            layoutParams = LinearLayout.LayoutParams(size, size)
            isClickable = true
        }
    }

    private fun createPanel(): LinearLayout {
        val titleView = TextView(this).apply {
            text = "小智控制台"
            textSize = 14f
            setTextColor(COLOR_TEXT_PRIMARY)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        statusDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COLOR_PRIMARY)
            }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
        }
        statusView = TextView(this).apply {
            text = "点击启动"
            textSize = 11f
            setTextColor(COLOR_TEXT_SECONDARY)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        collapseAction = TextView(this).apply {
            text = "×"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_SECONDARY)
            contentDescription = "收起悬浮控制台"
            background = circleRipple()
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
            isClickable = true
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(statusDot)
            addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(7)
            })
            addView(statusView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.35f))
            addView(collapseAction)
            (collapseAction.layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(3)
        }

        primaryAction = actionButton("启动小智", COLOR_PRIMARY)
        abortAction = actionButton("打断回复", COLOR_MUTED)
        wakeAction = actionButton("唤醒词 开", COLOR_SUCCESS)
        stopAction = actionButton("停止服务", COLOR_DANGER)
        openAction = actionButton("打开App", COLOR_MUTED)

        val firstRow = actionRow(wakeAction, abortAction)
        val secondRow = actionRow(stopAction, openAction)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(COLOR_PANEL)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), COLOR_PANEL_STROKE)
            }
            elevation = dp(11).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                dp(PANEL_WIDTH_DP),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            addView(header)
            addView(primaryAction, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(9) })
            addView(firstRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
            addView(secondRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
        }
    }

    private fun actionButton(text: String, accentColor: Int): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            maxLines = 1
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            paint.isFakeBoldText = true
            minHeight = dp(42)
            setPadding(dp(6), dp(10), dp(6), dp(10))
            background = buttonRipple(accentColor)
            isClickable = true
        }
    }

    private fun actionRow(first: TextView, second: TextView): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(first, rowParams())
            addView(second, rowParams())
        }
    }

    private fun rowParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {
            marginStart = dp(3)
            marginEnd = dp(3)
        }
    }

    private fun buttonRipple(color: Int): RippleDrawable {
        val content = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(8).toFloat()
        }
        return RippleDrawable(ColorStateList.valueOf(COLOR_RIPPLE), content, null)
    }

    private fun circleRipple(): RippleDrawable {
        val content = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
        }
        return RippleDrawable(ColorStateList.valueOf(COLOR_RIPPLE), content, null)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachBallGestures() {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startPositionX = 0
        var startPositionY = 0
        var moved = false

        ballView.setOnLongClickListener {
            openApp()
            true
        }
        ballView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startPositionX = layoutParams.x
                    startPositionY = layoutParams.y
                    moved = false
                    ballView.animate().scaleX(0.92f).scaleY(0.92f)
                        .setDuration(90).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        moved = true
                        if (panelExpanded) setPanelExpanded(false)
                    }
                    if (moved) {
                        layoutParams.x = startPositionX + dx.roundToInt()
                        layoutParams.y = startPositionY + dy.roundToInt()
                        clampPosition()
                    }
                    moved
                }
                MotionEvent.ACTION_UP -> {
                    ballView.animate().scaleX(1f).scaleY(1f)
                        .setDuration(140).setInterpolator(OvershootInterpolator(1.25f))
                        .start()
                    if (moved) {
                        snapToNearestEdge()
                    } else {
                        setPanelExpanded(!panelExpanded)
                        ballView.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    ballView.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (moved) snapToNearestEdge()
                    true
                }
                else -> false
            }
        }
    }

    private fun attachPanelActions() {
        bindAction(primaryAction) {
            when {
                !VoiceForegroundService.isRunning() -> openApp()
                latestState.status == ConnectionStatus.ActivationRequired -> openApp()
                latestState.status == ConnectionStatus.Error ->
                    VoiceForegroundService.reconnectNow(this)
                latestState.status == ConnectionStatus.Connecting -> openApp()
                latestState.deviceState == DeviceState.Listening ->
                    VoiceForegroundService.stopListening(this)
                else -> {
                    if (latestState.wakeWordEnabled) {
                        VoiceForegroundService.setWakeWordEnabled(this, false)
                    }
                    VoiceForegroundService.startListening(this)
                }
            }
            setPanelExpanded(false)
        }
        bindAction(wakeAction) {
            if (VoiceForegroundService.isRunning()) {
                VoiceForegroundService.setWakeWordEnabled(
                    this,
                    !latestState.wakeWordEnabled
                )
            }
        }
        bindAction(abortAction) {
            VoiceForegroundService.abortSpeaking()
        }
        bindAction(stopAction) {
            if (VoiceForegroundService.isRunning()) VoiceForegroundService.stop(this)
            setPanelExpanded(false)
        }
        bindAction(openAction) { openApp() }
        bindAction(collapseAction) { setPanelExpanded(false) }
    }

    private fun attachMusicIslandActions() {
        bindAction(musicPreviousAction) {
            runMusicAction { NativeMusicController.playAdjacent(-1) }
        }
        bindAction(musicPlayPauseAction) {
            runMusicAction {
                if (latestMusicState.paused) {
                    NativeMusicController.resume()
                } else {
                    NativeMusicController.pause()
                }
            }
        }
        bindAction(musicNextAction) { runMusicAction { NativeMusicController.playAdjacent(1) } }
        bindAction(musicStopAction) { runMusicAction { NativeMusicController.stop() } }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindAction(view: TextView, action: () -> Unit) {
        view.setOnClickListener {
            tapFeedback(view)
            action()
        }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    view.background?.setHotspot(event.x, event.y)
                    view.animate().scaleX(0.96f).scaleY(0.96f)
                        .setDuration(80).start()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    val inside = event.x >= 0 && event.y >= 0 &&
                        event.x <= view.width && event.y <= view.height
                    view.animate().scaleX(1f).scaleY(1f)
                        .setDuration(130)
                        .setInterpolator(OvershootInterpolator(1.2f))
                        .start()
                    if (inside) view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    view.animate().scaleX(1f).scaleY(1f)
                        .setDuration(100).start()
                    true
                }
                else -> false
            }
        }
    }

    private fun tapFeedback(view: View) {
        view.animate().scaleX(1.03f).scaleY(1.03f)
            .setDuration(80)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            .start()
    }

    private fun setPanelExpanded(expanded: Boolean) {
        if (panelExpanded == expanded) return
        panelExpanded = expanded
        handler.removeCallbacks(autoCollapse)
        panelView.animate().cancel()

        if (expanded) {
            panelView.visibility = View.VISIBLE
            applyWindowSize(expanded = true)
            panelView.post {
                panelView.pivotX = ballView.width / 2f
                panelView.pivotY = 0f
                panelView.alpha = 0f
                panelView.scaleX = 0.68f
                panelView.scaleY = 0.68f
                panelView.translationY = -8f
                panelView.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setDuration(260)
                    .setInterpolator(OvershootInterpolator(1.08f))
                    .start()
                clampPosition()
            }
            animatePanelChildren()
            handler.postDelayed(autoCollapse, PANEL_AUTO_COLLAPSE_MS)
        } else {
            panelView.pivotX = ballView.width / 2f
            panelView.pivotY = 0f
            panelView.animate()
                .alpha(0f)
                .scaleX(0.68f)
                .scaleY(0.68f)
                .translationY(-8f)
                .setDuration(170)
                .withEndAction {
                    if (!panelExpanded) {
                        panelView.visibility = View.GONE
                        panelView.translationY = 0f
                        applyWindowSize(expanded = false)
                        clampPosition()
                    }
                }
                .start()
        }
        contentView.post { clampPosition() }
    }

    private fun setControlContentHidden(hidden: Boolean) {
        if (contentHiddenByApp == hidden) return
        if (hidden && panelExpanded) setPanelExpanded(false)
        contentHiddenByApp = hidden
        if (hidden) {
            contentView.visibility = View.GONE
            layoutParams.width = 1
            layoutParams.height = 1
            runCatching { windowManager.updateViewLayout(contentView, layoutParams) }
        } else {
            contentView.visibility = View.VISIBLE
            layoutParams.width = dp(BALL_SIZE_DP)
            layoutParams.height = dp(BALL_SIZE_DP)
            clampPosition()
        }
    }

    private fun applyWindowSize(expanded: Boolean) {
        if (!contentView.isAttachedToWindow) return
        if (contentHiddenByApp) {
            layoutParams.width = 1
            layoutParams.height = 1
            runCatching { windowManager.updateViewLayout(contentView, layoutParams) }
            return
        }
        val ballSize = dp(BALL_SIZE_DP)
        layoutParams.width = ballSize
        layoutParams.height = ballSize

        if (expanded) {
            val panelWidth = dp(PANEL_WIDTH_DP)
            panelView.measure(
                View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val panelTopMargin = (panelView.layoutParams as? LinearLayout.LayoutParams)
                ?.topMargin ?: dp(8)
            layoutParams.width = max(ballSize, panelWidth)
            layoutParams.height = ballSize + panelTopMargin + panelView.measuredHeight
        }

        runCatching { windowManager.updateViewLayout(contentView, layoutParams) }
    }

    private fun animatePanelChildren() {
        val children = (0 until panelView.childCount).map { panelView.getChildAt(it) }
        children.forEachIndexed { index, child ->
            child.alpha = 0f
            child.translationY = dp(7).toFloat()
            child.postDelayed({
                child.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(210)
                    .setInterpolator(OvershootInterpolator(0.9f))
                    .start()
            }, 35L + index * 28L)
        }
    }

    private fun clampPosition() {
        if (!contentView.isAttachedToWindow) return
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        val viewWidth = if (layoutParams.width == ViewGroup.LayoutParams.WRAP_CONTENT) {
            contentView.width.coerceAtLeast(1)
        } else {
            layoutParams.width
        }
        val viewHeight = if (layoutParams.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
            contentView.height.coerceAtLeast(1)
        } else {
            layoutParams.height
        }
        layoutParams.x = layoutParams.x.coerceIn(0, (width - viewWidth).coerceAtLeast(0))
        layoutParams.y = layoutParams.y.coerceIn(0, (height - viewHeight).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(contentView, layoutParams) }
    }

    private fun snapToNearestEdge() {
        if (!contentView.isAttachedToWindow) return
        val screenWidth = resources.displayMetrics.widthPixels
        val ballSize = dp(BALL_SIZE_DP)
        val targetX = if (layoutParams.x + ballSize / 2f < screenWidth / 2f) {
            0
        } else {
            screenWidth - ballSize
        }

        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(layoutParams.x, targetX).apply {
            duration = 220
            interpolator = DecelerateInterpolator(2.2f)
            addUpdateListener { animator ->
                layoutParams.x = animator.animatedValue as Int
                runCatching { windowManager.updateViewLayout(contentView, layoutParams) }
            }
            start()
        }
    }

    private fun renderState(state: VoiceRuntimeState) {
        if (!::ballView.isInitialized) return
        val running = VoiceForegroundService.isRunning()
        val activeState = running && state.deviceState != DeviceState.Idle
        val targetColor = when {
            !running -> COLOR_MUTED
            state.deviceState == DeviceState.Listening -> COLOR_SUCCESS
            state.deviceState == DeviceState.Speaking -> COLOR_WARNING
            else -> COLOR_PRIMARY
        }

        animateBallColor(targetColor)
        (statusDot.background as? GradientDrawable)?.setColor(targetColor)
        if (activeState) {
            startBallPulse()
            startStatusPulse()
        } else {
            stopPulses()
        }

        statusView.text = when {
            !running -> "点击启动"
            state.deviceState == DeviceState.Listening -> "正在聆听"
            state.deviceState == DeviceState.Speaking -> "正在回复"
            state.status == ConnectionStatus.ActivationRequired -> "需要激活"
            state.waitingForNetwork -> "等待网络后自动继续"
            state.status == ConnectionStatus.Error ->
                if (state.autoRecoveryEnabled) "自动恢复中" else "点击重试"
            state.status == ConnectionStatus.Connecting -> "准备中"
            state.wakeWordEnabled -> "唤醒词待命"
            else -> "随时可对话"
        }
        primaryAction.text = when {
            !running -> "启动小智"
            state.status == ConnectionStatus.ActivationRequired -> "去激活"
            state.status == ConnectionStatus.Connecting -> "准备中"
            state.status == ConnectionStatus.Error -> "重试"
            state.deviceState == DeviceState.Listening -> "停止聆听"
            state.wakeWordEnabled -> "手动聆听"
            else -> "开始聆听"
        }
        wakeAction.text = if (state.wakeWordEnabled) "唤醒词 开" else "唤醒词 关"

        val wakeEnabled = running
        val abortEnabled = state.deviceState == DeviceState.Speaking
        val stopEnabled = running
        setActionEnabled(primaryAction, true)
        setActionEnabled(wakeAction, wakeEnabled)
        setActionEnabled(abortAction, abortEnabled)
        setActionEnabled(stopAction, stopEnabled)
        setActionEnabled(openAction, true)
    }

    private fun renderMusicState(state: MusicRuntimeState) {
        if (!::musicIslandView.isInitialized) return
        setMusicIslandShown(state.active)

        musicTitleView.text = when {
            state.loading && state.title.isBlank() -> "正在搜索歌曲"
            state.hasTrack || state.loading -> state.title
            else -> ""
        }
        musicSourceView.text = listOf(state.playbackStatusLabel, state.sourceName)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        val paused = state.paused && state.hasTrack
        musicPlayPauseAction.text = if (paused) "▶" else "││"
        musicPlayPauseAction.contentDescription = if (paused) "继续播放" else "暂停音乐"
        setActionEnabled(musicPreviousAction, state.hasTrack && state.hasPrevious)
        setActionEnabled(musicPlayPauseAction, state.hasTrack)
        setActionEnabled(musicNextAction, state.hasTrack && state.hasNext)
        setActionEnabled(musicStopAction, state.hasTrack)

        val accent = if (paused) COLOR_WARNING else COLOR_PRIMARY
        (musicPulseView.background as? GradientDrawable)?.setColor(accent)
        (musicIslandView.background as? GradientDrawable)?.setStroke(dp(1), COLOR_ISLAND_STROKE)
        if (state.active && !paused) {
            startMusicBreath()
        } else {
            stopMusicBreath(paused)
        }
    }

    private fun setMusicIslandShown(shown: Boolean) {
        if (musicIslandShown == shown) return
        musicIslandShown = shown
        musicIslandView.animate().cancel()
        musicIslandSnapAnimator?.cancel()
        handler.removeCallbacks(restoreMusicIsland)
        musicIslandDragging = false
        if (shown) {
            positionMusicIslandCenter()
            musicIslandView.visibility = View.VISIBLE
            musicIslandView.alpha = 0f
            musicIslandView.scaleX = 0.82f
            musicIslandView.scaleY = 0.82f
            musicIslandView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(280)
                .setInterpolator(OvershootInterpolator(1.08f))
                .start()
        } else {
            musicIslandView.animate()
                .alpha(0f)
                .scaleX(0.84f)
                .scaleY(0.84f)
                .setDuration(180)
                .withEndAction {
                    if (!musicIslandShown) musicIslandView.visibility = View.GONE
                }
                .start()
        }
    }

    private fun startMusicBreath() {
        if (musicBreath?.isRunning == true) return
        musicBreath = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1900
            repeatCount = ObjectAnimator.INFINITE
            addUpdateListener { animator ->
                val phase = animator.animatedValue as Float
                val wave = kotlin.math.sin(phase * 2f * Math.PI.toFloat())
                musicPulseView.alpha = 0.55f + 0.35f * wave
                musicPulseView.scaleX = 0.86f + 0.24f * wave
                musicPulseView.scaleY = 0.86f + 0.24f * wave
                val strokeAlpha = (78 + 48 * wave).roundToInt().coerceIn(40, 150)
                (musicIslandView.background as? GradientDrawable)?.setStroke(
                    dp(1),
                    Color.argb(strokeAlpha, 3, 155, 229)
                )
            }
            start()
        }
    }

    private fun stopMusicBreath(paused: Boolean) {
        musicBreath?.cancel()
        musicBreath = null
        musicPulseView.alpha = if (paused) 0.78f else 0.42f
        musicPulseView.scaleX = 1f
        musicPulseView.scaleY = 1f
        (musicIslandView.background as? GradientDrawable)?.setStroke(
            dp(1),
            if (paused) Color.parseColor("#80EF6C00") else COLOR_ISLAND_STROKE
        )
    }

    private fun runMusicAction(action: () -> Unit) {
        scope.launch(Dispatchers.IO) { action() }
    }

    private fun centeredMusicIslandPoint(): Pair<Int, Int> {
        val islandWidth = dp(MUSIC_ISLAND_WIDTH_DP)
        val x = ((resources.displayMetrics.widthPixels - islandWidth) / 2)
            .coerceAtLeast(dp(8))
        return x to statusBarHeight() + dp(MUSIC_ISLAND_TOP_MARGIN_DP)
    }

    private fun positionMusicIslandCenter() {
        if (!::musicIslandView.isInitialized || !musicIslandView.isAttachedToWindow) return
        val center = centeredMusicIslandPoint()
        musicLayoutParams.x = center.first
        musicLayoutParams.y = center.second
        runCatching { windowManager.updateViewLayout(musicIslandView, musicLayoutParams) }
    }

    private fun animateMusicIslandToCenter() {
        if (!::musicIslandView.isInitialized || !musicIslandView.isAttachedToWindow) return
        val target = centeredMusicIslandPoint()
        val startX = musicLayoutParams.x
        val startY = musicLayoutParams.y
        if (startX == target.first && startY == target.second) return

        musicIslandSnapAnimator?.cancel()
        musicIslandSnapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MUSIC_ISLAND_SNAP_DURATION_MS
            interpolator = OvershootInterpolator(1.04f)
            addUpdateListener { animator ->
                val phase = animator.animatedValue as Float
                musicLayoutParams.x =
                    (startX + (target.first - startX) * phase).roundToInt()
                musicLayoutParams.y =
                    (startY + (target.second - startY) * phase).roundToInt()
                runCatching { windowManager.updateViewLayout(musicIslandView, musicLayoutParams) }
            }
            start()
        }
    }

    private fun clampMusicIslandPosition() {
        if (!::musicIslandView.isInitialized || !musicIslandView.isAttachedToWindow) return
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val margin = dp(8)
        val islandWidth = dp(MUSIC_ISLAND_WIDTH_DP)
        val islandHeight = dp(MUSIC_ISLAND_HEIGHT_DP)
        val topLimit = statusBarHeight() + dp(8)
        val bottomLimit = (screenHeight - islandHeight - margin).coerceAtLeast(topLimit)
        val maxX = (screenWidth - islandWidth - margin).coerceAtLeast(margin)
        musicLayoutParams.x = musicLayoutParams.x.coerceIn(margin, maxX)
        musicLayoutParams.y = musicLayoutParams.y.coerceIn(topLimit, bottomLimit)
        runCatching { windowManager.updateViewLayout(musicIslandView, musicLayoutParams) }
    }

    private fun statusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            resources.getDimensionPixelSize(resourceId)
        } else {
            dp(24)
        }
    }

    private fun animateBallColor(targetColor: Int) {
        if (targetColor == currentBallColor) return
        val startColor = currentBallColor
        currentBallColor = targetColor
        val drawable = ballView.background as? GradientDrawable ?: return
        ValueAnimator.ofObject(ArgbEvaluator(), startColor, targetColor).apply {
            duration = 280
            addUpdateListener { animator ->
                drawable.setColor(animator.animatedValue as Int)
            }
            start()
        }
    }

    private fun startBallPulse() {
        if (ballPulse?.isRunning == true) return
        ballPulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            repeatCount = ObjectAnimator.INFINITE
            addUpdateListener { animator ->
                val phase = animator.animatedValue as Float
                val scale = 1f + 0.05f * kotlin.math.sin(phase * 2f * Math.PI.toFloat())
                ballView.scaleX = scale
                ballView.scaleY = scale
            }
            start()
        }
    }

    private fun startStatusPulse() {
        if (statusPulse?.isRunning == true) return
        statusPulse = ObjectAnimator.ofFloat(statusDot, View.ALPHA, 1f, 0.35f, 1f).apply {
            duration = 1200
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun stopPulses() {
        ballPulse?.cancel()
        statusPulse?.cancel()
        ballPulse = null
        statusPulse = null
        ballView.scaleX = 1f
        ballView.scaleY = 1f
        statusDot.alpha = 1f
    }

    private fun setActionEnabled(view: TextView, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.52f
    }

    private fun openApp() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }

    companion object {
        private const val ACTION_STOP = "com.xiaozhi.android.action.STOP_SYSTEM_OVERLAY"
        private const val ACTION_APP_FOREGROUND =
            "com.xiaozhi.android.action.APP_FOREGROUND_SYSTEM_OVERLAY"
        private const val ACTION_APP_BACKGROUND =
            "com.xiaozhi.android.action.APP_BACKGROUND_SYSTEM_OVERLAY"
        private const val BALL_SIZE_DP = 54
        private const val MUSIC_ISLAND_WIDTH_DP = 300
        private const val MUSIC_ISLAND_HEIGHT_DP = 64
        private const val MUSIC_ISLAND_TOP_MARGIN_DP = 12
        private const val MUSIC_ISLAND_RESTORE_DELAY_MS = 5_000L
        private const val MUSIC_ISLAND_SNAP_DURATION_MS = 420L
        private const val PANEL_WIDTH_DP = 216
        private const val PANEL_AUTO_COLLAPSE_MS = 7_000L
        private val COLOR_PANEL = Color.parseColor("#F2141A1F")
        private val COLOR_ISLAND = Color.parseColor("#F2081116")
        private val COLOR_ISLAND_STROKE = Color.parseColor("#40FFFFFF")
        private val COLOR_PANEL_STROKE = Color.parseColor("#33FFFFFF")
        private val COLOR_BALL_STROKE = Color.parseColor("#66FFFFFF")
        private val COLOR_PRIMARY = Color.parseColor("#039BE5")
        private val COLOR_SUCCESS = Color.parseColor("#2E7D32")
        private val COLOR_WARNING = Color.parseColor("#EF6C00")
        private val COLOR_DANGER = Color.parseColor("#C62828")
        private val COLOR_MUTED = Color.parseColor("#37474F")
        private val COLOR_TEXT_PRIMARY = Color.parseColor("#ECEFF1")
        private val COLOR_TEXT_SECONDARY = Color.parseColor("#B0BEC5")
        private val COLOR_RIPPLE = Color.parseColor("#33FFFFFF")

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            context.startService(Intent(context, SystemOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SystemOverlayService::class.java)
                    .setAction(ACTION_STOP)
            )
        }

        fun setAppForeground(context: Context, foreground: Boolean) {
            if (!Settings.canDrawOverlays(context)) return
            context.startService(
                Intent(context, SystemOverlayService::class.java)
                    .setAction(
                        if (foreground) ACTION_APP_FOREGROUND else ACTION_APP_BACKGROUND
                    )
            )
        }
    }
}
