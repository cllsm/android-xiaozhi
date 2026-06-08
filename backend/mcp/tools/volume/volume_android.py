"""Android 音量控制器.

支持两种运行模式：
- Android 设备：通过原生桥接调用 AudioManager API
- 桌面调试：使用 sounddevice（如果可用）或返回模拟数据

在 Android 上通过 subprocess 调用 native bridge 命令来控制系统音量。
实际 Android 设备上会通过 UTS 原生插件调用 AudioManager API。
"""

import json
import platform as plat
import subprocess
from typing import Any

from backend.log import get_logger
from backend.utils.resource_finder import get_platform_info

logger = get_logger()


class AndroidVolumeController:
    """Android 音量控制器.

    根据运行环境自动选择实现：
    - Android ARM 设备：通过 native bridge 调用原生 API
    - 桌面调试环境：使用 sounddevice 或模拟数据
    """

    # 默认音量常量
    DEFAULT_VOLUME = 70

    # Android 音频流类型（对应 AudioManager.STREAM_*）
    STREAM_MUSIC = 3  # 媒体音量

    def __init__(self):
        """初始化音量控制器."""
        self._mode = self._detect_mode()
        self._available = self._check_availability()
        logger.info(f"[VolumeController] 初始化完成, 模式: {self._mode}, 可用: {self._available}")

    def _detect_mode(self) -> str:
        """检测当前运行模式.

        Returns:
            "android" - Android 设备
            "desktop_sounddevice" - 桌面环境（使用 sounddevice）
            "fallback" - 兜底模式（模拟数据）
        """
        plat_dir, _ = get_platform_info()
        if plat_dir == "android":
            return "android"

        # 桌面环境：尝试使用 sounddevice
        try:
            import sounddevice  # noqa: F401

            return "desktop_sounddevice"
        except ImportError:
            logger.debug("sounddevice 不可用，使用 fallback 模式")
            return "fallback"

    def _check_availability(self) -> bool:
        """检查音量控制功能是否可用."""
        if self._mode == "android":
            return True
        elif self._mode == "desktop_sounddevice":
            return True
        else:
            return False

    def is_available(self) -> bool:
        """返回音量控制器是否可用."""
        return self._available

    def get_mode(self) -> str:
        """返回当前运行模式."""
        return self._mode

    def get_volume(self) -> int:
        """获取当前音量 (0-100).

        Returns:
            音量值 0-100
        """
        try:
            if self._mode == "android":
                return self._get_volume_android()
            elif self._mode == "desktop_sounddevice":
                return self._get_volume_sounddevice()
            else:
                return self._get_volume_fallback()
        except Exception as e:
            logger.error(f"[VolumeController] 获取音量失败: {e}", exc_info=True)
            return self.DEFAULT_VOLUME

    def set_volume(self, volume: int) -> bool:
        """设置音量 (0-100).

        Args:
            volume: 目标音量 0-100

        Returns:
            是否设置成功
        """
        volume = max(0, min(100, volume))
        try:
            if self._mode == "android":
                return self._set_volume_android(volume)
            elif self._mode == "desktop_sounddevice":
                return self._set_volume_sounddevice(volume)
            else:
                return self._set_volume_fallback(volume)
        except Exception as e:
            logger.error(f"[VolumeController] 设置音量失败: {e}", exc_info=True)
            return False

    # ----- Android 原生实现 -----

    def _call_native_bridge(self, action: str, params: dict[str, Any] | None = None) -> dict:
        """调用 Android 原生桥接.

        在 Android 设备上通过 native bridge 执行原生 API 调用。

        Args:
            action: 操作名称
            params: 操作参数

        Returns:
            原生桥接返回的结果字典
        """
        payload = {
            "module": "audio",
            "action": action,
            "params": params or {},
        }

        try:
            # 通过 native bridge CLI 工具调用
            # 实际部署时使用 UTS 原生插件提供的接口
            result = subprocess.run(
                ["native-bridge", "--json", json.dumps(payload)],
                capture_output=True,
                text=True,
                timeout=5,
            )
            if result.returncode == 0 and result.stdout.strip():
                return json.loads(result.stdout.strip())
            else:
                logger.warning(f"[VolumeController] native bridge 返回错误: {result.stderr}")
                return {"success": False, "error": result.stderr}
        except FileNotFoundError:
            logger.warning("[VolumeController] native-bridge 命令不存在，尝试替代方案")
            return self._call_native_bridge_alternative(payload)
        except subprocess.TimeoutExpired:
            logger.warning("[VolumeController] native bridge 调用超时")
            return {"success": False, "error": "timeout"}
        except Exception as e:
            logger.error(f"[VolumeController] native bridge 调用失败: {e}")
            return {"success": False, "error": str(e)}

    def _call_native_bridge_alternative(self, payload: dict) -> dict:
        """替代的原生桥接调用方式.

        通过 Android am (Activity Manager) 或 service 命令调用。

        Args:
            payload: 要发送的 JSON 数据

        Returns:
            调用结果
        """
        try:
            # 使用 Android settings 命令直接设置音量
            action = payload.get("action", "")
            params = payload.get("params", {})

            if action == "get_volume":
                # 通过 content query 获取系统设置中的音量
                result = subprocess.run(
                    [
                        "content", "query",
                        "--uri", "content://settings/system",
                        "--where", "name='volume_music'",
                    ],
                    capture_output=True,
                    text=True,
                    timeout=5,
                )
                if result.returncode == 0 and result.stdout.strip():
                    return {"success": True, "volume": self._parse_settings_volume(result.stdout)}
                return {"success": False, "error": result.stderr}

            elif action == "set_volume":
                volume = params.get("volume", 50)
                result = subprocess.run(
                    [
                        "settings", "put", "system", "volume_music",
                        str(volume),
                    ],
                    capture_output=True,
                    text=True,
                    timeout=5,
                )
                return {"success": result.returncode == 0}

            elif action == "get_max_volume":
                result = subprocess.run(
                    ["settings", "get", "system", "volume_music"],
                    capture_output=True,
                    text=True,
                    timeout=5,
                )
                return {"success": True, "max_volume": 15}  # Android 默认最大值

            return {"success": False, "error": f"未知操作: {action}"}

        except Exception as e:
            logger.error(f"[VolumeController] 替代桥接调用失败: {e}")
            return {"success": False, "error": str(e)}

    def _parse_settings_volume(self, output: str) -> int:
        """解析 Android settings 输出中的音量值.

        Args:
            output: settings 命令的输出

        Returns:
            音量值 0-100
        """
        try:
            # 尝试提取数值
            import re

            match = re.search(r"value=(\d+)", output)
            if match:
                raw_value = int(match.group(1))
                # Android 音量通常是 0-15 范围，需要转换为 0-100
                # 假设最大值为 15
                return int(raw_value / 15 * 100)
        except (ValueError, IndexError):
            pass
        return self.DEFAULT_VOLUME

    def _get_volume_android(self) -> int:
        """通过 Android 原生 API 获取音量."""
        result = self._call_native_bridge("get_volume")
        if result.get("success"):
            return result.get("volume", self.DEFAULT_VOLUME)

        # 尝试使用 media命令获取
        try:
            proc = subprocess.run(
                ["dumpsys", "audio"],
                capture_output=True,
                text=True,
                timeout=5,
            )
            if proc.returncode == 0:
                import re

                # 从 dumpsys audio 输出中提取 STREAM_MUSIC 音量
                match = re.search(
                    r"STREAM_MUSIC.*?Volume:.*?(\d+)(?:\(range.*?\))?",
                    proc.stdout,
                )
                if match:
                    raw = int(match.group(1))
                    # 转换到 0-100
                    return min(100, int(raw / 15 * 100))
        except Exception as e:
            logger.debug(f"[VolumeController] dumpsys 获取音量失败: {e}")

        return self.DEFAULT_VOLUME

    def _set_volume_android(self, volume: int) -> bool:
        """通过 Android 原生 API 设置音量.

        Args:
            volume: 目标音量 0-100

        Returns:
            是否成功
        """
        result = self._call_native_bridge(
            "set_volume", {"volume": volume, "stream": self.STREAM_MUSIC}
        )
        if result.get("success"):
            return True

        # 备用方案：通过 media 命令
        try:
            # 将 0-100 转换为 Android 的 0-15 音量级别
            android_volume = int(volume / 100 * 15)
            proc = subprocess.run(
                [
                    "media", "volume",
                    "--stream", str(self.STREAM_MUSIC),
                    "--set", str(android_volume),
                ],
                capture_output=True,
                text=True,
                timeout=5,
            )
            return proc.returncode == 0
        except Exception as e:
            logger.warning(f"[VolumeController] media 命令设置音量失败: {e}")
            return False

    # ----- 桌面调试实现 (sounddevice) -----

    def _get_volume_sounddevice(self) -> int:
        """通过 sounddevice 获取音量（桌面调试）."""
        try:
            import sounddevice as sd

            # sounddevice 不直接提供系统音量控制
            # 使用 pycaw (Windows) 或其他平台工具
            return self._get_volume_platform_fallback()
        except Exception as e:
            logger.debug(f"[VolumeController] sounddevice 获取音量失败: {e}")
            return self._get_volume_platform_fallback()

    def _set_volume_sounddevice(self, volume: int) -> bool:
        """通过 sounddevice 设置音量（桌面调试）."""
        try:
            return self._set_volume_platform_fallback(volume)
        except Exception as e:
            logger.debug(f"[VolumeController] sounddevice 设置音量失败: {e}")
            return False

    def _get_volume_platform_fallback(self) -> int:
        """桌面环境平台特定的音量获取."""
        system = plat.system()
        if system == "Windows":
            return self._get_volume_windows()
        elif system == "Darwin":
            return self._get_volume_macos()
        else:
            return self._get_volume_linux()

    def _set_volume_platform_fallback(self, volume: int) -> bool:
        """桌面环境平台特定的音量设置."""
        system = plat.system()
        if system == "Windows":
            return self._set_volume_windows(volume)
        elif system == "Darwin":
            return self._set_volume_macos(volume)
        else:
            return self._set_volume_linux(volume)

    def _get_volume_windows(self) -> int:
        """获取 Windows 音量."""
        try:
            import ctypes

            # 使用 Windows Core Audio API
            from ctypes import cast, POINTER, POINTER as _POINTER

            from comtypes import CLSCTX_ALL
            from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume

            devices = AudioUtilities.GetSpeakers()
            interface = devices.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
            volume_control = cast(interface, POINTER(IAudioEndpointVolume))
            volume_scalar = volume_control.GetMasterVolumeLevelScalar()
            return int(volume_scalar * 100)
        except ImportError:
            logger.debug("[VolumeController] pycaw/comtypes 未安装，使用 fallback")
            return self.DEFAULT_VOLUME
        except Exception as e:
            logger.warning(f"[VolumeController] Windows 音量获取失败: {e}")
            return self.DEFAULT_VOLUME

    def _set_volume_windows(self, volume: int) -> bool:
        """设置 Windows 音量."""
        try:
            import ctypes
            from ctypes import cast, POINTER

            from comtypes import CLSCTX_ALL
            from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume

            devices = AudioUtilities.GetSpeakers()
            interface = devices.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
            volume_control = cast(interface, POINTER(IAudioEndpointVolume))
            volume_control.SetMasterVolumeLevelScalar(volume / 100.0, None)
            return True
        except ImportError:
            return False
        except Exception as e:
            logger.warning(f"[VolumeController] Windows 音量设置失败: {e}")
            return False

    def _get_volume_macos(self) -> int:
        """获取 macOS 音量."""
        try:
            result = subprocess.run(
                ["osascript", "-e", "output volume of (get volume settings)"],
                capture_output=True,
                text=True,
                timeout=5,
            )
            if result.returncode == 0 and result.stdout.strip().isdigit():
                return int(result.stdout.strip())
        except Exception as e:
            logger.warning(f"[VolumeController] macOS 音量获取失败: {e}")
        return self.DEFAULT_VOLUME

    def _set_volume_macos(self, volume: int) -> bool:
        """设置 macOS 音量."""
        try:
            result = subprocess.run(
                ["osascript", "-e", f"set volume output volume {volume}"],
                capture_output=True,
                text=True,
                timeout=5,
            )
            return result.returncode == 0
        except Exception as e:
            logger.warning(f"[VolumeController] macOS 音量设置失败: {e}")
            return False

    def _get_volume_linux(self) -> int:
        """获取 Linux 音量."""
        try:
            import re

            import shutil

            if shutil.which("pactl"):
                result = subprocess.run(
                    ["pactl", "list", "sinks"],
                    capture_output=True,
                    text=True,
                    timeout=5,
                )
                if result.returncode == 0:
                    for line in result.stdout.split("\n"):
                        if "Volume:" in line:
                            match = re.search(r"(\d+)%", line)
                            if match:
                                return int(match.group(1))
            elif shutil.which("amixer"):
                result = subprocess.run(
                    ["amixer", "get", "Master"],
                    capture_output=True,
                    text=True,
                    timeout=5,
                )
                if result.returncode == 0:
                    match = re.search(r"\[(\d+)%\]", result.stdout)
                    if match:
                        return int(match.group(1))
        except Exception as e:
            logger.warning(f"[VolumeController] Linux 音量获取失败: {e}")
        return self.DEFAULT_VOLUME

    def _set_volume_linux(self, volume: int) -> bool:
        """设置 Linux 音量."""
        try:
            import shutil

            if shutil.which("pactl"):
                result = subprocess.run(
                    ["pactl", "set-sink-volume", "@DEFAULT_SINK@", f"{volume}%"],
                    capture_output=True,
                    text=True,
                    timeout=5,
                )
                return result.returncode == 0
            elif shutil.which("amixer"):
                result = subprocess.run(
                    ["amixer", "sset", "Master", f"{volume}%"],
                    capture_output=True,
                    text=True,
                    timeout=5,
                )
                return result.returncode == 0
        except Exception as e:
            logger.warning(f"[VolumeController] Linux 音量设置失败: {e}")
        return False

    # ----- Fallback 模拟实现 -----

    def _get_volume_fallback(self) -> int:
        """获取模拟音量（fallback 模式）."""
        return self.DEFAULT_VOLUME

    def _set_volume_fallback(self, volume: int) -> bool:
        """设置模拟音量（fallback 模式）."""
        self.DEFAULT_VOLUME = volume
        logger.info(f"[VolumeController] [Fallback] 模拟设置音量到 {volume}")
        return True
