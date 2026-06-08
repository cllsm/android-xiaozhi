# android-xiaozhi

> 小智陪伴机器人 Android 客户端 —— UniApp X + Python 本地架构

基于 [py-xiaozhi](../py-xiaozhi/) 重构的 Android 原生客户端，采用 **UniApp X (Vue 3) 前端 + Python 本地后端 + Android 原生插件** 三层架构，无需额外服务器开销。

---

## 架构概览

```
┌──────────────────────────────────────────────────────────────┐
│                     Android APK                              │
│                                                              │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │           UniApp X 前端 (Vue 3 + Vite)                  │ │
│  │   主界面 │ 设置 │ 激活 │ 情绪表情 │ 通知栏              │ │
│  └────────────────────┬────────────────────────────────────┘ │
│                       │ localhost WebSocket :18080            │
│  ┌────────────────────▼────────────────────────────────────┐ │
│  │           Python 后端 (aiohttp + Chaquopy)               │ │
│  │   协议层 │ 音频编解码 │ 状态机 │ 事件总线 │ MCP 工具     │ │
│  └──────────┬──────────────────────────┬───────────────────┘ │
│             │ UTS Native Bridge        │ WebSocket / MQTT    │
│  ┌──────────▼──────────────────┐       │                     │
│  │    UTS 原生插件 (→ Kotlin)   │       │                     │
│  │  AudioRecord │ AudioTrack   │       │                     │
│  │  ForegroundSvc │ WakeLock   │       │                     │
│  │  Volume │ Camera │ MediaCodec│      │                     │
│  └─────────────────────────────┘       │                     │
└─────────────────────────────────────────┼───────────────────┘
                                          │
                                          ▼
                                  小智服务器 (现有)
                              WebSocket / MQTT 端点
```

## 技术栈

| 层 | 技术 | 用途 |
|---|------|------|
| **前端** | UniApp X (Vue 3 + Composition API) | UI 界面、用户交互 |
| **状态管理** | Pinia | 全局状态、设备状态同步 |
| **UI 组件** | uni-ui / uView Plus | 基础组件库 |
| **后端** | Python 3.10+ (Chaquopy 嵌入) | 核心业务逻辑 |
| **本地通信** | aiohttp + WebSocket (localhost) | 前后端数据交换 |
| **音频 I/O** | UTS → Android AudioRecord/AudioTrack | 麦克风采集、扬声器播放 |
| **Opus 编解码** | libopus (Android ARM64 编译) | 音频压缩/解压 |
| **唤醒词** | sherpa-onnx Android SDK | 离线唤醒词检测 |
| **后台保活** | Android Foreground Service | 后台持续运行 |
| **打包** | HBuilderX + Chaquopy Gradle 插件 | 生成 APK |

## 项目结构

```
android-xiaozhi/
├── README.md                         # 本文件
├── docs/                             # 项目文档
│   ├── ARCHITECTURE.md               # 架构设计文档
│   ├── REFACTOR.md                   # 重构文档（从 py-xiaozhi 迁移）
│   ├── API.md                        # 前后端通信 API 设计
│   └── DEVELOPMENT.md                # 开发指南
│
├── frontend/                         # UniApp X 前端项目
│   ├── src/
│   │   ├── pages/                    # 页面
│   │   │   ├── index/                # 主页面（语音对话）
│   │   │   ├── settings/             # 设置页面
│   │   │   └── activation/           # 设备激活页
│   │   ├── components/               # 可复用组件
│   │   │   ├── ChatBubble.vue        # 对话气泡
│   │   │   ├── AudioWave.vue         # 音频波形动画
│   │   │   ├── EmotionFace.vue       # 情绪表情
│   │   │   └── StatusBar.vue         # 状态指示栏
│   │   ├── store/                    # Pinia 状态管理
│   │   │   ├── app.ts                # 应用全局状态
│   │   │   ├── audio.ts              # 音频状态
│   │   │   └── settings.ts           # 设置状态
│   │   ├── services/                 # 服务层
│   │   │   ├── backend.ts            # Python 后端通信
│   │   │   └── notification.ts       # 通知管理
│   │   ├── utils/                    # 工具函数
│   │   ├── App.vue
│   │   ├── main.ts
│   │   ├── manifest.json             # UniApp 配置
│   │   ├── pages.json                # 路由配置
│   │   └── uni.scss                  # 全局样式
│   ├── nativeplugins/                # UTS 原生插件
│   │   ├── xiaozhi-audio/            # 音频采集/播放
│   │   ├── xiaozhi-service/          # 前台服务 + 后台保活
│   │   └── xiaozhi-wakeword/         # 唤醒词检测
│   ├── package.json
│   └── vite.config.ts
│
├── backend/                          # Python 后端（Chaquopy 打包）
│   ├── server.py                     # aiohttp 本地服务入口
│   ├── core/                         # 核心模块
│   │   ├── event_bus.py              # 事件总线 ← 复用
│   │   ├── state_manager.py          # 状态管理器 ← 复用
│   │   ├── task_manager.py           # 任务管理器 ← 复用
│   │   └── config_manager.py         # 配置管理器 ← 改造
│   ├── audio/                        # 音频模块
│   │   ├── audio_bridge.py           # 音频桥接 ← 新增（替代 sounddevice）
│   │   ├── opus_codec.py             # Opus 编解码 ← 改造
│   │   ├── audio_converter.py        # 格式转换 ← 复用
│   │   └── audio_buffer.py           # 音频缓冲 ← 复用
│   ├── protocols/                    # 协议模块
│   │   ├── protocol.py               # 协议基类 ← 复用
│   │   ├── websocket_protocol.py     # WebSocket ← 复用
│   │   └── mqtt_protocol.py          # MQTT + UDP ← 复用
│   ├── plugins/                      # 插件系统
│   │   ├── base.py                   # 插件基类 ← 复用
│   │   ├── manager.py                # 插件管理 ← 复用
│   │   ├── audio_plugin.py           # 音频插件 ← 改造
│   │   └── mcp_plugin.py             # MCP 插件 ← 改造
│   ├── mcp/                          # MCP 工具系统
│   │   ├── mcp_server.py             # JSON-RPC 服务器 ← 复用
│   │   ├── decorators.py             # @mcp_tool 装饰器 ← 复用
│   │   └── tools/                    # MCP 工具实现
│   │       ├── volume/               # 音量控制 ← Android 适配
│   │       ├── camera/               # 摄像头 ← Android 适配
│   │       ├── music/                # 音乐播放 ← Android 适配
│   │       ├── app_launcher/         # 应用启动 ← Android 适配
│   │       └── weather/              # 天气查询 ← 复用
│   ├── services/                     # 业务服务
│   │   └── activation.py             # 设备激活 ← 改造
│   ├── libs/                         # 原生库（Android ARM64）
│   │   └── libopus/
│   │       ├── arm64-v8a/libopus.so
│   │       └── armeabi-v7a/libopus.so
│   ├── models/                       # 唤醒词模型文件
│   │   └── zh/
│   │       ├── encoder.onnx
│   │       ├── decoder.onnx
│   │       ├── joiner.onnx
│   │       └── tokens.txt
│   └── requirements.txt
│
└── android/                          # Android 壳项目（构建时生成/定制）
    ├── app/
    │   ├── src/main/
    │   │   ├── AndroidManifest.xml
    │   │   └── assets/               # Python 代码由 Chaquopy 自动注入
    │   └── build.gradle              # Chaquopy + UniApp 集成配置
    ├── build.gradle
    └── settings.gradle
```

## 与 py-xiaozhi 的关系

| | py-xiaozhi | android-xiaozhi |
|---|-----------|-----------------|
| **平台** | Windows / macOS / Linux (GPIO) | Android |
| **UI** | PySide6 (QML) / CLI / GPIO | UniApp X (Vue 3) |
| **音频 I/O** | sounddevice (PortAudio) | UTS → AudioRecord/AudioTrack |
| **Opus** | libopus (ctypes) | libopus Android ARM64 编译版 |
| **唤醒词** | sherpa-onnx Python | sherpa-onnx Android SDK |
| **核心逻辑** | Python 直接运行 | Python 嵌入 Android (Chaquopy) |
| **代码复用** | — | 核心模块 ~60% 直接复用 |

## 文档索引

| 文档 | 说明 |
|------|------|
| [架构设计](docs/ARCHITECTURE.md) | 整体架构、技术选型、模块设计、数据流 |
| [重构文档](docs/REFACTOR.md) | 从 py-xiaozhi 迁移的模块映射和改造方案 |
| [API 设计](docs/API.md) | 前端 UniApp ↔ Python 后端通信协议 |
| [开发指南](docs/DEVELOPMENT.md) | 环境搭建、开发流程、调试、构建发布 |

## 快速开始

> 详细的开发环境搭建请参阅 [开发指南](docs/DEVELOPMENT.md)

```bash
# 1. 前端开发（需要 HBuilderX）
cd frontend
npm install

# 2. 后端开发（可以先用桌面 Python 调试）
cd backend
pip install -r requirements.txt
python server.py  # 启动本地服务，前端通过 localhost:18080 连接

# 3. 联调：HBuilderX 运行到 Android 设备
# 前端连接手机上的 Python 后端（通过 Chaquopy 启动）
```

## 许可证

同 py-xiaozhi 项目
