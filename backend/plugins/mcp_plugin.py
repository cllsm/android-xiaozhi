"""MCP 插件.

管理 MCP 工具注册、消息路由和 MusicPlayer EventBus 注入。
从 py-xiaozhi 的 src/plugins/mcp.py 移植。
"""

from typing import TYPE_CHECKING, Optional

from backend.log import get_logger
from backend.mcp.mcp_server import McpServer
from backend.plugins.base import Plugin

if TYPE_CHECKING:
    from backend.bootstrap.protocols import PluginCommands, PluginContext

logger = get_logger()


class McpPlugin(Plugin):
    """MCP 工具管理插件.

    职责：
    1. 初始化 McpServer 并注册所有 MCP 工具（音乐、天气、音量等）
    2. 处理 AI 下发的 MCP 消息，路由到 McpServer
    3. 为 MusicPlayer 注入 EventBus，使其能发出状态变化事件
    4. 管理资源清理（停止音乐播放、重置回调）

    优先级: 20（在 AudioPlugin 之后、WakeWordPlugin 之前初始化）
    """

    name = "mcp"
    priority = 20  # 工具注册，需要较早初始化

    def __init__(self) -> None:
        super().__init__()
        self._server: Optional[McpServer] = None

    async def setup(self, ctx: "PluginContext", cmd: "PluginCommands") -> None:
        """初始化 MCP 服务.

        1. 设置 MCP 消息发送回调（通过协议层发送）
        2. 注册所有通用 MCP 工具
        3. 为 MusicPlayer 注入 EventBus
        """
        await super().setup(ctx, cmd)
        self._server = McpServer.get_instance()

        # MCP 响应需要使用 send_mcp_message 包装消息格式
        async def _send(msg: str):
            try:
                await cmd.send_mcp_message(msg)
            except Exception as e:
                logger.error(f"MCP 发送响应失败: {e}")

        try:
            self._server.set_send_callback(_send)
            self._server.add_common_tools()
            logger.info("MCP 工具注册完成")
        except Exception as e:
            logger.error(f"MCP 工具注册失败: {e}", exc_info=True)

        # 为 MusicPlayer 注入 EventBus
        try:
            from backend.mcp.tools.music.music_player import get_music_player_instance

            music_player = get_music_player_instance()
            music_player.set_event_bus(ctx.event_bus, ctx)
            logger.info("MusicPlayer EventBus 已注入")
        except Exception as e:
            logger.warning(f"设置 MusicPlayer EventBus 失败: {e}")

    async def on_incoming_json(self, message) -> None:
        """处理 AI 下发的 MCP 消息.

        当收到 type="mcp" 的 JSON 消息时，解析并路由到 McpServer。
        """
        if not isinstance(message, dict):
            return
        try:
            if message.get("type") == "mcp":
                payload = message.get("payload")
                if not payload:
                    return
                if self._server is None:
                    self._server = McpServer.get_instance()
                await self._server.parse_message(payload)
        except Exception as e:
            logger.error(f"MCP 消息处理失败: {e}", exc_info=True)

    def register_resources(self, pool) -> None:
        """注册资源清理函数."""
        async def _mcp_cleanup():
            try:
                from backend.mcp.tools.music.music_player import get_music_player_instance

                music_player = get_music_player_instance()
                if music_player.is_playing:
                    await music_player.stop()
            except Exception as e:
                logger.debug(f"停止音乐播放器失败: {e}")

            try:
                if self._server:
                    self._server.set_send_callback(None)
            except Exception as e:
                logger.debug(f"MCP shutdown 清理失败: {e}")

        pool.register("mcp.server", _mcp_cleanup)
