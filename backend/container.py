"""Android 服务容器.

简化版 ServiceContainer，适配 Android 平台。
移除 GUI/CLI/GPIO UI 层，新增 LocalServer 和 BridgePlugin。
"""

import asyncio
from typing import Any, Awaitable, Callable, Optional

from backend.bootstrap.protocols import PluginCommands, PluginContext
from backend.constants.constants import DeviceState, ListeningMode
from backend.core.event_bus import EventBus, Events
from backend.core.protocol_manager import ProtocolManager
from backend.core.resource_pool import ResourcePool
from backend.core.state_manager import StateManager
from backend.core.task_manager import TaskManager
from backend.log import get_logger
from backend.plugins.manager import PluginManager
from backend.server import LocalServer
from backend.utils.config_manager import ConfigManager

logger = get_logger()


class PluginContextAdapter:
    """PluginContext 适配器.

    实现 PluginContext 协议，为插件提供只读状态访问。
    相比原版新增 local_server 属性，供 BridgePlugin 使用。
    """

    def __init__(self, container: "AndroidServiceContainer"):
        self._container = container

    def get_device_state(self) -> DeviceState:
        """获取当前设备状态."""
        return self._container.state.device_state

    def get_listening_mode(self) -> ListeningMode:
        """获取当前监听模式."""
        return self._container.state.listening_mode

    def is_listening(self) -> bool:
        """是否正在监听."""
        return self._container.state.is_listening()

    def is_speaking(self) -> bool:
        """是否正在说话."""
        return self._container.state.is_speaking()

    def is_idle(self) -> bool:
        """是否处于空闲状态."""
        return self._container.state.is_idle()

    def is_audio_channel_opened(self) -> bool:
        """音频通道是否已打开."""
        return self._container.protocol.is_audio_channel_opened()

    def should_capture_audio(self) -> bool:
        """是否应该采集音频."""
        return self._container.state.should_capture_audio()

    def is_keep_listening(self) -> bool:
        """是否保持持续监听."""
        return self._container.state.keep_listening

    def get_config(self) -> ConfigManager:
        """获取配置管理器."""
        return self._container.config

    @property
    def event_bus(self) -> EventBus:
        """获取事件总线."""
        return self._container.event_bus

    @property
    def local_server(self) -> Optional[LocalServer]:
        """获取本地服务器实例.

        供 BridgePlugin 访问，实现事件到 WebSocket 的桥接。
        """
        return self._container.local_server


class PluginCommandsAdapter:
    """PluginCommands 适配器.

    实现 PluginCommands 协议，为插件提供操作命令。
    """

    def __init__(self, container: "AndroidServiceContainer"):
        self._container = container

    async def start_listening(self, mode: ListeningMode) -> None:
        """开始监听."""
        await self._container.start_listening(mode)

    async def stop_listening(self) -> None:
        """停止监听."""
        await self._container.stop_listening()

    async def abort_speaking(self, reason: str) -> None:
        """中止语音输出."""
        await self._container.abort_speaking(reason)

    async def send_audio(self, data: bytes) -> None:
        """发送音频数据."""
        await self._container.protocol.send_audio(data)

    async def send_text(self, text: str) -> None:
        """发送文本消息."""
        await self._container.protocol.send_text(text)

    async def send_wake_word_detected(self, text: str) -> None:
        """发送检测到的唤醒词文本."""
        await self._container.protocol.send_wake_word_detected(text)

    async def send_mcp_message(self, payload: str) -> None:
        """发送 MCP 消息."""
        await self._container.protocol.send_mcp_message(payload)

    async def connect_protocol(self) -> bool:
        """连接协议通道."""
        return await self._container.connect_protocol()

    def spawn(self, coro: Awaitable[Any], name: str) -> Any:
        """创建异步任务."""
        return self._container.tasks.spawn(coro, name)

    def schedule_command_nowait(self, fn: Callable, *args, **kwargs) -> None:
        """调度命令（非阻塞）."""
        self._container.tasks.schedule_nowait(fn, *args, **kwargs)

    def stop_wake_word_monitoring(self) -> None:
        """停止唤醒词持续监听模式."""
        self._container._wake_word_monitoring = False

    def request_shutdown(self) -> None:
        """请求关闭应用."""
        self._container.tasks.request_shutdown()


class AndroidServiceContainer:
    """Android 服务容器.

    简化版 ServiceContainer，适配 Android 平台。
    与原版 ServiceContainer 的区别:
    - 移除 WindowContextAdapter 整个类
    - 移除 UIPlugin、ShortcutsPlugin、WakeWordPlugin、AudioPlugin 注册
    - 新增 LocalServer 作为本地 HTTP/WS 服务器
    - PluginContextAdapter 新增 local_server 属性
    - _setup_plugins 只注册 BridgePlugin

    职责:
    - 初始化和管理所有核心服务
    - 注册事件处理器
    - 处理 TTS 开始/停止状态转换
    - 提供操作方法（连接、监听、中止等）
    - 启动 LocalServer 提供前后端通信
    """

    def __init__(self):
        logger.debug("初始化 AndroidServiceContainer")

        # 配置
        self.config = ConfigManager.get_instance()

        # 获取 AEC 配置
        try:
            aec_enabled = bool(self.config.get_config("AEC_OPTIONS.ENABLED", True))
        except Exception:
            aec_enabled = True

        # 核心服务
        self.event_bus = EventBus()
        self.state = StateManager(self.event_bus, aec_enabled=aec_enabled)
        self.protocol = ProtocolManager(self.event_bus)
        self.tasks = TaskManager()
        self.plugins = PluginManager()
        self.resource_pool = ResourcePool()

        # 本地服务器（前后端通信桥梁）
        self.local_server = LocalServer(
            container=self,
            event_bus=self.event_bus,
            state_manager=self.state,
            config_manager=self.config,
            protocol_manager=self.protocol,
        )

        # 适配器
        self._plugin_context: Optional[PluginContextAdapter] = None
        self._plugin_commands: Optional[PluginCommandsAdapter] = None

        # 中止标志
        self._aborted = False

        # 唤醒词持续监听标志（前端持续发音频给后端检测）
        self._wake_word_monitoring = False
        self._monitoring_audio_count = 0  # 调试用：音频帧计数

        # 关闭状态（防重入）
        self._shutting_down = False

    # -------------------------
    # 适配器创建
    # -------------------------

    def create_plugin_context(self) -> PluginContext:
        """创建插件上下文适配器."""
        if not self._plugin_context:
            self._plugin_context = PluginContextAdapter(self)
        return self._plugin_context

    def create_plugin_commands(self) -> PluginCommands:
        """创建插件命令适配器."""
        if not self._plugin_commands:
            self._plugin_commands = PluginCommandsAdapter(self)
        return self._plugin_commands

    # -------------------------
    # 生命周期
    # -------------------------

    async def run(
        self,
        *,
        protocol: str = "websocket",
        port: int = 18080,
        skip_activation: bool = False,
    ) -> int:
        """启动服务容器.

        Args:
            protocol: 通信协议类型 (websocket / mqtt)
            port: 本地服务器端口
            skip_activation: 是否跳过设备激活

        Returns:
            退出码 (0 正常退出, 1 异常退出)
        """
        logger.info(f"启动 AndroidServiceContainer, protocol={protocol}, port={port}")

        try:
            # 初始化任务管理器
            self.tasks.initialize()

            # 设置协议
            self.protocol.set_protocol(protocol)

            # 注册事件处理器
            self._setup_event_handlers()

            # 启动本地服务器
            await self.local_server.start(host="0.0.0.0", port=port)

            # -- 激活流程 --
            # 服务器先启动，前端可通过 WS 连接接收激活事件
            from backend.activation.service import ActivationService

            activation_service = await ActivationService.get_instance()
            init_result = await activation_service.initialize()

            if not init_result.get("success", False):
                logger.error(f"激活初始化失败: {init_result.get('error', '未知错误')}")
                return 1

            if not skip_activation and init_result.get("need_activation_ui", False):
                from backend.activation.web_activation import WebActivation

                web_activation = WebActivation(
                    activation_service, init_result, self.local_server
                )
                activation_ok = await web_activation.run()
                if not activation_ok:
                    logger.error("设备激活失败")
                    return 1
            else:
                if skip_activation:
                    logger.warning("跳过激活流程（调试模式）")
                else:
                    logger.info("设备已激活，无需激活流程")
            # -- 激活流程结束 --

            # 创建适配器
            ctx = self.create_plugin_context()
            cmd = self.create_plugin_commands()

            # 设置并启动插件
            await self._setup_plugins(ctx, cmd)
            await self.plugins.start_all()

            # 广播初始状态
            await self.plugins.notify_device_state_changed(
                self.state.device_state
            )

            logger.info("AndroidServiceContainer 启动完成，等待关闭信号...")

            # 等待关闭信号
            await self.tasks.wait_shutdown()
            return 0

        except Exception as e:
            logger.error(f"应用运行失败: {e}", exc_info=True)
            return 1
        finally:
            await self.shutdown()

    async def _setup_plugins(
        self, ctx: PluginContext, cmd: PluginCommands
    ) -> None:
        """设置插件.

        注册 AudioPlugin、McpPlugin、WakeWordPlugin、BridgePlugin。
        PluginManager 按优先级拓扑排序：Audio(10) → Mcp(20) → WakeWord(30) → Bridge(40)
        """
        from backend.plugins.audio import AudioPlugin
        from backend.plugins.bridge_plugin import BridgePlugin
        from backend.plugins.mcp_plugin import McpPlugin
        from backend.plugins.wake_word import WakeWordPlugin

        # 创建插件实例
        audio_plugin = AudioPlugin()
        mcp_plugin = McpPlugin()
        wake_word_plugin = WakeWordPlugin()
        bridge_plugin = BridgePlugin()

        # 注册插件（Manager 自动拓扑排序）
        self.plugins.register(
            audio_plugin,
            mcp_plugin,
            wake_word_plugin,
            bridge_plugin,
        )

        # 初始化所有插件（PluginManager 会自动拓扑排序并注入依赖）
        await self.plugins.setup_all(ctx, cmd)

        # 设置音频直连通道（TTS 音频不经过 EventBus，减少延迟）
        self.protocol.set_audio_handler(audio_plugin.on_incoming_audio)

        # 注册所有资源的清理函数到资源池（逆序释放）
        self._register_cleanup_resources()

    def _setup_event_handlers(self) -> None:
        """设置事件处理器."""
        self.event_bus.on(Events.AUDIO_CHANNEL_OPENED, self._on_audio_channel_opened)
        self.event_bus.on(Events.AUDIO_CHANNEL_CLOSED, self._on_audio_channel_closed)
        self.event_bus.on(Events.INCOMING_JSON, self._on_incoming_json)
        self.event_bus.on(Events.NETWORK_ERROR, self._on_network_error)
        self.event_bus.on(Events.DEVICE_STATE_CHANGED, self._on_device_state_changed)

    def _register_cleanup_resources(self) -> None:
        """将所有模块的清理函数注册到资源池（先注册的后释放）."""
        pool = self.resource_pool

        # 本地服务器
        pool.register("local_server", self.local_server.stop)

        # 事件总线（最先注册，最后释放）
        pool.register("event_bus", self.event_bus.clear)

        # 各插件注册自身资源
        for plugin in self.plugins._plugins:
            plugin.register_resources(pool)

        # 网络连接
        pool.register("protocol", self.protocol.disconnect)

        # 异步任务（最后注册，最先释放）
        pool.register("tasks", self.tasks.cancel_all)

    async def shutdown(self) -> None:
        """关闭应用，统一通过资源池逆序释放所有资源."""
        if self._shutting_down:
            logger.debug("AndroidServiceContainer 已在关闭中，跳过")
            return
        self._shutting_down = True
        logger.info("正在关闭 AndroidServiceContainer...")

        try:
            # 资源池统一释放（逆序执行注册的清理函数）
            await self.resource_pool.shutdown()
            logger.info("AndroidServiceContainer 关闭完成")
        except Exception as e:
            logger.error(f"关闭时出错: {e}", exc_info=True)

    # -------------------------
    # 事件处理器
    # -------------------------

    async def _on_audio_channel_opened(self, _=None) -> None:
        """音频通道打开 → 设置为监听状态."""
        await self.state.set_device_state(DeviceState.LISTENING)

    async def _on_audio_channel_closed(self, _=None) -> None:
        """音频通道关闭 → 设置为空闲状态."""
        await self.state.set_device_state(DeviceState.IDLE)

    async def _on_network_error(self, error_message: str = None) -> None:
        """网络错误 → 停止持续监听."""
        self.state.set_keep_listening(False)

    async def _on_device_state_changed(self, data: dict) -> None:
        """设备状态变更 → 通知所有插件."""
        new_state = data.get("new_state")
        if new_state:
            await self.plugins.notify_device_state_changed(new_state)
            if new_state == DeviceState.LISTENING:
                await asyncio.sleep(0.5)
                self._aborted = False

    async def _on_incoming_json(self, json_data: dict) -> None:
        """收到 JSON 消息 → 处理 TTS 状态并通知插件.

        Args:
            json_data: 协议层收到的 JSON 字典
        """
        try:
            msg_type = json_data.get("type") if isinstance(json_data, dict) else None
            logger.info(f"收到JSON消息: type={msg_type}")

            if msg_type == "tts":
                state = json_data.get("state")
                if state == "start":
                    await self._handle_tts_start()
                elif state == "stop":
                    await self._handle_tts_stop()

            # 通知所有插件（McpPlugin 会处理 type="mcp" 的消息）
            await self.plugins.notify_incoming_json(json_data)

        except Exception as e:
            logger.error(f"处理 JSON 消息失败: {e}")

    # -------------------------
    # TTS 处理
    # -------------------------

    async def _handle_tts_start(self) -> None:
        """TTS 开始播放.

        如果处于持续监听且为实时模式，保持 LISTENING 状态；
        否则切换到 SPEAKING 状态。
        """
        if (
            self.state.keep_listening
            and self.state.listening_mode == ListeningMode.REALTIME
        ):
            await self.state.set_device_state(DeviceState.LISTENING)
        else:
            await self.state.set_device_state(DeviceState.SPEAKING)

    async def _handle_tts_stop(self) -> None:
        """TTS 停止播放.

        如果处于持续监听，清空音频队列并恢复监听；
        否则切换到 IDLE 状态。
        """
        if self.state.keep_listening:
            # 清空音频队列（如果 audio 插件存在）
            try:
                audio_plugin = self.plugins.get_plugin("audio")
                if audio_plugin and hasattr(audio_plugin, "codec") and audio_plugin.codec:
                    await audio_plugin.codec.clear_audio_queue()
            except Exception as e:
                logger.warning(f"清空音频队列失败: {e}")

            await self.state.set_device_state(DeviceState.LISTENING)

            # 重新发送开始监听
            if not (
                self.state.listening_mode == ListeningMode.REALTIME
                and self.state.is_listening()
            ):
                await self.protocol.send_start_listening(self.state.listening_mode)
        else:
            await self.state.set_device_state(DeviceState.IDLE)

    # -------------------------
    # 操作方法
    # -------------------------

    async def connect_protocol(self) -> bool:
        """连接协议通道.

        Returns:
            是否连接成功
        """
        if self.protocol.is_audio_channel_opened():
            return True

        try:
            opened = await self.protocol.connect()
            if opened:
                await self.plugins.notify_protocol_connected(self.protocol.protocol)
            return opened
        except Exception as e:
            logger.error(f"连接协议失败: {e}")
            return False

    async def start_listening(self, mode: ListeningMode) -> None:
        """开始监听.

        Args:
            mode: 监听模式
        """
        ok = await self.connect_protocol()
        if not ok:
            return

        self.state.set_listening_mode(mode)
        self.state.set_keep_listening(mode != ListeningMode.MANUAL)
        await self.protocol.send_start_listening(mode)
        await self.state.set_device_state(DeviceState.LISTENING)

    async def stop_listening(self) -> None:
        """停止监听."""
        self.state.set_keep_listening(False)
        await self.protocol.send_stop_listening()
        await self.state.set_device_state(DeviceState.IDLE)

    async def start_listening_manual(self) -> None:
        """手动监听 - 按下.

        如果正在说话，先打断再开始监听。
        """
        ok = await self.connect_protocol()
        if not ok:
            return

        self.state.set_keep_listening(False)

        if self.state.is_speaking():
            logger.info("说话中发送打断")
            await self.protocol.send_abort_speaking(None)
            await self.state.set_device_state(DeviceState.IDLE)

        await self.protocol.send_start_listening(ListeningMode.MANUAL)
        await self.state.set_device_state(DeviceState.LISTENING)

    async def stop_listening_manual(self) -> None:
        """手动监听 - 释放."""
        await self.protocol.send_stop_listening()
        await self.state.set_device_state(DeviceState.IDLE)

    async def start_auto_conversation(self) -> None:
        """启动自动对话.

        根据 AEC 配置选择实时模式或自动停止模式。
        """
        ok = await self.connect_protocol()
        if not ok:
            return

        mode = (
            ListeningMode.REALTIME
            if self.state.aec_enabled
            else ListeningMode.AUTO_STOP
        )
        self.state.set_listening_mode(mode)
        self.state.set_keep_listening(True)

        await self.protocol.send_start_listening(mode)
        await self.state.set_device_state(DeviceState.LISTENING)

    async def start_wake_word_monitoring(self) -> None:
        """启动唤醒词持续监听模式.

        前端持续发送音频给后端，唤醒词检测器实时检测。
        检测到唤醒词后自动连接服务器并开始对话。
        """
        wake_word_plugin = self.plugins.get_plugin("wake_word")
        if not wake_word_plugin or not wake_word_plugin.detector:
            raise RuntimeError("唤醒词检测器未初始化，请先在设置中启用唤醒词功能")

        if not wake_word_plugin.detector.enabled:
            raise RuntimeError("唤醒词功能未启用")

        if not wake_word_plugin.detector._running:
            # 尝试启动检测器（可能之前因无本地麦克风而未启动）
            if wake_word_plugin._audio_plugin and wake_word_plugin._audio_plugin.codec:
                await wake_word_plugin.detector.start(wake_word_plugin._audio_plugin.codec)
            else:
                raise RuntimeError("音频编解码器不可用")

        self._wake_word_monitoring = True
        logger.info("唤醒词持续监听模式已启动")

    def stop_wake_word_monitoring(self) -> None:
        """停止唤醒词持续监听模式."""
        self._wake_word_monitoring = False
        logger.info("唤醒词持续监听模式已停止")

    # 前端音频诊断计数器
    _frontend_audio_count: int = 0
    _frontend_audio_drop_logged: dict = {}  # 按原因记录是否已输出过日志

    async def on_frontend_audio(self, pcm_data: bytes) -> None:
        """接收前端发来的麦克风 PCM 音频.

        两种工作模式:
        1. 唤醒词监听模式: 将 PCM 直接传给唤醒词检测器
        2. 正常对话模式: 编码为 Opus 后发送给远程服务器

        两种模式可同时工作（唤醒词检测中开始对话后，音频同时
        送往检测器和远程服务器）。

        Args:
            pcm_data: PCM float32 小端序, 16kHz, mono
        """
        try:
            import numpy as np

            AndroidServiceContainer._frontend_audio_count += 1
            count = AndroidServiceContainer._frontend_audio_count

            # 解码 PCM（两种模式共用）
            audio = np.frombuffer(pcm_data, dtype=np.float32)

            # 前 5 帧打印诊断信息
            if count <= 5:
                max_val = float(np.abs(audio).max()) if len(audio) > 0 else 0.0
                rms_val = float(np.sqrt(np.mean(audio ** 2))) if len(audio) > 0 else 0.0
                logger.info(
                    f"前端音频帧 #{count}: {len(audio)} samples, "
                    f"size={len(pcm_data)} bytes, max={max_val:.4f}, rms={rms_val:.4f}"
                )

            # --- 唤醒词监听模式: 将音频传给检测器 ---
            if self._wake_word_monitoring:
                self._monitoring_audio_count += 1
                wake_word_plugin = self.plugins.get_plugin("wake_word")
                if not wake_word_plugin:
                    if self._monitoring_audio_count <= 3:
                        logger.warning("唤醒词监听: 未找到 wake_word 插件")
                elif not wake_word_plugin.detector:
                    if self._monitoring_audio_count <= 3:
                        logger.warning("唤醒词监听: 检测器未初始化")
                else:
                    detector = wake_word_plugin.detector
                    if not detector.enabled:
                        if self._monitoring_audio_count <= 3:
                            logger.warning("唤醒词监听: 检测器未启用")
                    elif not detector._running:
                        if self._monitoring_audio_count <= 3:
                            logger.warning("唤醒词监听: 检测器未运行")
                    elif detector._paused:
                        if self._monitoring_audio_count <= 3:
                            logger.warning("唤醒词监听: 检测器已暂停")
                    else:
                        detector.on_audio_data(audio)
                        # 每 100 帧（约 2.5 秒）打印音频状态
                        if self._monitoring_audio_count % 100 == 0:
                            max_val = np.abs(audio).max()
                            rms_val = np.sqrt(np.mean(audio ** 2))
                            logger.info(f"唤醒词监听: 已处理 {self._monitoring_audio_count} 帧, "
                                        f"振幅 max={max_val:.4f} rms={rms_val:.4f}, "
                                        f"队列={detector._audio_queue.qsize() if detector._audio_queue else 0}")

            # --- 正常对话模式: 编码并发送给远程服务器 ---
            if not self.protocol or not self.protocol.is_audio_channel_opened():
                self._log_drop_once("channel_closed", count,
                                    "远程服务器未连接或音频通道未打开，音频被丢弃")
                return

            # 检查是否应该发送（LISTENING 状态）
            if not self.state.should_capture_audio():
                self._log_drop_once("not_listening", count,
                                    f"设备状态非 LISTENING (当前: {self.state.device_state})，音频被丢弃")
                return

            from backend.audio_codecs.audio_codec import AudioConfig

            # 计算 Opus 帧大小（16kHz * 20ms = 320 采样点）
            frame_size = int(AudioConfig.INPUT_SAMPLE_RATE * AudioConfig.FRAME_DURATION / 1000)

            # 按 frame_size 分帧编码并发送
            codec = None
            for plugin in self.plugins._plugins:
                if plugin.name == "audio" and plugin.codec:
                    codec = plugin.codec
                    break

            if not codec:
                # AudioPlugin 未初始化或 codec 不可用 → 使用独立 Opus 编码器
                codec = self._get_standalone_opus_encoder()
                if not codec:
                    self._log_drop_once("no_codec", count,
                                        "Opus 编码器不可用，音频被丢弃")
                    return

            if len(audio) >= frame_size:
                # 只取整数帧
                num_frames = len(audio) // frame_size
                for i in range(num_frames):
                    frame = audio[i * frame_size : (i + 1) * frame_size]
                    encoded = codec.opus_codec.encode(frame, frame_size)
                    if encoded:
                        await self.protocol.send_audio(encoded)
            elif count <= 5:
                logger.debug(f"前端音频帧 #{count} 太短 ({len(audio)} < {frame_size})，跳过")

        except Exception as e:
            logger.warning(f"处理前端音频失败: {e}", exc_info=True)

    def _log_drop_once(self, reason: str, count: int, message: str) -> None:
        """对每种丢弃原因只输出前 3 次日志，避免刷屏. """
        if reason not in AndroidServiceContainer._frontend_audio_drop_logged:
            AndroidServiceContainer._frontend_audio_drop_logged[reason] = 0
        AndroidServiceContainer._frontend_audio_drop_logged[reason] += 1
        drop_count = AndroidServiceContainer._frontend_audio_drop_logged[reason]
        if drop_count <= 3:
            logger.warning(f"前端音频 #{count} 丢弃: {message}")
        elif drop_count == 50 or drop_count == 500:
            logger.info(f"前端音频已因 [{reason}] 丢弃 {drop_count} 帧")

    def _get_standalone_opus_encoder(self):
        """获取独立 Opus 编码器（当 AudioPlugin.codec 不可用时的备选方案）.

        创建一个仅包含 OpusCodec 的轻量编码器，不依赖 sounddevice。
        """
        if hasattr(self, '_standalone_codec') and self._standalone_codec:
            return self._standalone_codec

        try:
            from backend.audio_codecs.opus_codec import OpusCodec
            from backend.constants.constants import AudioConfig

            AudioConfig.reload()
            opus = OpusCodec(
                input_sample_rate=AudioConfig.INPUT_SAMPLE_RATE,
                output_sample_rate=AudioConfig.OUTPUT_SAMPLE_RATE,
                channels=AudioConfig.CHANNELS,
            )
            opus.initialize()

            # 包装为简易 codec 对象（只需 opus_codec 属性）
            class _StandaloneCodec:
                def __init__(self, op):
                    self.opus_codec = op

            self._standalone_codec = _StandaloneCodec(opus)
            logger.info("已创建独立 Opus 编码器（AudioPlugin.codec 不可用时的备选）")
            return self._standalone_codec
        except Exception as e:
            logger.error(f"创建独立 Opus 编码器失败: {e}")
            self._standalone_codec = None
            return None

    async def abort_speaking(self, reason: str) -> None:
        """中止语音输出.

        Args:
            reason: 中止原因
        """
        if self._aborted:
            logger.debug(f"已经中止，忽略重复请求: {reason}")
            return

        logger.info(f"中止语音输出: {reason}")
        self._aborted = True
        self.state.set_aborted(True)
        await self.protocol.send_abort_speaking(reason)
        await self.state.set_device_state(DeviceState.IDLE)
