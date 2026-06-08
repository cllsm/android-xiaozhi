"""android-xiaozhi 后端入口.

Python 本地服务器，通过 aiohttp 提供前后端通信接口。
"""

import os
import sys

# 确保项目根目录在 sys.path 中，使 `from backend.xxx` 正确解析
# 同时避免 backend/logging/ 与标准库 logging 冲突
_project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _project_root not in sys.path:
    sys.path.insert(0, _project_root)

import argparse
import asyncio
import signal

from backend.log import get_logger, setup_logging


def parse_args():
    """解析命令行参数."""
    parser = argparse.ArgumentParser(description="android-xiaozhi 后端服务")
    parser.add_argument(
        "--protocol",
        choices=["websocket", "mqtt"],
        default="websocket",
        help="通信协议 (默认: websocket)",
    )
    parser.add_argument(
        "--skip-activation",
        action="store_true",
        help="跳过设备激活流程",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=18080,
        help="本地服务器端口 (默认: 18080)",
    )
    parser.add_argument(
        "--log-level",
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
        default="INFO",
        help="日志级别 (默认: INFO)",
    )
    return parser.parse_args()


async def main():
    """主入口."""
    args = parse_args()

    # 初始化日志
    setup_logging(level=args.log_level, enable_file=False)
    logger = get_logger()

    logger.info("=" * 50)
    logger.info("android-xiaozhi 后端服务启动")
    logger.info(f"协议: {args.protocol}")
    logger.info(f"端口: {args.port}")
    logger.info(f"跳过激活: {args.skip_activation}")
    logger.info("=" * 50)

    # 延迟导入，确保日志已初始化
    from backend.container import AndroidServiceContainer

    container = AndroidServiceContainer()

    # 设置优雅关闭
    loop = asyncio.get_running_loop()

    def signal_handler():
        logger.info("收到关闭信号")
        container.tasks.request_shutdown()

    # 注册信号处理（Windows 下 SIGINT 有效，SIGTERM 不一定）
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, signal_handler)
        except NotImplementedError:
            # Windows 不完全支持 add_signal_handler
            pass

    # 运行服务容器
    try:
        result = await container.run(
            protocol=args.protocol,
            port=args.port,
            skip_activation=args.skip_activation,
        )
        return result
    except KeyboardInterrupt:
        logger.info("用户中断")
        return 0
    except Exception as e:
        logger.error(f"服务运行失败: {e}", exc_info=True)
        return 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
