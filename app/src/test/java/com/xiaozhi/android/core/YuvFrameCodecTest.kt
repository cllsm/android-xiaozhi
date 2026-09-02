package com.xiaozhi.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Test

// compressToJpeg 依赖 android.graphics，交给真机/模拟器实测；此处覆盖纯函数组装逻辑
class YuvFrameCodecTest {

    @Test
    fun packsNv21WithoutPadding() {
        // 4x2 图像，Y 平面无跨距填充、UV 平面无对齐（NV21 原生布局）
        val y = byteArrayOf(
            0, 1, 2, 3,
            4, 5, 6, 7
        )
        val u = byteArrayOf(8, 9)
        val v = byteArrayOf(12, 13)

        val nv21 = YuvFrameCodec.toNv21(
            y = y, yRowStride = 4,
            u = u, uRowStride = 2, uPixelStride = 1,
            v = v, vRowStride = 2, vPixelStride = 1,
            width = 4, height = 2
        )

        // NV21 = Y 后接 VU 交错；色度 2 列 1 行
        assertArrayEquals(
            byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 12, 8, 13, 9),
            nv21
        )
    }

    @Test
    fun skipsRowStridePadding() {
        // 4x2 图像，每行末尾多 1 字节对齐填充，应被剔除
        val y = byteArrayOf(
            0, 1, 2, 3, 99,
            4, 5, 6, 7, 99
        )
        val u = byteArrayOf(6, 7, 99)
        val v = byteArrayOf(8, 9, 99)

        val nv21 = YuvFrameCodec.toNv21(
            y = y, yRowStride = 5,
            u = u, uRowStride = 3, uPixelStride = 1,
            v = v, vRowStride = 3, vPixelStride = 1,
            width = 4, height = 2
        )

        assertArrayEquals(
            byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 6, 9, 7),
            nv21
        )
    }

    @Test
    fun skipsChromaPixelStride() {
        // 半平面布局下 U/V 平面 pixelStride=2，隔字节取有效色度
        val y = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
        val u = byteArrayOf(10, 0, 30, 0)
        val v = byteArrayOf(20, 0, 40, 0)

        val nv21 = YuvFrameCodec.toNv21(
            y = y, yRowStride = 4,
            u = u, uRowStride = 4, uPixelStride = 2,
            v = v, vRowStride = 4, vPixelStride = 2,
            width = 4, height = 2
        )

        assertArrayEquals(
            byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 20, 10, 40, 30),
            nv21
        )
    }
}
