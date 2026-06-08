# 重构文档

> 从 py-xiaozhi 到 android-xiaozhi 的模块迁移映射和改造方案。

---

## 1. 迁移总览

### 1.1 代码复用率评估

```
py-xiaozhi 代码库                   android-xiaozhi 复用情况
─────────────────────               ──────────────────────────
src/core/          (~15%)     →     100% 复用，无修改
src/protocols/     (~12%)     →     90% 复用，小幅适配
src/plugins/       (~10%)     →     70% 复用，音频插件需改造
src/mcp/           (~15%)     →     80% 复用，工具实现需 Android 适配
src/audio_codecs/  (~20%)     →     60% 复用，替换 sounddevice
src/audio_processing/ (~8%)   →     50% 复用，唤醒词改用原生 SDK
src/bootstrap/     (~5%)      →     40% 复用，ServiceContainer 大幅简化
src/ui/            (~15%)     →     0% 复用，完全用 Vue 3 重写
─────────────────────────────────────────────────────────────
总体代码复用率：                    ~60%（按行数计算）
```

### 1.2 迁移分类

| 分类 | 说明 | 占比 |
|------|------|------|
| 🟢 **原样复用** | 直接复制文件，无需修改 | ~40% |
| 🟡 **小幅改造** | 修改部分导入路径、平台检测逻辑 | ~20% |
| 🟠 **大幅改造** | 替换核心依赖，重写部分逻辑 | ~20% |
| 🔴 **完全重写** | 无法复用，使用全新技术栈 | ~20% |

---

## 2. 逐模块迁移方案

### 2.1 核心模块 (`src/core/`) → `backend/core/`

**复用程度：🟢 100% 原样复用**

| 源文件 | 目标文件 | 改造内容 |
|--------|---------|---------|
| `src/core/event_bus.py` | `backend/core/event_bus.py` | 无修改 |
| `src/core/state_manager.py` | `backend/core/state_manager.py` | 无修改 |
| `src/core/task_manager.py` | `backend/core/task_manager.py` | 无修改 |
| `src/core/resource_pool.py` | `backend/core/resource_pool.py` | 无修改 |
| `src/core/protocol_manager.py` | `backend/core/protocol_manager.py` | 无修改 |

**原因**：这些模块全部是纯 Python asyncio 代码，无平台依赖，无第三方库依赖。

### 2.2 协议模块 (`src/protocols/`) → `backend/protocols/`

**复用程度：🟡 90% 小幅改造**

#### `protocol.py` → `backend/protocols/protocol.py`

```python
# 改造点：无，原样复用
# Protocol 基类是纯 asyncio 实现
```

#### `websocket_protocol.py` → `backend/protocols/websocket_protocol.py`

```python
# 改造点：
# 1. SSL 上下文创建需要适配 Android 的证书存储
# 2. 调整最大消息大小（移动网络可能不稳定）

# 原代码:
import ssl
ssl_context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
ssl_context.check_hostname = False
ssl_context.verify_mode = ssl.CERT_NONE

# Android 上可能需要:
ssl_context = ssl.create_default_context()
ssl_context.check_hostname = False
ssl_context.verify_mode = ssl.CERT_NONE
# Chaquopy 支持标准 ssl 模块
```

#### `mqtt_protocol.py` → `backend/protocols/mqtt_protocol.py`

```python
# 改造点：
# 1. UDP socket 在 Android 上行为可能有差异
# 2. MQTT TLS 证书需要适配 Android
# 3. threading.Thread 替换为 asyncio 兼容方式

# 主要改动：UDP 音频接收线程
# 原代码使用 threading.Thread + blocking recvfrom()
# Android 上建议使用 asyncio.DatagramProtocol 或保持线程方式（已验证可行）
```

### 2.3 音频编解码 (`src/audio_codecs/`) → `backend/audio/`

**复用程度：🟠 60% 大幅改造**

#### `stream_manager.py` → ❌ 不迁移，替换为 `audio_bridge.py`

**这是最大的改造点。** `stream_manager.py` 完全依赖 `sounddevice`，在 Android 上不可用。

```python
# 原代码 (stream_manager.py):
import sounddevice as sd

class StreamManager:
    def start_input_stream(self, ...):
        self._input_stream = sd.InputStream(
            device=device_index,
            samplerate=device_config.sample_rate,
            channels=device_config.channels,
            dtype='float32',
            blocksize=blocksize,
            callback=self._input_callback,
        )
        self._input_stream.start()

    def start_output_stream(self, ...):
        self._output_stream = sd.OutputStream(...)
        self._output_stream.start()
```

**替换方案** (`audio_bridge.py`):

```python
# 新代码 (audio_bridge.py):
from jnius import autoclass  # Chaquopy 的 Python-Java 桥接

# 或者通过 UTS 插件暴露的接口
class AudioBridge:
    """
    替代 StreamManager 的 Android 音频桥接层。
    保持与 StreamManager 相同的对外接口，
    使 AudioCodec 无需感知底层变化。
    """

    def __init__(self):
        self._input_callback: Callable | None = None
        self._output_callback: Callable | None = None
        self._audio_service = None  # UTS 插件实例

    def set_input_callback(self, callback: Callable[[np.ndarray], None]):
        """等价于 sd.InputStream 的 callback 参数"""
        self._input_callback = callback

    def set_output_callback(self, callback: Callable[[], np.ndarray]):
        """等价于 sd.OutputStream 的 callback 参数"""
        self._output_callback = callback

    def start_input_stream(self, sample_rate: int, channels: int,
                           blocksize: int):
        """启动 AudioRecord，通过 JNI 回调 Python"""
        # 方案 A：通过 Chaquopy 的 jnius 直接调用 Android API
        # 方案 B：通过 localhost WebSocket 接收 UTS 插件的音频数据
        pass

    def start_output_stream(self, sample_rate: int, channels: int,
                            blocksize: int):
        """启动 AudioTrack"""
        pass

    def stop_input_stream(self):
        pass

    def stop_output_stream(self):
        pass

    def write_output(self, data: np.ndarray):
        """向 AudioTrack 写入 PCM 数据"""
        pass
```

#### `opus_codec.py` → `backend/audio/opus_codec.py`

```python
# 复用程度：🟡 90%
# 改造点：libopus 加载路径

# 原代码 (opus_loader.py):
def get_platform_info():
    system = platform.system().lower()  # 'linux', 'darwin', 'windows'
    machine = platform.machine().lower()  # 'x86_64', 'arm64', etc.
    ...

# 新代码 (opus_loader.py):
def get_platform_info():
    system = platform.system().lower()  # Android 上返回 'linux' 或通过 Java 检测
    machine = platform.machine().lower()  # 'aarch64' on ARM64

    # 新增 Android 检测
    if _is_android():
        return 'android', 'arm64'  # 或 'arm' for ARMv7

    # ... 原有逻辑
```

**需要新增的 libopus 编译产物：**
```
backend/libs/libopus/
├── arm64-v8a/libopus.so      # Android ARM64
└── armeabi-v7a/libopus.so    # Android ARMv7
```

编译方式：
```bash
# 使用 Android NDK 交叉编译 libopus
git clone https://github.com/xiph/opus
cd opus
mkdir build && cd build
cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-21 \
  -DCMAKE_BUILD_TYPE=Release
cmake --build .
```

#### `audio_converter.py` → `backend/audio/audio_converter.py`

```python
# 复用程度：🟡 85%
# 改造点：soxr 可能需要替换

# 方案 A：保留 soxr（如果 Chaquopy 能安装）
# 方案 B：使用 scipy.signal.resample 或 numpy 线性插值

# 最简方案：AudioRecord 直接采 16kHz，AudioTrack 直接播 24kHz
# 则 audio_converter 几乎不需要重采样逻辑，仅保留 downmix/upmix

# downmix_to_mono: 多声道 → 单声道（numpy mean，纯 Python）
# upmix_mono_to_channels: 单声道 → 多声道（numpy tile，纯 Python）
```

#### `audio_buffer.py` → `backend/audio/audio_buffer.py`

```python
# 复用程度：🟢 100%
# 纯 asyncio.Queue 封装，无平台依赖
```

#### `audio_codec.py` → `backend/audio/audio_codec.py`

```python
# 复用程度：🟡 75%
# 改造点：
# 1. 导入路径：stream_manager → audio_bridge
# 2. 设备枚举：Android 设备固定（不需要选择）
# 3. 如果直接采 16kHz，可省去输入重采样

# 原代码:
from src.audio_codecs.stream_manager import StreamManager

# 新代码:
from backend.audio.audio_bridge import AudioBridge as StreamManager
# 保持类名一致，最小化 AudioCodec 的改动
```

### 2.4 唤醒词检测 (`src/audio_processing/`) → UTS 原生插件

**复用程度：🔴 0% 代码复用，但逻辑完全对应**

| 原实现 (Python) | 新实现 (UTS/Kotlin) |
|-----------------|---------------------|
| `sherpa_onnx.KeywordSpotter` | sherpa-onnx Android SDK (AAR) |
| `OnlineStream.accept_waveform()` | `OnlineStream.acceptWaveform()` |
| `keyword_spotter.decode_stream()` | `keywordSpotter.decodeStream()` |
| `result.text` | `result.getText()` |

**逻辑对照：**

```python
# py-xiaozhi (Python)
class WakeWordDetector(AudioListener):
    def __init__(self):
        self.kws = sherpa_onnx.KeywordSpotter(
            config=sherpa_onnx.KeywordSpotterConfig(
                model_config=sherpa_onnx.OnlineModelConfig(
                    transducer=sherpa_onnx.OnlineTransducerModelConfig(
                        encoder="models/zh/encoder.onnx",
                        decoder="models/zh/decoder.onnx",
                        joiner="models/zh/joiner.onnx",
                    ),
                    tokens="models/zh/tokens.txt",
                ),
            ),
        )

    def on_audio_data(self, audio: np.ndarray):
        self.stream.accept_waveform(16000, audio.tolist())
        if self.kws.is_ready(self.stream):
            self.kws.decode_stream(self.stream)
            result = self.kws.get_result(self.stream)
            if result:
                # 触发唤醒
```

```typescript
// android-xiaozhi (UTS)
export class WakeWordDetector {
  private spotter: KeywordSpotter | null = null

  init(modelDir: string, threshold: number) {
    const config = new KeywordSpotterConfig()
    config.modelConfig.transducer.encoder = `${modelDir}/encoder.onnx`
    config.modelConfig.transducer.decoder = `${modelDir}/decoder.onnx`
    config.modelConfig.transducer.joiner = `${modelDir}/joiner.onnx`
    config.modelConfig.tokens = `${modelDir}/tokens.txt`
    config.maxActivePaths = 3
    this.spotter = new KeywordSpotter(config)
  }

  detect(samples: Float32Array): boolean {
    this.stream.acceptWaveform(16000, samples)
    if (this.spotter.isReady(this.stream)) {
      this.spotter.decodeStream(this.stream)
      const result = this.spotter.getResult(this.stream)
      if (result != null && result.text.length > 0) {
        return true  // 唤醒词触发
      }
    }
    return false
  }
}
```

**唤醒词流程变更：**

```
py-xiaozhi:
  AudioCodec._input_callback → WakeWordDetector.on_audio_data → Python asyncio

android-xiaozhi:
  AudioRecord → UTS WakeWordDetector.detect → 通过 WebSocket 通知 Python
  或
  AudioRecord → AudioBridge → Python WakeWordDetector（使用 Chaquopy 调用 sherpa-onnx）
```

推荐方案：唤醒词在**原生层 (UTS)** 运行，性能更好，避免每帧 JNI 开销。

### 2.5 插件系统 (`src/plugins/`) → `backend/plugins/`

**复用程度：🟡 70%**

#### 保留的插件

| 插件 | 改造程度 | 说明 |
|------|---------|------|
| `base.py` (Plugin 基类) | 🟢 无修改 | 纯 Python |
| `manager.py` (PluginManager) | 🟢 无修改 | 纯 Python |
| `audio.py` (AudioPlugin) | 🟠 大幅改造 | 替换 sounddevice，调整初始化流程 |
| `mcp.py` (McpPlugin) | 🟡 小幅改造 | MCP 工具实现需适配 Android |
| `wake_word.py` (WakeWordPlugin) | 🔴 重写 | 改为通过 WebSocket 接收原生层唤醒事件 |
| `ui.py` (UIPlugin) | 🔴 重写 | 改为 LocalServer 的 WebSocket 广播 |

#### 新增的插件

| 插件 | 说明 |
|------|------|
| `bridge_plugin.py` | 新增：管理 LocalServer，向前端广播事件 |

#### `audio_plugin.py` 改造要点

```python
# 原代码:
from src.audio_codecs.audio_codec import AudioCodec

class AudioPlugin(Plugin):
    async def setup(self, ctx, cmd):
        self.codec = AudioCodec(...)
        self.codec.initialize(...)  # 内部使用 sounddevice

# 新代码:
from backend.audio.audio_codec import AudioCodec

class AudioPlugin(Plugin):
    async def setup(self, ctx, cmd):
        self.codec = AudioCodec(...)
        self.codec.initialize(...)
        # AudioCodec 内部已将 stream_manager 替换为 audio_bridge
        # AudioBridge 通过 JNI/UTS 调用 Android 音频 API
```

### 2.6 MCP 工具 (`src/mcp/tools/`) → `backend/mcp/tools/`

**复用程度：🟠 各工具差异大**

| 工具 | 原实现 | Android 替代 | 改造程度 |
|------|--------|-------------|---------|
| `weather/` | HTTP API 请求 | 复用 | 🟢 无修改 |
| `volume/` | pycaw/applescript/pactl | Android AudioManager via UTS | 🔴 重写 |
| `camera/` | OpenCV VideoCapture | Android Camera2 API via UTS | 🔴 重写 |
| `screenshot/` | PIL ImageGrab / screencap | Android MediaProjection API | 🔴 重写 |
| `music/` | FFmpeg + AudioCodec | Android ExoPlayer / MediaPlayer | 🔴 重写 |
| `app/` | platform-specific launchers | Android Intent + PackageManager | 🔴 重写 |

**MCP 工具 Android 适配模式：**

```python
# 通用模式：Python MCP 框架 → 通过 WebSocket 调用 UTS 原生能力

# backend/mcp/tools/volume/volume_controller.py (Android 版)
class AndroidVolumeController:
    """通过 UTS 原生插件控制 Android 音量"""

    async def get_volume(self) -> int:
        # 通过 LocalServer 向前端发请求，前端调用 UTS 插件获取
        result = await self._bridge.call_native('volume.get')
        return result['volume']

    async def set_volume(self, volume: int) -> None:
        await self._bridge.call_native('volume.set', {'volume': volume})
```

或者更简洁：Python 直接通过 Chaquopy JNI 调用 Android API：

```python
# 使用 jnius (Chaquopy 内置)
from jnius import autoclass

PythonService = autoclass('org.kivy.android.PythonService')
AudioManager = autoclass('android.media.AudioManager')

class AndroidVolumeController:
    def __init__(self):
        self.am = cast('android.media.AudioManager',
                       PythonService.getActivity()
                       .getSystemService(Context.AUDIO_SERVICE))

    def get_volume(self) -> int:
        return self.am.getStreamVolume(AudioManager.STREAM_MUSIC)

    def set_volume(self, volume: int) -> None:
        self.am.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
```

### 2.7 Bootstrap (`src/bootstrap/`) → `backend/server.py`

**复用程度：🟠 40% 大幅简化**

`ServiceContainer` 的核心编排逻辑保留，但需要：

1. **去掉 GUI 事件循环**：不再需要 `qasync.QEventLoop`
2. **新增 LocalServer**：aiohttp 服务，向前端暴露接口
3. **简化插件注册**：去掉 `UIPlugin`、`ShortcutsPlugin`，新增 `BridgePlugin`
4. **新增 Android 生命周期集成**：与 Android Service 生命周期绑定

```python
# backend/server.py
class AndroidServiceContainer:
    """Android 版 ServiceContainer，替代原 bootstrap/container.py"""

    def __init__(self):
        self.event_bus = EventBus()
        self.state_manager = StateManager(self.event_bus)
        self.task_manager = TaskManager()
        self.protocol_manager: ProtocolManager | None = None
        self.plugin_manager = PluginManager()
        self.local_server = LocalServer(self.event_bus)
        self.config = ConfigManager.instance()

    async def run(self):
        """主入口，替代 ServiceContainer.run()"""
        # 1. 加载配置
        self.config.load_defaults()

        # 2. 设置协议
        protocol_type = self.config.get_config("SYSTEM_OPTIONS.NETWORK.PROTOCOL", "websocket")
        self.protocol_manager = ProtocolManager(
            event_bus=self.event_bus,
            protocol_type=protocol_type,
        )

        # 3. 注册插件（简化版）
        plugins = [
            AudioPlugin(priority=10),
            McpPlugin(priority=20),
            WakeWordPlugin(priority=30),
            BridgePlugin(priority=40),  # 新增：向前端广播事件
        ]
        for plugin in plugins:
            self.plugin_manager.register(plugin)

        # 4. 初始化插件
        ctx = PluginContext(
            event_bus=self.event_bus,
            state_manager=self.state_manager,
            task_manager=self.task_manager,
            protocol_manager=self.protocol_manager,
        )
        cmd = PluginCommands(
            config_manager=self.config,
        )
        await self.plugin_manager.setup_all(ctx, cmd)

        # 5. 启动本地服务器（前端通过 localhost 连接）
        await self.local_server.start(port=18080)

        # 6. 保持运行
        try:
            await asyncio.Event().wait()
        except asyncio.CancelledError:
            await self.shutdown()

    async def shutdown(self):
        """优雅关闭"""
        await self.plugin_manager.teardown_all()
        await self.local_server.stop()
        await self.task_manager.cancel_all()
```

### 2.8 UI (`src/ui/`) → `frontend/` (Vue 3)

**复用程度：🔴 0% 完全重写**

所有 QML UI 代码需要用 Vue 3 + UniApp X 组件重写。

| QML 组件 | Vue 3 替代 | 对应文件 |
|----------|-----------|---------|
| `MainWindow.qml` | `pages/index/index.vue` | 主页面 |
| `SettingsWindow.qml` | `pages/settings/settings.vue` | 设置页 |
| `ActivationWindow.qml` | `pages/activation/activation.vue` | 激活页 |
| `XButton.qml` | `<uni-button>` 或自定义组件 | 按钮控件 |
| `XCard.qml` | `<uni-card>` 或自定义组件 | 卡片控件 |
| `XComboBox.qml` | `<uni-data-select>` | 下拉选择 |
| `EmotionFace` (QML) | `components/EmotionFace.vue` (Lottie) | 情绪表情 |
| `tray_service.py` | UTS 前台服务 + 通知栏 | 系统通知 |
| `event_bridge.py` | `services/backend.ts` (WebSocket) | 事件桥接 |

### 2.9 配置管理 (`src/utils/`) → `backend/core/`

**复用程度：🟡 80%**

| 工具 | 改造程度 | 说明 |
|------|---------|------|
| `config_manager.py` | 🟡 修改存储路径 | Android 应用私有目录 |
| `resource_finder.py` | 🟠 重写路径逻辑 | Android 资源路径不同 |
| `opus_loader.py` | 🟡 添加 Android 分支 | 加载 arm64 libopus.so |
| `audio_device.py` | 🔴 简化 | Android 设备固定 |
| `audio_utils.py` | 🟢 复用 | 纯 numpy 操作 |

```python
# resource_finder.py Android 适配
def get_data_dir() -> Path:
    """获取应用数据目录"""
    if _is_android():
        # Chaquopy 可以通过 jnius 获取 Android Context
        from jnius import autoclass
        PythonActivity = autoclass('org.kivy.android.PythonActivity')
        context = PythonActivity.mActivity
        return Path(context.getFilesDir().getAbsolutePath())
    # ... 原有逻辑
```

### 2.10 常量 (`src/constants/`) → `backend/constants/`

**复用程度：🟢 95%**

```python
# constants.py 中 FRAME_DURATION 的平台检测需要更新：
def _get_frame_duration() -> int:
    """帧时长（毫秒）"""
    machine = platform.machine().lower()
    if machine in ('arm64', 'aarch64', 'armv7l'):
        return 60   # ARM 设备（包括 Android）
    return 20       # x86 设备
```

---

## 3. 新增模块

以下模块是 android-xiaozhi 独有的，py-xiaozhi 中没有对应：

| 模块 | 位置 | 说明 |
|------|------|------|
| `LocalServer` | `backend/server.py` | aiohttp 本地服务，管理前后端 WebSocket 通信 |
| `BridgePlugin` | `backend/plugins/bridge_plugin.py` | 监听 EventBus，向前端广播事件 |
| `AudioBridge` | `backend/audio/audio_bridge.py` | 替代 sounddevice，桥接 Android 原生音频 |
| `AudioRecorder` (UTS) | `frontend/nativeplugins/xiaozhi-audio/` | Android AudioRecord 封装 |
| `AudioPlayer` (UTS) | `frontend/nativeplugins/xiaozhi-audio/` | Android AudioTrack 封装 |
| `ForegroundService` (UTS) | `frontend/nativeplugins/xiaozhi-service/` | 前台服务 + WakeLock |
| `WakeWordDetector` (UTS) | `frontend/nativeplugins/xiaozhi-wakeword/` | sherpa-onnx Android SDK |
| `BackendService` | `frontend/src/services/backend.ts` | UniApp 与 Python 后端通信层 |

---

## 4. 依赖迁移

### 4.1 Python 依赖 (backend/requirements.txt)

| 依赖 | py-xiaozhi | android-xiaozhi | 说明 |
|------|-----------|-----------------|------|
| `numpy` | >=1.26 | >=1.26 | Chaquopy 预装，需确认版本 |
| `aiohttp` | >=3.9 | >=3.9 | 纯 Python，Chaquopy 可安装 |
| `websockets` | >=11.0 | >=11.0 | 纯 Python |
| `paho-mqtt` | >=2.1 | >=2.1 | 纯 Python |
| `opuslib` | >=3.0.1 | >=3.0.1 | ctypes 包装器（需 libopus.so） |
| `cryptography` | >=42.0 | >=42.0 | 需要编译 Android 版本 |
| `pillow` | >=10.0 | >=10.0 | Chaquopy 可能有预编译版 |
| `requests` | >=2.31 | >=2.31 | 纯 Python |
| `pendulum` | various | various | 纯 Python |
| `pypinyin` | >=0.51 | >=0.51 | 纯 Python |
| `lunar_python` | >=1.3 | >=1.3 | 纯 Python |
| `mutagen` | >=1.47 | >=1.47 | 纯 Python |
| ~~sounddevice~~ | >=0.4.4 | ❌ 不需要 | 替换为 AudioBridge |
| ~~soxr~~ | >=0.5.0 | ⚠️ 可选 | 如果 AudioRecord 直接采 16kHz 则不需要 |
| ~~PySide6~~ | >=6.6 | ❌ 不需要 | 替换为 UniApp X |
| ~~qasync~~ | >=0.27 | ❌ 不需要 | 替换为 UniApp X |
| ~~sherpa-onnx~~ | >=1.12 | ❌ 不需要 | 替换为 UTS 原生插件 |
| ~~pynput~~ | >=1.7.6 | ❌ 不需要 | Android 不需要全局热键 |
| ~~sounddevice~~ | - | ❌ | 已移除 |
| ~~gpiozero~~ | - | ❌ | Android 不需要 GPIO |
| ~~pycaw/comtypes/pywin32~~ | - | ❌ | Windows 特有 |
| ~~applescript/pyobjc~~ | - | ❌ | macOS 特有 |
| ~~opencv-python~~ | - | ⚠️ 可选 | 如果通过 UTS Camera2 则不需要 |
| ~~pyperclip~~ | - | ❌ | 通过 UTS 使用 Android 剪贴板 |

### 4.2 原生库编译

| 库 | 来源 | Android 编译方式 |
|----|------|-----------------|
| **libopus** | https://github.com/xiph/opus | NDK cmake 交叉编译 |
| **libcrypto** (cryptography) | https://github.com/pyca/cryptography | Chaquopy 可能预编译 |
| **sherpa-onnx** | https://github.com/k2-fsa/sherpa-onnx | 官方提供 Android AAR |

---

## 5. 分阶段迁移计划

### 阶段一：骨架搭建（1-2 周）

```
目标：跑通 "UniApp 前端 ←WebSocket→ Python 后端" 的最小闭环

任务：
├── [1] 创建 UniApp X 项目骨架
├── [2] 创建 Python 后端骨架 (aiohttp + WebSocket)
├── [3] 实现 LocalServer 基础通信
├── [4] 前端连接后端，显示连接状态
├── [5] 集成 Chaquopy，Python 能在 Android 上启动
└── [6] 前端显示后端返回的 "hello" 消息

可复用模块：
├── core/event_bus.py
├── core/state_manager.py
└── core/task_manager.py
```

### 阶段二：协议对接（1-2 周）

```
目标：能通过 Android 设备与小智服务器完成一次对话

任务：
├── [1] 迁移 WebSocket 协议模块
├── [2] 迁移 MQTT 协议模块
├── [3] 迁移激活服务
├── [4] 前端实现激活流程
└── [5] 协议连接状态在前端实时显示

可复用模块：
├── protocols/protocol.py
├── protocols/websocket_protocol.py
├── protocols/mqtt_protocol.py
└── services/activation.py
```

### 阶段三：音频管道（2-3 周）

```
目标：能在 Android 上进行实时语音对话

任务：
├── [1] 编译 libopus Android ARM64 版本
├── [2] 实现 AudioBridge (替代 sounddevice)
├── [3] 实现 UTS 音频采集/播放插件
├── [4] 迁移 AudioCodec、OpusCodec、AudioBuffer
├── [5] 打通 "麦克风 → Opus编码 → 服务器 → Opus解码 → 扬声器" 全链路
├── [6] 前端音频波形动画
└── [7] 延迟测试和优化

可复用模块：
├── audio_codecs/opus_codec.py
├── audio_codecs/audio_converter.py
├── audio_codecs/audio_buffer.py
└── audio_codecs/audio_codec.py (大幅改造)
```

### 阶段四：完整体验（2-3 周）

```
目标：功能完整的小智 Android 客户端

任务：
├── [1] 实现 UTS 唤醒词检测插件
├── [2] 实现 UTS 前台服务 + 后台保活
├── [3] 迁移 MCP 工具（音量/摄像头/音乐等 Android 适配）
├── [4] 完整 UI（设置页面、情绪表情、通知栏）
├── [5] 配置管理（Android 路径适配）
├── [6] 错误处理和断线重连
└── [7] 性能优化和 APK 体积控制

可复用模块：
├── mcp/mcp_server.py
├── mcp/decorators.py
├── mcp/tools/weather/ (直接复用)
└── utils/config_manager.py (小幅改造)
```

### 阶段五：打磨发布（1-2 周）

```
目标：可发布的正式版本

任务：
├── [1] 多设备测试（不同 Android 版本、不同屏幕尺寸）
├── [2] 权限申请流程完善
├── [3] 电池优化（后台运行功耗控制）
├── [4] 应用图标和启动画面
├── [5] 应用商店素材准备
└── [6] 用户文档和 FAQ
```

---

## 6. 风险和应对

| 风险 | 影响 | 应对方案 |
|------|------|---------|
| Chaquopy 不支持某个 Python 包 | 无法使用该包 | 用 UTS 原生实现替代 |
| libopus Android 编译失败 | 无法进行 Opus 编解码 | 改用 Android MediaCodec Opus |
| 音频延迟过高 | 用户体验差 | 优化 AudioRecord buffer size，降低帧长 |
| Chaquopy 商用授权 | 费用 | 个人/开源项目免费；商用需评估 |
| UniApp X 某些 API 不稳定 | 开发受阻 | 降级到标准 UniApp (WebView) |
| 后台保活被系统杀掉 | 唤醒词失效 | 使用 JobScheduler 定期重启 |
