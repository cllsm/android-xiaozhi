"""Android 截屏 MCP 工具（装饰器注册）.

支持两种运行模式：
- Android 设备：通过 MediaProjection API 或 screencap 命令截屏
- 桌面调试：使用 PIL ImageGrab 或其他平台工具截屏
"""

import asyncio
import json

from backend.log import get_logger
from backend.mcp.decorators import Prop, PropType, mcp_tool

from .screenshot_android import AndroidScreenshot

logger = get_logger()

# 模块级截屏单例
_screenshot: AndroidScreenshot | None = None


def _get_screenshot() -> AndroidScreenshot:
    """获取或初始化截屏单例."""
    global _screenshot
    if _screenshot is None:
        _screenshot = AndroidScreenshot()
    return _screenshot


@mcp_tool(
    name="take_screenshot",
    description=(
        "截屏/屏幕分析工具。当用户提到：截屏、截图、看看桌面、分析屏幕、桌面上有什么、"
        "屏幕截图、查看当前界面、分析当前页面、读取屏幕内容、屏幕OCR 时调用本工具。\n"
        "功能：①截取屏幕画面；②屏幕内容识别与分析；③屏幕OCR文字提取；④界面元素分析。\n"
        "参数说明：\n"
        "- question: 你想了解的关于屏幕的问题\n"
        "- display: 显示器选择（可选，桌面模式有效）\n\n"
        "在 Android 设备上使用 screencap 命令截屏。\n"
        "在桌面调试模式下使用 PIL ImageGrab 截屏。\n"
        "注意：Android 上截屏需要 MEDIA_PROJECTION 权限。"
    ),
    props=[
        Prop("question", PropType.STR),
        Prop("display", PropType.STR, default=""),
    ],
)
async def take_screenshot(arguments: dict) -> str:
    """截取屏幕并分析的工具函数."""
    screenshot = _get_screenshot()
    logger.info(f"使用截屏实现: {screenshot.get_mode()}")

    question = arguments.get("question", "")
    display_id = arguments.get("display", None)

    logger.info(f"截屏, 问题: {question}, 显示器: {display_id}")

    # 截屏
    success = await asyncio.to_thread(screenshot.capture, display_id)
    if not success:
        logger.error("截屏失败")
        return json.dumps({"success": False, "message": "截屏失败"})

    # 分析截图
    logger.info("截屏成功，开始分析...")
    return await asyncio.to_thread(screenshot.analyze, question)
