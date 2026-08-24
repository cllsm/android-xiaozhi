# 仓库指南

## 项目结构与模块组织

本仓库是纯 Android 原生项目，只包含一个 Gradle `:app` 模块。应用源码在 `app/src/main/java/com/xiaozhi/android/`，按 `audio/`、`core/`、`data/`、`mcp/`、`media/`、`network/`、`service/`、`ui/`、`wake/` 划分。单元测试在 `app/src/test/java/`，唤醒词模型在 `app/src/main/assets/models/zh/`，sherpa-onnx AAR 在 `app/libs/`。

## 构建、测试与开发命令

在仓库根目录使用 Gradle Wrapper：

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

`testDebugUnitTest` 运行单元测试；`assembleDebug` 生成调试 APK；`assembleRelease` 生成发布 APK。调试包输出在 `app/build/outputs/apk/debug/`。

## 配置与安全

Android SDK 路径、签名信息和网易云无损网关 AppKey 写入根目录 `local.properties` 或同名环境变量。常用键名包括 `sdk.dir`、`XIAOZHI_STORE_FILE`、`XIAOZHI_KEY_ALIAS`、`XIAOZHI_NETEASE_LOSSLESS_APP_KEY`。不要提交 `local.properties`、证书、密码、AppKey、APK 或构建目录。

## 代码风格与命名约定

Kotlin 使用 4 空格缩进和官方 Android 风格。类与对象使用 `PascalCase`，函数与变量使用 `camelCase`，常量使用 `UPPER_SNAKE_CASE`。Compose 组件保持单一职责，状态优先使用 `StateFlow`。新增测试文件命名为 `*Test.kt`。

## 测试与提交规范

提交前至少运行 `.\gradlew.bat :app:testDebugUnitTest`；涉及 UI 或发布配置时，再运行对应的 `assembleDebug` / `assembleRelease`。提交信息遵循 Conventional Commits，例如 `feat: ...`、`fix: ...`、`refactor: ...`。Pull Request 应说明行为变化、验证命令和必要的截图或录屏。
