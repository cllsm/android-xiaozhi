package com.xiaozhi.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionRecoveryPolicyTest {

    @Test
    fun `retry limit is never below one`() {
        assertEquals(1, ConnectionRecoveryPolicy.normalizeRetryLimit(0))
        assertEquals(1, ConnectionRecoveryPolicy.normalizeRetryLimit(-3))
        assertEquals(6, ConnectionRecoveryPolicy.normalizeRetryLimit(6))
    }

    @Test
    fun `retry delay doubles and is capped`() {
        assertEquals(
            4_000L,
            ConnectionRecoveryPolicy.nextDelay(2_000L, 30_000L)
        )
        assertEquals(
            30_000L,
            ConnectionRecoveryPolicy.nextDelay(20_000L, 30_000L)
        )
    }

    @Test
    fun `recovery message keeps rapid retries actionable`() {
        val message = ConnectionRecoveryPolicy.recoveryMessage(
            reason = "等待服务端 hello 超时",
            attempt = 2,
            retryLimit = 5,
            autoRetryEnabled = true,
            nextDelayMillis = 4_000L
        )

        assertEquals(
            "连接断开：等待服务端 hello 超时。第 2/5 次恢复，4 秒后重试",
            message
        )
    }

    @Test
    fun `recovery message explains slow recovery and manual option`() {
        val slowMessage = ConnectionRecoveryPolicy.recoveryMessage(
            reason = "服务器暂时不可用",
            attempt = 5,
            retryLimit = 5,
            autoRetryEnabled = true,
            nextDelayMillis = 30_000L
        )
        val manualMessage = ConnectionRecoveryPolicy.recoveryMessage(
            reason = "服务器暂时不可用",
            attempt = 1,
            retryLimit = 5,
            autoRetryEnabled = false,
            nextDelayMillis = null
        )

        assertEquals(
            "连接断开：服务器暂时不可用。已连续恢复 5 次，仍会每 30 秒自动重试",
            slowMessage
        )
        assertEquals(
            "连接断开：服务器暂时不可用。自动重连已关闭，可点“立即重连”",
            manualMessage
        )
    }
}
