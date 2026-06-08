# 开发指南

> android-xiaozhi 开发环境搭建、开发流程、调试方法和构建发布。

---

## 1. 环境要求

### 1.1 必需软件

| 软件 | 版本 | 用途 | 安装方式 |
|------|------|------|---------|
| **HBuilderX** | 最新版 | UniApp X 开发 IDE | [官网下载](https://www.dcloud.io/hbuilderx.html) |
| **Android Studio** | 最新版 | Android 原生开发/调试 | [官网下载](https://developer.android.com/studio) |
| **Python** | ≥3.10 | 后端开发 | [官网下载](https://python.org) |
| **Node.js** | ≥18 | 前端构建 | [官网下载](https://nodejs.org) |
| **JDK** | 17 | Android 构建 | Android Studio 内置 |
| **Android SDK** | API 33+ | Android 编译目标 | Android Studio SDK Manager |

### 1.2 可选软件

| 软件 | 用途 |
|------|------|
| **Android NDK** | 编译 libopus 等原生库 |
| **Gradle** | Android 构建工具（HBuilderX 内置） |
| **Chrome DevTools** | UniApp 调试（ WebView 模式） |

### 1.3 Android 设备要求

- Android 8.0 (API 26) 及以上
- 至少 2GB RAM
- 麦克风和网络权限

---

## 2. 项目初始化

### 2.1 获取代码

```bash
# 项目位于 py-xiaozhi 同级目录
cd /path/to/小智陪伴机器人/
# android-xiaozhi 目录已存在
cd android-xiaozhi
```

### 2.2 前端初始化

```bash
cd frontend

# 安装依赖
npm install

# 安装 UniApp X CLI（如需命令行构建）
npm install -g @dcloudio/cli
```

### 2.3 后端初始化

```bash
cd backend

# 创建虚拟环境（桌面开发调试用）
python -m venv venv
source venv/bin/activate  # Linux/macOS
# 或 venv\Scripts\activate  # Windows

# 安装依赖
pip install -r requirements.txt
```

### 2.4 HBuilderX 配置

1. 打开 HBuilderX
2. 文件 → 打开目录 → 选择 `android-xiaozhi/frontend`
3. 安装 UniApp X 编译器插件（菜单 → 工具 → 插件安装）
4. 配置 Android SDK 路径（菜单 → 运行 → 运行到手机或模拟器 → 配置）

---

## 3. 开发流程

### 3.1 整体开发流程

```
┌──────────────────────────────────────────────────┐
│              开发调试流程                          │
│                                                  │
│  1. 桌面开发调试（推荐）                           │
│     ├── Python 后端在本地运行                      │
│     ├── UniApp 前端在浏览器/模拟器中运行            │
│     └── 前端通过 localhost:18080 连接后端          │
│                                                  │
│  2. 设备联调                                      │
│     ├── Python 后端在本地运行                      │
│     ├── UniApp 前端运行到 Android 设备              │
│     └── 设备通过 WiFi 连接电脑上的后端              │
│                                                  │
│  3. 完整 APK 调试                                 │
│     ├── Python 后端通过 Chaquopy 打包进 APK        │
│     ├── UniApp X 编译为原生 Android                │
│     └── Android Studio 安装调试完整 APK            │
└──────────────────────────────────────────────────┘
```

### 3.2 桌面开发（日常开发推荐）

**启动后端：**

```bash
cd backend
source venv/bin/activate
python server.py
# 后端启动在 localhost:18080
```

**启动前端：**

方式 A：HBuilderX
1. 在 HBuilderX 中打开 `frontend` 目录
2. 运行 → 运行到浏览器（选择 Chrome）
3. 前端自动连接 `localhost:18080`

方式 B：命令行
```bash
cd frontend
npm run dev:h5
# 浏览器打开 http://localhost:5173
```

**桌面调试的优势：**
- 前端可在浏览器 DevTools 中调试
- 后端可在终端看到完整日志
- 修改代码后热重载
- 不需要 Android 设备

### 3.3 设备联调

当需要测试音频、摄像头等原生功能时：

1. **修改后端监听地址**：
```python
# server.py 临时改为监听所有接口
site = web.TCPSite(self.runner, '0.0.0.0', 18080)
```

2. **修改前端连接地址**：
```typescript
// services/backend.ts
const backendUrl = `ws://${getComputerIP()}:18080/ws`
```

3. **HBuilderX 运行到设备**：
- 运行 → 运行到手机或模拟器 → 选择设备
- 前端通过 WiFi 连接电脑上的 Python 后端

### 3.4 完整 APK 调试

当需要测试 Chaquopy 集成和完整 Android 功能时：

```bash
# 1. HBuilderX 生成本地打包 App 资源
# 运行 → 运行到手机或模拟器 → 本地打包 → 生成本地打包App资源

# 2. 在生成的 Android 项目中集成 Chaquopy
cd frontend/unpackage/resources/__UNI__xxx/android
# 编辑 build.gradle 添加 Chaquopy 插件

# 3. 用 Android Studio 打开项目
# 将 backend/ 中的 Python 代码复制到 app/src/main/python/

# 4. 编译安装到设备
```

---

## 4. 目录开发规范

### 4.1 前端代码规范

```
frontend/src/
├── pages/               # 页面（对应路由）
│   ├── index/
│   │   └── index.vue    # 页面文件
│   └── settings/
│       └── settings.vue
│
├── components/          # 可复用组件
│   ├── ChatBubble.vue   # PascalCase 命名
│   └── AudioWave.vue
│
├── store/               # Pinia Store
│   ├── app.ts           # 一个文件一个 store
│   └── settings.ts
│
├── services/            # 服务层（API 调用）
│   └── backend.ts       # 与后端通信
│
└── utils/               # 工具函数
    └── audio.ts
```

**Vue 组件模板：**

```vue
<template>
  <!-- 模板内容 -->
</template>

<script setup lang="ts">
// Composition API + TypeScript
import { ref, onMounted } from 'vue'
import { useAppStore } from '@/store/app'

const store = useAppStore()
const loading = ref(false)

onMounted(() => {
  // 初始化
})
</script>

<style scoped lang="scss">
/* 组件样式 */
</style>
```

### 4.2 后端代码规范

**与 py-xiaozhi 保持一致：**
- Google 风格 docstring
- 中文注释和日志
- Ruff 格式化（行宽 88）
- Conventional Commits

**文件命名：**
- 模块用 `snake_case.py`
- 类名用 `PascalCase`
- 常量用 `UPPER_SNAKE_CASE`

### 4.3 Git 分支策略

```
main                # 稳定发布版本
├── develop         # 开发主分支
├── feature/xxx     # 功能分支
├── fix/xxx         # 修复分支
└── docs/xxx        # 文档更新
```

**Commit 格式：**
```
feat(audio): 实现 AudioBridge 替代 sounddevice
fix(protocol): 修复 Android 上 WebSocket SSL 握手失败
docs(api): 更新前后端 API 文档
refactor(store): 重构 Pinia 状态管理
```

---

## 5. 关键模块开发指南

### 5.1 添加新页面

1. 创建页面文件 `frontend/src/pages/<name>/<name>.vue`
2. 在 `pages.json` 中注册路由：
```json
{
  "pages": [
    { "path": "pages/index/index", "style": { "navigationBarTitleText": "小智" } },
    { "path": "pages/settings/settings", "style": { "navigationBarTitleText": "设置" } }
  ]
}
```

### 5.2 添加新的 WebSocket 事件

**后端：**
```python
# 1. 在 bridge_plugin.py 中添加事件监听
self.event_bus.on("YOUR_EVENT", self._on_your_event)

async def _on_your_event(self, data):
    await self.local_server.broadcast_event('your_event', data)

# 2. 在 API.md 中记录事件格式
```

**前端：**
```typescript
// 1. 在 BackendService 中监听事件
backendService.on('your_event', (data) => {
  console.log('收到事件:', data)
})

// 2. 更新 Pinia Store
const store = useYourStore()
store.handleYourEvent(data)
```

### 5.3 添加 MCP 工具

**后端：**
```python
# backend/mcp/tools/your_tool/tool.py
from backend.mcp.decorators import mcp_tool
from backend.mcp.tooling import Property, PropertyList

@mcp_tool(
    name="your_tool",
    description="你的工具描述",
    properties=PropertyList([
        Property("param1", "string", "参数1描述", required=True),
    ]),
)
async def your_tool(param1: str) -> dict:
    """工具实现"""
    return {"result": "ok"}
```

### 5.4 添加 UTS 原生插件

1. 在 `frontend/nativeplugins/` 下创建插件目录
2. 编写 UTS 代码：
```
frontend/nativeplugins/your-plugin/
├── package.json        # 插件配置
├── index.uts           # UTS 入口
└── android/            # Android 特定代码（可选）
```

3. 在 `manifest.json` 中注册插件：
```json
{
  "nativePlugins": {
    "your-plugin": {
      "type": "module",
      "path": "./nativeplugins/your-plugin"
    }
  }
}
```

4. 在 Vue 组件中使用：
```typescript
import { yourFunction } from '@/nativeplugins/your-plugin'
```

---

## 6. 调试技巧

### 6.1 后端调试

```bash
# 启用详细日志
PYTHONPATH=backend python -c "
import logging
logging.basicConfig(level=logging.DEBUG)
from server import LocalServer
import asyncio

async def main():
    server = LocalServer(...)
    await server.start(18080)
    print('Server started on http://localhost:18080')
    await asyncio.Event().wait()

asyncio.run(main())
"

# 测试 HTTP API
curl http://localhost:18080/health
curl http://localhost:18080/api/status
curl http://localhost:18080/api/config
```

### 6.2 前端调试

**浏览器 DevTools（桌面开发）：**
```typescript
// 在代码中使用 console.log
console.log('[BackendService] 连接后端:', url)

// 查看 WebSocket 消息
// DevTools → Network → WS → 查看消息内容
```

**Android 设备调试：**
```bash
# Chrome 远程调试 WebView
# 1. 手机开启 USB 调试
# 2. 电脑 Chrome 打开 chrome://inspect
# 3. 选择设备 → inspect

# adb 查看日志
adb logcat | grep -i "xiaozhi\|python\|chaquopy"
```

### 6.3 Chaquopy 调试

```python
# 在 Python 代码中输出日志到 Android logcat
import sys
print("[XIAOZHI] Python backend started", file=sys.stderr)

# 在 Android Studio 的 Logcat 中过滤 "XIAOZHI"
```

```bash
# 查看 Python 异常
adb logcat -s "python.stdout" "python.stderr"
```

### 6.4 音频调试

```python
# 在 AudioBridge 中添加性能计时
import time

class AudioBridge:
    def on_input_data(self, data):
        start = time.monotonic()
        # ... 处理音频 ...
        elapsed = (time.monotonic() - start) * 1000
        if elapsed > 10:  # 超过 10ms 警告
            print(f"[AUDIO] 输入处理耗时: {elapsed:.1f}ms")
```

---

## 7. 构建和发布

### 7.1 构建 APK

**步骤一：UniApp X 打包前端**

```bash
# HBuilderX → 发行 → 原生App-本地打包
# 或命令行：
cd frontend
npx uni build --platform app-android
```

**步骤二：集成 Chaquopy**

在生成的 Android 项目中：

```gradle
// app/build.gradle
plugins {
    id 'com.chaquo.python' // 添加 Chaquopy
}

chaquopy {
    defaultConfig {
        // Python 版本
        version "3.10"

        // Python 源码目录
        pip {
            // 安装 Python 依赖
            install "aiohttp>=3.9"
            install "websockets>=11.0"
            install "numpy>=1.26"
            install "paho-mqtt>=2.1"
            install "opuslib>=3.0.1"
            // ... 其他依赖
        }

        // 原生库
        extractPackages "backend.libs"
    }

    sourceSets {
        main {
            python.srcDir "../../backend"  // 指向 Python 后端代码
        }
    }
}
```

**步骤三：Android 原生库**

```
app/src/main/jniLibs/
├── arm64-v8a/
│   ├── libopus.so          # 从 backend/libs/ 复制
│   └── libsherpa-onnx.so   # sherpa-onnx Android SDK
└── armeabi-v7a/
    ├── libopus.so
    └── libsherpa-onnx.so
```

**步骤四：编译**

```bash
cd android-project/
./gradlew assembleRelease
# APK 在 app/build/outputs/apk/release/
```

### 7.2 APK 体积优化

| 组件 | 预估大小 | 优化方案 |
|------|---------|---------|
| UniApp X 运行时 | ~5 MB | 不可减少 |
| Chaquopy Python | ~15 MB | 使用 `chaquopy.miniconda` 最小安装 |
| Python 依赖 | ~10 MB | 移除不需要的包 |
| libopus.so | ~0.5 MB | 只保留 arm64-v8a |
| sherpa-onnx 模型 | ~5-20 MB | 使用量化模型 |
| **总计** | **~35-50 MB** | |

**只支持 ARM64（推荐）：**
```gradle
android {
    defaultConfig {
        ndk {
            abiFilters 'arm64-v8a'  // 只支持 64 位 ARM
        }
    }
}
```

### 7.3 签名发布

```bash
# 生成签名密钥
keytool -genkey -v -keystore xiaozhi-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias xiaozhi

# 配置签名
# android/app/build.gradle
android {
    signingConfigs {
        release {
            storeFile file('xiaozhi-release.jks')
            storePassword 'xxx'
            keyAlias 'xiaozhi'
            keyPassword 'xxx'
        }
    }
}

# 构建签名 APK
./gradlew assembleRelease
```

---

## 8. 编译原生库

### 8.1 编译 libopus (Android ARM64)

```bash
# 安装 Android NDK
# 从 Android Studio SDK Manager 安装，或：
# https://developer.android.com/ndk/downloads

# 设置 NDK 路径
export NDK=/path/to/android-ndk

# 克隆 opus
git clone https://github.com/xiph/opus.git
cd opus
git checkout v1.5.2  # 使用最新稳定版

# 编译 ARM64
mkdir build-arm64 && cd build-arm64
cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-21 \
  -DCMAKE_BUILD_TYPE=Release \
  -DOPUS_BUILD_SHARED_LIBRARY=ON \
  -DBUILD_TESTING=OFF
cmake --build . -j$(nproc)

# 编译 ARMv7
cd ..
mkdir build-arm && cd build-arm
cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=armeabi-v7a \
  -DANDROID_PLATFORM=android-21 \
  -DCMAKE_BUILD_TYPE=Release \
  -DOPUS_BUILD_SHARED_LIBRARY=ON \
  -DBUILD_TESTING=OFF
cmake --build . -j$(nproc)

# 复制产物
cp build-arm64/libopus.so backend/libs/arm64-v8a/
cp build-arm/libopus.so backend/libs/armeabi-v7a/
```

### 8.2 集成 sherpa-onnx Android SDK

```gradle
// app/build.gradle
dependencies {
    // sherpa-onnx Android SDK
    implementation 'com.k2fsa.sherpa:sherpa-onnx:1.10.+'
}
```

或从 GitHub 下载 AAR：
```bash
# https://github.com/k2-fsa/sherpa-onnx/releases
# 下载 sherpa-onnx-<version>.aar
# 放入 app/libs/
```

---

## 9. 常见问题

### Q1: Chaquopy 无法安装某个 Python 包

**原因**：该包包含 C 扩展，没有 Android 预编译版本。

**解决**：
1. 检查 Chaquopy 是否支持：https://chaquo.com/chaquopy/doc/current/packages.html
2. 如不支持，用 UTS 原生插件替代该功能
3. 或尝试手动编译该 C 扩展的 Android 版本

### Q2: 前端连接不上后端

**检查清单**：
1. Python 后端是否已启动：`curl http://localhost:18080/health`
2. 防火墙是否阻止了 18080 端口
3. Android 设备和电脑是否在同一网络
4. 后端是否监听了 `0.0.0.0`（设备联调时需要）

### Q3: 音频延迟高

**优化方向**：
1. 减小 AudioRecord buffer size
2. 降低 Opus 帧长（20ms → 10ms，需要服务器支持）
3. 检查 JNI 桥接是否有不必要的拷贝
4. 使用 `VOICE_COMMUNICATION` 音频源（已内置 AEC）

### Q4: 后台被系统杀死

**解决方案**：
1. 确保前台服务正在运行（通知栏有常驻通知）
2. 在系统设置中关闭"电池优化"
3. 使用 `WorkManager` 定期检查服务状态
4. 部分厂商 ROM 需要额外设置（小米：自启动权限；华为：受保护应用）

### Q5: UniApp X 原生插件编译报错

**常见原因**：
1. UTS 语法错误（UTS 不是完整的 TypeScript，有子集限制）
2. Android API 调用方式不对（需要参考 UTS 文档）
3. 权限未在 AndroidManifest.xml 中声明
