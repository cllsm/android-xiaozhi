package com.xiaozhi.android.core

object UserErrorMessages {
    fun from(message: String): String {
        val text = message.lowercase()
        return when {
            "unable to resolve" in text || "unknownhost" in text ->
                "网络无法解析服务器地址，请检查网络或 OTA 地址"
            "timeout" in text || "timed out" in text ->
                "连接服务器超时，请稍后重试或检查网络"
            "http 401" in text || "unauthorized" in text ->
                "服务器拒绝了访问令牌，正在尝试重新获取配置"
            "http 403" in text || "forbidden" in text ->
                "服务器拒绝访问，请确认设备已完成激活"
            "http 404" in text ->
                "服务地址不存在，请检查 OTA 或 WebSocket 配置"
            "http 5" in text ->
                "小智服务器暂时不可用，稍后会自动重试"
            "sent ping" in text || "receive pong" in text || "ping/pong" in text ->
                "语音连接波动，正在自动恢复"
            "激活" in message ->
                "设备还没完成绑定，请在弹窗中复制验证码到控制台激活"
            "缺少麦克风权限" in message ->
                "需要麦克风权限才能开始语音对话"
            "websocket" in text && ("failed" in text || "closed" in text || "error" in text) ->
                "语音连接断开，正在尝试恢复"
            else -> message
        }
    }
}
