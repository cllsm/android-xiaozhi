# xiaozhi

小智陪伴机器人 Android 原生客户端。项目只包含一个 Gradle `:app` 模块，交付物是单个 APK。

## 功能概览

- Kotlin + Jetpack Compose + Material 3，深浅主题适配
- OTA v2 激活、WebSocket、Opus 编解码、连续聆听
- sherpa-onnx 离线唤醒词与自定义中文唤醒词
- 聊天记录、文本输入、屏幕识别、拍照识别和 MCP 工具
- 多源音乐搜索、网易云无损优先、候选序号选择、最近播放队列
- 语音前台服务、系统悬浮球和音乐“灵动岛”

## 使用与转载说明

转载、引用、参考、二开或分发本项目的代码、文档、截图、构建产物时，须在显著位置注明出处：

```text
基于 cllsm/xiaozhi 二次开发
https://github.com/cllsm/xiaozhi
```

二开项目还需说明已修改的内容，并保留原项目的出处信息；不得暗示本项目官方为衍生项目背书。第三方组件与模型仍须遵守其原始协议，例如 sherpa-onnx 及随包模型相关文件采用 Apache License 2.0。本项目不授予第三方服务、音乐内容、服务器接口或用户凭证的任何权利。

## 环境要求

- JDK 17
- Android SDK API 36
- Android Studio
- minSdk 26，targetSdk 36

## 构建与运行

在仓库根目录执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

调试 APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

发布构建：

```powershell
.\gradlew.bat :app:assembleRelease
```

## 本机配置

`local.properties` 不入库，示例：

```properties
sdk.dir=D:/Android/sdk
XIAOZHI_NETEASE_LOSSLESS_APP_KEY=your-app-key
```

发布签名与 ABI 收敛也支持环境变量或 `local.properties`：`XIAOZHI_STORE_FILE`、`XIAOZHI_STORE_PASSWORD`、`XIAOZHI_KEY_ALIAS`、`XIAOZHI_KEY_PASSWORD`、`XIAOZHI_RELEASE_ABIS`。未配置 AppKey 时应用仍可构建，自动多源音乐会跳过网易云无损。

## 源码结构

```text
app/src/main/java/com/xiaozhi/android/
├── audio/    # 录音、播放、Opus MediaCodec
├── core/     # 状态、设置模型与校验
├── data/     # DataStore、聊天、音乐与设备凭证
├── mcp/      # MCP 分发、视觉与系统工具
├── media/    # 截屏、拍照、音乐多源与播放器
├── network/  # OTA 与 WebSocket
├── service/  # 语音前台服务与系统悬浮窗
├── ui/       # Compose 界面
└── wake/     # sherpa-onnx 唤醒词
```
