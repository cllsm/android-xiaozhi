"""激活验证码播报模块.

使用预录制音频播报激活验证码，仅在设备激活流程中使用。
通过 ffmpeg 解码 .ogg 音频文件，再通过 sounddevice 播放。
在 Termux 环境下需安装 portaudio（pkg install portaudio）和 ffmpeg（pkg install ffmpeg）。
"""

import subprocess
import sys
import threading
from pathlib import Path

import numpy as np

from backend.log import get_logger
from backend.utils.resource_finder import get_app_root

logger = get_logger()

# 音频资源目录
_ASSETS_DIR = get_app_root() / "assets" / "sounds"


class ActivationAnnouncer:
    """激活验证码播报器.

    通过 ffmpeg + sounddevice 播放预录制的语音文件来播报验证码。
    在 Termux/Android 环境下，音频通过 sounddevice + portaudio 输出到设备扬声器。
    """

    def __init__(self, locale: str = "zh-CN"):
        self._locale = locale
        self._process: subprocess.Popen | None = None
        self._stop_flag = threading.Event()
        self._play_thread: threading.Thread | None = None

    def _get_sound_path(self, name: str) -> Path | None:
        """获取音效文件路径."""
        sound_file = _ASSETS_DIR / self._locale / f"{name}.ogg"
        if sound_file.exists():
            return sound_file
        # 回退到 zh-CN
        if self._locale != "zh-CN":
            fallback = _ASSETS_DIR / "zh-CN" / f"{name}.ogg"
            if fallback.exists():
                return fallback
        return None

    def _decode_with_ffmpeg(self, file_path: Path) -> np.ndarray | None:
        """使用 ffmpeg 解码音频文件.

        Args:
            file_path: .ogg 音频文件路径

        Returns:
            float32 numpy 数组，或解码失败时返回 None
        """
        try:
            cmd = [
                "ffmpeg",
                "-i", str(file_path),
                "-f", "s16le",      # 16位小端 PCM
                "-ar", "24000",     # 采样率
                "-ac", "1",         # 单声道
                "-loglevel", "error",
                "-"
            ]

            popen_kw = {}
            if sys.platform == "win32":
                popen_kw["creationflags"] = subprocess.CREATE_NO_WINDOW

            self._process = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                **popen_kw,
            )

            stdout, stderr = self._process.communicate(timeout=10)
            self._process = None

            if stderr:
                logger.debug(f"ffmpeg stderr: {stderr.decode('utf-8', errors='ignore')}")

            if stdout:
                # 转换为 float32
                audio = np.frombuffer(stdout, dtype=np.int16).astype(np.float32) / 32768.0
                return audio

        except subprocess.TimeoutExpired:
            if self._process:
                self._process.kill()
                self._process = None
            logger.warning(f"ffmpeg 解码超时: {file_path}")
        except FileNotFoundError:
            logger.error("ffmpeg 未安装（Termux 下请运行 pkg install ffmpeg）")
        except Exception as e:
            logger.error(f"解码失败 {file_path}: {e}")

        return None

    def _play_sounds(self, names: list[str]):
        """播放音效序列（在工作线程中执行）."""
        try:
            import sounddevice as sd
        except ImportError:
            logger.warning("sounddevice 未安装，无法播报验证码（Termux 下请运行 pkg install portaudio）")
            return

        sample_rate = 24000

        for name in names:
            if self._stop_flag.is_set():
                logger.debug("播报被中断")
                break

            sound_path = self._get_sound_path(name)
            if not sound_path:
                logger.warning(f"音效文件不存在: {name}")
                continue

            audio = self._decode_with_ffmpeg(sound_path)
            if audio is None or self._stop_flag.is_set():
                continue

            try:
                sd.play(audio, sample_rate)
                # 分段等待，便于响应中断
                while sd.get_stream().active:
                    if self._stop_flag.is_set():
                        sd.stop()
                        break
                    self._stop_flag.wait(0.05)
            except Exception as e:
                logger.error(f"播放失败: {e}")

    def announce(self, code: str):
        """播报验证码（非阻塞）.

        在后台线程中依次播放 "activation" 提示音和每位数字的语音。

        Args:
            code: 验证码字符串，如 "123456"
        """
        if not code or not code.isdigit():
            logger.warning(f"无效的验证码: {code}")
            return

        # 停止之前的播报
        self.stop()

        # 构建播放序列: 激活提示 + 各个数字
        sounds = ["activation"] + list(code)

        logger.info(f"播报验证码: {code}")

        self._stop_flag.clear()
        self._play_thread = threading.Thread(
            target=self._play_sounds,
            args=(sounds,),
            daemon=True,
            name="ActivationAnnouncer"
        )
        self._play_thread.start()

    def stop(self):
        """停止播报."""
        self._stop_flag.set()

        # 停止 ffmpeg 进程
        if self._process:
            try:
                self._process.kill()
            except Exception as e:
                logger.debug(f"终止播报进程失败: {e}")
            self._process = None

        # 停止音频播放
        try:
            import sounddevice as sd
            sd.stop()
        except Exception as e:
            logger.debug(f"停止音频播放失败: {e}")

        # 等待线程结束
        if self._play_thread and self._play_thread.is_alive():
            self._play_thread.join(timeout=1)

        self._play_thread = None


# 全局实例
_announcer: ActivationAnnouncer | None = None


def announce_activation_code(code: str, locale: str = "zh-CN"):
    """播报激活验证码.

    Args:
        code: 验证码字符串
        locale: 语言代码（默认 zh-CN）
    """
    global _announcer
    if _announcer is None:
        _announcer = ActivationAnnouncer(locale)
    _announcer.announce(code)


def stop_announcement():
    """停止验证码播报."""
    global _announcer
    if _announcer:
        _announcer.stop()
