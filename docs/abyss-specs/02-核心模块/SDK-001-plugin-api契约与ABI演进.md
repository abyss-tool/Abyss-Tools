# SDK-001 · plugin-api 契约与 ABI 演进

> 深渊工具 Abyss Tools · 软件规格说明书（核心）  
> 状态：**基线已实现（ABI 桥未落地）** ｜ 优先级：**P0** ｜ 里程碑：**M0** ｜ 承载形态：**plugin-sdk** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `SDK-001` |
| 所属域 | 核心 |
| 承载形态 | plugin-sdk |
| 当前状态 | 基线已实现（ABI 桥未落地） |
| 优先级 / 里程碑 | P0 / M0 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | ABI · AIDL · 缺口 |

## 1. 目标与范围

宿主 ↔ 插件共享 ABI；当前仅 manifest 发现 + 补丁通道，AIDL 双向桥与 DexClassLoader 动态加载仍是 v0.2 预告。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/plugin-sdk/plugin-api/src/main/kotlin/com/abyss/tools/plugin/api/PluginContract.kt`
- `Abyss-Tools/plugin-sdk/plugin-api/src/main/kotlin/com/abyss/tools/plugin/api/PluginManifest.kt`
- `Abyss-Tools/docs/PLUGIN_SDK.md`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | HOST_API_VERSION 只增不减；升级需提供兼容期与迁移说明。 |
| FR-002 | AbyssPlugin 接口提供 onInstalled / onEnabled / onDisabled 生命周期钩子。 |
| FR-003 | IAbyssPlugin / IAbyssPluginHost AIDL 双向桥落地前，插件 UI 入口只能走独立 APK。 |
| FR-004 | META_UI_ENTRIES 发现机制需与 PackageManager service 发现配套设计。 |
| FR-005 | ABI 破坏性变更必须同步提升 HOST_API 并让旧插件 fail-closed 拒绝。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

沿用宿主既有契约（`PluginContract.HOST_API_VERSION = 1`、`SemVer`、`UiState` 三态），本单元不新增跨模块接口。

## 5. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 拒绝并给人话错误，不猜测、不静默降级。
- 远端数据统一走 `UiState`（Loading / Error / Data），加载中禁止展示假数据。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | 新增 ABI 版本时 PluginFrameworkTest 断言 minHost 校验正确。 |

> 验收方法：纯 JVM 单测（`./gradlew test`）/ CI 门禁脚本 / 真机回归清单（Android 15 与 16 各一台）/ 通道清单校验，按单元性质择一或组合。

## 7. 依赖与风险

**依赖**

- `core:common`（SemVer / UiState / 渠道常量）

**风险**

- AIDL 桥缺失导致三个引擎级插件只能以独立 APK 形态存在，跨进程调用与宿主 UI 集成受限。

## 8. 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| 0.1 | 2026-11-21 | 基于 abyss-tool 组织 19 仓库克隆研究创建；对齐 v0.9.5 基线。 |

