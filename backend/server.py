"""本地 HTTP/WebSocket 服务器.

通过 aiohttp 提供 HTTP API 和 WebSocket 接口，
作为 Android 前端（WebView / 原生层）与 Python 后端之间的通信桥梁。
"""

import asyncio
import json
import platform
import sys
import time
import uuid
from typing import Any, Optional

from aiohttp import web, WSMsgType

from backend.log import get_logger

logger = get_logger()

# 服务器版本号
SERVER_VERSION = "1.0.0"


class LocalServer:
    """本地 HTTP + WebSocket 服务器.

    职责:
    - 提供健康检查、状态查询、配置读写等 HTTP API
    - 维护 WebSocket 长连接，支持双向通信
    - 将后端事件广播给所有 WebSocket 客户端
    - 解析前端命令并分发到 container 执行

    Args:
        container: AndroidServiceContainer 实例
        event_bus: 事件总线
        state_manager: 状态管理器
        config_manager: 配置管理器
        protocol_manager: 协议管理器
    """

    def __init__(
        self,
        container: Any,
        event_bus: Any,
        state_manager: Any,
        config_manager: Any,
        protocol_manager: Any,
    ) -> None:
        self._container = container
        self._event_bus = event_bus
        self._state = state_manager
        self._config = config_manager
        self._protocol = protocol_manager

        # WebSocket 客户端集合
        self._ws_clients: set[web.WebSocketResponse] = set()

        # native_call 等待中的请求（request_id → Future）
        self._pending_native_calls: dict[str, asyncio.Future] = {}

        # aiohttp 应用
        self._app: Optional[web.Application] = None
        self._runner: Optional[web.AppRunner] = None
        self._site: Optional[web.TCPSite] = None

        # 启动时间（用于 uptime 计算）
        self._start_time: float = 0.0

    # -------------------------
    # 生命周期
    # -------------------------

    async def start(self, host: str = "127.0.0.1", port: int = 18080) -> None:
        """启动本地服务器.

        Args:
            host: 监听地址，默认 127.0.0.1（仅本地访问）
            port: 监听端口，默认 18080
        """
        self._start_time = time.time()
        self._app = web.Application()

        # 保存事件循环引用（供 camera 等模块跨线程提交协程）
        self._loop = asyncio.get_running_loop()

        # 注入 server 实例到 camera 模块（启用 native_call 拍照通道）
        try:
            from backend.mcp.tools.camera import camera_android
            camera_android.set_server(self)
        except Exception as e:
            logger.debug(f"[Server] 注入 camera server 引用失败: {e}")

        # 注册路由
        self._app.router.add_get("/health", self._handle_health)
        self._app.router.add_get("/api/status", self._handle_status)
        self._app.router.add_get("/api/config", self._handle_config_get)
        self._app.router.add_put("/api/config", self._handle_config_put)
        self._app.router.add_get("/api/audio/devices", self._handle_audio_devices)
        self._app.router.add_get("/api/mcp/tools", self._handle_mcp_tools)
        self._app.router.add_get("/api/activation", self._handle_activation_get)
        self._app.router.add_post("/api/activation", self._handle_activation_post)
        self._app.router.add_get("/api/system", self._handle_system_info)
        self._app.router.add_post("/api/analyze_photo", self._handle_analyze_photo)
        self._app.router.add_get("/ws", self._handle_ws)
        # Web 测试页面（仅调试用）
        self._app.router.add_get("/test", self._handle_test_page)

        # H5 前端静态文件服务
        self._setup_h5_routes()

        # 启动服务器
        self._runner = web.AppRunner(self._app)
        await self._runner.setup()
        self._site = web.TCPSite(self._runner, host, port)
        await self._site.start()
        logger.info(f"本地服务器已启动: http://{host}:{port}")

    async def stop(self) -> None:
        """停止本地服务器."""
        logger.info("正在停止本地服务器...")

        # 关闭所有 WebSocket 连接
        for ws in list(self._ws_clients):
            try:
                await ws.close(code=1001, message=b"server_shutdown")
            except Exception:
                pass
        self._ws_clients.clear()

        # 关闭服务器
        if self._runner:
            await self._runner.cleanup()
            self._runner = None

        self._app = None
        logger.info("本地服务器已停止")

    # -------------------------
    # HTTP 路由处理
    # -------------------------

    async def _handle_health(self, request: web.Request) -> web.Response:
        """健康检查接口.

        GET /health
        返回: {"status": "ok", "version": "1.0.0", "uptime_seconds": 123}
        """
        uptime = int(time.time() - self._start_time) if self._start_time else 0
        data = {
            "status": "ok",
            "version": SERVER_VERSION,
            "uptime_seconds": uptime,
        }
        return web.json_response(data)

    async def _handle_status(self, request: web.Request) -> web.Response:
        """状态查询接口.

        GET /api/status
        返回: {"device_state": "IDLE", "connected": false, "protocol": "websocket", ...}
        """
        data = {
            "device_state": str(self._state.device_state.value)
            if hasattr(self._state.device_state, "value")
            else str(self._state.device_state),
            "listening": self._state.is_listening(),
            "speaking": self._state.is_speaking(),
            "idle": self._state.is_idle(),
            "connected": self._protocol.is_audio_channel_opened(),
            "listening_mode": str(self._state.listening_mode.value)
            if hasattr(self._state.listening_mode, "value")
            else str(self._state.listening_mode),
            "keep_listening": self._state.keep_listening,
            "protocol": getattr(
                getattr(self._protocol, "_transport", None),
                "protocol",
                None,
            ),
        }
        # 序列化时将 protocol 对象转为字符串
        if data["protocol"] is not None:
            data["protocol"] = type(data["protocol"]).__name__

        return web.json_response(data)

    async def _handle_config_get(self, request: web.Request) -> web.Response:
        """配置获取接口.

        GET /api/config?key=xxx
        返回: {"key": "xxx", "value": "yyy"} 或完整配置
        """
        key = request.query.get("key")
        if key:
            value = self._config.get_config(key)
            return web.json_response({"key": key, "value": value})
        else:
            # 返回完整配置（脱敏后的）
            try:
                full_config = self._config._config if hasattr(self._config, "_config") else {}
                return web.json_response({"config": full_config})
            except Exception as e:
                return web.json_response(
                    {"error": f"获取配置失败: {e}"},
                    status=500,
                )

    async def _handle_config_put(self, request: web.Request) -> web.Response:
        """配置更新接口.

        PUT /api/config
        Body: {"key": "xxx", "value": "yyy"}
        返回: {"success": true/false, "message": "..."}
        """
        try:
            body = await request.json()
        except json.JSONDecodeError:
            return web.json_response(
                {"success": False, "message": "无效的 JSON 数据"},
                status=400,
            )

        key = body.get("key")
        value = body.get("value")

        if not key:
            return web.json_response(
                {"success": False, "message": "缺少 key 参数"},
                status=400,
            )

        success = self._config.update_config(key, value)
        if success:
            logger.info(f"配置已更新: {key}")
            return web.json_response({"success": True, "message": "配置已更新"})
        else:
            return web.json_response(
                {"success": False, "message": "配置更新失败"},
                status=500,
            )

    # -------------------------
    # 新增 HTTP API 路由
    # -------------------------

    async def _handle_audio_devices(self, request: web.Request) -> web.Response:
        """音频设备列表接口.

        GET /api/audio/devices
        返回: {"input": [...], "output": [...]}
        """
        try:
            audio_config = self._config.get_config("AUDIO_DEVICES", {})
            devices = {
                "input": [
                    {
                        "id": audio_config.get("input_device_id", "default"),
                        "name": audio_config.get("input_device_name", "内置麦克风"),
                        "sample_rate": audio_config.get("input_sample_rate", 16000),
                        "channels": audio_config.get("input_channels", 1),
                    }
                ],
                "output": [
                    {
                        "id": audio_config.get("output_device_id", "default"),
                        "name": audio_config.get("output_device_name", "内置扬声器"),
                        "sample_rate": audio_config.get("output_sample_rate", 24000),
                        "channels": audio_config.get("output_channels", 1),
                    }
                ],
            }
            return web.json_response(devices)
        except Exception as e:
            logger.error(f"获取音频设备失败: {e}")
            return web.json_response(
                {"error": f"获取音频设备失败: {e}"},
                status=500,
            )

    async def _handle_mcp_tools(self, request: web.Request) -> web.Response:
        """MCP 工具列表接口.

        GET /api/mcp/tools
        返回: {"tools": [...]}
        """
        try:
            from backend.mcp.mcp_server import McpServer

            mcp = McpServer.get_instance()
            tools = []
            for tool in mcp.tools:
                tool_json = tool.to_json()
                tools.append({
                    "name": tool_json.get("name", ""),
                    "description": tool_json.get("description", ""),
                    "properties": [
                        {
                            "name": prop_name,
                            "type": prop_info.get("type", "string"),
                            "required": prop_name in tool_json.get("inputSchema", {}).get("required", []),
                        }
                        for prop_name, prop_info in tool_json.get("inputSchema", {}).get("properties", {}).items()
                    ],
                })

            return web.json_response({"tools": tools})
        except Exception as e:
            logger.error(f"获取 MCP 工具列表失败: {e}")
            return web.json_response(
                {"error": f"获取 MCP 工具列表失败: {e}"},
                status=500,
            )

    async def _handle_activation_get(self, request: web.Request) -> web.Response:
        """获取激活状态接口.

        GET /api/activation
        返回: {"activated": bool, "device_id": "...", ...}
        """
        try:
            from backend.activation.service import ActivationService

            activation = ActivationService.get_instance_sync()
            status = activation.get_activation_status()
            efuse = activation.get_device_info()

            data = {
                "activated": status.get("local_activated", False),
                "device_id": self._config.get_config("SYSTEM_OPTIONS.DEVICE_ID"),
                "serial_number": efuse.get("serial_number"),
                "server_activated": status.get("server_activated", False),
                "status_consistent": status.get("status_consistent", True),
            }
            return web.json_response(data)
        except Exception as e:
            # ActivationService 可能未初始化（如桌面调试模式）
            logger.debug(f"获取激活状态失败: {e}")
            return web.json_response({
                "activated": True,
                "device_id": self._config.get_config("SYSTEM_OPTIONS.DEVICE_ID"),
                "message": f"激活服务未就绪: {e}",
            })

    async def _handle_activation_post(self, request: web.Request) -> web.Response:
        """执行激活接口.

        POST /api/activation
        Body: {"code": "XXXX-XXXX"}
        返回: {"success": bool, "device_id": "...", ...}
        """
        try:
            body = await request.json()
        except json.JSONDecodeError:
            return web.json_response(
                {"success": False, "message": "无效的 JSON 数据"},
                status=400,
            )

        try:
            from backend.activation.service import ActivationService

            activation = ActivationService.get_instance_sync()
            activation_data = activation.get_activation_data()

            if activation_data:
                success = await activation.activate(activation_data)
            else:
                success = False

            return web.json_response({
                "success": success,
                "device_id": self._config.get_config("SYSTEM_OPTIONS.DEVICE_ID"),
            })
        except Exception as e:
            logger.error(f"激活失败: {e}")
            return web.json_response(
                {"success": False, "message": f"激活失败: {e}"},
                status=500,
            )

    async def _handle_system_info(self, request: web.Request) -> web.Response:
        """系统信息接口.

        GET /api/system
        返回: {"version": "...", "python_version": "...", ...}
        """
        data = {
            "version": SERVER_VERSION,
            "python_version": platform.python_version(),
            "platform": platform.system(),
            "platform_release": platform.release(),
            "machine": platform.machine(),
            "device_model": platform.node(),
            "cpu_count": _get_cpu_count(),
            "memory_usage_mb": _get_memory_mb(),
        }
        return web.json_response(data)

    async def _handle_analyze_photo(self, request: web.Request) -> web.Response:
        """分析照片接口（前端手动拍照后调用）.

        POST /api/analyze_photo
        Body: {"image_data": "base64...", "question": "描述这张照片"}
        返回: {"success": true, "result": "分析结果"}
        """
        try:
            body = await request.json()
            image_b64 = body.get("image_data", "")
            question = body.get("question", "描述这张照片的内容")

            if not image_b64:
                return web.json_response(
                    {"success": False, "message": "缺少 image_data"}, status=400
                )

            import base64

            try:
                image_bytes = base64.b64decode(image_b64)
            except Exception:
                return web.json_response(
                    {"success": False, "message": "image_data 不是有效的 base64"}, status=400
                )

            # 使用 camera 单例分析图片
            from backend.mcp.tools.camera import get_camera_instance

            camera = get_camera_instance()
            # 直接注入图片数据，跳过 capture 步骤
            camera._jpeg_data = {"buf": image_bytes, "len": len(image_bytes)}

            result = await asyncio.to_thread(camera.analyze, question)

            try:
                parsed = json.loads(result)
                return web.json_response(parsed)
            except (json.JSONDecodeError, TypeError):
                return web.json_response({"success": True, "result": result})

        except Exception as e:
            logger.error(f"[Server] analyze_photo 失败: {e}", exc_info=True)
            return web.json_response(
                {"success": False, "message": str(e)}, status=500
            )

    # -------------------------
    # H5 前端静态文件服务
    # -------------------------

    def _setup_h5_routes(self) -> None:
        """注册 H5 前端静态文件路由.

        如果 frontend/dist/build/h5/ 目录存在（已执行 npm run build:h5），
        则提供 H5 前端页面服务。访问 http://host:port/ 即可打开与 Android 相同的界面。
        """
        from pathlib import Path

        self._h5_dir: Optional[Path] = None

        # 搜索 H5 构建产物目录
        candidates = [
            Path(__file__).resolve().parent.parent / "frontend" / "dist" / "build" / "h5",
            Path(__file__).resolve().parent.parent / "frontend" / "dist" / "h5",
        ]

        for candidate in candidates:
            if candidate.is_dir() and (candidate / "index.html").exists():
                self._h5_dir = candidate
                logger.info(f"H5 前端目录: {candidate}")
                break

        if not self._h5_dir:
            logger.debug("H5 前端构建产物未找到，跳过静态文件服务")
            logger.debug("提示: 执行 'cd frontend && npm run build:h5' 构建 H5 前端")
            return

        # 注册 H5 静态路由（放在所有 API 路由之后，作为 fallback）
        self._app.router.add_get("/", self._handle_h5_index)
        self._app.router.add_get("/{path:.*}", self._handle_h5_static)

    async def _handle_h5_index(self, request: web.Request) -> web.Response:
        """返回 H5 前端 index.html."""
        if not self._h5_dir:
            return web.Response(text="H5 前端未构建", status=404)

        index_file = self._h5_dir / "index.html"
        if not index_file.exists():
            return web.Response(text="index.html 不存在", status=404)

        return web.FileResponse(index_file)

    async def _handle_h5_static(self, request: web.Request) -> web.Response:
        """返回 H5 前端静态文件.

        处理 /static/js/xxx、/static/css/xxx 等静态资源请求。
        对于未匹配的路径（SPA 路由），返回 index.html 让前端处理。
        """
        if not self._h5_dir:
            return web.Response(text="H5 前端未构建", status=404)

        path = request.match_info.get("path", "")
        file_path = self._h5_dir / path

        # 安全检查：防止路径穿越
        try:
            file_path = file_path.resolve()
            self._h5_dir.resolve()
            if not str(file_path).startswith(str(self._h5_dir.resolve())):
                return web.Response(text="Forbidden", status=403)
        except Exception:
            return web.Response(text="Forbidden", status=403)

        # 文件存在且是文件 → 返回文件
        if file_path.is_file():
            return web.FileResponse(file_path)

        # 文件不存在 → 返回 index.html（SPA 路由 fallback）
        index_file = self._h5_dir / "index.html"
        if index_file.exists():
            return web.FileResponse(index_file)

        return web.Response(text="Not Found", status=404)

    async def _handle_test_page(self, request: web.Request) -> web.Response:
        """Web 测试页面.

        GET /test
        返回一个 HTML 页面，用于在浏览器中测试所有后端 API。
        """
        from backend.web_test import WEB_TEST_HTML

        return web.Response(
            text=WEB_TEST_HTML,
            content_type="text/html",
            charset="utf-8",
        )

    # -------------------------
    # WebSocket 路由处理
    # -------------------------

    async def _handle_ws(self, request: web.Request) -> web.WebSocketResponse:
        """WebSocket 连接处理.

        GET /ws
        消息格式: {"type": "xxx", "data": {...}, "id": "xxx"}
        """
        ws = web.WebSocketResponse(heartbeat=30)
        await ws.prepare(request)

        self._ws_clients.add(ws)
        logger.info(f"WebSocket 客户端已连接，当前连接数: {len(self._ws_clients)}")

        # 发送连接成功消息
        await ws.send_json({
            "type": "connected",
            "data": {"version": SERVER_VERSION},
        })

        try:
            async for msg in ws:
                await self._handle_ws_message(ws, msg)
        except Exception as e:
            logger.error(f"WebSocket 处理异常: {e}")
        finally:
            self._ws_clients.discard(ws)
            logger.info(f"WebSocket 客户端已断开，当前连接数: {len(self._ws_clients)}")

        return ws

    async def _handle_ws_message(self, ws: web.WebSocketResponse, msg: Any) -> None:
        """处理 WebSocket 消息.

        支持的消息类型:
        - command: 执行命令
        - ping: 心跳检测

        Args:
            ws: WebSocket 连接
            msg: aiohttp WebSocket 消息对象
        """
        # 调试：记录前5条消息类型
        import backend.server as _self_mod
        if not hasattr(_self_mod, '_msg_type_count'):
            _self_mod._msg_type_count = {}
        mtype = str(msg.type)
        _self_mod._msg_type_count[mtype] = _self_mod._msg_type_count.get(mtype, 0) + 1
        if _self_mod._msg_type_count[mtype] <= 2:
            logger.info(f"WS消息类型={mtype}, 累计={{k: v for k, v in _self_mod._msg_type_count.items()}}")

        if msg.type == WSMsgType.TEXT:
            try:
                data = json.loads(msg.data)
            except json.JSONDecodeError:
                await ws.send_json({
                    "type": "error",
                    "data": {"message": "无效的 JSON 数据"},
                })
                return

            msg_type = data.get("type")

            if msg_type == "command":
                # 命令执行
                msg_id = data.get("id")
                command_data = data.get("data", {})
                await self._execute_command(ws, msg_id, command_data)

            elif msg_type == "ping":
                # 心跳响应
                await ws.send_json({"type": "pong"})

            else:
                logger.warning(f"未知的 WebSocket 消息类型: {msg_type}")
                await ws.send_json({
                    "type": "error",
                    "data": {"message": f"未知的消息类型: {msg_type}"},
                })

        elif msg.type == WSMsgType.BINARY:
            # 前端发送的麦克风 PCM 音频，转发给协议层
            if self._container:
                # 用模块级计数器（避免实例属性问题）
                import backend.server as _self_mod
                if not hasattr(_self_mod, '_global_binary_count'):
                    _self_mod._global_binary_count = 0
                _self_mod._global_binary_count += 1
                if _self_mod._global_binary_count <= 3:
                    logger.info(f"收到前端音频帧 #{_self_mod._global_binary_count}, 大小={len(msg.data)} bytes")
                elif _self_mod._global_binary_count % 200 == 0:
                    logger.info(f"已收到 {_self_mod._global_binary_count} 帧前端音频")
                try:
                    await self._container.on_frontend_audio(msg.data)
                except Exception as e:
                    logger.debug(f"转发前端音频失败: {e}")

        elif msg.type == WSMsgType.ERROR:
            logger.error(f"WebSocket 错误: {ws.exception()}")

        elif msg.type in (WSMsgType.CLOSE, WSMsgType.CLOSING, WSMsgType.CLOSED):
            pass

    async def _execute_command(
        self, ws: web.WebSocketResponse, msg_id: Optional[str], data: dict
    ) -> None:
        """执行前端发来的命令并返回结果.

        支持的命令:
        - start_listening: 开始监听
        - stop_listening: 停止监听
        - start_auto_conversation: 开始自动对话
        - abort_speaking: 中止语音输出
        - manual_listen_press: 手动监听按下
        - manual_listen_release: 手动监听释放
        - send_text: 发送文本
        - connect: 连接协议
        - shutdown: 关闭服务

        Args:
            ws: WebSocket 连接
            msg_id: 消息 ID（用于请求-响应模式）
            data: 命令数据，包含 action 和 params
        """
        action = data.get("action", "")
        params = data.get("params", {})

        try:
            result = await self._dispatch_command(action, params)
            # 发送命令响应
            if msg_id:
                response = {
                    "type": "command_response",
                    "id": msg_id,
                    "data": {"success": True, "result": result},
                }
                await ws.send_json(response)

        except Exception as e:
            logger.error(f"执行命令失败 [{action}]: {e}", exc_info=True)
            if msg_id:
                response = {
                    "type": "command_response",
                    "id": msg_id,
                    "data": {"success": False, "error": str(e)},
                }
                await ws.send_json(response)

    async def _dispatch_command(self, action: str, params: dict) -> Any:
        """将命令分发到 container 对应方法.

        Args:
            action: 命令动作名称
            params: 命令参数

        Returns:
            命令执行结果
        """
        container = self._container

        if action == "start_listening":
            from backend.constants.constants import ListeningMode

            mode_str = params.get("mode", "auto_stop")
            try:
                mode = ListeningMode(mode_str)
            except ValueError:
                mode = ListeningMode.AUTO_STOP
            await container.start_listening(mode)
            return {"state": str(container.state.device_state.value)}

        elif action == "stop_listening":
            await container.stop_listening()
            return {"state": str(container.state.device_state.value)}

        elif action == "start_auto_conversation":
            await container.start_auto_conversation()
            return {"state": str(container.state.device_state.value)}

        elif action == "abort_speaking":
            reason = params.get("reason", "user_interruption")
            await container.abort_speaking(reason)
            return {"state": str(container.state.device_state.value)}

        elif action == "manual_listen_press":
            await container.start_listening_manual()
            return {"state": str(container.state.device_state.value)}

        elif action == "manual_listen_release":
            await container.stop_listening_manual()
            return {"state": str(container.state.device_state.value)}

        elif action == "send_text":
            text = params.get("text", "")
            if text:
                # 参考 py-xiaozhi UIPlugin._send_text:
                # 1. 如果正在说话，先打断
                if container.state.is_speaking():
                    await container.abort_speaking("user_interruption")
                # 2. 确保协议已连接
                if not container.protocol.is_audio_channel_opened():
                    await container.connect_protocol()
                # 3. 文本通过 send_wake_word_detected 发送为结构化 JSON
                #    {"session_id": "", "type": "listen", "state": "detect", "text": "..."}
                await container.protocol.send_wake_word_detected(text)
            return {"sent": True}

        elif action == "connect":
            connected = await container.connect_protocol()
            if not connected:
                # 检查 URL 是否已配置，给出明确提示
                ws_url = self._config.get_config("SYSTEM_OPTIONS.NETWORK.WEBSOCKET_URL")
                if not ws_url:
                    return {
                        "connected": False,
                        "error": "WebSocket URL 未配置，请先通过 PUT /api/config 设置 SYSTEM_OPTIONS.NETWORK.WEBSOCKET_URL",
                    }
            return {"connected": connected}

        elif action == "disconnect_server":
            await container.protocol.disconnect()
            return {"disconnected": True}

        elif action == "set_listening_mode":
            from backend.constants.constants import ListeningMode

            mode_str = params.get("mode", "auto_stop")
            try:
                mode = ListeningMode(mode_str)
            except ValueError:
                mode = ListeningMode.AUTO_STOP
            container.state.set_listening_mode(mode)
            return {"mode": str(mode.value)}

        elif action == "activate":
            return await self._cmd_activate(params)

        elif action == "start_wake_word_monitoring":
            await container.start_wake_word_monitoring()
            return {"monitoring": True}

        elif action == "stop_wake_word_monitoring":
            container.stop_wake_word_monitoring()
            return {"monitoring": False}

        elif action == "set_wake_word":
            return await self._cmd_set_wake_word(params)

        elif action == "call_mcp_tool":
            return await self._cmd_call_mcp_tool(params)

        elif action == "native_call":
            return await self._cmd_native_call(params)

        elif action == "native_call_response":
            # 前端返回原生调用结果，唤醒等待中的 Future
            return await self._cmd_native_call_response(params)

        elif action == "shutdown":
            container.tasks.request_shutdown()
            return {"shutting_down": True}

        else:
            raise ValueError(f"未知的命令: {action}")

    # -------------------------
    # WebSocket 命令实现
    # -------------------------

    async def _cmd_activate(self, params: dict) -> dict:
        """设备激活命令.

        Args:
            params: {"code": "XXXX-XXXX"}
        """
        try:
            from backend.activation.service import ActivationService

            activation = ActivationService.get_instance_sync()
            activation_data = activation.get_activation_data()

            if activation_data:
                success = await activation.activate(activation_data)
                return {
                    "activated": success,
                    "device_id": self._config.get_config("SYSTEM_OPTIONS.DEVICE_ID"),
                }
            else:
                return {"activated": False, "message": "没有待处理的激活数据"}

        except Exception as e:
            logger.error(f"激活命令执行失败: {e}")
            return {"activated": False, "error": str(e)}

    async def _cmd_set_wake_word(self, params: dict) -> dict:
        """唤醒词设置命令.

        Args:
            params: {"enabled": true, "sensitivity": 0.2, "wake_word": "你好小智"}
        """
        enabled = params.get("enabled", True)
        sensitivity = params.get("sensitivity", 0.2)
        wake_word_text = params.get("wake_word", None)

        # 更新配置
        self._config.update_config("WAKE_WORD_OPTIONS.USE_WAKE_WORD", enabled)
        self._config.update_config("WAKE_WORD_OPTIONS.KEYWORDS_THRESHOLD", sensitivity)

        result = {"enabled": enabled, "sensitivity": sensitivity}

        # 如果提供了新的唤醒词文本，更新配置并热重载模型
        if wake_word_text:
            self._config.update_config("WAKE_WORD_OPTIONS.WAKE_WORD", wake_word_text)
            result["wake_word"] = wake_word_text

            # 触发唤醒词插件热重载（会重新生成 keywords 文件并重载模型）
            wake_word_plugin = self._container.plugins.get_plugin("wake_word")
            if wake_word_plugin and wake_word_plugin.detector:
                try:
                    await wake_word_plugin.reload_model()
                    logger.info(f"唤醒词已更新并重载: {wake_word_text}")
                except Exception as e:
                    logger.error(f"唤醒词热重载失败: {e}")
                    result["error"] = str(e)

        logger.info(f"唤醒词设置已更新: enabled={enabled}, sensitivity={sensitivity}")
        return result

    async def _cmd_call_mcp_tool(self, params: dict) -> dict:
        """MCP 工具调用命令.

        Args:
            params: {"tool_name": "...", "arguments": {...}}
        """
        tool_name = params.get("tool_name", "")
        arguments = params.get("arguments", {})

        if not tool_name:
            raise ValueError("缺少 tool_name 参数")

        try:
            from backend.mcp.mcp_server import McpServer

            mcp = McpServer.get_instance()

            # 查找工具
            tool = None
            for t in mcp.tools:
                if t.name == tool_name:
                    tool = t
                    break

            if not tool:
                raise ValueError(f"未知的 MCP 工具: {tool_name}")

            # 调用工具
            result = await tool.call(arguments)
            return {"tool_name": tool_name, "result": json.loads(result)}

        except Exception as e:
            logger.error(f"MCP 工具调用失败 [{tool_name}]: {e}")
            return {"tool_name": tool_name, "error": str(e)}

    async def _cmd_native_call(self, params: dict) -> dict:
        """原生能力调用命令（转发给前端执行）.

        后端通过 WebSocket 将请求广播给前端，前端使用 plus.android
        执行原生调用后返回结果。支持超时机制。

        Args:
            params: {"method": "screenshot", "args": {...}}

        Returns:
            {"success": bool, "result": any} 或 {"success": False, "message": str}
        """
        import asyncio

        method = params.get("method", "")
        args = params.get("args", {})

        logger.info(f"[native_call] 请求原生调用: method={method}, args={args}")

        # 没有前端客户端连接时，直接返回模拟数据
        if not self._ws_clients:
            return {
                "success": False,
                "simulated": True,
                "message": "无前端连接，原生调用不可用",
            }

        # 生成请求 ID，创建 Future 等待结果
        request_id = f"native_{uuid.uuid4().hex[:8]}"
        future: asyncio.Future = asyncio.get_running_loop().create_future()
        self._pending_native_calls[request_id] = future

        try:
            # 向前端广播原生调用请求
            await self.broadcast_event("native_call_request", {
                "request_id": request_id,
                "method": method,
                "args": args,
            })

            # 等待前端响应（超时 30 秒）
            result = await asyncio.wait_for(future, timeout=30.0)
            return {"success": True, "result": result}

        except asyncio.TimeoutError:
            logger.warning(f"[native_call] 超时: method={method}, request_id={request_id}")
            return {"success": False, "message": f"原生调用超时: {method}"}
        except Exception as e:
            logger.error(f"[native_call] 失败: {e}")
            return {"success": False, "message": str(e)}
        finally:
            self._pending_native_calls.pop(request_id, None)

    async def _cmd_native_call_response(self, params: dict) -> dict:
        """前端返回原生调用结果，唤醒等待中的 Future.

        Args:
            params: {"request_id": "native_xxx", "result": {...}}
        """
        request_id = params.get("request_id", "")
        result = params.get("result")

        future = self._pending_native_calls.get(request_id)
        if future and not future.done():
            future.set_result(result)
            logger.info(f"[native_call] 收到前端响应: request_id={request_id}")
        else:
            logger.warning(f"[native_call] 无匹配的等待请求: request_id={request_id}")

        return {"received": True}

    # -------------------------
    # 事件广播
    # -------------------------

    async def broadcast_event(self, event_type: str, data: Any = None) -> None:
        """向所有 WebSocket 客户端广播事件.

        Args:
            event_type: 事件类型
            data: 事件数据
        """
        if not self._ws_clients:
            return

        message = {"type": event_type, "data": data}

        # 并行发送给所有客户端
        disconnected: list[web.WebSocketResponse] = []
        for ws in list(self._ws_clients):
            try:
                await ws.send_json(message)
            except Exception:
                disconnected.append(ws)

        # 清理已断开的连接
        for ws in disconnected:
            self._ws_clients.discard(ws)

    async def broadcast_audio(self, pcm_data: bytes) -> None:
        """向前端广播 PCM 音频二进制数据.

        Args:
            pcm_data: PCM float32 小端序二进制数据
        """
        if not self._ws_clients:
            return

        disconnected: list[web.WebSocketResponse] = []
        for ws in list(self._ws_clients):
            try:
                await ws.send_bytes(pcm_data)
            except Exception as e:
                logger.info(f"[AUDIO_DEBUG] broadcast_audio 发送失败: {e}")
                disconnected.append(ws)

        for ws in disconnected:
            self._ws_clients.discard(ws)

    @property
    def client_count(self) -> int:
        """当前 WebSocket 客户端连接数."""
        return len(self._ws_clients)


# -------------------------
# 系统信息辅助函数
# -------------------------

def _get_cpu_count() -> int:
    """获取 CPU 核心数."""
    try:
        import os
        return os.cpu_count() or 1
    except Exception:
        return 1


def _get_memory_mb() -> float:
    """获取当前进程内存使用（MB）."""
    try:
        import os
        import psutil
        process = psutil.Process(os.getpid())
        return round(process.memory_info().rss / 1024 / 1024, 1)
    except Exception:
        return 0.0
