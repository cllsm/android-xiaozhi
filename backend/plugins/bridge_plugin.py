"""前后端桥接插件.

将 EventBus 事件转发到 WebSocket，实现后端状态向前端的实时推送。
"""

from backend.core.event_bus import Events
from backend.log import get_logger
from backend.plugins.base import Plugin

logger = get_logger()


class BridgePlugin(Plugin):
    """前后端桥接插件.

    职责:
    - 订阅 EventBus 关键事件
    - 将事件转换为 WebSocket 消息格式
    - 通过 LocalServer 广播给前端

    INCOMING_JSON 解析规则:
    - type: "tts" + state: "start" → 提取 text 字段 → "text_response" 事件
    - type: "stt" → 提取识别文本 → "text_response" 事件
    - type: "llm" + emotion 字段 → "emotion" 事件
    - 其他 JSON → "json_message" 事件
    """

    name = "bridge"
    priority = 40

    def __init__(self) -> None:
        super().__init__()
        self._local_server = None

    async def setup(self, ctx, cmd) -> None:
        """初始化桥接插件.

        从 ctx 获取 local_server 引用，订阅 EventBus 事件。
        """
        await super().setup(ctx, cmd)

        # 从 ctx 获取 local_server 引用
        self._local_server = getattr(ctx, "local_server", None)
        if not self._local_server:
            logger.warning("BridgePlugin: 未找到 local_server，事件桥接功能不可用")
            return

        # 订阅 EventBus 事件
        event_bus = ctx.event_bus

        # 设备状态变更
        event_bus.on(Events.DEVICE_STATE_CHANGED, self._on_device_state_changed)

        # 协议连接/断开
        event_bus.on(Events.PROTOCOL_CONNECTED, self._on_protocol_connected)
        event_bus.on(Events.PROTOCOL_DISCONNECTED, self._on_protocol_disconnected)

        # 收到 JSON 消息（TTS / STT / LLM 等）
        event_bus.on(Events.INCOMING_JSON, self._on_incoming_json)

        # 网络错误
        event_bus.on(Events.NETWORK_ERROR, self._on_network_error)

        # 音频通道状态
        event_bus.on(Events.AUDIO_CHANNEL_OPENED, self._on_audio_channel_opened)
        event_bus.on(Events.AUDIO_CHANNEL_CLOSED, self._on_audio_channel_closed)

        logger.info("BridgePlugin 初始化完成，已订阅 EventBus 事件")

    # -------------------------
    # 事件处理器
    # -------------------------

    async def _on_device_state_changed(self, data: dict) -> None:
        """设备状态变更 → 广播 state_change 事件.

        Args:
            data: {"old_state": DeviceState, "new_state": DeviceState}
        """
        new_state = data.get("new_state")
        state_value = new_state.value if hasattr(new_state, "value") else str(new_state)
        await self._local_server.broadcast_event("state_change", {"state": state_value})

    async def _on_protocol_connected(self, protocol: object = None) -> None:
        """协议连接成功 → 广播 connection_status 事件."""
        protocol_name = type(protocol).__name__ if protocol else "unknown"
        await self._local_server.broadcast_event("connection_status", {
            "connected": True,
            "protocol": protocol_name,
        })

    async def _on_protocol_disconnected(self, _=None) -> None:
        """协议断开连接 → 广播 connection_status 事件."""
        await self._local_server.broadcast_event("connection_status", {
            "connected": False,
        })

    async def _on_incoming_json(self, json_data: dict) -> None:
        """收到 JSON 消息 → 解析并广播对应事件.

        解析规则:
        - type: "tts" + state: "start" → 检查 text 字段 → text_response
        - type: "stt" → 语音识别结果 → text_response
        - type: "llm" + emotion → 情绪推送
        - 其他 → json_message 转发

        Args:
            json_data: 协议层收到的 JSON 字典
        """
        if not isinstance(json_data, dict):
            return

        msg_type = json_data.get("type")

        if msg_type == "tts":
            state = json_data.get("state")
            if state == "start":
                text = json_data.get("text")
                if text:
                    # TTS 开始播放，推送文本给前端
                    await self._local_server.broadcast_event("text_response", {
                        "source": "tts",
                        "text": text,
                    })

        elif msg_type == "stt":
            # 语音识别结果
            text = json_data.get("text")
            if text:
                await self._local_server.broadcast_event("text_response", {
                    "source": "stt",
                    "text": text,
                })

        elif msg_type == "llm":
            # LLM 响应，检查情绪
            emotion = json_data.get("emotion")
            if emotion:
                await self._local_server.broadcast_event("emotion", {
                    "emotion": emotion,
                })

        # 所有 JSON 消息都作为 json_message 转发
        await self._local_server.broadcast_event("json_message", json_data)

    async def _on_network_error(self, error_message: str = None) -> None:
        """网络错误 → 广播 error 事件.

        Args:
            error_message: 错误信息字符串
        """
        await self._local_server.broadcast_event("error", {
            "type": "network_error",
            "message": error_message or "网络连接异常",
        })

    async def _on_audio_channel_opened(self, _=None) -> None:
        """音频通道打开 → 广播 connection_status 事件."""
        await self._local_server.broadcast_event("connection_status", {
            "audio_channel": True,
        })

    async def _on_audio_channel_closed(self, _=None) -> None:
        """音频通道关闭 → 广播 connection_status 事件."""
        await self._local_server.broadcast_event("connection_status", {
            "audio_channel": False,
        })

    async def stop(self) -> None:
        """停止桥接插件."""
        await super().stop()
        self._local_server = None
        logger.info("BridgePlugin 已停止")
