# -*- coding: utf-8 -*-
"""Abstract base class for keyword converters."""


from abc import ABC, abstractmethod


class KeywordConverter(ABC):
    """唤醒词关键词转换器基类."""

    @abstractmethod
    def convert(self, text: str) -> str:
        """将文本转换为关键词格式."""
        pass

    @abstractmethod
    def can_convert(self, text: str) -> bool:
        """判断是否能转换该文本."""
        pass

    @property
    @abstractmethod
    def language(self) -> str:
        """返回支持的语言代码."""
        pass

    @property
    @abstractmethod
    def model_path(self) -> str:
        """返回对应的模型路径."""
        pass

    def to_keywords_file_content(self, text: str) -> str:
        """生成关键词文件内容."""
        return self.convert(text) + "\n"
