# Android 原生重写计划

## 0. 当前进度

截至 2026-08-20，阶段 0 到阶段 5 的可编译基线与 P0-P2 产品化增强已落到 `native-stage/app`，并同步到 `D:\Myapp\AndroidExamples\HelloAndroid\app`：

1. 单 Gradle `:app`、Kotlin、Compose、Material 3、`com.xiaozhi.android` 已完成，`:app:assembleDebug` 通过。
2. DataStore 设置、设备身份、OTA 配置拉取、WebSocket 认证握手、激活码展示和指数退避重连已接入。
3. `AudioRecord`、Android `MediaCodec` Opus 编解码、`AudioTrack` 播放和 `listen/start/detect` 消息已接入。
4. 官方 sherpa-onnx `v1.13.6` AAR 与 `models/zh` 已打包，唤醒词检测链路已编译通过。
5. Activation v2 已实现：持久化序列号与 HMAC key，OTA 下发 challenge 后签名轮询 `/activate`，成功后重新获取 WebSocket 配置。
6. MCP 已接入 initialize、分页 tools/list、tools/call，以及音量、应用启动/扫描、截图、拍照、天气、音乐工具；视觉分析 URL/token 由服务端 capabilities 下发，工具在 IO 协程执行。
7. 首页已有真实 `stt/tts` 聊天记录、输入/输出音量波形、激活状态、相机权限和 MediaProjection 授权入口。
8. 首页已按旧版结构复刻：顶部状态与工具按钮、快捷标签、聊天气泡、实时字幕、情绪、波形、错误条、底部控制、文本输入与拍照入口。
9. 手动聆听已对齐旧行为：连接后空闲，点击“开始对话”才发送 manual listen 并开启麦克风；“正在聆听”点击停止；唤醒词监听是独立开关。
10. 激活流程保持 OTA v2，验证码改为首页 `AlertDialog` 弹窗展示，包含格式化验证码、Device-Id、复制并打开 xiaozhi.me，绑定成功后自动继续连接。
11. 悬浮窗已补齐：设置可开关；应用内为可拖拽悬浮球并提供开始/停止/打断/唤醒词操作；后台使用 `SYSTEM_ALERT_WINDOW` 系统悬浮球显示状态并点击回到 App。
12. 设置页已按旧版复刻：网络、唤醒词、音频、音乐、外观、应用行为、悬浮窗与关于；保存、清除聊天记录确认弹窗、主题持久化已在雷电验证。
13. 自定义中文唤醒词已接入 Pinyin4j 与 sherpa `createStream(keywords)`，保存后无需重连即可热更新检测参数。
14. 当前 debug APK 曾验证约为 155 MB，主要来自官方 AAR 的四组 ABI 原生库和 13 MB 模型；后续可按目标设备收敛 ABI。

### P0-P2 产品化增强

1. P0：首次启动分步引导覆盖麦克风、通知、相机、悬浮窗、MediaProjection 和电池优化建议；诊断页可检查权限、网络、配置、语音链路和后台运行，并支持复制/分享脱敏报告；服务层错误已转换为用户可理解文案； release 构建支持环境变量或 `local.properties` 签名配置和 ABI 收敛。
2. P1：聊天支持搜索、日期分隔、复制和用户消息重发；设置支持搜索、保存前校验、恢复默认、聊天 JSON 导出/导入；音乐错误按未启用、搜索、直链和播放细分。
3. P2：新增隐私说明页、可配置更新源的应用内更新检查（浏览器下载，不申请静默安装权限）、诊断反馈载体；语音服务通知提供打开 App、开始聆听和停止服务快捷操作。
4. 2026-08-20 已在雷电完成 debug 构建安装、首页/设置/诊断/引导冒烟、前台服务启动和通知快捷操作检查；`:app:testDebugUnitTest` 与 `:app:assembleDebug` 均通过。

尚未完成：真机 OTA/激活/WebSocket 联调、MediaCodec Opus 兼容性验证、sherpa-onnx 唤醒验证、截图与拍照权限链路验证、音乐接口实测、长稳测试和发布混淆签名。不要将当前状态视为可交付终版。

## 1. 结论

可以将本项目全部功能重写为 Android 原生应用，目标位置为：

```text
D:\Myapp\AndroidExamples\HelloAndroid\app
```

推荐采用 **Kotlin + Jetpack Compose + Coroutines/Flow + 前台服务**。最终交付物只有 **一个原生 Android 应用、一个 APK、一个应用包名、一个 Gradle `:app` 模块**。重写后移除 UniApp、Chaquopy、本地 aiohttp 服务和前后端 WebSocket 桥接，让 UI、业务状态、音频管道、系统工具全部运行在同一个 Android 原生进程内；Python 后端不会再作为独立 App、服务或运行时存在。

需要注意：这是完整架构重写，不是简单代码搬运。核心工作量在小智服务器协议兼容、Opus 音频链路、唤醒词、设备激活、后台保活和 MCP 工具权限处理。

## 2. 重写范围

| 现有模块 | 原生重写内容 |
|---|---|
| `frontend/src/pages/tab/home` | 首页状态、聊天记录、情绪表情、音频波形、监听控制 |
| `frontend/src/pages/tab/settings` | 网络配置、唤醒词、音频、音乐、主题、悬浮窗、聊天记录设置 |
| `frontend/src/pages/common/activation` | OTA v2 激活流程；验证码在首页弹窗展示，不再跳转独立页面 |
| `backend/protocols` | 首版重写为小智服务器 WebSocket 客户端、二进制音频帧、JSON 消息；MQTT 作为后续扩展 |
| `backend/audio_codecs` | Opus 编解码、采样率转换、音频缓冲、播放队列 |
| `backend/audio_processing` | 离线唤醒词检测、关键词配置、灵敏度参数 |
| `backend/activation` | OTA 配置、挑战签名、设备指纹、激活状态持久化 |
| `backend/mcp` | MCP 消息解析、工具注册、工具调用和结果返回 |
| Android 原生桥接 | 音量、摄像头、截图、应用启动、悬浮窗、Toast、前台服务 |
| 音乐工具 | 搜索、播放、暂停、恢复、停止、进度、歌词、本地缓存歌单 |
| 配置系统 | DataStore 持久化、敏感信息加密、主题和运行时参数热更新 |

## 3. 目标架构

```text
HelloAndroid/app/src/main/
├── AndroidManifest.xml
├── java/<现有应用包名>/xiaozhi/
│   ├── App.kt                       # Application、依赖容器
│   ├── MainActivity.kt              # Compose 唯一入口
│   ├── core/
│   │   ├── state/                   # 设备状态机、会话状态
│   │   ├── event/                   # Flow 事件总线
│   │   ├── config/                  # DataStore 配置
│   │   └── logging/                 # 文件与控制台日志
│   ├── network/
│   │   ├── websocket/               # OkHttp WebSocket
│   │   ├── mqtt/                    # 预留后续 MQTT 扩展，首版不实现
│   │   ├── protocol/                # JSON/二进制协议模型
│   │   └── activation/              # OTA 与激活流程
│   ├── audio/
│   │   ├── recorder/                # AudioRecord、AEC、降噪
│   │   ├── player/                  # AudioTrack、焦点、音乐避让
│   │   ├── codec/                   # Opus 编解码封装
│   │   └── pipeline/                # 帧队列、重采样、波形数据
│   ├── wake/
│   │   └── sherpa/                  # sherpa-onnx Android 集成
│   ├── mcp/
│   │   ├── McpDispatcher.kt
│   │   └── tools/                   # 音量、摄像头、截图、音乐、应用、天气
│   ├── platform/
│   │   ├── overlay/                 # 系统悬浮球
│   │   ├── camera/                  # CameraX 拍照
│   │   ├── screenshot/              # MediaProjection 截图
│   │   ├── launcher/                # 应用查询与启动
│   │   └── volume/                  # AudioManager
│   ├── service/
│   │   └── VoiceForegroundService.kt
│   └── ui/
│       ├── home/
│       ├── settings/
│       ├── activation/
│       ├── theme/
│       └── components/              # 波形、聊天气泡、表情、状态栏
├── cpp/
│   └── opusjni/                     # 如使用 libopus JNI，则放在此处
├── assets/
│   └── models/zh/                   # 唤醒词 ONNX 与配置
└── res/
    ├── values/
    ├── drawable/
    └── xml/
```

目录中的 `xiaozhi/` 只是 app 内部的 Kotlin 包结构，不是多个应用。整个重写结果仍然只有一个 `:app` 模块；实际构建命令通常在工程根目录 `D:\Myapp\AndroidExamples\HelloAndroid` 执行。

## 4. 技术选型

| 领域 | 建议 |
|---|---|
| 语言 | Kotlin，最低 API 26，targetSdk 按目标工程当前配置 |
| UI | Jetpack Compose + Material 3；如果 HelloAndroid 已固定 XML，也可用 View 体系实现同一结构 |
| 异步 | Coroutines、Flow、`StateFlow` |
| WebSocket | OkHttp，保持现有 JSON 和二进制帧兼容 |
| MQTT | 首版不实现；后续可评估 HiveMQ MQTT Client 或 Paho Android |
| 序列化 | kotlinx.serialization |
| 配置 | DataStore Preferences；令牌、密码用 EncryptedSharedPreferences 或 Keystore 加密 |
| 音频采集 | `AudioRecord`，16 kHz、单声道、PCM16 |
| 音频播放 | `AudioTrack`，24 kHz 或用户配置采样率 |
| Opus | 优先集成 Android 版 libopus JNI；备选 Java Opus 实现，但必须做兼容性测试 |
| 唤醒词 | sherpa-onnx Android SDK/AAR，继续复用 `models/zh` |
| 摄像头 | CameraX |
| 后台 | 前台服务 + `PARTIAL_WAKE_LOCK` + WiFi Lock |

### 唤醒词选型：sherpa-onnx KWS

唤醒词不重复造轮子，直接采用 GitHub 开源项目 [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)：

- 官方支持 Android、Kotlin/Java、arm64-v8a、armeabi-v7a、x86、x86_64。
- 官方提供 Keyword Spotting 和专用 Android 示例 `android/SherpaOnnxKws`。
- Kotlin API 已包含 `KeywordSpotter`、`KeywordSpotterConfig`、`OnlineStream`。
- 现有 `models/zh` 就是 sherpa-onnx KWS 使用的 Zipformer 模型，Apache-2.0，模型总计约 13 MB，可随 APK 打包或后续改为首次启动下载。

原生接入方式：

1. 从 sherpa-onnx `v1.13.6` 官方发布产物获取 AAR，或将官方 Kotlin API 与 `libsherpa-onnx-jni.so` 放入 `app/libs`、`app/src/main/jniLibs`。
2. 将 `encoder.onnx`、`decoder.onnx`、`joiner.onnx`、`tokens.txt`、`keywords.txt` 放入 `app/src/main/assets/models/zh/`。
3. 使用 `KeywordSpotter(assetManager, KeywordSpotterConfig(...))` 初始化。
4. `AudioRecord` 以 16 kHz、单声道、PCM16 采集，每约 100 ms 读取一次并转成 `FloatArray`。
5. 调用 `stream.acceptWaveform(samples, sampleRate = 16000)`，循环执行 `isReady` → `decode` → `getResult`。
6. 检测到关键词后立即 `reset(stream)`，并发送 `WakeWordDetected` 事件给状态机。
7. `keywordsScore` 与 `keywordsThreshold` 从 DataStore 读取，保留现有设置范围。

官方示例的关键调用流程如下：

```kotlin
val spotter = KeywordSpotter(
    assetManager = context.assets,
    config = KeywordSpotterConfig(
        featConfig = getFeatureConfig(sampleRate = 16000, featureDim = 80),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "models/zh/encoder.onnx",
                decoder = "models/zh/decoder.onnx",
                joiner = "models/zh/joiner.onnx",
            ),
            tokens = "models/zh/tokens.txt",
            modelType = "zipformer2",
        ),
        keywordsFile = "models/zh/keywords.txt",
        keywordsScore = 1.8f,
        keywordsThreshold = 0.25f,
    ),
)
```

首版只重写唤醒词业务封装，不重写识别引擎、特征提取、解码搜索或模型。自定义中文文本通过 Pinyin4j 生成 Sherpa 关键词行，并调用 `createStream(keywords)`；Pinyin4j 的第三声音调会统一规范为模型 token 使用的写法。

## 5. 权限与系统限制

需要在 Manifest 中声明并做运行时申请：

- `RECORD_AUDIO`：麦克风和唤醒词。
- `CAMERA`：拍照。
- `INTERNET`、`ACCESS_NETWORK_STATE`：服务器通信。
- `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MICROPHONE`：Android 9+ 后台语音。
- `WAKE_LOCK`：后台保持检测。
- `SYSTEM_ALERT_WINDOW`：系统悬浮球，需要跳转系统授权页。
- `QUERY_ALL_PACKAGES` 或精确 `<queries>`：应用启动和扫描。
- `POST_NOTIFICATIONS`：Android 13+ 前台服务通知。
- `READ_MEDIA_AUDIO` 或分区存储 API：本地音乐缓存。

高风险限制：

1. Android 10+ 后台启动 Activity 受限，悬浮球点击回 App 要验证前台/后台行为。
2. Android 14+ 前台服务和麦克风权限管控更严格，服务启动时机必须合法。
3. 截图不能静默获取全局屏幕，需要 MediaProjection 用户授权。方案采用“一次授权、前台服务生命周期内复用”，授权结果不跨进程重启持久化；系统录屏/投屏提示可能仍会出现。
4. 扫描全部应用会受包可见性限制，优先配置精确 `<queries>`，避免只为工具能力申请宽泛权限。

## 6. 实施阶段

### 阶段 0：目标工程确认

1. 已确认允许升级到 Kotlin、Compose 和当前稳定版 Android Gradle Plugin。
2. 已确认 `HelloAndroid/settings.gradle` 只包含 `:app` 一个模块。
3. 已确认当前 AGP 为 `8.13.0`，`compileSdk/targetSdk 36`，`minSdk 23`；源码仅一个 Java `MainActivity`，可直接重建为 Kotlin + Compose。
4. 建议新建分支，例如 `feature/xiaozhi-native-rewrite`。
5. 将 `assets/sounds`、`models/zh` 迁移到 app 的资源目录。

验收：`gradlew.bat :app:assembleDebug` 能稳定通过。

### 阶段 1：应用骨架与状态模型

1. 建立 `App`、`MainActivity`、`VoiceForegroundService`。
2. 建立配置仓库和默认值，字段与现有设置页一一对应。
3. 建立设备状态机：`IDLE`、`LISTENING`、`SPEAKING`、连接中、错误。
4. 先实现 Compose 导航：首页、设置、激活。

验收：可安装启动，设置能保存并重启恢复。

### 阶段 2：服务器协议与激活

1. 用 kotlinx.serialization 定义现有 WebSocket JSON 消息。
2. 实现 OkHttp WebSocket 连接、心跳、重连、认证、二进制音频收发。
3. 用 Kotlin 重写设备指纹、挑战签名、OTA 配置拉取和激活流程。
4. 实现激活 UI 与激活结果事件。

验收：真实服务器可连接、可完成激活、可收发非音频 JSON 消息。

### 阶段 3：音频主链路

1. 实现 `AudioRecord` 采集、AEC、降噪、静音检测和音量计算。
2. 集成 Opus encoder，按协议输出 16 kHz 单声道帧。
3. 实现 Opus decoder、PCM 缓冲和 `AudioTrack` 播放。
4. 处理音频焦点：TTS 说话时暂停音乐，说完恢复。
5. 首页显示实时波形、录音状态和播放状态。

验收：完成一次稳定语音对话，延时可接受，无长期内存增长。

### 阶段 4：唤醒词与后台运行

1. 引入 sherpa-onnx `v1.13.6` Android AAR/JNI 库，锁定版本并记录来源。
2. 从 assets 加载中文模型和关键词，封装 `WakeWordEngine`。
3. 用前台服务中的 `AudioRecord` 持续供流，检测后自动连接/进入对话。
4. 将灵敏度、关键词得分、检测阈值接入设置页和 DataStore。
5. 完成前台服务通知、WakeLock、WiFi Lock 和服务生命周期。

验收：锁屏或后台时唤醒词可用，服务被系统回收后有清晰提示和恢复策略。

### 阶段 5：MCP 工具

按依赖顺序迁移：

1. 音量：`AudioManager`。
2. Toast 与应用启动：`PackageManager`、`Intent`。
3. 摄像头：CameraX 拍照、压缩、Base64/路径返回。
4. 截图：MediaProjection 授权、前台服务生命周期内复用投影实例、图片编码。
5. 天气：保留现有 HTTP API 逻辑。
6. 音乐：搜索、下载缓存、播放、暂停、恢复、停止、seek、歌词、歌单。

每个工具独立实现 `McpTool` 接口，并由 `McpDispatcher` 统一注册和调度。

验收：AI 下发工具调用后，结果格式与现有 Python 实现兼容。

### 阶段 6：UI 完整还原

1. 首页：状态栏、情绪表情、聊天气泡、快捷操作、波形、底部控制。
2. 设置页：网络、唤醒词、音频、音乐、外观、悬浮窗、聊天记录清理。
3. 深浅主题、持久化、无障碍、横竖屏和_SAFE_AREA_ 适配。
4. 聊天记录上限、清除、错误提示、连接重试。

验收：与现有 UniApp 页面逐项对照，核心交互不缺项。

### 阶段 7：稳定性、测试与发布

1. 单元测试：协议解析、状态机、配置、激活签名、音频帧切割、MCP 分发。
2. AndroidX Test：权限、前台服务、音频循环、设置页。
3. 真机矩阵：至少覆盖 Android 8、10、12、14。
4. 长稳测试：连续运行、后台运行、弱网、服务器断开重连、耳机切换。
5. 混淆、崩溃上报、日志轮转、发布签名。

验收：所有主流程通过，旧功能清单逐项勾选完成。

## 7. 关键实现映射

| 现有实现 | 原生实现 |
|---|---|
| `backend/server.py` 本地 HTTP/WS | 删除；UI 直接调用 Kotlin 服务层 |
| `frontend/src/api/backend/index.ts` | `XiaozhiConnection` + `Flow<ConnectionEvent>` |
| `backend/core/event_bus.py` | 多个类型化 `SharedFlow` 或 `EventBus` |
| `backend/core/state_manager.py` | `DeviceStateMachine` + `StateFlow<DeviceState>` |
| `backend/utils/config_manager.py` | `SettingsRepository` + DataStore |
| `backend/audio_codecs/opus_codec.py` | `OpusEncoder`/`OpusDecoder` JNI 封装 |
| `frontend/src/utils/native-bridge.ts` | Kotlin `platform/*` 模块 |
| `backend/mcp/tools/**` | `mcp/tools/*Tool.kt` |
| Pinia stores | ViewModel + Repository + Compose State |

## 8. 建议里程碑

- M1：原生壳、设置、配置持久化可用。
- M2：服务器连接、激活、JSON 协议可用。
- M3：录音、Opus、播放和首页对话闭环可用。
- M4：唤醒词和前台后台保活可用。
- M5：MCP 工具和音乐能力可用。
- M6：UI 完整还原、测试和发布准备完成。

按单人全职估算，M1-M6 大约需要 3 到 4 周；如果 sherpa-onnx、Opus JNI、截图或音乐平台接口遇到兼容问题，需要额外预留时间。

## 9. 决策状态与剩余确认

已确认：

1. 允许 HelloAndroid 升级到 Kotlin、Compose 和当前稳定版 Android Gradle Plugin。
2. 目标项目没有不能变更的既有包名和签名。默认包名使用 `com.xiaozhi.android`，发布签名后续单独创建。
3. 第一版只交付 WebSocket，不实现 MQTT；最终交付物仍然只有一个 APK。MQTT 可作为后续版本扩展。
4. 截图不要求每次重新授权，但接受系统提示。实现为首次截图时申请 MediaProjection，并在前台服务保持期间复用；服务停止或应用重启后按 Android 规则重新申请。
5. 唤醒词采用 GitHub 开源 sherpa-onnx KWS；默认将当前约 13 MB 模型随 APK 打包，如包体不可接受再改为首次启动下载。

剩余确认：

1. 已确认：音乐接口做成可配置，并先用现有默认值联调。默认搜索使用 `http://search.kuwo.cn/r.s`，直链使用 `https://lxmusicapi.onrender.com`，Key 默认 `share-v3`。配置保存在 DataStore，可随时替换；音乐功能可整体降级，不影响语音对话主链路。
2. 真实小智服务器联调条件见下表。使用默认 OTA 流程时，不需要手工提供 WebSocket 地址和 token，由服务器返回。

| 场景 | 需要提供 | 说明 |
|---|---|---|
| 官方服务 | OTA 地址 `https://api.tenclass.net/xiaozhi/ota/`，以及可登录 `https://xiaozhi.me/` 添加设备的控制台账号 | App POST OTA 后自动获得 WebSocket URL/token；未授权时显示验证码，用户在控制台完成绑定 |
| 自定义服务 | OTA 地址；若跳过 OTA，则还需 WebSocket 地址和 Bearer token | 响应需包含 `websocket.url`、`websocket.token`，可选 `activation`；激活地址约定为 `<OTA>/activate` |
| 可重复测试 | 控制台可删除/重置测试设备，或可更换测试 `Device-Id` | 否则只能测试一次新设备激活，无法覆盖重新授权、服务器取消授权等分支 |
| 环境要求 | 测试手机可访问 OTA/WSS 域名；自签证书需提供测试证书链 | 首版原生实现不应沿用 Python 版“跳过证书校验”的做法 |

可采用默认值、无需阻塞开工：

1. `minSdk 26`，优先测试 Android 10、12、14 真机。
2. `applicationId` 使用 `com.xiaozhi.android`。
3. 阶段 0 直接重建 `HelloAndroid/app/src/main`，保留 `local.properties` 等本机配置。
4. 发布签名和分发渠道在 M6 前处理，不阻塞开发。

确认后建议从阶段 0 和阶段 1 开始实施，不要直接并行迁移所有模块。
