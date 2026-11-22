# FEAT-003 · 插件中心页 PluginScreens

> 深渊工具 Abyss Tools · 软件规格说明书（功能）  
> 状态：**基线已实现** ｜ 优先级：**P0** ｜ 里程碑：**M0** ｜ 承载形态：**主壳内置** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `FEAT-003` |
| 所属域 | 功能 |
| 承载形态 | 主壳内置 |
| 当前状态 | 基线已实现 |
| 优先级 / 里程碑 | P0 / M0 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | 插件中心 · M3 · 下拉刷新 |

## 1. 目标与范围

插件列表 / 我的插件 / 详情 + 补丁时间线 + 安装与更新闭环；v0.9.0 接入 M3 PullToRefreshBox 下拉刷新。

v0.9.12（FEAT-034）补充**双形态契约**：`PluginHubScreen` 同时服务两种深度 ——
- **顶层 tab 态**：`onBack = null`、`title = "插件"`——不渲染返回箭头、无返回语义（`BackHandler(canPop=false)`，系统返回不退出）；底栏可见（4 tab 第 3 格高亮）。
- **详情页态**（自顶层点插件 push 后不属于本页；自功能页遗留路径进入时）：`onBack = pop 闭包`、`title` 缺省「插件中心」——外观 / 行为与 v0.9.10 完全一致。
业务逻辑（索引拉取 / ChannelPolicy / 安装闭环 / 镜像测速 / 下拉刷新）两态共用同一条管线，零改动。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/feature/plugins/src/main/kotlin/com/abyss/tools/feature/plugins/PluginScreens.kt`
- `Abyss-Tools/feature/plugins/src/main/kotlin/com/abyss/tools/feature/plugins/MirrorSection.kt`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | 索引拉取走 PluginRepository + ChannelPolicy，拒收条目不展示。 |
| FR-002 | 详情页展示 Shizuku 独立授权引导（ownAuth + reason）。 |
| FR-003 | 「一键检查更新」并发拉取全部已装插件补丁通道，展示可更新徽章。 |
| FR-004 | PullToRefreshBox 与既有 retryKey 索引重取管线共用，数据态原地保留。 |
| FR-005 | MirrorSection 提供测速与镜像选择（auto / 手动固定）。 |
| FR-006 | 未发布 APK 资产的插件给出人话提示，不隐藏失败。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

沿用宿主既有契约（`PluginContract.HOST_API_VERSION = 1`、`SemVer`、`UiState` 三态），本单元不新增跨模块接口。

## 5. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 拒绝并给人话错误，不猜测、不静默降级。
- 远端数据统一走 `UiState`（Loading / Error / Data），加载中禁止展示假数据。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | plugins.json 注入非法条目后该条目不出现且诊断页可见拒收原因。 |

> 验收方法：纯 JVM 单测（`./gradlew test`）/ CI 门禁脚本 / 真机回归清单（Android 15 与 16 各一台）/ 通道清单校验，按单元性质择一或组合。

## 7. 依赖与风险

**依赖**

- `core:common`（SemVer / UiState / 渠道常量）

**风险**

- 无新增外部依赖；行为变更需回归既有单测。

## 8. 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| 0.1 | 2026-11-21 | 基于 abyss-tool 组织 19 仓库克隆研究创建；对齐 v0.9.5 基线。 |

