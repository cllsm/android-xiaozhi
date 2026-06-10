"""Android 摄像头控制器.

支持两种运行模式：
- Android 设备：通过 WebSocket native_call 通道 → 前端调用 Android 原生 Camera2 API
- 桌面调试：使用 OpenCV（如果可用）或返回模拟数据
"""

import asyncio
import base64
import io
import json
import subprocess
from typing import Any

from backend.log import get_logger
from backend.utils.resource_finder import get_platform_info

logger = get_logger()

# 模块级引用：server 实例，用于发起 native_call
_server_ref: Any = None


def set_server(server: Any) -> None:
    """注入 LocalServer 实例，用于通过 native_call 通道向前端请求拍照."""
    global _server_ref
    _server_ref = server
    logger.info("[Camera] 已注入 server 实例，可通过 native_call 通道拍照")


class AndroidCamera:
    """Android 摄像头控制器.

    根据运行环境自动选择实现：
    - Android ARM 设备：通过 native bridge 调用 Camera2 API
    - 桌面调试 + OpenCV：使用 cv2 拍照
    - fallback：返回模拟数据
    """

    def __init__(self):
        """初始化摄像头控制器."""
        self._mode = self._detect_mode()
        self._jpeg_data: dict[str, Any] = {"buf": None, "len": 0}
        # 视觉分析 URL 和 token（由 MCP capabilities 设置）
        self._explain_url: str = ""
        self._explain_token: str = ""
        logger.info(f"[Camera] 初始化完成, 模式: {self._mode}")

    def _detect_mode(self) -> str:
        """检测当前运行模式.

        Returns:
            "android" - Android 设备（Camera2 API）
            "desktop_opencv" - 桌面环境（使用 OpenCV）
            "fallback" - 兜底模式（模拟数据）
        """
        plat_dir, _ = get_platform_info()
        if plat_dir == "android":
            return "android"

        # 桌面环境：尝试使用 OpenCV
        try:
            import cv2  # noqa: F401

            return "desktop_opencv"
        except ImportError:
            logger.debug("OpenCV 不可用，使用 fallback 模式")
            return "fallback"

    def get_mode(self) -> str:
        """返回当前运行模式."""
        return self._mode

    def set_explain_url(self, url: str):
        """设置视觉分析服务 URL."""
        self._explain_url = url
        logger.info(f"[Camera] 视觉分析 URL 已设置: {url}")

    def set_explain_token(self, token: str):
        """设置视觉分析服务 token."""
        self._explain_token = token
        if token:
            logger.info("[Camera] 视觉分析 token 已设置")

    def capture(self) -> bool:
        """拍照.

        Returns:
            是否拍照成功
        """
        try:
            if self._mode == "android":
                return self._capture_android()
            elif self._mode == "desktop_opencv":
                return self._capture_opencv()
            else:
                return self._capture_fallback()
        except Exception as e:
            logger.error(f"[Camera] 拍照失败: {e}", exc_info=True)
            return False

    def analyze(self, question: str) -> str:
        """分析照片.

        Args:
            question: 用户关于图片的问题

        Returns:
            分析结果（JSON 字符串）
        """
        if not self._explain_url:
            return json.dumps({"success": False, "message": "视觉分析服务 URL 未设置"})

        buf = self._jpeg_data.get("buf")
        if not buf:
            return json.dumps({"success": False, "message": "没有可用的照片数据"})

        try:
            return self._analyze_remote(question, buf)
        except Exception as e:
            logger.error(f"[Camera] 分析图片失败: {e}", exc_info=True)
            return json.dumps({"success": False, "message": f"分析图片失败: {e}"})

    def _analyze_remote(self, question: str, image_data: bytes) -> str:
        """通过远程 API 分析图片.

        Args:
            question: 用户问题
            image_data: JPEG 图片数据

        Returns:
            分析结果
        """
        import requests

        headers = {}
        if self._explain_token:
            headers["Authorization"] = f"Bearer {self._explain_token}"

        files = {
            "question": (None, question),
            "file": ("camera.jpg", image_data, "image/jpeg"),
        }

        try:
            logger.info(f"[Camera] POST {self._explain_url}, question={question}, file_size={len(image_data)} bytes")
            response = requests.post(
                self._explain_url,
                headers=headers,
                files=files,
                timeout=10,
            )

            if response.status_code != 200:
                error_msg = f"上传照片失败, HTTP {response.status_code}"
                logger.error(error_msg)
                return json.dumps({"success": False, "message": error_msg})

            logger.info(f"[Camera] 分析完成, 图片大小={len(image_data)}, question={question}")
            return response.text

        except requests.RequestException as e:
            error_msg = f"连接视觉分析服务失败: {e}"
            logger.error(error_msg)
            return json.dumps({"success": False, "message": error_msg})

    # ----- Android 原生实现 -----

    def _capture_android(self) -> bool:
        """通过 native_call 通道请求前端拍照.

        流程：通过 server._cmd_native_call → WebSocket → 前端 native-bridge →
        Android Camera2 API 拍照 → base64 回传 → 解码存储。

        注意：此方法在 asyncio.to_thread 中调用，
        需要通过 asyncio.run_coroutine_threadsafe 跨线程提交协程。

        Returns:
            是否成功
        """
        if not _server_ref:
            logger.warning("[Camera] server 未注入，尝试 subprocess 兜底")
            return self._capture_android_subprocess()

        try:
            # 在线程池中需要跨线程提交协程到事件循环
            loop = _server_ref._loop if hasattr(_server_ref, '_loop') else None
            if not loop or loop.is_closed():
                logger.warning("[Camera] 事件循环不可用，尝试 subprocess 兜底")
                return self._capture_android_subprocess()

            future = asyncio.run_coroutine_threadsafe(
                _server_ref._cmd_native_call({
                    "method": "take_photo",
                    "args": {"quality": 85, "format": "jpeg"},
                }),
                loop,
            )
            # 等待结果（最多 30 秒，与 server 端超时一致）
            result = future.result(timeout=30)

            if not result.get("success"):
                logger.warning(f"[Camera] native_call 拍照失败: {result.get('message')}")
                return self._capture_android_subprocess()

            # 提取前端返回的图片数据
            native_result = result.get("result", {})
            image_b64 = native_result.get("image_data", "")

            if not image_b64:
                logger.warning("[Camera] 前端未返回图片数据，尝试 subprocess 兜底")
                return self._capture_android_subprocess()

            image_bytes = base64.b64decode(image_b64)
            self._jpeg_data = {"buf": image_bytes, "len": len(image_bytes)}
            logger.info(f"[Camera] 通过 native_call 拍照成功, 大小: {len(image_bytes)} bytes")
            return True

        except Exception as e:
            logger.error(f"[Camera] native_call 拍照失败: {e}，尝试 subprocess 兜底")
            return self._capture_android_subprocess()

    def _capture_android_subprocess(self) -> bool:
        """subprocess 兜底方案（Termux 命令行环境）."""
        payload = {
            "module": "camera",
            "action": "take_photo",
            "params": {"quality": 85, "format": "jpeg"},
        }

        try:
            result = subprocess.run(
                ["native-bridge", "--json", json.dumps(payload)],
                capture_output=True,
                text=True,
                timeout=10,
            )

            if result.returncode == 0 and result.stdout.strip():
                response = json.loads(result.stdout.strip())
                if response.get("success"):
                    image_b64 = response.get("image_data", "")
                    if image_b64:
                        image_bytes = base64.b64decode(image_b64)
                        self._jpeg_data = {"buf": image_bytes, "len": len(image_bytes)}
                        logger.info(f"[Camera] subprocess 拍照成功, 大小: {len(image_bytes)} bytes")
                        return True

            return self._capture_android_alternative()

        except FileNotFoundError:
            logger.warning("[Camera] native-bridge 命令不存在")
            return self._capture_android_alternative()
        except Exception as e:
            logger.error(f"[Camera] subprocess 拍照失败: {e}")
            return self._capture_android_alternative()

    def _capture_android_alternative(self) -> bool:
        """Android 拍照的替代方案.

        使用 am (Activity Manager) 触发系统拍照 Intent。

        Returns:
            是否成功
        """
        try:
            result = subprocess.run(
                [
                    "am", "start",
                    "-a", "android.media.action.IMAGE_CAPTURE",
                    "--ez", "android.intent.extra.quickCapture", "true",
                ],
                capture_output=True,
                text=True,
                timeout=5,
            )
            if result.returncode == 0:
                logger.info("[Camera] 通过 Intent 拍照成功（异步操作）")
                # Intent 拍照是异步的，无法立即获取数据
                # 生成一个占位响应
                self._jpeg_data = {"buf": None, "len": 0}
                return False
        except Exception as e:
            logger.debug(f"[Camera] Intent 拍照失败: {e}")

        return False

    # ----- 桌面调试实现 (OpenCV) -----

    def _capture_opencv(self) -> bool:
        """通过 OpenCV 拍照（桌面调试）.

        Returns:
            是否成功
        """
        try:
            import cv2

            cap = cv2.VideoCapture(0)
            if not cap.isOpened():
                logger.error("[Camera] 无法打开摄像头")
                return False

            try:
                # 读取一帧
                ret, frame = cap.read()
                if not ret:
                    logger.error("[Camera] 摄像头读取失败")
                    return False

                # 编码为 JPEG
                encode_param = [int(cv2.IMWRITE_JPEG_QUALITY), 85]
                success, encoded = cv2.imencode(".jpg", frame, encode_param)
                if not success:
                    logger.error("[Camera] JPEG 编码失败")
                    return False

                image_bytes = encoded.tobytes()
                self._jpeg_data = {"buf": image_bytes, "len": len(image_bytes)}
                logger.info(f"[Camera] OpenCV 拍照成功, 大小: {len(image_bytes)} bytes")
                return True
            finally:
                cap.release()

        except Exception as e:
            logger.error(f"[Camera] OpenCV 拍照失败: {e}")
            return False

    # ----- Fallback 模拟实现 -----

    def _capture_fallback(self) -> bool:
        """模拟拍照（fallback 模式）.

        生成一个纯色占位图片用于测试。

        Returns:
            是否成功
        """
        try:
            # 尝试使用 PIL 生成占位图片
            from PIL import Image

            # 创建 640x480 的浅蓝色占位图片
            img = Image.new("RGB", (640, 480), color=(135, 206, 235))
            byte_io = io.BytesIO()
            img.save(byte_io, format="JPEG", quality=85)
            image_bytes = byte_io.getvalue()

            self._jpeg_data = {"buf": image_bytes, "len": len(image_bytes)}
            logger.info(f"[Camera] [Fallback] 生成占位图片, 大小: {len(image_bytes)} bytes")
            return True

        except ImportError:
            logger.warning("[Camera] PIL 不可用，无法生成占位图片")
            return False
