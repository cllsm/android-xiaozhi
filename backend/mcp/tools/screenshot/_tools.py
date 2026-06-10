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
        "截取当前屏幕并使用视觉AI分析屏幕内容。\n"
        "调用时机（满足任意一条即调用）：\n"
        "- 用户提到截屏、截图、看看屏幕、看看这个\n"
        "- 用户想了解屏幕上的内容：在干嘛、显示什么、写了什么、这是哪\n"
        "- 聊天相关：聊天记录、怎么回、说了什么、对方回什么、群里消息\n"
        "- 帮助类：帮我看看、帮我读一下、念一下、上面写的啥\n"
        "- 问题类：报错了、出问题、怎么回事、卡住了、打不开\n"
        "- 操作类：怎么操作、怎么用、点哪、在哪设置、下一步\n"
        "- 任何隐含需要\"看到屏幕才能回答\"的问题\n\n"
        "参数说明：\n"
        "- question: 告诉视觉AI应该关注什么。请根据用户意图构造清晰的指令，"
        "不要直接传用户原话。例如：\n"
        "  · 用户说'看看我和小明的聊天记录我该怎么回' → "
        "question='看看我和小明的聊天记录我该怎么回他'\n"
        "  · 用户说'帮我看看屏幕' → question='描述当前屏幕状态和主要内容'\n"
        "  · 用户说'这里报错了' → question='找出屏幕上的错误信息并分析原因'\n"
        "  · 用户说'这个怎么用' → question='列出屏幕上的操作按钮和使用步骤'\n"
        "  · 用户说'帮我读一下' → question='提取屏幕上所有文字内容'\n"
        "  · 用户说'他说啥了' → question='查看聊天界面，提取对方最新消息'\n"
        "- display: 显示器选择（可选，桌面模式有效）\n\n"
        "工具会自动识别场景（聊天分析/文字提取/错误诊断/操作指引/屏幕理解），"
        "并使用对应的提示词模板进行分析，确保结果精准。"
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
