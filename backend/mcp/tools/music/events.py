"""音乐播放器事件数据类.

用于 EventBus 上传递音乐控制请求、状态变化和歌词更新。
"""

from dataclasses import dataclass, field


@dataclass
class MusicControlRequest:
    """音乐控制请求（暂停/恢复）。

    Attributes:
        source: 请求来源，如 "tts"（TTS 触发的暂停）、"manual"（用户手动）
    """

    source: str = "manual"


@dataclass
class MusicStateData:
    """音乐播放状态变化数据.

    Attributes:
        state: 播放状态 ("playing", "paused", "stopped", "completed")
        song: 歌曲名称
        position: 当前播放位置（秒）
        duration: 总时长（秒）
        pause_source: 暂停来源（仅 state="paused" 时有值）
    """

    state: str = "stopped"
    song: str = ""
    position: float = 0.0
    duration: float = 0.0
    pause_source: str | None = None


@dataclass
class MusicLyricsData:
    """歌词更新数据.

    Attributes:
        text: 歌词文本（含时间标签）
        time_sec: 歌词对应的时间戳（秒）
        song_id: 歌曲 ID
    """

    text: str = ""
    time_sec: float = 0.0
    song_id: str = ""
