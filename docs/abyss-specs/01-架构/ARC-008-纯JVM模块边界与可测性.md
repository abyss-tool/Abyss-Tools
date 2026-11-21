# ARC-008 · 纯 JVM 模块边界与可测性

> 深渊工具 Abyss Tools · 软件规格说明书（架构）  
> 状态：**基线已实现** ｜ 优先级：**P0** ｜ 里程碑：**M0** ｜ 承载形态：**工程规范** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `ARC-008` |
| 所属域 | 架构 |
| 承载形态 | 工程规范 |
| 当前状态 | 基线已实现 |
| 优先级 / 里程碑 | P0 / M0 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | 可测性 · 纯JVM · 测试夹具 |

## 1. 目标与范围

指令编排 / 解析器 / 排程数学 / zip 防护 / 家族数学等全部零 Android 依赖，可纯 JVM 单测；测试夹具即通道契约镜像。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/core/update/src/test/kotlin/com/abyss/tools/core/update/UpdateEngineTest.kt`
- `Abyss-Tools/core/plugin/src/test/kotlin/com/abyss/tools/core/plugin/PluginFrameworkTest.kt`
- `Abyss-Tools/core/shell/src/test/kotlin/com/abyss/tools/core/shell/ShizukuCommandsTest.kt`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | 新增能力必须拆出纯 JVM 的 Commands / Parser / Math / Guard 对象。 |
| FR-002 | src/test/resources 中的 JSON 夹具必须与真实通道 schema 一致，由 validate_manifests.py 校验防手写漂移。 |
| FR-003 | CI 的 test 任务覆盖全部纯 JVM 模块，不依赖模拟器。 |
| FR-004 | 禁止用 Robolectric 掩盖本可纯 JVM 实现的逻辑。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

沿用宿主既有契约（`PluginContract.HOST_API_VERSION = 1`、`SemVer`、`UiState` 三态），本单元不新增跨模块接口。

## 5. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 拒绝并给人话错误，不猜测、不静默降级。
- 远端数据统一走 `UiState`（Loading / Error / Data），加载中禁止展示假数据。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | `./gradlew test` 在 CI 全绿；新增模块必须带至少一个测试类。 |
| AC-002 | validate_manifests.py 校验测试夹具通过。 |

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

