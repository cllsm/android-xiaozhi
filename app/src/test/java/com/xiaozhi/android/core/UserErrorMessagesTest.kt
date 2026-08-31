package com.xiaozhi.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

class UserErrorMessagesTest {
    @Test
    fun `heartbeat timeout becomes a calm recovery message`() {
        assertEquals(
            "语音连接波动，正在自动恢复",
            UserErrorMessages.from(
                "sent ping but didn't receive pong within 20000ms (after 3 successful ping/pongs)"
            )
        )
    }
}
