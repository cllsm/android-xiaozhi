# 仓库指南

## 项目结构与模块组织

本仓库包含三部分：`native-stage/` 是当前主线 Android 原生应用（Kotlin + Compose）；`frontend/` 与 `backend/` 是旧版 UniApp X/嵌入式 Python 实现，仅作功能对照。原生应用源码在 `native-stage/app/src/main/java/com/xiaozhi/android/`，测试在 `native-stage/app/src/test/java/`，唤醒词模型在 `native-stage/app/src/main/assets/models/zh/`。设计文档位于 `docs/`。

## 构建、测试与开发命令

原生 Android 单元测试与本地构建：

```bash
cd native-stage
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Release 构建通过环境变量或本机 `local.properties` 提供签名，不要提交证书。旧前端如需运行，安装依赖并启动 H5 开发服务器：

```bash
cd frontend
pnpm install
pnpm dev:h5
```

从仓库根目录安装后端依赖并启动本地服务：

```bash
python -m pip install -r backend/requirements.txt
python -m backend.main --port 18080
```

其他常用前端命令：`pnpm type-check`、`pnpm build:h5`、`pnpm build:app-android`。详见 `docs/DEVELOPMENT.md`。

## 代码风格与命名约定

Kotlin 使用 4 空格缩进，遵循 Android/Kotlin 官方风格；类与对象用 `PascalCase`，函数与变量用 `camelCase`，常量用 `UPPER_SNAKE_CASE`。Compose 组件保持单一职责，状态优先使用 `StateFlow`。Python 使用 4 空格缩进、Google 风格 docstring，88 字符行宽。TypeScript、Vue 和 SCSS 使用 2 空格缩进。不要提交构建产物、证书、`local.properties` 或 `.env` 文件。

## 测试指南

原生 Android 测试使用 JUnit 4，位于 `native-stage/app/src/test/java/`，文件命名为 `*Test.kt`。提交前至少运行 `./gradlew :app:testDebugUnitTest`；涉及 UI 或发布配置时再运行 `assembleDebug`/`assembleRelease`。旧后端如新增测试应使用 pytest，放在 `backend/tests/`。

## 提交与 Pull Request 规范

Git 历史遵循 Conventional Commits，常见前缀为 `feat:`、`fix:`、`refactor:` 或 `docs:`，后接简短中文说明；也可使用作用域，例如 `feat(audio): ...`。保持提交聚焦单一变更。Pull Request 应说明行为变化、列出已执行的验证、关联相关 issue；UI 变更需附截图或录屏。
