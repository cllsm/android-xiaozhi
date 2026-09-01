package com.xiaozhi.android.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

enum class CameraPreviewStatus {
    Idle, Starting, Running, PermissionRequired, Error
}

class CameraPreviewController(private val context: Context) {
    private val statusFlow = MutableStateFlow(CameraPreviewStatus.Idle)
    val status: StateFlow<CameraPreviewStatus> = statusFlow.asStateFlow()

    private var textureView: TextureView? = null
    private var thread: HandlerThread? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSize = Size(1280, 720)
    private var sensorOrientation = 270
    private var isFrontCamera = true
    @Volatile
    private var generation = 0

    private val listener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(
            texture: SurfaceTexture,
            width: Int,
            height: Int
        ) {
            startCamera(textureView ?: return, texture, width, height)
        }

        override fun onSurfaceTextureSizeChanged(
            texture: SurfaceTexture,
            width: Int,
            height: Int
        ) {
            textureView?.let { configureTransform(it, width, height) }
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            closeCamera()
            generation++
            return true
        }
        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    fun start(view: TextureView) {
        textureView = view
        view.surfaceTextureListener = listener
        if (!hasPermission()) {
            statusFlow.value = CameraPreviewStatus.PermissionRequired
            return
        }
        val texture = view.surfaceTexture
        if (texture == null) {
            statusFlow.value = CameraPreviewStatus.Starting
        } else {
            startCamera(view, texture, view.width, view.height)
        }
    }

    fun stop() {
        textureView?.surfaceTextureListener = null
        textureView = null
        closeCamera()
        generation++
        statusFlow.value = CameraPreviewStatus.Idle
    }

    private fun hasPermission() = context.checkSelfPermission(Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startCamera(
        view: TextureView,
        texture: SurfaceTexture,
        width: Int,
        height: Int
    ) {
        if (!hasPermission()) {
            statusFlow.value = CameraPreviewStatus.PermissionRequired
            return
        }
        closeCamera()
        generation++
        val startGeneration = generation
        statusFlow.value = CameraPreviewStatus.Starting
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
            } ?: manager.cameraIdList.firstOrNull() ?: error("没有可用摄像头")
            val characteristics = manager.getCameraCharacteristics(cameraId)
            isFrontCamera = characteristics.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT
            sensorOrientation =
                characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            previewSize = selectSize(characteristics, width, height)
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            configureTransform(view, width, height)

            val cameraThread = HandlerThread("xiaozhi-camera-preview").also { it.start() }
            val handler = Handler(cameraThread.looper)
            thread = cameraThread
            val surface = Surface(texture)
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        if (generation != startGeneration) {
                            device.close()
                            return
                        }
                        camera = device
                        createSession(device, surface, handler, startGeneration)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        if (generation == startGeneration && camera == device) {
                            statusFlow.value = CameraPreviewStatus.Error
                        }
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        if (generation == startGeneration && camera == device) {
                            statusFlow.value = CameraPreviewStatus.Error
                        }
                    }
                },
                handler
            )
        } catch (_: Exception) {
            statusFlow.value = CameraPreviewStatus.Error
        }
    }

    private fun createSession(
        device: CameraDevice,
        surface: Surface,
        handler: Handler,
        sessionGeneration: Int
    ) {
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                .apply { addTarget(surface) }
            device.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(activeSession: CameraCaptureSession) {
                        if (generation != sessionGeneration || camera != device) {
                            activeSession.close()
                            return
                        }
                        try {
                            session = activeSession
                            activeSession.setRepeatingRequest(request.build(), null, handler)
                            statusFlow.value = CameraPreviewStatus.Running
                            // 预览开始出帧后注册陪学巡查帧源
                            StudyCompanionController.registerFrameSource(this@CameraPreviewController) {
                                captureFrame()
                            }
                        } catch (_: Exception) {
                            activeSession.close()
                            statusFlow.value = CameraPreviewStatus.Error
                        }
                    }

                    override fun onConfigureFailed(activeSession: CameraCaptureSession) {
                        activeSession.close()
                        if (generation == sessionGeneration && camera == device) {
                            statusFlow.value = CameraPreviewStatus.Error
                        }
                    }
                },
                handler
            )
        } catch (_: Exception) {
            statusFlow.value = CameraPreviewStatus.Error
        }
    }

    private fun selectSize(
        characteristics: CameraCharacteristics,
        width: Int,
        height: Int
    ): Size {
        val sizes = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        )?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        if (sizes.isEmpty()) return Size(1280, 720)
        // 缓冲区为横向的传感器方向，目标比例取视图比例的倒数（竖屏视图对应横向 16:9）
        val targetRatio = if (width > 0 && height > 0) {
            if (width > height) width.toFloat() / height else height.toFloat() / width
        } else {
            16f / 9f
        }
        return sizes.minByOrNull { size ->
            val ratio = size.width.toFloat() / size.height.toFloat()
            abs(max(size.width, size.height) - 1280) + abs(ratio - targetRatio) * 1000f
        } ?: Size(1280, 720)
    }

    /**
     * 计算预览画面的显示变换：相机缓冲区是传感器自然方向（通常为横向），
     * 需按传感器方向与屏幕方向的差值旋转转正；前置摄像头额外水平镜像，
     * 呈现"照镜子"的自然观感；最后等比缩放铺满视图（超出部分裁切）。
     */
    private fun configureTransform(view: TextureView, width: Int, height: Int) {
        if (width == 0 || height == 0) return
        val displayRotation = when (view.display.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        // 把缓冲区画面转正所需的顺时针角度（前摄与后摄的传感器方向约定相反）
        val rotationDegrees = if (isFrontCamera) {
            (360 - (sensorOrientation + displayRotation) % 360) % 360
        } else {
            (sensorOrientation - displayRotation + 360) % 360
        }
        val rotated = rotationDegrees == 90 || rotationDegrees == 270
        val contentWidth = if (rotated) previewSize.height.toFloat() else previewSize.width.toFloat()
        val contentHeight = if (rotated) previewSize.width.toFloat() else previewSize.height.toFloat()
        // 等比缩放铺满视图（centerCrop），避免画面拉伸变形
        val scale = max(width / contentWidth, height / contentHeight)
        val bufferCenterX = previewSize.width / 2f
        val bufferCenterY = previewSize.height / 2f

        // 构造"缓冲区画面 → 视图"的正向变换：绕缓冲区中心旋转、镜像、缩放并对齐视图中心
        val forward = Matrix()
        if (rotationDegrees != 0) {
            forward.postRotate(rotationDegrees.toFloat(), bufferCenterX, bufferCenterY)
        }
        if (isFrontCamera) {
            forward.postScale(-1f, 1f, bufferCenterX, bufferCenterY)
        }
        forward.postScale(scale, scale, bufferCenterX, bufferCenterY)
        forward.postTranslate(width / 2f - bufferCenterX, height / 2f - bufferCenterY)

        // setTransform 接收的是视图坐标到纹理坐标的采样映射，传入正向变换的逆矩阵
        val sampling = Matrix()
        if (!forward.invert(sampling)) return
        view.setTransform(sampling)
    }

    private fun closeCamera() {
        // 预览停止时同步注销陪学巡查帧源
        StudyCompanionController.unregisterFrameSource(this)
        try {
            session?.close()
            camera?.close()
        } catch (_: Exception) {
        }
        session = null
        camera = null
        thread?.quitSafely()
        thread = null
    }

    /**
     * 抓取当前预览画面并压缩为 JPEG：切到主线程读取 TextureView 位图，
     * 按最长边限制缩放，控制图片体积以保证视觉分析上传速度。
     * 仅在预览处于 Running 状态时可用，失败或超时返回 null。
     */
    fun captureFrame(maxDimension: Int = 1280, jpegQuality: Int = 80): ByteArray? {
        val view = textureView
        if (statusFlow.value != CameraPreviewStatus.Running || view == null) return null
        val latch = CountDownLatch(1)
        var frame: ByteArray? = null
        view.post {
            frame = runCatching {
                view.bitmap?.let { bitmap ->
                    val scaled = scaleBitmap(bitmap, maxDimension)
                    val output = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)
                    if (scaled !== bitmap) scaled.recycle()
                    bitmap.recycle()
                    output.toByteArray()
                }
            }.getOrNull()
            latch.countDown()
        }
        return if (latch.await(CAPTURE_FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)) frame else null
    }

    /** 按最长边限制等比缩放，未超限时返回原位图 */
    private fun scaleBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / maxSide
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
        return scaled
    }

    private companion object {
        const val CAPTURE_FRAME_TIMEOUT_MS = 1_000L
    }
}
