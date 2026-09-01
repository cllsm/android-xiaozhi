package com.xiaozhi.android.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppNameMatcherTest {

    @Test
    fun normalizesVoiceStyleAppNames() {
        assertEquals(
            "微信",
            AppNameMatcher.normalize("帮我打开微信APP。")
        )
        assertEquals(
            "com.tencent.mm",
            AppNameMatcher.normalize("启动 com.tencent.mm")
        )
        assertEquals(
            "抖音",
            AppNameMatcher.normalize("打开一下抖音软件")
        )
        assertEquals(
            "settings",
            AppNameMatcher.normalize("please open Settings")
        )
    }

    @Test
    fun prefersExactReadableLabel() {
        val target = LauncherAppCandidate("微信", "com.tencent.mm")
        val decoy = LauncherAppCandidate("微信读书", "com.tencent.weread")

        assertEquals(target, AppNameMatcher.bestMatch(listOf(decoy, target), "微信"))
    }

    @Test
    fun supportsCommonPackageAliases() {
        val target = LauncherAppCandidate("WeChat", "com.tencent.mm")

        assertEquals(target, AppNameMatcher.bestMatch(listOf(target), "微信"))
    }

    @Test
    fun returnsNullWhenAppNameDoesNotMatch() {
        val target = LauncherAppCandidate("微信", "com.tencent.mm")

        assertNull(AppNameMatcher.bestMatch(listOf(target), "某个 app"))
    }

    @Test
    fun detectsOnlyDirectLaunchCommands() {
        assertEquals(true, AppNameMatcher.isDirectLaunchCommand("帮我打开微信"))
        assertEquals(true, AppNameMatcher.isDirectLaunchCommand("打开一下抖音"))
        assertEquals(true, AppNameMatcher.isDirectLaunchCommand("open com.android.settings"))
        assertEquals(false, AppNameMatcher.isDirectLaunchCommand("怎么打开微信"))
    }
}
