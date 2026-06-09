"""音乐解码器.

使用 FFmpeg (asyncio subprocess) 将音频文件解码为 PCM float32 帧，
推送到 asyncio.Queue 供 MusicPlayer 消费。
"""

import asyncio
import struct
from pathlib import Path

import numpy as np

from backend.log import get_logger

logger = get_logger()


class MusicDecoder:
    """基于 FFmpeg 的音频解码器。

    将任意格式音频文件解码为指定采样率 / 单声道 float32 PCM，
    以 numpy 数组形式放入 asyncio.Queue。
    """

    def __init__(self, sample_rate: int = 24000, channels: int = 1):
        self.sample_rate = sample_rate
        self.channels = channels
        self._process: asyncio.subprocess.Process | None = None
        self._decode_task: asyncio.Task | None = None
        self._stopped = False

    # ------------------------------------------------------------------
    # 公共接口
    # ------------------------------------------------------------------

    async def start_decode(
        self,
        file_path: Path,
        queue: asyncio.Queue,
        start_position: float = 0.0,
    ) -> bool:
        """启动 FFmpeg 解码并将 PCM 帧推入 *queue*。

        解码完成后自动向 queue 推入 ``None`` 作为哨兵值。

        Args:
            file_path: 音频文件路径
            queue: 用于接收 numpy float32 数组的队列
            start_position: 起始位置（秒）

        Returns:
            True 表示 FFmpeg 成功启动
        """
        try:
            cmd = self._build_ffmpeg_cmd(file_path, start_position)
            logger.info(
                f"启动 FFmpeg 解码: {file_path.name}, "
                f"采样率={self.sample_rate}, 起始={start_position:.1f}s"
            )

            self._process = await asyncio.create_subprocess_exec(
                *cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )

            self._stopped = False
            self._decode_task = asyncio.create_task(
                self._read_loop(self._process, queue)
            )
            return True

        except FileNotFoundError:
            logger.error("未找到 ffmpeg，请确保已安装并加入 PATH")
            return False
        except Exception as e:
            logger.error(f"启动 FFmpeg 解码失败: {e}", exc_info=True)
            return False

    async def stop(self):
        """停止解码并清理进程。"""
        self._stopped = True

        if self._decode_task and not self._decode_task.done():
            self._decode_task.cancel()
            try:
                await self._decode_task
            except asyncio.CancelledError:
                pass
            self._decode_task = None

        if self._process and self._process.returncode is None:
            try:
                self._process.kill()
                await self._process.wait()
            except Exception:
                pass
            self._process = None

        logger.debug("MusicDecoder 已停止")

    @staticmethod
    async def get_duration(file_path: Path) -> float:
        """使用 ffprobe 获取音频时长（秒）。

        Args:
            file_path: 音频文件路径

        Returns:
            时长（秒），失败返回 0
        """
        try:
            cmd = [
                "ffprobe",
                "-v", "quiet",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                str(file_path),
            ]
            proc = await asyncio.create_subprocess_exec(
                *cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            stdout, _ = await asyncio.wait_for(proc.communicate(), timeout=10)
            output = stdout.decode().strip()

            if output:
                return float(output)

        except FileNotFoundError:
            logger.debug("ffprobe 未安装，无法获取时长")
        except asyncio.TimeoutError:
            logger.warning("ffprobe 获取时长超时")
        except Exception as e:
            logger.debug(f"ffprobe 获取时长失败: {e}")

        return 0.0

    # ------------------------------------------------------------------
    # 内部方法
    # ------------------------------------------------------------------

    def _build_ffmpeg_cmd(self, file_path: Path, start_position: float) -> list[str]:
        """构建 ffmpeg 命令行参数。

        输出格式: f32le (float32 little-endian), 单声道, 目标采样率。
        """
        cmd = ["ffmpeg"]

        if start_position > 0:
            cmd.extend(["-ss", f"{start_position:.3f}"])

        cmd.extend(["-i", str(file_path)])
        cmd.extend(["-vn"])  # 不要视频流
        cmd.extend(["-ac", str(self.channels)])
        cmd.extend(["-ar", str(self.sample_rate)])
        cmd.extend(["-f", "f32le"])  # 输出 float32 LE
        cmd.extend(["-acodec", "pcm_f32le"])
        cmd.extend(["-loglevel", "error"])
        cmd.append("pipe:1")

        return cmd

    async def _read_loop(
        self,
        process: asyncio.subprocess.Process,
        queue: asyncio.Queue,
    ):
        """持续从 FFmpeg stdout 读取 PCM 数据并推入队列。"""
        # 每次读取的样本数（约 20ms 的音频）
        samples_per_chunk = int(self.sample_rate * 0.02)  # 20ms
        bytes_per_chunk = samples_per_chunk * self.channels * 4  # float32 = 4 bytes
        chunks_sent = 0

        try:
            while not self._stopped:
                raw = await process.stdout.read(bytes_per_chunk)
                if not raw:
                    # FFmpeg 已结束
                    break

                # 将原始字节转为 numpy float32 数组
                float_data = np.frombuffer(raw, dtype=np.float32).copy()

                if float_data.size == 0:
                    continue

                await queue.put(float_data)
                chunks_sent += 1

        except asyncio.CancelledError:
            logger.debug("解码读取循环被取消")
        except Exception as e:
            logger.error(f"解码读取异常: {e}", exc_info=True)
        finally:
            # 哨兵值：通知消费者解码结束
            await queue.put(None)
            logger.info(f"解码完成，共发送 {chunks_sent} 帧")

            # 读取 stderr 获取 FFmpeg 错误信息
            if process.returncode is None:
                try:
                    await process.wait()
                except Exception:
                    pass

            if process.returncode and process.returncode != 0:
                stderr = ""
                try:
                    stderr_bytes = await asyncio.wait_for(process.stderr.read(), timeout=2)
                    stderr = stderr_bytes.decode(errors="replace")
                except Exception:
                    pass
                if stderr:
                    logger.warning(f"FFmpeg 退出码={process.returncode}: {stderr[:500]}")
