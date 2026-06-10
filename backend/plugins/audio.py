"""音频插件.

负责音频采集、编码、播放和发送。
支持两种播放模式：
- backend（默认）: 后端 sounddevice 本地播放
- frontend: 通过 WebSocket 将 PCM 推送给前端播放
"""

import asyncio
import os
from typing import TYPE_CHECKING, Optional

import numpy as np

from backend.audio_codecs.audio_codec import AudioCodec
from backend.log import get_logger
from backend.plugins.base import Plugin
from backend.utils.config_manager import ConfigManager

if TYPE_CHECKING:
    from backend.bootstrap.protocols import PluginCommands, PluginContext

logger = get_logger()

MAX_CONCURRENT_AUDIO_SENDS = 4


class AudioPlugin(Plugin):
    name = "audio"
    priority = 10  # 最高优先级，其他插件依赖 audio_codec

    def __init__(self) -> None:
        super().__init__()
        self.codec: Optional[AudioCodec] = None
        self._send_sem = asyncio.Semaphore(MAX_CONCURRENT_AUDIO_SENDS)
        self._in_silence_period = False
        self._playback_mode: str = "backend"  # 缓存播放模式，避免频繁读配置

    async def setup(self, ctx: "PluginContext", cmd: "PluginCommands") -> None:
        await super().setup(ctx, cmd)

        if os.getenv("XIAOZHI_DISABLE_AUDIO") == "1":
            return

        try:
            self.codec = AudioCodec()
            await self.codec.initialize()
            self.codec.set_encoded_callback(self._on_encoded_audio)

            from backend.mcp.tools.music.music_player import get_music_player_instance

            music_player = get_music_player_instance()
            music_player.set_audio_codec(self.codec)

            # 订阅配置变更事件
            from backend.core.event_bus import Events
            ctx.event_bus.on(Events.CONFIG_CHANGED, self._on_config_changed)

        except Exception as e:
            logger.error(f"音频插件初始化失败: {e}", exc_info=True)
            self.codec = None

    async def _on_config_changed(self, data=None):
        """配置变更时重新加载音频设备."""
        if self.codec:
            logger.info("AudioPlugin: 收到配置变更事件，重新加载音频设备")
            await self.codec.reload_devices()

    async def on_device_state_changed(self, state):
        """设备状态变化时处理."""
        if not self.codec:
            return

        from backend.constants.constants import DeviceState

        if state == DeviceState.LISTENING:
            self._in_silence_period = True
            try:
                await asyncio.sleep(0.2)
            finally:
                self._in_silence_period = False

    async def on_incoming_json(self, message) -> None:
        """处理 TTS 事件."""
        if not isinstance(message, dict):
            return

        try:
            if message.get("type") == "tts":
                state = message.get("state")
                if state == "start":
                    await self._pause_music_for_tts()
                elif state == "stop":
                    await self._resume_music_after_tts()
        except Exception as e:
            logger.error(f"处理 TTS 事件失败: {e}", exc_info=True)

    async def on_incoming_audio(self, data: bytes) -> None:
        """接收音频数据并根据播放模式分流.

        - backend 模式: sounddevice 本地播放（默认）
        - frontend 模式: 通过 WebSocket 推送 PCM 给前端播放
        """
        if not self.codec:
            return

        try:
            mode = self._get_playback_mode()

            if mode == "frontend":
                # 前端播放：解码 Opus → float32 PCM → Int16 → WebSocket 推送
                pcm_float32 = self.codec.decode_to_pcm(data)
                if pcm_float32 is not None:
                    await self._broadcast_to_frontend(pcm_float32)
            else:
                # 后端播放：sounddevice 本地播放（默认）
                await self.codec.write_audio(data)

        except Exception as e:
            logger.debug(f"音频处理失败: {e}")

    def _get_playback_mode(self) -> str:
        """获取当前播放模式（从配置读取，带缓存）.

        Returns:
            'backend' 或 'frontend'
        """
        try:
            config_mgr = ConfigManager.get_instance()
            mode = config_mgr.get_config("APP_OPTIONS.AUDIO_PLAYBACK_MODE", "backend")
            if mode in ("frontend", "backend"):
                return mode
        except Exception:
            pass
        return "backend"

    async def _broadcast_to_frontend(self, pcm_float32: np.ndarray) -> None:
        """将 PCM float32 数据转换为 Int16 并通过 WebSocket 广播给前端.

        Args:
            pcm_float32: numpy float32 PCM 数据（单声道，24kHz）
        """
        local_server = getattr(self._ctx, "local_server", None)
        if not local_server:
            return

        # float32 [-1, 1] → Int16 [-32768, 32767]
        pcm_int16 = (np.clip(pcm_float32, -1.0, 1.0) * 32767).astype(np.int16)
        pcm_bytes = pcm_int16.tobytes()

        await local_server.broadcast_audio(pcm_bytes)

    async def _pause_music_for_tts(self):
        """TTS 开始时暂停音乐."""
        try:
            from backend.core.event_bus import Events
            from backend.mcp.tools.music.events import MusicControlRequest

            logger.info("TTS 开始，发送音乐暂停请求")
            await self._ctx.event_bus.emit(
                Events.MUSIC_PAUSE_REQUEST, MusicControlRequest(source="tts")
            )
        except Exception as e:
            logger.warning(f"发送音乐暂停请求失败: {e}")

    async def _resume_music_after_tts(self):
        """TTS 结束后恢复音乐."""
        try:
            from backend.core.event_bus import Events
            from backend.mcp.tools.music.events import MusicControlRequest

            logger.info("TTS 播放完成，发送音乐恢复请求")
            await self._ctx.event_bus.emit(
                Events.MUSIC_RESUME_REQUEST, MusicControlRequest(source="tts")
            )
        except Exception as e:
            logger.error(f"发送音乐恢复请求失败: {e}", exc_info=True)

    def register_resources(self, pool) -> None:
        codec = self.codec
        if codec:

            async def _cleanup():
                """音频编解码器完整清理"""
                import gc

                try:
                    from backend.mcp.tools.music.music_player import get_music_player_instance

                    try:
                        music_player = get_music_player_instance()
                        if music_player.is_playing:
                            await music_player.stop()
                        if music_player.decoder:
                            await music_player.decoder.stop()
                            music_player.decoder = None
                        music_player.set_audio_codec(None)
                    except Exception as e:
                        logger.debug(f"清理音乐播放器失败: {e}")
                except Exception:
                    pass
                gc.collect()
                await codec.close()

            pool.register("audio.codec", _cleanup)

    def _on_encoded_audio(self, encoded_data: bytes) -> None:
        """音频编码回调（从音频线程调用）."""
        try:
            if not self._cmd:
                return
            self._cmd.schedule_command_nowait(self._send_audio_async, encoded_data)
        except Exception as e:
            logger.error(f"调度音频发送失败: {e}")

    async def _send_audio_async(self, encoded_data: bytes) -> None:
        """异步发送音频数据."""
        async with self._send_sem:
            try:
                if not self._ctx.is_audio_channel_opened():
                    return
                if self._should_send_microphone_audio():
                    await self._cmd.send_audio(encoded_data)
            except Exception as e:
                logger.error(f"发送音频数据失败: {e}")

    def _should_send_microphone_audio(self) -> bool:
        """判断是否应该发送麦克风音频."""
        try:
            if self._in_silence_period:
                return False
            return self._ctx.should_capture_audio()
        except Exception:
            return False
