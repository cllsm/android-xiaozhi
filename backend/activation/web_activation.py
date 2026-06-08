"""Web 激活流程处理.

参考 py-xiaozhi 的 BaseActivation / CLIActivation 模式，
通过 WebSocket 广播激活事件给前端（Web 测试页面或 UniApp 前端）。
"""

from backend.constants.system import SystemConstants
from backend.log import get_logger

logger = get_logger()


class WebActivation:
    """Web 模式激活处理.

    通过 LocalServer.broadcast_event() 向所有 WebSocket 客户端
    推送激活状态、验证码和结果。

    WS 事件格式:
    - activation_required: 设备需要激活
    - activation_code:     验证码展示
    - activation_result:   激活结果（成功/失败）
    - activation_error:    激活错误

    Args:
        activation_service: ActivationService 单例
        init_result: initialize() 返回的结果字典
        local_server: LocalServer 实例，用于广播事件
    """

    def __init__(self, activation_service, init_result: dict, local_server) -> None:
        self._service = activation_service
        self._init_result = init_result
        self._local_server = local_server

    def needs_activation(self) -> bool:
        """是否需要激活 UI."""
        if self._init_result is None:
            return False
        return self._init_result.get("need_activation_ui", False)

    async def run(self) -> bool:
        """执行激活流程.

        Returns:
            bool: 激活是否成功
        """
        logger.info("启动 Web 激活流程")

        # 广播激活需求
        device_info = self._service.get_device_info()
        await self._broadcast("activation_required", {
            "serial_number": device_info.get("serial_number", "--"),
            "mac_address": device_info.get("mac_address", "--"),
            "message": "设备需要激活",
        })

        if not self.needs_activation():
            logger.info("设备已激活，无需激活流程")
            return True

        try:
            return await self._core_activate()
        except Exception as e:
            logger.error(f"激活流程异常: {e}", exc_info=True)
            self._show_error(str(e))
            return False

    async def _core_activate(self) -> bool:
        """核心激活流程 — 展示验证码 → 轮询激活 → 展示结果."""
        if self._service is None:
            self._show_error("激活服务未初始化")
            return False

        data = self._service.get_activation_data()
        if not data:
            self._show_error("未获取到激活数据")
            return False

        # 展示验证码
        self._show_code(data)

        # 执行激活（内部含轮询，最多 60 次重试）
        success = await self._service.activate(data)

        # 展示结果
        self._show_result(success)
        return success

    # -------------------------
    # 展示方法（广播到 WS 客户端）
    # -------------------------

    def _show_code(self, data: dict) -> None:
        """展示验证码 — 广播 activation_code 事件."""
        code = data.get("code", "------")
        message = data.get("message", "请访问 xiaozhi.me 输入验证码")
        authorization_url = self._service.get_config_manager().get_config(
            "SYSTEM_OPTIONS.NETWORK.AUTHORIZATION_URL", "https://xiaozhi.me/"
        )

        # 格式化验证码显示（X X X X - X X X X）
        display_code = " ".join(code) if code else "------"

        logger.info(f"验证码: {display_code}")

        # 广播给所有 WS 客户端
        import asyncio
        asyncio.create_task(self._broadcast("activation_code", {
            "code": display_code,
            "raw_code": code,
            "message": message,
            "url": authorization_url,
        }))

    def _show_result(self, success: bool) -> None:
        """展示激活结果 — 广播 activation_result 事件."""
        if success:
            message = "设备激活成功！"
            logger.info(message)
        else:
            message = "设备激活失败，请重试"
            logger.error(message)

        import asyncio
        asyncio.create_task(self._broadcast("activation_result", {
            "success": success,
            "message": message,
        }))

    def _show_error(self, msg: str) -> None:
        """展示错误 — 广播 activation_error 事件."""
        logger.error(f"激活错误: {msg}")

        import asyncio
        asyncio.create_task(self._broadcast("activation_error", {
            "message": msg,
        }))

    # -------------------------
    # 工具方法
    # -------------------------

    async def _broadcast(self, event_type: str, data: dict) -> None:
        """广播事件到所有 WebSocket 客户端."""
        if self._local_server:
            try:
                await self._local_server.broadcast_event(event_type, data)
            except Exception as e:
                logger.warning(f"广播激活事件失败: {e}")
