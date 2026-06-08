"""Android 应用启动 MCP 工具（装饰器注册）.

支持两种运行模式：
- Android 设备：通过 Intent 启动其他应用
- 桌面调试：使用系统命令启动应用，或返回模拟数据
"""

import asyncio
import json

from backend.log import get_logger
from backend.mcp.decorators import Prop, PropType, mcp_tool

from .launcher_android import AndroidAppLauncher

logger = get_logger()

# 模块级启动器单例
_launcher: AndroidAppLauncher | None = None


def _get_launcher() -> AndroidAppLauncher:
    """获取或初始化应用启动器单例."""
    global _launcher
    if _launcher is None:
        _launcher = AndroidAppLauncher()
    return _launcher


# ----- MCP 工具注册 -----


@mcp_tool(
    name="self.application.launch",
    description=(
        "Launch applications by name. On Android devices, this launches apps using Intents. "
        "On desktop (debug mode), it launches apps using system commands.\n"
        "Use this tool when the user wants to:\n"
        "1. Open specific applications (e.g., 'QQ', 'WeChat', '微信', 'Chrome')\n"
        "2. Launch system utilities (e.g., 'Calculator', '计算器', 'Settings', '设置')\n"
        "3. Start browsers, media players, or other installed programs\n\n"
        "On Android, the tool will try to find the correct package name and launch it. "
        "Common Android app names are supported:\n"
        "- '微信' / 'WeChat' → com.tencent.mm\n"
        "- 'QQ' → com.tencent.mobileqq\n"
        "- 'Chrome' → com.android.chrome\n"
        "- '设置' / 'Settings' → com.android.settings\n"
        "- '相机' / 'Camera' → com.android.camera\n\n"
        "Parameter:\n"
        "- app_name: String, the name or package name of the application to launch."
    ),
    props=[Prop("app_name", PropType.STR)],
)
async def tool_launch_application(args):
    """启动应用工具."""
    try:
        app_name = args["app_name"]
        logger.info(f"[AppLauncher] 尝试启动应用: {app_name}")

        launcher = _get_launcher()
        success = await asyncio.to_thread(launcher.launch, app_name)

        if success:
            logger.info(f"[AppLauncher] 成功启动应用: {app_name}")
        else:
            logger.warning(f"[AppLauncher] 启动应用失败: {app_name}")

        return success

    except KeyError:
        logger.error("[AppLauncher] 缺少 app_name 参数")
        return False
    except Exception as e:
        logger.error(f"[AppLauncher] 启动应用失败: {e}", exc_info=True)
        return False


@mcp_tool(
    name="self.application.scan_installed",
    description=(
        "Scan and list installed applications on the device.\n"
        "On Android, this uses 'pm list packages' to enumerate all installed apps.\n"
        "On desktop (debug mode), it returns a simulated list of common apps.\n\n"
        "Use this tool when:\n"
        "1. User asks what applications are available\n"
        "2. You need to find the correct app name before launching\n"
        "3. Application launch fails and you need to check available apps"
    ),
    props=[Prop("force_refresh", PropType.BOOL, default=False)],
)
async def tool_scan_installed(args):
    """扫描已安装应用工具."""
    try:
        launcher = _get_launcher()
        result = await asyncio.to_thread(launcher.scan_installed)
        return result
    except Exception as e:
        logger.error(f"[AppLauncher] 扫描应用失败: {e}", exc_info=True)
        return json.dumps({"success": False, "message": f"扫描应用失败: {e}"})
