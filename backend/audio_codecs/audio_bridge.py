"""音频 I/O 桥接层.

提供统一的音频 I/O 接口，按平台自动选择实现:
- 桌面端 (Windows/macOS/Linux): sounddevice (PortAudio)
- Android Termux: sounddevice (pkg install portaudio 后可用)
- Android 原生 (Chaquopy/JNI): 原生 AudioRecord/AudioTrack (TODO)
"""

import sys
from typing import Callable, Optional

import numpy as np

from backend.log import get_logger
from backend.utils.audio_device import DeviceConfig

logger = get_logger()


def _is_android() -> bool:
    """检测是否运行在 Android 平台."""
    return hasattr(sys, 'getandroidapilevel') or \
           'ANDROID_ARGUMENT' in __import__('os').environ


def _is_termux() -> bool:
    """检测是否运行在 Termux 环境.

    Termux 也是 Android，但拥有完整的 Linux 用户空间，
    portaudio + sounddevice 可以正常工作。
    """
    if not _is_android():
        return False
    try:
        import os
        return os.path.exists('/data/data/com.termux')
    except Exception:
        return False


def _sounddevice_available() -> bool:
    """检查 sounddevice 是否可用."""
    try:
        import sounddevice  # noqa: F401
        return True
    except (ImportError, OSError):
        return False


class AudioBridge:
    """音频 I/O 桥接.

    自动检测运行环境并选择最佳音频后端:
    1. sounddevice (桌面端 & Termux) — 优先
    2. Android 原生 (Chaquopy/JNI) — 备选

    对外接口与 py-xiaozhi 的 AudioStreamManager 保持一致，
    使 AudioCodec 无需修改即可使用。
    """

    def __init__(self, device_config: DeviceConfig):
        self._config = device_config
        self._input_stream = None
        self._output_stream = None
        self._stopped = True
        self._is_android = _is_android()
        self._is_termux = _is_termux()

    def create_streams(
        self,
        input_callback: Callable,
        output_callback: Callable,
    ) -> None:
        """创建音频输入/输出流.

        Args:
            input_callback: 输入回调 (indata, frames, time_info, status)
            output_callback: 输出回调 (outdata, frames, time_info, status)
        """
        # Termux 或有 sounddevice 的环境 — 统一走 desktop 路径
        if self._is_termux or _sounddevice_available():
            self._create_desktop_streams(input_callback, output_callback)
        elif self._is_android:
            # Android 原生环境 (Chaquopy/JNI)，无 sounddevice
            self._create_android_streams(input_callback, output_callback)
        else:
            self._create_desktop_streams(input_callback, output_callback)

    def start(self) -> None:
        """启动音频流."""
        self._stopped = False
        if self._input_stream:
            self._input_stream.start()
        if self._output_stream:
            self._output_stream.start()
        logger.info("音频流已启动")

    def stop(self) -> None:
        """停止音频流（幂等）."""
        if self._stopped:
            return
        self._stopped = True
        try:
            if self._input_stream:
                self._input_stream.stop()
                self._input_stream.close()
                self._input_stream = None
        except Exception as e:
            logger.warning(f"停止输入流失败: {e}")
        try:
            if self._output_stream:
                self._output_stream.stop()
                self._output_stream.close()
                self._output_stream = None
        except Exception as e:
            logger.warning(f"停止输出流失败: {e}")
        logger.info("音频流已停止")

    def reinitialize_stream(
        self,
        is_input: bool,
        input_callback: Optional[Callable] = None,
        output_callback: Optional[Callable] = None,
    ) -> bool:
        """重新初始化单个流（热插拔支持）."""
        try:
            if is_input:
                if self._input_stream:
                    self._input_stream.stop()
                    self._input_stream.close()
                if input_callback:
                    import sounddevice as sd
                    blocksize = int(
                        self._config.input_sample_rate
                        * 20
                        / 1000
                    )
                    self._input_stream = sd.InputStream(
                        device=self._config.input_device_id,
                        samplerate=self._config.input_sample_rate,
                        channels=self._config.input_channels,
                        dtype=np.float32,
                        blocksize=blocksize,
                        callback=input_callback,
                        latency="low",
                    )
                    if not self._stopped:
                        self._input_stream.start()
            else:
                if self._output_stream:
                    self._output_stream.stop()
                    self._output_stream.close()
                if output_callback:
                    import sounddevice as sd
                    blocksize = int(
                        self._config.output_sample_rate
                        * 20
                        / 1000
                    )
                    self._output_stream = sd.OutputStream(
                        device=self._config.output_device_id,
                        samplerate=self._config.output_sample_rate,
                        channels=self._config.output_channels,
                        dtype=np.float32,
                        blocksize=blocksize,
                        callback=output_callback,
                        latency="low",
                    )
                    if not self._stopped:
                        self._output_stream.start()
            return True
        except Exception as e:
            logger.error(f"重新初始化流失败: {e}")
            return False

    # ==================== sounddevice 实现（桌面 + Termux） ====================

    def _create_desktop_streams(
        self,
        input_callback: Callable,
        output_callback: Callable,
    ) -> None:
        """使用 sounddevice 创建音频流（桌面端 & Termux 通用）."""
        try:
            import sounddevice as sd
        except ImportError:
            logger.error(
                "sounddevice 不可用。\n"
                "  桌面端: pip install sounddevice\n"
                "  Termux: pkg install portaudio && pip install sounddevice"
            )
            raise

        frame_duration_ms = 20

        input_blocksize = int(
            self._config.input_sample_rate * frame_duration_ms / 1000
        )
        output_blocksize = int(
            self._config.output_sample_rate * frame_duration_ms / 1000
        )

        platform_label = "Termux" if self._is_termux else "桌面"
        logger.info(
            f"创建 {platform_label} 音频流: "
            f"输入 {self._config.input_sample_rate}Hz/{self._config.input_channels}ch "
            f"(block={input_blocksize}), "
            f"输出 {self._config.output_sample_rate}Hz/{self._config.output_channels}ch "
            f"(block={output_blocksize})"
        )

        # 输入流（Termux/Android 可能无法访问麦克风，前端 App 负责录音）
        try:
            self._input_stream = sd.InputStream(
                device=self._config.input_device_id,
                samplerate=self._config.input_sample_rate,
                channels=self._config.input_channels,
                dtype=np.float32,
                blocksize=input_blocksize,
                callback=input_callback,
                latency="low",
            )
        except Exception as e:
            logger.warning(f"输入流创建失败（麦克风不可用）: {e}")
            logger.info("前端 App 将负责麦克风录音，后端仅处理输出播放")
            self._input_stream = None

        # 输出流（扬声器播放，必须成功）
        self._output_stream = sd.OutputStream(
            device=self._config.output_device_id,
            samplerate=self._config.output_sample_rate,
            channels=self._config.output_channels,
            dtype=np.float32,
            blocksize=output_blocksize,
            callback=output_callback,
            latency="low",
        )

    # ==================== Android 原生实现（Chaquopy / JNI） ====================

    def _create_android_streams(
        self,
        input_callback: Callable,
        output_callback: Callable,
    ) -> None:
        """使用 Android AudioRecord/AudioTrack 创建音频流.

        仅在 Chaquopy/JNI 环境下使用（APK 内嵌模式）。
        Termux 环境不会走到这里（已走 sounddevice 路径）。
        """
        raise NotImplementedError(
            "Android 原生音频桥接尚未实现。\n"
            "如果在 Termux 中运行，请执行:\n"
            "  pkg install portaudio\n"
            "  pip install sounddevice"
        )
