# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

小智陪伴机器人 Android 原生客户端。单个 Gradle `:app` 模块，Kotlin + Jetpack Compose + Material 3，交付物为单个 APK。JDK 17，compileSdk/targetSdk 36，minSdk 26。根目录 `AGENTS.md` 与 `README.md` 包含仓库指南，`NOTICE.md` 为出处声明。

## 常用命令

```bash
./gradlew.bat :app:testDebugUnitTest          # 运行全部单元测试
./gradlew.bat :app:testDebugUnitTest --tests "com.xiaozhi.android.media.KuwoMusicSelectorTest"  # 运行单个测试类
./gradlew.bat :app:assembleDebug              # 调试 APK → app/build/outputs/apk/debug/
./gradlew.bat :app:assembleRelease            # 发布 APK（需签名配置）
```

PowerShell 中将 `./gradlew.bat` 写作 `.\gradlew.bat`。提交前至少运行 `testDebugUnitTest`；涉及 UI 或发布配置时再运行对应的 `assembleDebug` / `assembleRelease`。

## 架构

### 全局状态中心：VoiceSessionState

`core/VoiceSessionState.kt` 是单例 object，持有全应用可观察状态（连接状态、设备状态、聊天记录、音量电平），通过 StateFlow 暴露。**服务层写入、UI 层读取**——Service 与 Activity/Compose 之间没有 Binder，全靠这个状态单例通信。`ui/XiaozhiViewModel` 基本是对它的薄封装加业务编排。类似的全局单例还有 `media/MusicPlaybackState`（音乐播放状态）和 `media/NativeMusicController`（音乐编排）。

### 应用心脏：VoiceForegroundService

`service/VoiceForegroundService.kt`（前台服务，microphone+camera 类型）承载全部语音会话逻辑：

1. **OTA 激活**：`network/OtaClient.fetch()` 获取配置；未激活时展示验证码并调用 `activate()`，成功后把 WebSocket URL/token 持久化到 `SettingsRepository`。
2. **连接循环**：指数退避重连（2s 起、上限 30s）；连续失败达到 `connectRetryCount` 上限后调用 `enterStandby()` 进入**待命模式**——只保留离线唤醒词监听（低功耗路径），屏幕点亮（SCREEN_ON/USER_PRESENT 广播）或唤醒词命中时重新触发 `requestConnection()`。
3. **connectOnce()**：每次连接组装一套对象——`XiaozhiWebSocketClient` + `AudioInputEngine`/`AudioOutputEngine`（Opus 编解码）+ 可选 `SherpaWakeWordEngine` + `McpDispatcher`，连接关闭时整套销毁。
4. **外部命令入口**：companion object 上的静态方法（`sendText`、`startListening`、`setWakeWordEnabled` 等）配合并发队列（`pendingTexts`、`conversationCommands`、`pendingWakeWords`），让 UI、悬浮球、通知栏在服务未连接时也能投递意图，连接建立后由 `drainPendingTexts` 等补发。

### WebSocket 协议与服务端消息处理

`network/XiaozhiWebSocketClient.kt` 收发：二进制 Opus 音频帧、JSON 消息（hello/tts/stt/mcp/emotion）。`onJson` 回调（在 VoiceForegroundService 内实现）中有两处关键拦截：

- **STT 本地音乐拦截**：识别文本先经 `NativeMusicController.pendingSelectionIndex()`（候选序号）和 `MusicIntentParser.extractSongName()`（点歌意图）匹配，命中则 `sendAbortSpeaking()` 中断服务端应答并改走本地音乐播放，不发给 LLM。
- **TTS 与音乐互斥**：tts start 时暂停音乐（`NativeMusicController.pause(source = "tts")`），tts stop 时恢复并自动继续聆听。

### MCP 工具系统

`mcp/McpDispatcher.kt` 在客户端实现 JSON-RPC 2.0 服务端（initialize / tools/list 分页 / tools/call），工具列表按 `SettingsState` 动态注册：音量、应用启动、截屏、拍照、天气，以及 `musicEnabled` 时的整套音乐工具。服务端经 WebSocket 下发 `type: "mcp"` 消息，结果原路回传。MCP initialize 响应中的 `capabilities.vision` 会配置 `VisionService`（截图/拍照识别走独立 HTTP 多部分上传，不走 WebSocket）。

### 音乐子系统

`media/NativeMusicController.kt`（单例）编排多源搜索与播放：各音源一个 `MusicSelector`（酷我、网易云、网易云无损、Audius、iTunes），按 `musicSourceMode` 与可用性选择。多候选时通过 `selectionPrompt`（StateFlow）提示用户回复序号，序号解析回到 `pendingSelectionIndex`。播放历史在 `data/MusicHistoryRepository`。网易云无损网关 AppKey 经 `BuildConfig.NETEASE_LOSSLESS_APP_KEY` 注入，未配置时自动跳过该源。

### 三个前台服务

- `VoiceForegroundService`：语音会话（见上）。
- `MediaProjectionForegroundService`：为屏幕截取保持 MediaProjection 前台。
- `SystemOverlayService`：悬浮球 + 音乐"灵动岛"（SYSTEM_ALERT_WINDOW 传统 View，非 Compose），直接观察 `VoiceSessionState` / `MusicPlaybackState`。

### 数据层与唤醒词

- `data/`：`SettingsRepository`（DataStore Preferences）、`ChatHistoryRepository`、`MusicHistoryRepository`、`DeviceIdentityRepository` / `DeviceCredentialRepository`（设备激活凭证，支持带密码加密导入导出，恢复后需重启语音服务）、`DiagnosticRepository`（诊断报告）。
- `wake/`：`SherpaWakeWordEngine` 基于 sherpa-onnx KWS（AAR 在 `app/libs/`，模型在 `app/src/main/assets/models/zh/`）；`WakeWordKeywordBuilder` 用 pinyin4j 将自定义中文唤醒词转拼音动态生成 keywords，修改唤醒词相关设置后需经 `VoiceForegroundService.reloadWakeWord()` 生效。
- `audio/`：`AudioInputEngine` 录音→Opus 编码→WebSocket，同时把原始采样喂给唤醒词引擎；`AudioOutputEngine` 解码播放并回报电平。

## 配置与安全

Android SDK 路径、发布签名、网易云无损 AppKey 写入根目录 `local.properties`（不入库）或同名环境变量，键名：`sdk.dir`、`XIAOZHI_STORE_FILE`、`XIAOZHI_STORE_PASSWORD`、`XIAOZHI_KEY_ALIAS`、`XIAOZHI_KEY_PASSWORD`、`XIAOZHI_RELEASE_ABIS`、`XIAOZHI_NETEASE_LOSSLESS_APP_KEY`。不要提交 `local.properties`、证书、密码、AppKey、APK 或构建目录。

## 代码风格与提交规范

- Kotlin 4 空格缩进，官方 Android 风格；类/对象 `PascalCase`，函数/变量 `camelCase`，常量 `UPPER_SNAKE_CASE`。
- Compose 组件保持单一职责，状态优先使用 `StateFlow`；代码注释使用中文。
- 新增测试文件命名 `*Test.kt`（JUnit 4 纯单元测试，无 Robolectric，主要覆盖解析器、校验器与编解码逻辑）。
- 提交信息遵循 Conventional Commits（`feat:`、`fix:`、`refactor:` 等），使用中文描述。

## 出处与分发要求

转载、引用、二开或分发本仓库时，必须保留 `NOTICE.md` 并在显著位置注明 `cllsm/xiaozhi` 与仓库地址；二开项目需写明修改内容，不得表述为原项目官方版本。
