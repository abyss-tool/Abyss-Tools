# SRS-005 · 工程纪律与 AI 协作规范

> 深渊工具 Abyss Tools · 软件规格说明书（总纲）  
> 状态：**基线已实现** ｜ 优先级：**P1** ｜ 里程碑：**M0** ｜ 承载形态：**工程规范** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `SRS-005` |
| 所属域 | 总纲 |
| 承载形态 | 工程规范 |
| 当前状态 | 基线已实现 |
| 优先级 / 里程碑 | P1 / M0 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | AGENTS.md · 工程纪律 |

## 1. 目标与范围

对齐 AGENTS.md：业务 UI 不散落玻璃 API、远端数据三态、纯 JVM 模块边界、手动 DI、fail-closed 数据契约。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/AGENTS.md`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | 业务 UI 一律经由 core:designsystem 的 glass 包，不直接引用 vendor:backdrop。 |
| FR-002 | 远端数据一律 UiState 三态；网络出口 Result → toUiState()。 |
| FR-003 | core:update / core:plugin / core:common / plugin-sdk 保持纯 JVM，零 Android 依赖。 |
| FR-004 | 框架期不引 Hilt/KSP/kapt，新依赖先问「能否接口隔离」。 |
| FR-005 | 补丁应用必须 备份 → 应用 → 失败回滚。 |
| FR-006 | 提交使用 Conventional commits + 中文描述；CI 红灯不合并。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

工具链（勿单独升降）：Kotlin 2.3.21 / AGP 8.13.2 / Gradle 8.14 /
Compose BOM 2026.02.00 / JDK 17 / compileSdk 36 / minSdk 26 / targetSdk 28。

## 5. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 拒绝并给人话错误，不猜测、不静默降级。
- 远端数据统一走 `UiState`（Loading / Error / Data），加载中禁止展示假数据。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | 新增 PR 由 review checklist 逐条核对上述 6 项。 |
| AC-002 | CI test 任务在纯 JVM 模块零 Android 依赖下通过。 |

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

