# N-APP-001 · 构建器插件工程脚手架与 HOST_API 接入

> 深渊工具 Abyss Tools · 软件规格说明书（新增能力）  
> 状态：**规划中 · 待实现** ｜ 优先级：**P0** ｜ 里程碑：**M1** ｜ 承载形态：**新增能力** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `N-APP-001` |
| 所属域 | 新增能力 |
| 承载形态 | 新增能力 |
| 当前状态 | 规划中 · 待实现 |
| 优先级 / 里程碑 | P0 / M1 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | M1 · 脚手架 · 引擎级插件 |

## 1. 目标与范围

在 app-builder 仓新增独立 Android Gradle 工程（当前 15 个文件全为骨架），接入 HOST_API v1、固定签名与 CI 构建。

**目标**

- 让插件从「框架预览」变为可安装 APK。
- 建立与宿主同构的 CI / 补丁 / 发布链路。

## 2. 现状锚点（克隆研究实测）

- `abyss-plugin-app-builder/plugin.json`
- `Abyss-Tools/plugin-sdk/plugin-api/.../PluginContract.kt`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | 新增 settings.gradle.kts / build.gradle.kts / app 模块，compileSdk 36、minSdk 26、targetSdk 28 对齐主壳。 |
| FR-002 | 引入 plugin-api 契约，声明 minHost=1、minApp、entry 类。 |
| FR-003 | AndroidManifest 声明 META_HOST_ENTRY / META_UI_ENTRIES 与 FOREGROUND_SERVICE / WAKE_LOCK / REQUEST_INSTALL_PACKAGES。 |
| FR-004 | CI 使用独立 keystore（secrets 注入），apksigner 指纹写入插件常量并与宿主校验口径一致。 |
| FR-005 | 产物 APK 上传 GitHub Release，供 sync_index.py 自动探测填入 apk 字段。 |
| FR-006 | plugin-ci.yml 增加 Gradle assemble + 单元测试步骤（当前只有清单校验）。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

本单元为规划新增，具体接口在实现评审时定稿；与既有系统的边界必须沿用：

- `PluginContract.HOST_API_VERSION = 1`（只增不减）与 `PluginManifest` schema。
- `AbyssChannels` 渠道常量 + `ChannelPolicy` / `FetchHostPolicy` 运行时白名单。
- `SemVer` 版本比较、`UiState` 三态、`Result.toUiState()` 统一出口。
- 资产一律 SHA-256 校验 + 备份 → 应用 → 失败回滚（`abyss-patch-v1` 纪律）。
- 纯 JVM 可测边界：指令编排 / 解析 / 排程数学 / 防护逻辑零 Android 依赖。

## 5. 失败语义与边界

- 插件签名与宿主签名可不同，但宿主安装走同签名门禁之外仍需 SHA-256 + 发布者白名单校验。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | CI 产出 APK 且 `apksigner verify` 通过。 |
| AC-002 | plugins.json 中 apk.sha256 为 64 位 hex、sizeBytes 与实际一致。 |

> 验收方法：纯 JVM 单测（`./gradlew test`）/ CI 门禁脚本 / 真机回归清单（Android 15 与 16 各一台）/ 通道清单校验，按单元性质择一或组合。

## 7. 依赖与风险

**依赖**

- HUB-011
- SDK-001
- PLG-005

**风险**

- 插件仓目前无任何源码，脚手架是全部后续工作的前置。

## 8. 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| 0.1 | 2026-11-21 | 基于 abyss-tool 组织 19 仓库克隆研究创建；对齐 v0.9.5 基线。 |

