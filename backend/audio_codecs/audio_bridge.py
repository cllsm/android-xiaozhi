"""音频 I/O 桥接层.

替代 sounddevice 的 AudioStreamManager，提供统一的音频 I/O 接口。
桌面端使用 sounddevice（PortAudio），Android 端将替换为原生 AudioRecord/AudioTrack。
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


class AudioBridge:
    """音频 I/O 桥接.

    桌面端：封装 sounddevice（PortAudio）
    Android 端：封装 AudioRecord/AudioTrack（通过 JNI/UTS）

    对外接口与 py-xiaozhi 的 AudioStreamManager 保持一致，
    使 AudioCodec 无需修改即可使用。
    """

    def __init__(self, device_config: DeviceConfig):
        self._config = device_config
        self._input_stream = None
        self._output_stream = None
        self._stopped = True
        self._is_android = _is_android()

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
        if self._is_android:
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
                # 重新创建输入流
                if input_callback:
                    import sounddevice as sd
                    blocksize = int(
                        self._config.input_sample_rate
                        * 20  # FRAME_DURATION
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

    # ==================== 桌面端实现 ====================

    def _create_desktop_streams(
        self,
        input_callback: Callable,
        output_callback: Callable,
    ) -> None:
        """使用 sounddevice 创建桌面端音频流."""
        try:
            import sounddevice as sd
        except ImportError:
            logger.error("桌面端需要 sounddevice: pip install sounddevice")
            raise

        # 计算块大小
        # 使用 20ms 帧长（桌面端 x86）
        frame_duration_ms = 20

        input_blocksize = int(
            self._config.input_sample_rate * frame_duration_ms / 1000
        )
        output_blocksize = int(
            self._config.output_sample_rate * frame_duration_ms / 1000
        )

        logger.info(
            f"创建桌面音频流: "
            f"输入 {self._config.input_sample_rate}Hz/{self._config.input_channels}ch "
            f"(block={input_blocksize}), "
            f"输出 {self._config.output_sample_rate}Hz/{self._config.output_channels}ch "
            f"(block={output_blocksize})"
        )

        self._input_stream = sd.InputStream(
            device=self._config.input_device_id,
            samplerate=self._config.input_sample_rate,
            channels=self._config.input_channels,
            dtype=np.float32,
            blocksize=input_blocksize,
            callback=input_callback,
            latency="low",
        )

        self._output_stream = sd.OutputStream(
            device=self._config.output_device_id,
            samplerate=self._config.output_sample_rate,
            channels=self._config.output_channels,
            dtype=np.float32,
            blocksize=output_blocksize,
            callback=output_callback,
            latency="low",
        )

    # ==================== Android 端实现（TODO） ====================

    def _create_android_streams(
        self,
        input_callback: Callable,
        output_callback: Callable,
    ) -> None:
        """使用 Android AudioRecord/AudioTrack 创建音频流.

        TODO: 通过 UTS 原生插件或 Chaquopy JNI 实现
        """
        raise NotImplementedError(
            "Android 原生音频桥接尚未实现，请使用桌面模式开发调试"
        )
