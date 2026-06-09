# -*- coding: utf-8 -*-
"""拼音转换器 — 将中文唤醒词转换为 sherpa-onnx 关键词格式."""

import re
from typing import List

from .base import KeywordConverter

# 声母列表（按长度降序排列以优先匹配长声母）
INITIALS = [
    "zh", "ch", "sh",  # 翘舌音（2字符，优先匹配）
    "b", "p", "m", "f",  # 唇音
    "d", "t", "n", "l",  # 舌尖音
    "g", "k", "h",  # 舌根音
    "j", "q", "x",  # 舌面音
    "r", "z", "c", "s",  # 其他
    "y", "w",  # 零声母标记
]


class PinyinConverter(KeywordConverter):
    """中文唤醒词转换器.

    将中文文本转换为 sherpa-onnx 的关键词格式：
    "声母 韵母 ... @原文"
    例如："你好" → "ni hao @你好"
    """

    def __init__(self):
        self._pypinyin = None
        self._style = None

    def _ensure_pypinyin(self):
        """延迟加载 pypinyin 库."""
        if self._pypinyin is None:
            try:
                from pypinyin import Style, lazy_pinyin
                self._pypinyin = lazy_pinyin
                self._style = Style.TONE
            except ImportError:
                raise ImportError(
                    "pypinyin is required for Chinese wake word conversion. "
                    "Install it with: pip install pypinyin"
                )

    @property
    def language(self) -> str:
        return "zh"

    @property
    def model_path(self) -> str:
        return "models/zh"

    def can_convert(self, text: str) -> bool:
        chinese_pattern = re.compile(r"[一-鿿]")
        return bool(chinese_pattern.search(text))

    def _split_pinyin(self, pinyin: str) -> List[str]:
        """拆分拼音为声母和韵母."""
        if not pinyin:
            return []

        pinyin_lower = pinyin.lower()

        for initial in INITIALS:
            if pinyin_lower.startswith(initial):
                final = pinyin[len(initial):]
                if final:
                    return [initial, final]
                else:
                    return [initial]

        return [pinyin]

    def convert(self, text: str) -> str:
        """将中文文本转换为关键词格式."""
        self._ensure_pypinyin()

        pinyin_list = self._pypinyin(text, style=self._style)

        split_parts = []
        for pinyin in pinyin_list:
            parts = self._split_pinyin(pinyin)
            split_parts.extend(parts)

        pinyin_str = " ".join(split_parts)
        return f"{pinyin_str} @{text}"
