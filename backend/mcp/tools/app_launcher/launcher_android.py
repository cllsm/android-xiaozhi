"""Android 应用启动器.

支持两种运行模式：
- Android 设备：通过 am (Activity Manager) 使用 Intent 启动应用
- 桌面调试：使用系统命令启动应用，或返回模拟数据

在 Android 上通过 PackageManager 查找应用的包名，然后通过 Intent 启动。
"""

import json
import platform as plat
import subprocess

from backend.log import get_logger
from backend.utils.resource_finder import get_platform_info

logger = get_logger()

# 常见 Android 应用名称到包名的映射
COMMON_APP_PACKAGES: dict[str, str] = {
    # 社交
    "微信": "com.tencent.mm",
    "wechat": "com.tencent.mm",
    "qq": "com.tencent.mobileqq",
    "qq音乐": "com.tencent.qqmusic",
    "qq音乐": "com.tencent.qqmusic",
    # 浏览器
    "chrome": "com.android.chrome",
    "浏览器": "com.android.browser",
    "browser": "com.android.browser",
    # 系统工具
    "设置": "com.android.settings",
    "settings": "com.android.settings",
    "相机": "com.android.camera",
    "camera": "com.android.camera",
    "电话": "com.android.dialer",
    "phone": "com.android.dialer",
    "dialer": "com.android.dialer",
    "通讯录": "com.android.contacts",
    "contacts": "com.android.contacts",
    "短信": "com.android.messaging",
    "messages": "com.android.messaging",
    "计算器": "com.android.calculator2",
    "calculator": "com.android.calculator2",
    "日历": "com.android.calendar",
    "calendar": "com.android.calendar",
    "时钟": "com.android.deskclock",
    "clock": "com.android.deskclock",
    "文件管理": "com.android.filemanager",
    "files": "com.android.documentsui",
    "文件": "com.android.documentsui",
    # 媒体
    "相册": "com.android.gallery3d",
    "gallery": "com.android.gallery3d",
    "音乐": "com.android.music",
    "music": "com.android.music",
    "视频": "com.android.video",
    "video": "com.android.video",
    # 其他
    "地图": "com.android.maps",
    "maps": "com.android.maps",
    "应用商店": "com.android.vending",
    "play store": "com.android.vending",
    "商店": "com.android.vending",
}


class AndroidAppLauncher:
    """Android 应用启动器.

    根据运行环境自动选择实现：
    - Android ARM 设备：通过 am 命令使用 Intent 启动应用
    - 桌面调试：使用系统命令或返回模拟数据
    """

    def __init__(self):
        """初始化应用启动器."""
        self._mode = self._detect_mode()
        self._installed_apps_cache: list[dict[str, str]] | None = None
        logger.info(f"[AppLauncher] 初始化完成, 模式: {self._mode}")

    def _detect_mode(self) -> str:
        """检测当前运行模式."""
        plat_dir, _ = get_platform_info()
        if plat_dir == "android":
            return "android"
        return "desktop"

    # ----- 公共接口 -----

    def launch(self, app_name: str) -> bool:
        """启动应用.

        Args:
            app_name: 应用名称或包名

        Returns:
            是否启动成功
        """
        if self._mode == "android":
            return self._launch_android(app_name)
        else:
            return self._launch_desktop(app_name)

    def scan_installed(self) -> str:
        """扫描已安装的应用.

        Returns:
            JSON 格式的应用列表
        """
        if self._mode == "android":
            return self._scan_android()
        else:
            return self._scan_desktop()

    # ----- Android 实现 -----

    def _launch_android(self, app_name: str) -> bool:
        """在 Android 设备上启动应用.

        通过 am (Activity Manager) 使用 Intent 启动应用。

        Args:
            app_name: 应用名称或包名

        Returns:
            是否成功
        """
        # 解析包名
        package_name = self._resolve_package_name(app_name)
        if not package_name:
            logger.warning(f"[AppLauncher] 未找到应用: {app_name}")
            return False

        logger.info(f"[AppLauncher] 启动应用: {app_name} -> {package_name}")

        try:
            # 方案 1：使用 am start 启动应用的启动 Activity
            result = subprocess.run(
                [
                    "am", "start",
                    "-n", f"{package_name}/.ui.MainActivity",
                ],
                capture_output=True,
                text=True,
                timeout=10,
            )

            # 如果找不到 MainActivity，尝试其他常见 Activity
            if result.returncode != 0 or "Error" in result.stdout:
                # 方案 2：使用 monkey 命令启动应用
                result = subprocess.run(
                    [
                        "monkey", "-p", package_name,
                        "-c", "android.intent.category.LAUNCHER", "1",
                    ],
                    capture_output=True,
                    text=True,
                    timeout=10,
                )

            if result.returncode != 0 or "Error" in result.stdout:
                # 方案 3：使用 am start 只指定包名
                result = subprocess.run(
                    [
                        "am", "start",
                        "-a", "android.intent.action.MAIN",
                        "-n", f"{package_name}/.MainActivity",
                    ],
                    capture_output=True,
                    text=True,
                    timeout=10,
                )

            success = result.returncode == 0 and "Error" not in result.stdout
            if success:
                logger.info(f"[AppLauncher] 应用 {package_name} 启动成功")
            else:
                logger.warning(f"[AppLauncher] 应用 {package_name} 启动失败: {result.stdout}")
            return success

        except subprocess.TimeoutExpired:
            logger.error(f"[AppLauncher] 启动应用超时: {package_name}")
            return False
        except Exception as e:
            logger.error(f"[AppLauncher] 启动应用异常: {e}")
            return False

    def _resolve_package_name(self, app_name: str) -> str | None:
        """将应用名称解析为包名.

        Args:
            app_name: 应用名称或包名

        Returns:
            包名字符串，找不到返回 None
        """
        # 如果已经是包名格式（包含点），直接返回
        if "." in app_name and " " not in app_name:
            return app_name

        # 查找常见应用映射
        app_name_lower = app_name.lower().strip()
        for name, package in COMMON_APP_PACKAGES.items():
            if name.lower() == app_name_lower:
                return package

        # 在 Android 上通过 pm 命令查找
        if self._mode == "android":
            return self._find_package_by_name(app_name)

        return None

    def _find_package_by_name(self, app_name: str) -> str | None:
        """通过 pm 命令在 Android 设备上查找应用包名.

        Args:
            app_name: 应用名称

        Returns:
            包名字符串，找不到返回 None
        """
        try:
            result = subprocess.run(
                ["pm", "list", "packages"],
                capture_output=True,
                text=True,
                timeout=10,
            )

            if result.returncode == 0:
                app_name_lower = app_name.lower()
                for line in result.stdout.strip().split("\n"):
                    if line.startswith("package:"):
                        package = line[8:].strip()
                        # 简单匹配：包名中包含应用名称
                        if app_name_lower in package.lower():
                            return package

        except Exception as e:
            logger.debug(f"[AppLauncher] pm 命令查找失败: {e}")

        return None

    def _scan_android(self) -> str:
        """扫描 Android 设备上已安装的应用.

        Returns:
            JSON 格式的应用列表
        """
        try:
            result = subprocess.run(
                ["pm", "list", "packages", "-3"],  # -3 表示仅显示第三方应用
                capture_output=True,
                text=True,
                timeout=10,
            )

            if result.returncode == 0:
                apps = []
                for line in result.stdout.strip().split("\n"):
                    if line.startswith("package:"):
                        package = line[8:].strip()
                        # 提取简短名称
                        short_name = package.split(".")[-1] if "." in package else package
                        apps.append({
                            "name": short_name,
                            "package": package,
                            "display_name": short_name,
                        })

                # 同时获取系统应用
                sys_result = subprocess.run(
                    ["pm", "list", "packages", "-s"],  # -s 表示仅显示系统应用
                    capture_output=True,
                    text=True,
                    timeout=10,
                )
                if sys_result.returncode == 0:
                    for line in sys_result.stdout.strip().split("\n"):
                        if line.startswith("package:"):
                            package = line[8:].strip()
                            short_name = package.split(".")[-1] if "." in package else package
                            apps.append({
                                "name": short_name,
                                "package": package,
                                "display_name": short_name,
                                "system": True,
                            })

                return json.dumps(
                    {"success": True, "applications": apps, "count": len(apps)},
                    ensure_ascii=False,
                )

        except Exception as e:
            logger.error(f"[AppLauncher] 扫描 Android 应用失败: {e}")

        return json.dumps({"success": False, "message": "扫描应用失败"})

    # ----- 桌面调试实现 -----

    def _launch_desktop(self, app_name: str) -> bool:
        """桌面环境启动应用.

        Args:
            app_name: 应用名称

        Returns:
            是否成功
        """
        system = plat.system()

        try:
            if system == "Windows":
                # Windows: 使用 start 命令
                result = subprocess.run(
                    ["cmd", "/c", "start", "", app_name],
                    capture_output=True,
                    text=True,
                    timeout=10,
                )
                return result.returncode == 0

            elif system == "Darwin":
                # macOS: 使用 open 命令
                result = subprocess.run(
                    ["open", "-a", app_name],
                    capture_output=True,
                    text=True,
                    timeout=10,
                )
                return result.returncode == 0

            else:
                # Linux: 尝试直接执行
                result = subprocess.run(
                    [app_name],
                    capture_output=True,
                    text=True,
                    timeout=10,
                )
                return result.returncode == 0

        except FileNotFoundError:
            logger.warning(f"[AppLauncher] 应用未找到: {app_name}")
            return False
        except subprocess.TimeoutExpired:
            logger.warning(f"[AppLauncher] 启动应用超时: {app_name}")
            return False
        except Exception as e:
            logger.error(f"[AppLauncher] 桌面启动应用失败: {e}")
            return False

    def _scan_desktop(self) -> str:
        """桌面环境扫描已安装应用（模拟数据）.

        Returns:
            JSON 格式的应用列表
        """
        # 桌面调试模式下返回常见 Android 应用的模拟数据
        simulated_apps = [
            {"name": "微信", "package": "com.tencent.mm", "display_name": "微信"},
            {"name": "QQ", "package": "com.tencent.mobileqq", "display_name": "QQ"},
            {"name": "Chrome", "package": "com.android.chrome", "display_name": "Chrome"},
            {"name": "设置", "package": "com.android.settings", "display_name": "设置", "system": True},
            {"name": "相机", "package": "com.android.camera", "display_name": "相机", "system": True},
            {"name": "计算器", "package": "com.android.calculator2", "display_name": "计算器", "system": True},
        ]

        return json.dumps(
            {
                "success": True,
                "applications": simulated_apps,
                "count": len(simulated_apps),
                "note": "桌面调试模式 - 模拟数据",
            },
            ensure_ascii=False,
        )
