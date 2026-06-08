"""Android 截屏控制器.

支持两种运行模式：
- Android 设备：通过 screencap 命令或 MediaProjection API 截屏
- 桌面调试：使用 PIL ImageGrab 截屏
"""

import io
import json
import subprocess
from typing import Any

from backend.log import get_logger
from backend.utils.resource_finder import get_platform_info

logger = get_logger()


class AndroidScreenshot:
    """Android 截屏控制器.

    根据运行环境自动选择实现：
    - Android ARM 设备：通过 screencap 命令截屏
    - 桌面调试 + PIL：使用 ImageGrab 截屏
    - fallback：返回模拟数据
    """

    def __init__(self):
        """初始化截屏控制器."""
        self._mode = self._detect_mode()
        self._jpeg_data: dict[str, Any] = {"buf": None, "len": 0}
        # 视觉分析 URL 和 token（复用 camera 的分析服务）
        self._explain_url: str = ""
        self._explain_token: str = ""
        logger.info(f"[Screenshot] 初始化完成, 模式: {self._mode}")

    def _detect_mode(self) -> str:
        """检测当前运行模式.

        Returns:
            "android" - Android 设备（screencap）
            "desktop_pil" - 桌面环境（使用 PIL ImageGrab）
            "fallback" - 兜底模式
        """
        plat_dir, _ = get_platform_info()
        if plat_dir == "android":
            return "android"

        # 桌面环境：尝试使用 PIL
        try:
            import importlib.util

            if importlib.util.find_spec("PIL.ImageGrab") is not None:
                return "desktop_pil"
        except Exception:
            pass

        return "fallback"

    def get_mode(self) -> str:
        """返回当前运行模式."""
        return self._mode

    def set_explain_url(self, url: str):
        """设置视觉分析服务 URL."""
        self._explain_url = url
        logger.info(f"[Screenshot] 视觉分析 URL 已设置: {url}")

    def set_explain_token(self, token: str):
        """设置视觉分析服务 token."""
        self._explain_token = token

    def capture(self, display_id=None) -> bool:
        """截取屏幕.

        Args:
            display_id: 显示器 ID（Android 上忽略此参数）

        Returns:
            是否成功
        """
        try:
            if self._mode == "android":
                return self._capture_android()
            elif self._mode == "desktop_pil":
                return self._capture_desktop_pil(display_id)
            else:
                return self._capture_fallback()
        except Exception as e:
            logger.error(f"[Screenshot] 截屏失败: {e}", exc_info=True)
            return False

    def analyze(self, question: str) -> str:
        """分析截图.

        Args:
            question: 用户关于截图的问题

        Returns:
            分析结果（JSON 字符串）
        """
        if not self._explain_url:
            return json.dumps({"success": False, "message": "视觉分析服务 URL 未设置"})

        buf = self._jpeg_data.get("buf")
        if not buf:
            return json.dumps({"success": False, "message": "没有可用的截图数据"})

        try:
            return self._analyze_remote(question, buf)
        except Exception as e:
            logger.error(f"[Screenshot] 分析截图失败: {e}", exc_info=True)
            return json.dumps({"success": False, "message": f"分析截图失败: {e}"})

    def _analyze_remote(self, question: str, image_data: bytes) -> str:
        """通过远程 API 分析截图.

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
            "file": ("screenshot.jpg", image_data, "image/jpeg"),
        }

        try:
            logger.info(
                f"[Screenshot] POST {self._explain_url}, "
                f"question={question}, file_size={len(image_data)} bytes"
            )
            response = requests.post(
                self._explain_url,
                headers=headers,
                files=files,
                timeout=10,
            )

            if response.status_code != 200:
                error_msg = f"上传截图失败, HTTP {response.status_code}"
                logger.error(error_msg)
                return json.dumps({"success": False, "message": error_msg})

            return response.text

        except requests.RequestException as e:
            error_msg = f"连接视觉分析服务失败: {e}"
            logger.error(error_msg)
            return json.dumps({"success": False, "message": error_msg})

    # ----- Android 实现 -----

    def _capture_android(self) -> bool:
        """在 Android 设备上截屏.

        通过 screencap 命令截取屏幕画面。

        Returns:
            是否成功
        """
        try:
            # 方案 1：使用 screencap 命令（PNG 格式）
            result = subprocess.run(
                ["screencap", "-p"],
                capture_output=True,
                timeout=10,
            )

            if result.returncode == 0 and result.stdout:
                png_data = result.stdout

                # 转换 PNG 为 JPEG 以减小体积
                image_bytes = self._convert_to_jpeg(png_data)
                if image_bytes:
                    self._jpeg_data = {"buf": image_bytes, "len": len(image_bytes)}
                    logger.info(f"[Screenshot] Android 截屏成功, 大小: {len(image_bytes)} bytes")
                    return True

            logger.warning("[Screenshot] screencap 命令失败，尝试替代方案")
            return self._capture_android_alternative()

        except subprocess.TimeoutExpired:
            logger.error("[Screenshot] screencap 命令超时")
            return False
        except FileNotFoundError:
            logger.warning("[Screenshot] screencap 命令不存在")
            return self._capture_android_alternative()
        except Exception as e:
            logger.error(f"[Screenshot] Android 截屏失败: {e}")
            return False

    def _capture_android_alternative(self) -> bool:
        """Android 截屏替代方案.

        通过 native bridge 调用 MediaProjection API。

        Returns:
            是否成功
        """
        payload = {
            "module": "screenshot",
            "action": "capture",
            "params": {"format": "jpeg", "quality": 85},
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
                    import base64

                    image_b64 = response.get("image_data", "")
                    if image_b64:
                        image_bytes = base64.b64decode(image_b64)
                        self._jpeg_data = {"buf": image_bytes, "len": len(image_bytes)}
                        logger.info(
                            f"[Screenshot] 通过 native bridge 截屏成功, 大小: {len(image_bytes)} bytes"
                        )
                        return True

        except Exception as e:
            logger.debug(f"[Screenshot] native bridge 截屏失败: {e}")

        return False

    def _convert_to_jpeg(self, png_data: bytes) -> bytes | None:
        """将 PNG 数据转换为 JPEG.

        Args:
            png_data: PNG 格式的图片数据

        Returns:
            JPEG 格式的图片数据，失败返回 None
        """
        try:
            from PIL import Image

            img = Image.open(io.BytesIO(png_data))
            if img.mode == "RGBA":
                background = Image.new("RGB", img.size, (255, 255, 255))
                background.paste(img, mask=img.split()[3])
                img = background
            elif img.mode not in ["RGB", "L"]:
                img = img.convert("RGB")

            byte_io = io.BytesIO()
            img.save(byte_io, format="JPEG", quality=85)
            return byte_io.getvalue()

        except ImportError:
            # PIL 不可用，直接使用 PNG 数据
            logger.debug("[Screenshot] PIL 不可用，直接使用 PNG 数据")
            return png_data
        except Exception as e:
            logger.warning(f"[Screenshot] PNG 转 JPEG 失败: {e}")
            return png_data

    # ----- 桌面调试实现 (PIL ImageGrab) -----

    def _capture_desktop_pil(self, display_id=None) -> bool:
        """桌面环境使用 PIL ImageGrab 截屏.

        Args:
            display_id: 显示器 ID

        Returns:
            是否成功
        """
        try:
            import PIL.ImageGrab

            logger.debug("使用 PIL ImageGrab 截屏...")

            # 截取所有屏幕
            screenshot = PIL.ImageGrab.grab(all_screens=True)

            # 处理 RGBA 转换
            if screenshot.mode == "RGBA":
                from PIL import Image

                background = Image.new("RGB", screenshot.size, (255, 255, 255))
                background.paste(screenshot, mask=screenshot.split()[3])
                screenshot = background
            elif screenshot.mode not in ["RGB", "L"]:
                screenshot = screenshot.convert("RGB")

            # 转换为 JPEG
            byte_io = io.BytesIO()
            screenshot.save(byte_io, format="JPEG", quality=85)
            image_bytes = byte_io.getvalue()

            self._jpeg_data = {"buf": image_bytes, "len": len(image_bytes)}
            logger.info(f"[Screenshot] 桌面截屏成功, 大小: {len(image_bytes)} bytes")
            return True

        except Exception as e:
            logger.error(f"[Screenshot] PIL 截屏失败: {e}")
            return False

    # ----- Fallback 模拟实现 -----

    def _capture_fallback(self) -> bool:
        """模拟截屏（fallback 模式）.

        Returns:
            是否成功
        """
        try:
            from PIL import Image

            # 生成 800x600 的灰色占位图片
            img = Image.new("RGB", (800, 600), color=(200, 200, 200))
            byte_io = io.BytesIO()
            img.save(byte_io, format="JPEG", quality=85)
            image_bytes = byte_io.getvalue()

            self._jpeg_data = {"buf": image_bytes, "len": len(image_bytes)}
            logger.info(f"[Screenshot] [Fallback] 生成占位截图, 大小: {len(image_bytes)} bytes")
            return True

        except ImportError:
            logger.warning("[Screenshot] PIL 不可用，无法生成占位截图")
            return False
