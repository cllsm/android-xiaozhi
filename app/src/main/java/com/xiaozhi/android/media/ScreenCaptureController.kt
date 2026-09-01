package com.xiaozhi.android.media

import android.content.Context
import android.content.Intent
import android.util.Log
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

object ScreenCaptureController {
    @Volatile
    private var resultCode = Int.MIN_VALUE

    @Volatile
    private var permissionData: Intent? = null

    @Volatile
    private var projection: MediaProjection? = null

    fun savePermissionResult(newResultCode: Int, data: Intent?) {
        resultCode = newResultCode
        permissionData = data
    }

    fun hasPermission(): Boolean {
        return permissionData != null
    }

    @Synchronized
    fun createProjection(context: Context): Boolean {
        if (projection != null) return true
        val data = permissionData ?: return false
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        return try {
            val newProjection = manager.getMediaProjection(resultCode, data) ?: return false
            Log.i(TAG, "MediaProjection created")
            projection = newProjection
            newProjection.also {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    newProjection.registerCallback(
                        object : MediaProjection.Callback() {
                            override fun onStop() {
                                projection = null
                                invalidatePermission()
                                Log.i(TAG, "MediaProjection stopped")
                            }
                        },
                        Handler(context.mainLooper)
                    )
                }
            }
            true
        } catch (_: Exception) {
            invalidatePermission()
            Log.w(TAG, "MediaProjection creation failed")
            false
        }
    }

    @Synchronized
    fun capture(context: Context): ByteArray? {
        val activeProjection = projection ?: return null
        val metrics = context.resources.displayMetrics
        // 按最长边限制缩放采集：VirtualDisplay 会自动缩放渲染到低分辨率 surface，
        // 减小图片体积，避免视觉分析上传耗时过长导致云端 MCP 工具调用超时
        val (width, height) = scaledSize(
            metrics.widthPixels.coerceAtLeast(1),
            metrics.heightPixels.coerceAtLeast(1)
        )
        var reader: ImageReader? = null
        var display: VirtualDisplay? = null
        var thread: HandlerThread? = null

        return try {
            thread = HandlerThread("xiaozhi-screen-capture").also { it.start() }
            reader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                MAX_IMAGES
            )
            display = activeProjection.createVirtualDisplay(
                "xiaozhi-screen",
                width,
                height,
                metrics.densityDpi,
                0,
                reader.surface,
                null,
                Handler(thread.looper)
            )

            var image: Image? = null
            val deadline = SystemClock.elapsedRealtime() + CAPTURE_TIMEOUT_MS
            while (image == null && SystemClock.elapsedRealtime() < deadline) {
                image = reader.acquireLatestImage()
                if (image == null) SystemClock.sleep(FRAME_WAIT_MS)
            }

            image?.use { captured -> convertToJpeg(captured, width, height) }
        } catch (_: Exception) {
            null
        } finally {
            display?.release()
            reader?.close()
            thread?.quitSafely()
        }
    }

    fun stop() {
        val activeProjection = projection
        projection = null
        invalidatePermission()
        activeProjection?.stop()
    }

    @Synchronized
    private fun invalidatePermission() {
        resultCode = Int.MIN_VALUE
        permissionData = null
    }

    private fun convertToJpeg(image: Image, width: Int, height: Int): ByteArray {
        val plane = image.planes.first()
        val buffer = plane.buffer.rewind() as java.nio.ByteBuffer
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val offset = y * plane.rowStride + x * plane.pixelStride
                pixels[y * width + x] = Color.argb(
                    buffer.get(offset + 3).toInt() and 0xff,
                    buffer.get(offset).toInt() and 0xff,
                    buffer.get(offset + 1).toInt() and 0xff,
                    buffer.get(offset + 2).toInt() and 0xff
                )
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        bitmap.recycle()
        return output.toByteArray()
    }

    private const val MAX_IMAGES = 2
    private const val JPEG_QUALITY = 80
    private const val CAPTURE_TIMEOUT_MS = 1_200L
    private const val FRAME_WAIT_MS = 40L
    private const val TAG = "ScreenCapture"

    fun scaledSize(displayWidth: Int, displayHeight: Int): Pair<Int, Int> {
        val scale = (MAX_DIMENSION.toFloat() / maxOf(displayWidth, displayHeight))
            .coerceAtMost(1f)
        return Pair(
            (displayWidth * scale).roundToInt().coerceAtLeast(1),
            (displayHeight * scale).roundToInt().coerceAtLeast(1)
        )
    }

    private const val MAX_DIMENSION = 1600
}
