package com.xiaozhi.android.core

object ConnectionRecoveryPolicy {
    fun normalizeRetryLimit(configured: Int): Int = configured.coerceAtLeast(1)

    fun nextDelay(currentDelayMillis: Long, maxDelayMillis: Long): Long =
        (currentDelayMillis * 2).coerceAtMost(maxDelayMillis)

    fun recoveryMessage(
        reason: String,
        attempt: Int,
        retryLimit: Int,
        autoRetryEnabled: Boolean,
        nextDelayMillis: Long?
    ): String {
        val cleanReason = reason.trim().ifBlank { "连接不可用" }
        return when {
            !autoRetryEnabled ->
                "连接断开：$cleanReason。自动重连已关闭，可点“立即重连”"
            attempt >= retryLimit ->
                "连接断开：$cleanReason。已连续恢复 $retryLimit 次，仍会每 ${seconds(nextDelayMillis)} 秒自动重试"
            nextDelayMillis == null ->
                "连接断开：$cleanReason。等待网络恢复后自动重试"
            else ->
                "连接断开：$cleanReason。第 $attempt/$retryLimit 次恢复，" +
                    "${seconds(nextDelayMillis)} 秒后重试"
        }
    }

    fun waitingForNetworkMessage(): String = "当前网络不可用，网络恢复后会自动连接"

    private fun seconds(delayMillis: Long?): String {
        val value = ((delayMillis ?: return "30") + 999L) / 1000L
        return value.toString()
    }
}
