"""Android 音量控制 MCP 工具（装饰器注册）.

支持两种运行模式：
- Android 设备：通过原生桥接调用 AudioManager API
- 桌面调试：使用 sounddevice（如果可用）或返回模拟数据
"""

import asyncio
import json
from typing import Any

from backend.log import get_logger
from backend.mcp.decorators import Prop, PropType, mcp_tool

from .volume_android import AndroidVolumeController

logger = get_logger()

# 模块级音量控制器单例
_volume_controller: AndroidVolumeController | None = None


def _get_volume_controller() -> AndroidVolumeController:
    """获取或初始化音量控制器单例."""
    global _volume_controller
    if _volume_controller is None:
        _volume_controller = AndroidVolumeController()
    return _volume_controller


# ----- 工具实现 -----


async def _set_volume(args: dict[str, Any]) -> bool:
    """设置音量.

    Args:
        args: 包含 volume (0-100) 的参数字典

    Returns:
        是否设置成功
    """
    try:
        volume = args["volume"]
        logger.info(f"[VolumeTools] 设置音量到 {volume}")

        # 验证音量范围
        if not (0 <= volume <= 100):
            logger.warning(f"[VolumeTools] 音量值超出范围: {volume}")
            return False

        controller = _get_volume_controller()
        success = await asyncio.to_thread(controller.set_volume, volume)

        if success:
            logger.info(f"[VolumeTools] 音量设置成功: {volume}")
        else:
            logger.warning("[VolumeTools] 音量设置失败")
        return success

    except KeyError:
        logger.error("[VolumeTools] 缺少 volume 参数")
        return False
    except Exception as e:
        logger.error(f"[VolumeTools] 设置音量失败: {e}", exc_info=True)
        return False


async def _get_volume(args: dict[str, Any]) -> int:
    """获取当前音量.

    Args:
        args: 参数字典（无必要参数）

    Returns:
        当前音量值 (0-100)
    """
    try:
        logger.info("[VolumeTools] 获取当前音量")
        controller = _get_volume_controller()
        current_volume = await asyncio.to_thread(controller.get_volume)
        logger.info(f"[VolumeTools] 当前音量: {current_volume}")
        return current_volume

    except Exception as e:
        logger.error(f"[VolumeTools] 获取音量失败: {e}", exc_info=True)
        return AndroidVolumeController.DEFAULT_VOLUME


async def _get_volume_status(args: dict[str, Any]) -> str:
    """获取音频状态（音量/静音/可用性）.

    Args:
        args: 参数字典（无必要参数）

    Returns:
        JSON 格式的状态信息
    """
    try:
        controller = _get_volume_controller()
        current_volume = await asyncio.to_thread(controller.get_volume)
        status = {
            "volume": current_volume,
            "muted": current_volume == 0,
            "available": controller.is_available(),
            "mode": controller.get_mode(),
        }
    except Exception as e:
        logger.warning(f"[VolumeTools] 获取音量状态失败: {e}")
        status = {
            "volume": 50,
            "muted": False,
            "available": False,
            "mode": "fallback",
            "error": str(e),
        }

    return json.dumps(status, ensure_ascii=False)


# ----- MCP 工具注册 -----


@mcp_tool(
    name="self.audio_speaker.set_volume",
    description=(
        "Set the system speaker volume to an absolute value (0-100).\n"
        "Use when user mentions: volume, sound, louder, quieter, mute, unmute, adjust volume.\n"
        "Examples: 'set volume to 50', 'turn volume up', 'make it louder', 'mute', "
        "'音量设为50', '调大声音', '声音小一点', '静音'.\n"
        "Parameter:\n"
        "- volume: Integer (0-100) representing the target volume level. Set to 0 for mute."
    ),
    props=[Prop("volume", PropType.INT, min_val=0, max_val=100)],
)
async def tool_set_volume(args):
    """设置音量工具."""
    return await _set_volume(args)


@mcp_tool(
    name="self.audio_speaker.get_volume",
    description=(
        "Get the current system speaker volume level.\n"
        "Use when user asks about: current volume, volume level, how loud, what's the volume.\n"
        "Examples: 'what is the current volume?', 'how loud is it?', 'check volume level', "
        "'现在音量多少?', '查看音量', '音量是多少'.\n"
        "Returns: Integer (0-100) representing the current volume level."
    ),
)
async def tool_get_volume(args):
    """获取音量工具."""
    return await _get_volume(args)


@mcp_tool(
    name="self.audio_speaker.get_volume_status",
    description=(
        "Get detailed speaker volume status including whether audio output is muted and "
        "whether the volume controller is available. Returns a JSON payload "
        "with fields: volume (0-100), muted (bool), available (bool), mode (string)."
    ),
)
async def tool_get_volume_status(args):
    """获取音量状态工具."""
    return await _get_volume_status(args)
