"""Android 截屏控制器.

支持两种运行模式：
- Android 设备：通过 screencap 命令或 MediaProjection API 截屏
- 桌面调试：使用 PIL ImageGrab 截屏

提示词模板系统：
根据用户问题自动识别场景（聊天分析、文字提取、操作指引、错误诊断、屏幕理解），
组装结构化提示词发送给视觉 AI，确保分析结果精准有用。
"""

import io
import json
import subprocess
from datetime import datetime
from typing import Any

from backend.log import get_logger
from backend.utils.resource_finder import get_platform_info

logger = get_logger()


# ==================== 提示词模板系统 ====================

# 场景关键词映射（按匹配优先级排序，聊天分析最高）
SCENE_KEYWORDS: dict[str, list[str]] = {
    "chat_analysis": [
        "聊天", "记录", "对话", "怎么回", "说什么", "消息", "回复",
        "微信", "QQ", "发消息", "回了什么", "谁发的", "聊天记录",
        "说了什么", "对方说", "他回", "她回", "群里",
    ],
    "text_extract": [
        "文字", "读", "写什么", "内容", "OCR", "提取", "识别",
        "念一下", "上面写的", "显示什么", "写的啥", "上面说",
    ],
    "error_diagnose": [
        "报错", "失败", "不行", "错误", "异常", "崩溃",
        "为什么", "怎么回事", "出问题", "卡住了", "没反应", "打不开",
    ],
    "operation_guide": [
        "怎么操作", "按钮", "怎么用", "点哪", "怎么点", "如何",
        "下一步", "在哪设置", "怎么弄", "帮我点", "找不到",
    ],
    "screen_understand": [
        "看看", "屏幕", "在干嘛", "什么", "界面", "桌面",
        "当前页面", "这是哪", "啥应用", "看一下", "瞧瞧",
    ],
}

# 系统上下文（每次分析都注入）
SYSTEM_CONTEXT = """你是一个 Android 设备的屏幕分析助手。
当前时间: {time}
分析规则：
1. 优先关注屏幕上的文字内容（按钮、标题、提示、错误信息）
2. 识别当前所在的应用和页面
3. 注意任何异常状态（错误弹窗、加载失败、空状态）
4. 用简洁的中文描述你看到的内容
5. 如果是聊天界面，精确提取每条消息的发送者和内容"""

# 场景提示词模板
SCENE_PROMPTS: dict[str, str] = {
    "chat_analysis": """请分析这张屏幕截图中的聊天对话。

任务：
1. 识别这是什么聊天应用（微信、QQ、短信等）
2. 提取所有可见的聊天消息，按时间顺序排列
3. 区分消息的发送方（"我"发送的 vs 对方发送的）
4. 识别对方最后一条消息的内容和语气
5. 根据对话上下文，建议合适的回复内容

请严格按以下 JSON 格式输出：
{
  "app": "微信/QQ/短信/其他",
  "chat_target": "聊天对象名称",
  "messages": [
    {"sender": "我", "content": "消息内容"},
    {"sender": "对方", "content": "消息内容"}
  ],
  "last_message": "对方最后说的话",
  "tone": "轻松/严肃/生气/撒娇/询问/抱怨/开心",
  "topic": "对话主要话题的概括",
  "reply_suggestion": "建议的回复内容（自然口语，不要机器人感）"
}

注意：
- 只提取屏幕上可见的消息，不要猜测屏幕外的内容
- 消息内容要完整提取，不要省略
- 如果有图片/表情/语音条，标注出来
- 回复建议要贴合语境，像真人一样自然""",

    "text_extract": """请精确提取这张屏幕截图上的所有文字内容。

任务：
1. 按屏幕上的区域分组输出
2. 保留原始排版和层次关系
3. 如果有表单/输入框，标注其中的已有内容
4. 标注文字的颜色（红色/灰色等，可能有特殊含义）

输出格式：
{
  "regions": [
    {"position": "顶部/中部/底部", "type": "标题/正文/按钮/提示", "content": "文字内容"}
  ],
  "full_text": "按阅读顺序拼接的完整文字"
}""",

    "error_diagnose": """请检查这张屏幕截图中是否有错误或异常状态。

任务：
1. 找出所有错误/警告/异常提示
2. 分析可能导致错误的原因
3. 给出具体的解决建议

输出格式：
{
  "has_error": true/false,
  "errors": [
    {"location": "位置描述", "message": "错误信息", "severity": "高/中/低"}
  ],
  "possible_causes": ["原因1", "原因2"],
  "suggestions": ["建议1", "建议2"]
}

注意：即使没有明显错误，也要检查是否有加载失败、空状态等隐性异常。""",

    "operation_guide": """用户想知道如何操作当前屏幕上的界面。

任务：
1. 列出所有可见的可点击元素（按钮、链接、图标、卡片等）
2. 描述每个元素的位置（上/下/左/右）
3. 判断用户最可能想做什么操作
4. 给出具体的操作步骤

输出格式：
{
  "current_app": "当前应用名",
  "current_page": "当前页面描述",
  "clickable_elements": [
    {"label": "按钮文字", "position": "屏幕底部右侧", "action": "点击后的预期效果"}
  ],
  "user_intent": "推测用户想做什么",
  "steps": ["步骤1: 点击XX按钮", "步骤2: ..."]
}""",

    "screen_understand": """请描述这张屏幕截图的内容。

任务：
1. 当前是什么应用/页面
2. 屏幕上显示的主要内容（简洁概括）
3. 有哪些可操作的按钮或入口
4. 是否有需要注意的异常信息

输出格式：
{
  "app": "应用名",
  "page": "页面描述",
  "main_content": "主要内容概括（1-2句话）",
  "actionable_items": ["可操作元素列表"],
  "alerts": ["需要关注的提示/警告"]
}""",
}


def detect_scene(question: str) -> str:
    """根据用户问题自动识别场景.

    Args:
        question: 用户关于屏幕的问题

    Returns:
        场景标识符
    """
    question_lower = question.lower()
    for scene, keywords in SCENE_KEYWORDS.items():
        for kw in keywords:
            if kw in question_lower:
                return scene
    return "screen_understand"


def build_prompt(question: str) -> str:
    """根据用户问题自动组装结构化提示词.

    Args:
        question: 用户原始问题

    Returns:
        组装好的完整提示词
    """
    scene = detect_scene(question)
    system_ctx = SYSTEM_CONTEXT.format(time=datetime.now().strftime("%Y-%m-%d %H:%M"))
    scene_prompt = SCENE_PROMPTS.get(scene, SCENE_PROMPTS["screen_understand"])

    return f"{system_ctx}\n\n{scene_prompt}\n\n用户问题: {question}"


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
        """分析截图（使用提示词模板系统）.

        根据用户问题自动识别场景，组装结构化提示词，
        确保视觉 AI 返回精准有用的分析结果。

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
            # ★ 使用提示词模板系统构建结构化 prompt
            prompt = build_prompt(question)
            scene = detect_scene(question)
            logger.info(f"[Screenshot] 场景: {scene}, 原始问题: {question}")
            return self._analyze_remote(prompt, buf)
        except Exception as e:
            logger.error(f"[Screenshot] 分析截图失败: {e}", exc_info=True)
            return json.dumps({"success": False, "message": f"分析截图失败: {e}"})

    def _analyze_remote(self, question: str, image_data: bytes) -> str:
        """通过远程 API 分析截图.

        Args:
            question: 构建好的结构化提示词
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
                f"prompt_len={len(question)}, file_size={len(image_data)} bytes"
            )
            response = requests.post(
                self._explain_url,
                headers=headers,
                files=files,
                timeout=15,
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
        """Android 截屏替代方案（通过 native bridge 调用 MediaProjection）.

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
        """桌面环境使用 PIL ImageGrab 截屏."""
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
        """模拟截屏（fallback 模式）."""
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
