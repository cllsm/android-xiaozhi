"""天气查询工具."""

from .weather_tools import get_forecast, get_weather  # noqa: F401

__all__ = ["get_weather", "get_forecast"]
