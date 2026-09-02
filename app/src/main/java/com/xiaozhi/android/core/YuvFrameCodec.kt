package com.xiaozhi.android.core

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream

/**
 * YUV_420_888 取帧编解码工具。
 *
 * 部分设备的相机（尤其模拟器虚拟相机）对 JPEG 输出流的实现不完整，
 * 会往 JPEG ImageReader 里塞未压缩数据；而 YUV_420_888 是所有设备
 * 必须保证的格式，因此统一改用 YUV 取帧再软件压缩为 JPEG。
 */
object YuvFrameCodec {

    /**
     * 把带跨距信息的 Y/U/V 平面数据组装为 NV21（Y + VU 交错）。
     * 各平面允许存在行对齐填充与像素间隔，逐行复制时只取有效像素。
     */
    fun toNv21(
        y: ByteArray,
        yRowStride: Int,
        u: ByteArray,
        uRowStride: Int,
        uPixelStride: Int,
        v: ByteArray,
        vRowStride: Int,
        vPixelStride: Int,
        width: Int,
        height: Int
    ): ByteArray {
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val nv21 = ByteArray(width * height * 3 / 2)

        var position = 0
        for (row in 0 until height) {
            val rowStart = row * yRowStride
            for (col in 0 until width) {
                nv21[position++] = y[rowStart + col]
            }
        }
        for (row in 0 until chromaHeight) {
            val uRowStart = row * uRowStride
            val vRowStart = row * vRowStride
            for (col in 0 until chromaWidth) {
                nv21[position++] = v[vRowStart + col * vPixelStride]
                nv21[position++] = u[uRowStart + col * uPixelStride]
            }
        }
        return nv21
    }

    /** NV21 数据压缩为 JPEG；输入非法时返回 null。 */
    fun compressToJpeg(nv21: ByteArray, width: Int, height: Int, quality: Int): ByteArray? {
        if (width <= 0 || height <= 0 || nv21.size < width * height * 3 / 2) return null
        val output = ByteArrayOutputStream()
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, output)
        return output.toByteArray()
    }

    /** 从相机 Image（YUV_420_888）直接得到 NV21 字节。 */
    fun imageToNv21(image: Image): ByteArray {
        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        return toNv21(
            y = planeBytes(yPlane),
            yRowStride = yPlane.rowStride,
            u = planeBytes(uPlane),
            uRowStride = uPlane.rowStride,
            uPixelStride = uPlane.pixelStride,
            v = planeBytes(vPlane),
            vRowStride = vPlane.rowStride,
            vPixelStride = vPlane.pixelStride,
            width = image.width,
            height = image.height
        )
    }

    private fun planeBytes(plane: Image.Plane): ByteArray {
        val buffer = plane.buffer
        buffer.rewind()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }
}
