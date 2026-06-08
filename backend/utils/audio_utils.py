import asyncio
import os
import sys
from typing import Any, Union, Optional

import numpy as np

from backend.log import get_logger

logger = get_logger()


class ALSAErrorSuppressor:
    """
    ALSA 错误输出抑制器。

    在 Linux 系统上，ALSA 库会输出大量警告和错误信息到 stderr，
    这些信息会干扰终端输出。此上下文管理器可临时抑制这些输出。

    用法:
        with ALSAErrorSuppressor():
            # 执行 PyAudio 初始化等操作
            audio = pyaudio.PyAudio()

    注意:
        - 仅在 Linux 系统上生效
        - 在 Windows/macOS 上无操作
        - 退出上下文时会恢复 stderr
    """

    def __init__(self):
        self._old_stderr = None
        self._devnull = None
        self._is_linux = sys.platform.startswith("linux")

    def __enter__(self):
        if not self._is_linux:
            return self

        try:
            self._old_stderr = os.dup(2)
            self._devnull = os.open("/dev/null", os.O_WRONLY)
            os.dup2(self._devnull, 2)
        except OSError:
            # 如果无法操作文件描述符，静默失败
            self._old_stderr = None
            self._devnull = None

        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        if not self._is_linux:
            return False

        if self._old_stderr is not None:
            try:
                os.dup2(self._old_stderr, 2)
                os.close(self._old_stderr)
            except OSError:
                pass

        if self._devnull is not None:
            try:
                os.close(self._devnull)
            except OSError:
                pass

        return False  # 不抑制异常


def suppress_alsa_errors():
    """返回 ALSA 错误抑制器上下文管理器."""
    return ALSAErrorSuppressor()


def downmix_to_mono(
    pcm: Union[np.ndarray, bytes],
    *,
    keepdims: bool = True,
    dtype: Union[np.dtype, str] = np.int16,
    in_channels: Optional[int] = None,
) -> Union[np.ndarray, bytes]:
    """将任意格式的音频下混为单声道.

    支持两种输入:
    1. np.ndarray: 形状 (N,) 或 (N, C) 的 PCM 数组
    2. bytes: PCM 字节流 (需指定 dtype 和 in_channels)

    Args:
        pcm: 输入音频数据 (ndarray 或 bytes)
        keepdims: True 返回 (N,1)，False 返回 (N,) (仅 ndarray 输入)
        dtype: PCM 数据类型 (仅 bytes 输入时使用)
        in_channels: 输入声道数 (仅 bytes 输入时必需)

    Returns:
        单声道音频数据 (与输入类型相同)

    Examples:
        >>> # ndarray 输入
        >>> stereo = np.random.randint(-32768, 32767, (1000, 2), dtype=np.int16)
        >>> mono = downmix_to_mono(stereo, keepdims=False)  # shape: (1000,)

        >>> # bytes 输入
        >>> stereo_bytes = b'...'  # 立体声 PCM 数据
        >>> mono_bytes = downmix_to_mono(stereo_bytes, dtype=np.int16, in_channels=2)
    """
    # bytes 输入: 转换 -> 处理 -> 转回 bytes
    if isinstance(pcm, bytes):
        if in_channels is None:
            raise ValueError("bytes 输入必须指定 in_channels 参数")
        arr = np.frombuffer(pcm, dtype=dtype).reshape(-1, in_channels)
        mono_arr = downmix_to_mono(arr, keepdims=False)  # bytes 输出不需要 keepdims
        return mono_arr.tobytes()

    # ndarray 输入: 直接处理
    x = np.asarray(pcm)
    if x.ndim == 1:
        return x[:, None] if keepdims else x

    # 已经是单声道
    if x.shape[1] == 1:
        return x if keepdims else x[:, 0]

    # 多声道下混
    if np.issubdtype(x.dtype, np.integer):
        # 先转浮点求平均，再四舍五入回原整数类型，避免溢出
        y = np.rint(x.astype(np.float32).mean(axis=1))
        info = np.iinfo(x.dtype)
        y = np.clip(y, info.min, info.max).astype(x.dtype)
    else:
        # 浮点：保持原 dtype（比如 float32），避免默认为 float64
        y = x.mean(axis=1, dtype=x.dtype)

    return y[:, None] if keepdims else y


def safe_queue_put(
    queue: asyncio.Queue, item: Any, replace_oldest: bool = True
) -> bool:
    """安全地将项目放入队列，队列满时可选择丢弃最旧数据.

    Args:
        queue: asyncio.Queue 对象
        item: 要入队的数据
        replace_oldest: True=队列满时丢弃最旧数据并放入新数据, False=直接丢弃新数据

    Returns:
        True=成功入队, False=队列满且未入队
    """
    try:
        queue.put_nowait(item)
        return True
    except asyncio.QueueFull:
        if replace_oldest:
            try:
                queue.get_nowait()  # 丢弃最旧的
                queue.put_nowait(item)  # 放入新数据
                return True
            except asyncio.QueueEmpty:
                # 理论上不会发生,但保险起见
                queue.put_nowait(item)
                return True
        return False


def upmix_mono_to_channels(mono_data: np.ndarray, num_channels: int) -> np.ndarray:
    """将单声道音频上混到多声道（复制到所有声道）

    Args:
        mono_data: 单声道音频数据，形状 (N,)
        num_channels: 目标声道数

    Returns:
        多声道音频数据，形状 (N, num_channels)
    """
    if num_channels == 1:
        return mono_data.reshape(-1, 1)

    # 复制单声道到所有声道
    return np.tile(mono_data.reshape(-1, 1), (1, num_channels))
