# -*- coding: utf-8 -*-
"""唤醒词关键词转换器.

提供中文（拼音）和英文（BPE）两种唤醒词转换器，
将文本转换为 sherpa-onnx 的关键词文件格式。
"""

import re
from typing import Optional, Tuple

from .base import KeywordConverter
from .bpe_converter import BpeConverter
from .pinyin_converter import PinyinConverter

__all__ = [
    "KeywordConverter",
    "PinyinConverter",
    "BpeConverter",
    "detect_language",
    "get_converter",
    "convert_wake_word",
]

# Singleton converters
_pinyin_converter: Optional[PinyinConverter] = None
_bpe_converter: Optional[BpeConverter] = None


def _get_pinyin_converter() -> PinyinConverter:
    """获取或创建 PinyinConverter 单例."""
    global _pinyin_converter
    if _pinyin_converter is None:
        _pinyin_converter = PinyinConverter()
    return _pinyin_converter


def _get_bpe_converter() -> BpeConverter:
    """获取或创建 BpeConverter 单例."""
    global _bpe_converter
    if _bpe_converter is None:
        _bpe_converter = BpeConverter()
    return _bpe_converter


def detect_language(text: str) -> str:
    """检测文本语言.

    Args:
        text: 待检测文本

    Returns:
        "zh" 或 "en"
    """
    chinese_pattern = re.compile(r"[一-鿿]")
    if chinese_pattern.search(text):
        return "zh"
    return "en"


def get_converter(language: str) -> KeywordConverter:
    """根据语言获取对应的转换器.

    Args:
        language: "zh" 或 "en"

    Returns:
        对应的 KeywordConverter 实例

    Raises:
        ValueError: 不支持的语言
    """
    if language == "zh":
        return _get_pinyin_converter()
    elif language == "en":
        return _get_bpe_converter()
    else:
        raise ValueError(f"Unsupported language: {language}")


def convert_wake_word(text: str) -> Tuple[str, str, str]:
    """转换唤醒词文本为关键词格式.

    自动检测语言并使用对应的转换器。

    Args:
        text: 唤醒词文本（如 "你好小智" 或 "hey xiaozhi"）

    Returns:
        (keyword_line, language, model_path) 元组
    """
    language = detect_language(text)
    converter = get_converter(language)
    keyword_line = converter.convert(text)
    return keyword_line, language, converter.model_path
