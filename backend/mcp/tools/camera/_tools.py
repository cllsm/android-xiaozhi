"""Android 摄像头 MCP 工具（装饰器注册）.

支持两种运行模式：
- Android 设备：通过 UTS 原生插件调用 Camera2 API
- 桌面调试：使用 OpenCV（如果可用）或返回模拟数据
"""

import asyncio
import json

from backend.log import get_logger
from backend.mcp.decorators import Prop, PropType, mcp_tool

from .camera_android import AndroidCamera

logger = get_logger()

# 模块级摄像头单例
_camera: AndroidCamera | None = None


def get_camera_instance() -> AndroidCamera:
    """获取或初始化摄像头单例."""
    global _camera
    if _camera is None:
        _camera = AndroidCamera()
    return _camera


@mcp_tool(
    name="take_photo",
    description=(
        "拍照识图工具。当用户提到：拍照、拍张照、照张相、看一下、看看、帮我看、这是什么、识别、"
        "识图、看图、图片、照片、帮我瞧瞧 时调用本工具。\n"
        "功能：拍照并分析图片内容，回答用户关于图片的问题。\n"
        "使用场景：\n"
        "1. 用户要求拍照看东西 (例如: '帮我看看这是什么', '拍个照', '看看前面是什么')\n"
        "2. 物体/场景识别 ('这是什么东西', '帮我认一下', '识别一下')\n"
        "3. 文字识别OCR ('读一下上面的字', '提取文字', '这上面写的什么')\n"
        "4. 图片问答 ('图里有几个人', '这个是什么颜色', '上面有什么内容')\n\n"
        "参数说明：\n"
        "- question: 字符串类型，用户想了解的关于图片的问题\n\n"
        "English: Take a photo and explain it. Use this tool after the user asks you to see something.\n"
        "Args: question - The question that you want to ask about the photo.\n"
        "Return: A JSON object that provides the photo information.\n"
        "Examples: '帮我看看这是什么', '拍个照', '看看前面', 'take a photo', 'what is this'."
    ),
    props=[Prop("question", PropType.STR)],
)
async def take_photo(arguments: dict) -> str:
    """拍照并分析的工具函数."""
    camera = get_camera_instance()
    logger.info(f"使用摄像头实现: {camera.get_mode()}")

    question = arguments.get("question", "")
    logger.info(f"拍照，问题: {question}")

    # 拍照（可能涉及阻塞 I/O，放入线程池）
    success = await asyncio.to_thread(camera.capture)
    if not success:
        logger.error("拍照失败")
        return json.dumps({"success": False, "message": "拍照失败"})

    # 分析图片
    logger.info("拍照成功，开始分析...")
    return await asyncio.to_thread(camera.analyze, question)
