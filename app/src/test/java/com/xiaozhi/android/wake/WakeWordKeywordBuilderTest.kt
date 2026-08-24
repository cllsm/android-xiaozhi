package com.xiaozhi.android.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordKeywordBuilderTest {
    @Test
    fun buildsSherpaKeywordLine() {
        assertEquals(
            "n ǐ h ǎo x iǎo zh ì @你好小智",
            WakeWordKeywordBuilder.build(" 你好小智 ")
        )
    }

    @Test
    fun rejectsNonChineseWakeWord() {
        val error = runCatching {
            WakeWordKeywordBuilder.build("hey xiaozhi")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}
