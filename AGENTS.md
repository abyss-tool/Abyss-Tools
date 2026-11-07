# AGENTS.md —— 深渊工具工程纪律（给人类与 AI 协作者）

> 本文件对齐 Android-Guru-Agent 的工程纪律传统：AI 参与开发时同样受
> 约束。改代码前先读完本文件。

## 模块纪律

1. **业务 UI 禁止直接散落玻璃 API** —— 一律经由 `core:designsystem` 的
   `glass` 包（`AbyssGlassSurface` / `AbyssGlassBottomBar`）。底层库
   （vendor:backdrop）替换时只改 glass 包，业务组件零感知。
2. **远端数据一律走 `UiState` 三态**（Loading / Error / Data），禁止
   「加载中显示假数据」。网络层出口统一 `Result → toUiState()`。
3. **版本比较只用 `core:common` 的 `SemVer`** —— 全仓禁止手写
   `split(".")` 式版本比较。
4. **渠道 URL 只认 `AbyssChannels`** —— 禁止在业务代码里硬编码
   raw.githubusercontent 地址。
5. `core:update` / `core:plugin` / `core:common` / `plugin-sdk` 保持
   **纯 JVM**（可单测、CI 零 Android 依赖）：Android 侧依赖（Context /
   PackageManager）一律隔离在接口后面（如 `ApkInstallGateway`）。
   `core:shell` 同纪律：Shizuku AAR 只进网关实现，指令编排
   （ShizukuCommands）零 Android 依赖可单测。
6. **手动 DI（ServiceRegistry）**：框架期不引 Hilt/KSP/kapt —— 新依赖
   先问「能不能用接口隔离」。

## 数据契约纪律（fail-closed）

- 坏 JSON / 坏版本号 / 路径穿越 / 签名不符 → **一律拒绝并给人话错误**，
  绝不猜测、绝不静默降级成「当作成功」。
- 补丁应用必须**备份 → 应用 → 失败回滚**（`ZipPatchApplier` 语义）。
- 测试夹具（`src/test/resources`）即通道契约镜像：`tools/validate_manifests.py`
  在 CI 里校验夹具，防手写漂移。

## 提交纪律

- Conventional commits + 中文描述：`feat(core:update): 补丁引擎 …`。
- **完成一部分就写入仓库**：小步提交，每个提交自洽可读。
- CI 红灯不合并；本仓不做本地编译，全部以 GitHub Actions 为准。

## 工具链（勿单独升降）

Kotlin 2.3.21 / AGP 8.13.2 / Gradle 8.14 / Compose BOM 2026.02.00 /
JDK 17 / compileSdk 36 / minSdk 26 / targetSdk 28（钉 28：终端引擎的
SELinux 红线，见 app/build.gradle.kts 注释）。升降版本须整组联动并过
全量 CI。版本矩阵源自 Android-Guru-Agent 已验证组合。

## 仓库矩阵

- 开发仓（本仓）→ 发布仓 `Abyss-Tools-Release`（version.json 等）→
  客户端；插件中心 `Abyss-Plugins`（plugins.json）→ 七个
  `abyss-plugin-*` 重型插件仓（轻功能已内置主壳，v0.2 / v0.3 起）。
- 插件侧改动：先改插件仓（含其 CI 校验），再同步插件中心索引。

## 界面语言

面向中文极客用户：UI 文案中文；代码标识符 / 注释英文为主、关键决策
点中文注释（解释「为什么」而不是「是什么」）。
