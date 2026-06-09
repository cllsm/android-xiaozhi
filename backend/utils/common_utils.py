"""
通用工具函数集合模块.

包含验证码提取等通用工具函数。
适配 Android/Termux 环境，移除桌面端专有功能（浏览器、剪贴板）。
"""

import re
from typing import Optional

from backend.log import get_logger

logger = get_logger()


def extract_verification_code(text: str) -> Optional[str]:
    """从文本中提取验证码.

    在激活流程中使用，从 xiaozhi.me 控制面板页面文本中提取 6 位数字验证码。

    Args:
        text: 包含验证码的文本

    Returns:
        提取到的 6 位验证码字符串，未找到则返回 None
    """
    try:
        # 激活相关关键词列表
        activation_keywords = [
            "登录",
            "控制面板",
            "激活",
            "验证码",
            "绑定设备",
            "添加设备",
            "输入验证码",
            "输入",
            "面板",
            "xiaozhi.me",
            "激活码",
        ]

        # 检查文本是否包含激活相关关键词
        has_activation_keyword = any(keyword in text for keyword in activation_keywords)

        if not has_activation_keyword:
            logger.debug(f"文本不包含激活关键词，跳过验证码提取: {text}")
            return None

        # 更精确的验证码匹配模式
        patterns = [
            r"验证码[：:]\s*(\d{6})",
            r"输入验证码[：:]\s*(\d{6})",
            r"输入\s*(\d{6})",
            r"验证码\s*(\d{6})",
            r"激活码[：:]\s*(\d{6})",
            r"(\d{6})[，,。.]",
            r"[，,。.]\s*(\d{6})",
        ]

        for pattern in patterns:
            match = re.search(pattern, text)
            if match:
                code = match.group(1)
                logger.info(f"已从文本中提取验证码: {code}")
                return code

        # 通用模式匹配
        match = re.search(r"((?:\d\s*){6,})", text)
        if match:
            code = "".join(match.group(1).split())
            if len(code) == 6 and code.isdigit():
                logger.info(f"已从文本中提取验证码（通用模式）: {code}")
                return code

        logger.warning(f"未能从文本中找到验证码: {text}")
        return None
    except Exception as e:
        logger.error(f"提取验证码时出错: {e}")
        return None


def handle_verification_code(text: str) -> None:
    """处理验证码：提取并记录日志.

    在 Android/Termux 环境下，验证码通过前端 WebSocket 推送给前端，
    由前端负责显示和自动填入。此处仅做提取和日志记录。

    Args:
        text: 可能包含验证码的文本
    """
    code = extract_verification_code(text)
    if code:
        logger.info(f"已提取验证码: {code}（将通过 WebSocket 推送给前端）")
