"""音频设备管理（Android 适配版）.

简化原版的 sounddevice 设备枚举，使用固定设备配置。
桌面端使用 sounddevice 自动检测默认设备，Android 端使用固定参数。
"""

from __future__ import annotations

import sys
from dataclasses import dataclass

from backend.constants.constants import AudioConfig
from backend.log import get_logger
from backend.utils.config_manager import ConfigManager

logger = get_logger()


@dataclass
class DeviceConfig:
    """音频设备配置."""

    input_device_id: int
    output_device_id: int
    input_sample_rate: int
    output_sample_rate: int
    input_channels: int
    output_channels: int
    input_frame_size: int
    output_frame_size: int


class AudioDeviceManager:
    """音频设备管理器.

    桌面端: 使用 sounddevice 检测默认设备
    Android 端: 使用固定配置（内置麦克风/扬声器）
    """

    def __init__(self, config_manager: ConfigManager):
        self._config = config_manager

    def load_or_detect_devices(self) -> DeviceConfig:
        """加载或自动检测音频设备."""
        AudioConfig.reload()
        frame_duration_ms = AudioConfig.FRAME_DURATION

        if self._is_android():
            return self._get_android_device_config(frame_duration_ms)

        return self._detect_desktop_devices(frame_duration_ms)

    def _is_android(self) -> bool:
        """检测是否为 Android 平台."""
        return hasattr(sys, 'getandroidapilevel') or \
               'ANDROID_ARGUMENT' in __import__('os').environ

    def _get_android_device_config(self, frame_duration_ms: int) -> DeviceConfig:
        """Android 固定设备配置."""
        input_rate = AudioConfig.INPUT_SAMPLE_RATE
        output_rate = AudioConfig.OUTPUT_SAMPLE_RATE
        config = DeviceConfig(
            input_device_id=-1,
            output_device_id=-1,
            input_sample_rate=input_rate,
            output_sample_rate=output_rate,
            input_channels=1,
            output_channels=1,
            input_frame_size=int(input_rate * frame_duration_ms / 1000),
            output_frame_size=int(output_rate * frame_duration_ms / 1000),
        )
        logger.info(
            f"Android 设备配置: 输入 {input_rate}Hz/1ch, "
            f"输出 {output_rate}Hz/1ch, 帧长 {frame_duration_ms}ms"
        )
        return config

    def _detect_desktop_devices(self, frame_duration_ms: int) -> DeviceConfig:
        """桌面端自动检测音频设备."""
        try:
            import sounddevice as sd
        except ImportError:
            logger.warning("sounddevice 不可用，使用默认设备配置")
            return self._get_default_device_config(frame_duration_ms)

        try:
            default_input = sd.query_devices(kind='input')
            default_output = sd.query_devices(kind='output')

            input_rate = int(default_input['default_samplerate'])
            output_rate = int(default_output['default_samplerate'])
            input_channels = default_input['max_input_channels']
            output_channels = default_output['max_output_channels']

            config = DeviceConfig(
                input_device_id=default_input['index'],
                output_device_id=default_output['index'],
                input_sample_rate=input_rate,
                output_sample_rate=output_rate,
                input_channels=min(input_channels, 2),
                output_channels=min(output_channels, 2),
                input_frame_size=int(input_rate * frame_duration_ms / 1000),
                output_frame_size=int(output_rate * frame_duration_ms / 1000),
            )
            logger.info(
                f"桌面设备: 输入 '{default_input['name']}' "
                f"{input_rate}Hz/{input_channels}ch, "
                f"输出 '{default_output['name']}' "
                f"{output_rate}Hz/{output_channels}ch"
            )
            return config

        except Exception as e:
            logger.warning(f"检测音频设备失败: {e}，使用默认配置")
            return self._get_default_device_config(frame_duration_ms)

    def _get_default_device_config(self, frame_duration_ms: int) -> DeviceConfig:
        """默认设备配置（兜底方案）."""
        return DeviceConfig(
            input_device_id=-1,
            output_device_id=-1,
            input_sample_rate=16000,
            output_sample_rate=24000,
            input_channels=1,
            output_channels=1,
            input_frame_size=int(16000 * frame_duration_ms / 1000),
            output_frame_size=int(24000 * frame_duration_ms / 1000),
        )
