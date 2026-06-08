# 架构设计文档

> android-xiaozhi 整体架构设计，包含技术选型、模块划分、数据流和关键设计决策。

---

## 1. 整体架构

### 1.1 三层架构

```
┌──────────────────────────────────────────────────────────────┐
│                     展示层 (Presentation)                     │
│                   UniApp X (Vue 3 + Vite)                    │
│         负责 UI 渲染、用户交互、状态展示、设置管理             │
├──────────────────────────────────────────────────────────────┤
│                     业务层 (Business)                         │
│                  Python (aiohttp + asyncio)                   │
│    协议通信 │ 音频编解码 │ 状态机 │ 事件总线 │ MCP 工具系统    │
├──────────────────────────────────────────────────────────────┤
│                     原生层 (Native)                           │
│                  UTS 插件 (编译为 Kotlin)                     │
│    音频 I/O │ 唤醒词 │ 后台服务 │ 音量 │ 摄像头 │ 文件系统    │
└──────────────────────────────────────────────────────────────┘
```

### 1.2 通信机制

```
展示层 ←── localhost WebSocket :18080 ──→ 业务层
                                              │
                                              ├── WebSocket / MQTT ──→ 小智服务器
                                              │
                                              ├── UTS Native Bridge ──→ 原生层
                                              │
                                              └── opuslib (libopus.so) ──→ 音频编解码
```

| 通信通道 | 协议 | 用途 | 延迟要求 |
|---------|------|------|---------|
| 前端 ↔ 后端 | WebSocket (localhost) | 状态同步、事件推送、指令下发 | 中 (~50ms) |
| 前端 ↔ 后端 | HTTP (localhost) | 配置读写、一次性查询 | 低 |
| 后端 ↔ 原生 | Chaquopy JNI / Unix Socket | 音频数据、唤醒词回调 | 高 (<10ms) |
| 后端 ↔ 服务器 | WebSocket / MQTT | 语音对话、IoT 控制 | 中 |

### 1.3 进程模型

```
Android APK (单进程)
│
├── UniApp X Runtime (WebView / 原生渲染引擎)
│   └── Vue 3 应用实例
│
├── Python Runtime (Chaquopy 嵌入)
│   ├── aiohttp Server (localhost:18080)
│   ├── asyncio Event Loop
│   └── 各业务模块
│
└── Android Foreground Service
    └── 保持 Python 后端活跃
```

**关键决策**：所有组件运行在**同一进程**中，通过 Chaquopy 的 JNI 桥接调用 Python，避免 IPC 开销。

---

## 2. 技术选型

### 2.1 前端：UniApp X

| 选型 | 理由 |
|------|------|
| **UniApp X** 而非 UniApp (标准版) | X 版使用原生渲染引擎，性能远超 WebView 壳 |
| **Vue 3 + Composition API** | 更好的 TypeScript 支持、逻辑复用 |
| **Pinia** 替代 Vuex | 更轻量、更好的 TS 类型推断 |
| **UTS** 原生插件 | 一套 TypeScript 语法编译为 Kotlin/Swift，无需写原生代码 |

**UniApp X vs 标准 UniApp：**
- UniApp X 编译为原生代码（非 WebView），UI 流畅度接近原生
- UTS 插件直接访问 Android API，无需 Java/Kotlin 桥接
- 支持原生组件渲染，适合实时音频波形等动画

### 2.2 后端：Python (Chaquopy)

| 选型 | 理由 |
|------|------|
| **Chaquopy** 嵌入 Python | 最大化复用 py-xiaozhi 的 Python 代码 |
| **aiohttp** 本地服务器 | 支持 WebSocket 长连接，与 UniApp 通信 |
| **保持 asyncio 架构** | 与 py-xiaozhi 一致，事件驱动 |

**Chaquopy 替代方案对比：**

| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| Chaquopy | 官方支持、pip 可用、JNI 桥接 | APK 增大 ~20MB、C 扩展需手动编译 | ✅ 推荐 |
| Termux | 完整 Python 环境 | 需额外安装、用户门槛高 | ❌ 不适合分发 |
| QPython | 简单集成 | 不再维护、功能受限 | ❌ 已废弃 |

### 2.3 音频管道

| 环节 | 技术选择 | 理由 |
|------|---------|------|
| 麦克风采集 | UTS → `AudioRecord` (16kHz, mono) | Android 原生 API，低延迟 |
| Opus 编码 | Python `opuslib` + `libopus.so` (ARM64) | 直接复用 py-xiaozhi 编解码逻辑 |
| Opus 解码 | Python `opuslib` + `libopus.so` (ARM64) | 同上 |
| 扬声器播放 | UTS → `AudioTrack` (24kHz, mono) | Android 原生 API，低延迟 |
| 重采样 | AudioRecord 直接采 16kHz，AudioTrack 直接播 24kHz | 省去 soxr 依赖 |
| 唤醒词 | sherpa-onnx Android SDK | 官方 Android 支持，ONNX 原生推理 |
| AEC / 降噪 | Android 内置 `AcousticEchoCanceler` / `NoiseSuppressor` | 系统 API，无需额外库 |

### 2.4 后台运行

| 组件 | 技术 | 说明 |
|------|------|------|
| 前台服务 | `ForegroundService` + 通知 | 保持 Python 后端活跃 |
| WakeLock | `PARTIAL_WAKE_LOCK` | CPU 保持唤醒（唤醒词检测） |
| WiFi 锁 | `WIFI_MODE_FULL_HIGH_PERF` | 保持网络连接稳定 |

---

## 3. 模块详细设计

### 3.1 前端模块 (UniApp X)

#### 页面模块

```
pages/
├── index/index.vue          # 主页面
│   ├── 语音对话界面
│   ├── 实时音频波形
│   ├── 对话历史
│   ├── 情绪表情显示
│   └── 底部控制栏（开始/停止/静音）
│
├── settings/settings.vue    # 设置页面
│   ├── 音频设备配置
│   ├── 唤醒词开关
│   ├── 协议选择 (WebSocket / MQTT)
│   ├── 服务器地址配置
│   ├── MCP 工具管理
│   └── 关于 / 版本信息
│
└── activation/activation.vue # 激活页面
    ├── 二维码扫描
    ├── 激活码输入
    └── 激活状态显示
```

#### 状态管理 (Pinia)

```typescript
// store/app.ts - 应用全局状态
interface AppState {
  deviceState: 'IDLE' | 'CONNECTING' | 'LISTENING' | 'SPEAKING'
  listeningMode: 'REALTIME' | 'AUTO_STOP' | 'MANUAL'
  isConnected: boolean
  isWakeWordEnabled: boolean
  errorMessage: string | null
}

// store/audio.ts - 音频状态
interface AudioState {
  isRecording: boolean
  isPlaying: boolean
  volumeLevel: number          // 0-100
  audioWaveData: Float32Array   // 实时波形数据
  inputDevice: string
  outputDevice: string
}

// store/settings.ts - 设置状态
interface SettingsState {
  websocketUrl: string
  mqttBroker: string
  protocol: 'websocket' | 'mqtt'
  wakeWordEnabled: boolean
  wakeWordSensitivity: number
  opusOutputSampleRate: number
  mcpTools: McpToolConfig[]
}
```

#### 后端通信服务

```typescript
// services/backend.ts
class BackendService {
  private ws: WebSocket | null = null

  // 连接 Python 后端
  async connect(): Promise<void>

  // 发送指令
  sendCommand(command: string, params?: Record<string, any>): void

  // 监听事件
  onEvent(event: string, callback: (data: any) => void): void

  // HTTP 请求（配置等）
  async getConfig(key: string): Promise<any>
  async setConfig(key: string, value: any): Promise<void>

  // 生命周期
  disconnect(): void
}
```

### 3.2 后端模块 (Python)

#### 本地服务器

```python
# server.py
class LocalServer:
    """Python 本地服务器，嵌入 Android APK 中运行"""

    def __init__(self):
        self.app = web.Application()
        self.ws_clients: list[web.WebSocketResponse] = []

    async def start(self, port: int = 18080):
        """启动本地 HTTP + WebSocket 服务"""

    # WebSocket 处理
    async def ws_handler(self, request: web.Request):
        """处理前端 WebSocket 连接"""

    # HTTP 路由
    async def get_config(self, request: web.Request):
    async def set_config(self, request: web.Request):
    async def get_status(self, request: web.Request):
    async def get_devices(self, request: web.Request):
```

#### 音频桥接（替代 sounddevice）

```python
# audio/audio_bridge.py
class AudioBridge:
    """
    音频 I/O 桥接层，替代 sounddevice。
    通过 Chaquopy JNI 调用 Android 原生音频 API。

    与 py-xiaozhi 的 StreamManager 接口保持一致，
    使 AudioCodec 无需修改即可使用。
    """

    def __init__(self):
        self._input_callback: Callable | None = None
        self._output_callback: Callable | None = None

    def set_input_callback(self, callback: Callable):
        """设置音频输入回调（等价于 sounddevice.InputStream 回调）"""

    def set_output_callback(self, callback: Callable):
        """设置音频输出回调（等价于 sounddevice.OutputStream 回调）"""

    def start_input_stream(self, sample_rate: int, channels: int,
                           blocksize: int, callback: Callable):
        """启动麦克风采集（调用 UTS → AudioRecord）"""

    def start_output_stream(self, sample_rate: int, channels: int,
                            blocksize: int, callback: Callable):
        """启动扬声器播放（调用 UTS → AudioTrack）"""

    def stop_input_stream(self):
        """停止采集"""

    def stop_output_stream(self):
        """停止播放"""

    def write_output(self, data: np.ndarray):
        """向播放缓冲区写入数据"""
```

#### 核心模块（直接复用）

以下模块从 py-xiaozhi **原样复用**，无需修改：

| 模块 | 文件 | 复用程度 |
|------|------|---------|
| 事件总线 | `core/event_bus.py` | 100% |
| 状态管理器 | `core/state_manager.py` | 100% |
| 任务管理器 | `core/task_manager.py` | 100% |
| 协议基类 | `protocols/protocol.py` | 100% |
| WebSocket 协议 | `protocols/websocket_protocol.py` | 95%（调整 SSL 验证） |
| MQTT 协议 | `protocols/mqtt_protocol.py` | 90%（调整 UDP socket） |
| 插件基类 | `plugins/base.py` | 100% |
| 插件管理器 | `plugins/manager.py` | 100% |
| MCP 服务器 | `mcp/mcp_server.py` | 100% |
| MCP 装饰器 | `mcp/decorators.py` | 100% |
| Opus 编解码 | `audio/opus_codec.py` | 90%（调整库加载路径） |
| 音频格式转换 | `audio/audio_converter.py` | 85%（可能去掉 soxr） |
| 音频缓冲 | `audio/audio_buffer.py` | 100% |

### 3.3 原生插件模块 (UTS)

#### 音频插件 (`xiaozhi-audio`)

```typescript
// nativeplugins/xiaozhi-audio/index.uts

/**
 * 音频采集和播放的 UTS 原生插件
 * 编译为 Kotlin，直接调用 Android AudioRecord / AudioTrack
 */

// 麦克风采集
export class AudioRecorder {
  private recorder: android.media.AudioRecord
  private sampleRate: number = 16000
  private channels: number = 1   // CHANNEL_IN_MONO
  private encoding: number = 2   // ENCODING_PCM_FLOAT

  start(): void {
    const bufferSize = android.media.AudioRecord.getMinBufferSize(
      this.sampleRate,
      android.media.AudioFormat.CHANNEL_IN_MONO,
      android.media.AudioFormat.ENCODING_PCM_FLOAT
    )
    this.recorder = new android.media.AudioRecord(
      android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
      this.sampleRate,
      android.media.AudioFormat.CHANNEL_IN_MONO,
      android.media.AudioFormat.ENCODING_PCM_FLOAT,
      bufferSize * 2
    )
    this.recorder.startRecording()
    // 读取循环通过 JNI 回调 Python 端
  }

  stop(): void {
    this.recorder.stop()
    this.recorder.release()
  }
}

// 扬声器播放
export class AudioPlayer {
  private track: android.media.AudioTrack
  private sampleRate: number = 24000

  start(): void {
    const bufferSize = android.media.AudioTrack.getMinBufferSize(
      this.sampleRate,
      android.media.AudioFormat.CHANNEL_OUT_MONO,
      android.media.AudioFormat.ENCODING_PCM_FLOAT
    )
    this.track = new android.media.AudioTrack(
      android.media.AudioManager.STREAM_VOICE_CALL,
      this.sampleRate,
      android.media.AudioFormat.CHANNEL_OUT_MONO,
      android.media.AudioFormat.ENCODING_PCM_FLOAT,
      bufferSize,
      android.media.AudioTrack.MODE_STREAM
    )
    this.track.play()
  }

  write(data: Float32Array): number {
    return this.track.write(data, 0, data.length,
      android.media.AudioTrack.WRITE_BLOCKING)
  }

  stop(): void {
    this.track.stop()
    this.track.release()
  }
}

// 音效处理（AEC + 降噪）
export class AudioEffects {
  // Android 内置 AEC
  static getAEC(): android.media.audiofx.AcousticEchoCanceler | null {
    if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
      return android.media.audiofx.AcousticEchoCanceler.create(0)
    }
    return null
  }

  // Android 内置降噪
  static getNS(): android.media.audiofx.NoiseSuppressor | null {
    if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
      return android.media.audiofx.NoiseSuppressor.create(0)
    }
    return null
  }
}
```

#### 前台服务插件 (`xiaozhi-service`)

```typescript
// nativeplugins/xiaozhi-service/index.uts

export class ForegroundService {
  /**
   * 启动前台服务，保持 Python 后端活跃
   * 显示常驻通知："小智正在运行"
   */
  static start(title: string, content: string): void

  /**
   * 停止前台服务
   */
  static stop(): void

  /**
   * 获取 WakeLock（CPU 保持唤醒，用于唤醒词检测）
   */
  static acquireWakeLock(): void

  /**
   * 释放 WakeLock
   */
  static releaseWakeLock(): void

  /**
   * 获取 WiFi 锁（保持网络连接）
   */
  static acquireWifiLock(): void
}
```

#### 唤醒词插件 (`xiaozhi-wakeword`)

```typescript
// nativeplugins/xiaozhi-wakeword/index.uts

/**
 * 封装 sherpa-onnx Android SDK
 * 在原生层进行唤醒词检测，回调到 Python 端
 */
export class WakeWordDetector {
  private spotter: com.k2fsa.sherpa.onnx.KeywordSpotter
  private stream: com.k2fsa.sherpa.onnx.OnlineStream

  /**
   * 初始化唤醒词检测器
   * @param modelDir 模型文件目录（assets 中解压）
   * @param threshold 检测阈值（默认 0.2）
   */
  init(modelDir: string, threshold: number): void

  /**
   * 喂入音频数据
   * @param samples Float32 PCM 数据，16kHz 单声道
   */
  acceptWaveform(samples: Float32Array): void

  /**
   * 检测是否触发唤醒词
   * @returns 是否检测到唤醒词
   */
  isDetected(): boolean

  /**
   * 重置检测流
   */
  reset(): void

  /**
   * 释放资源
   */
  release(): void
}
```

---

## 4. 数据流设计

### 4.1 音频输入流（麦克风 → 服务器）

```
[Android AudioRecord]          原生层
    │ 16kHz mono float32
    │ 每 20ms 一帧 (320 samples)
    ▼
[UTS 插件回调] ──JNI──→       原生层 → Python
    │
    ▼
[AudioBridge.input_callback]   Python 业务层
    │
    ├──→ [WakeWordDetector]     唤醒词检测（仅在 IDLE 状态）
    │       └── 检测到 → EventBus("wake_word_detected")
    │
    ├──→ [AudioConverter]       下混 + 重采样（如果设备非 16kHz）
    │
    ├──→ [OpusCodec.encode]     Opus 编码
    │
    └──→ [Protocol.send_audio]  WebSocket/MQTT 发送到服务器
```

### 4.2 音频输出流（服务器 → 扬声器）

```
[小智服务器]                    外部
    │ Opus encoded bytes
    ▼
[Protocol.on_audio]            Python 业务层
    │
    ├──→ [OpusCodec.decode]     Opus 解码 → float32
    │
    └──→ [AudioBridge.write]    写入播放缓冲区
            │
            ▼
    [UTS 插件] ──JNI──→        Python → 原生层
            │
            ▼
    [Android AudioTrack]        原生层
        24kHz mono float32
        → 扬声器播放
```

### 4.3 前端状态同步流

```
[Python 后端]                   业务层
    │ EventBus 事件
    ▼
[LocalServer.ws_broadcast]     业务层
    │ WebSocket JSON 消息
    ▼
[UniApp BackendService]        展示层
    │ 解析事件类型
    ▼
[Pinia Store 更新]             展示层
    │ 响应式数据变化
    ▼
[Vue 组件重新渲染]             展示层
```

**前端 → 后端 指令流：**

```
[用户操作] (点击按钮/修改设置)  展示层
    │
    ▼
[BackendService.sendCommand]   展示层
    │ WebSocket JSON 消息
    ▼
[LocalServer.ws_handler]       业务层
    │ 解析指令
    ▼
[执行对应操作]                 业务层
    │ 状态变化 → EventBus
    ▼
[ws_broadcast] → 前端          反馈循环
```

### 4.4 对话交互完整流程

```
用户              UniApp前端              Python后端              小智服务器
 │                   │                       │                       │
 │ "你好小智"        │                       │                       │
 │ (唤醒词)          │                       │                       │
 │──────────────────→│                       │                       │
 │                   │  cmd:start_listening   │                       │
 │                   │──────────────────────→│                       │
 │                   │                       │   WebSocket connect   │
 │                   │                       │──────────────────────→│
 │                   │  event:LISTENING       │                       │
 │                   │←──────────────────────│                       │
 │ (开始说话)        │  [显示: 正在聆听]      │                       │
 │                   │                       │   audio bytes →       │
 │                   │                       │──────────────────────→│
 │                   │                       │                       │
 │                   │                       │  ← text + audio bytes │
 │                   │                       │←──────────────────────│
 │                   │  event:SPEAKING        │                       │
 │                   │  event:text_response   │                       │
 │                   │  event:audio_playing   │                       │
 │                   │←──────────────────────│                       │
 │                   │  [显示: AI 回复文字]    │                       │
 │                   │  [播放: AI 语音]       │                       │
 │                   │                       │                       │
 │                   │  event:IDLE            │                       │
 │                   │←──────────────────────│                       │
 │                   │  [显示: 待机状态]      │                       │
```

---

## 5. 关键设计决策

### 5.1 为什么用 localhost WebSocket 而非直接 JNI 调用？

| 方案 | 优点 | 缺点 |
|------|------|------|
| **localhost WebSocket** ✅ | 解耦前后端、可独立调试、协议标准化 | 增加约 1-2ms 延迟 |
| 直接 Chaquopy JNI | 延迟最低 | 强耦合、调试困难、UniApp 无法直接调用 |
| Unix Domain Socket | 比 TCP 延迟更低 | Android 支持不稳定、UniApp 不支持 |

**选择理由**：WebSocket 方案允许在**桌面浏览器中独立调试前端**（连接桌面运行的 Python 后端），开发效率远高于必须在 Android 设备上调试。1-2ms 的额外延迟在语音交互场景中可忽略不计（总延迟主要由网络和 ASR/TTS 决定）。

### 5.2 为什么音频 I/O 用 UTS 原生而非 Python？

- Python 的 `sounddevice` 依赖 PortAudio，Android 上不可用
- Android 的 `AudioRecord` / `AudioTrack` 必须从 Java/Kotlin 调用
- UTS 编译为 Kotlin，性能等同手写原生代码
- 音频 I/O 在原生层可以保证回调的实时性

### 5.3 为什么保留 Python 后端而非全部用 TypeScript 重写？

| 维度 | 保留 Python | 全部重写为 TS |
|------|-----------|-------------|
| 代码复用 | ~60% 直接复用 | 0% |
| 开发时间 | 2-3 个月 | 4-6 个月 |
| 维护成本 | 需同时维护 Python + Vue | 仅 Vue + TS |
| APK 体积 | +20MB (Python 运行时) | 更小 |
| C 扩展兼容 | opuslib/sherpa-onnx 需 Android 编译 | 需找 JS/WASM 替代 |

**选择理由**：保留 Python 可以**最大化利用 py-xiaozhi 已有的稳定逻辑**（协议处理、状态机、插件系统、MCP 工具框架），避免重复造轮子和引入新 bug。

### 5.4 为什么选择 Chaquopy 而非 Termux？

- **Chaquopy**：Python 嵌入 APK，用户安装后直接可用，支持 pip 安装纯 Python 包
- **Termux**：需要用户自行安装 Termux + Python，门槛高，不适合普通用户
- **打包发布**：Chaquopy 打包的 APK 可以直接上架应用商店

---

## 6. 性能预算

| 环节 | 目标延迟 | 说明 |
|------|---------|------|
| 麦克风 → AudioRecord 回调 | < 5ms | 原生层，20ms 帧长内 |
| AudioRecord → Python (JNI) | < 3ms | 内存拷贝 |
| Opus 编码 (320 samples) | < 1ms | libopus C 库 |
| 本地 WebSocket 传输 | < 2ms | localhost 回环 |
| Python → UniApp 状态同步 | < 5ms | localhost WebSocket + Pinia |
| **端到端（麦克风 → 发送到服务器）** | **< 15ms** | **满足实时语音要求** |

---

## 7. 安全设计

### 7.1 本地通信安全

- localhost WebSocket 仅监听 `127.0.0.1`，外部不可访问
- 无需 TLS（本地回环）

### 7.2 远程通信安全

- WebSocket：TLS + Bearer Token（复用 py-xiaozhi 逻辑）
- MQTT：TLS (port 8883) + AES-CTR 加密音频（复用 py-xiaozhi 逻辑）

### 7.3 数据存储

- 配置文件存储在 Android 应用私有目录 (`/data/data/<pkg>/`)
- 其他应用无法读取
- 卸载时自动清除

### 7.4 权限模型

```xml
<!-- AndroidManifest.xml 必需权限 -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />        <!-- 麦克风 -->
<uses-permission android:name="android.permission.INTERNET" />            <!-- 网络 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />  <!-- 后台服务 -->
<uses-permission android:name="android.permission.WAKE_LOCK" />           <!-- CPU 保活 -->
<uses-permission android:name="android.permission.CAMERA" />              <!-- 摄像头 (MCP 工具) -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />  <!-- 通知栏 -->
```
