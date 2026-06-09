"""音乐播放器工具包."""

from .music_player import get_music_player_instance
from . import _tools  # noqa: F401  触发 @mcp_tool 装饰器注册

__all__ = ["get_music_player_instance"]
