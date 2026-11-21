# ARC-002 · Gradle 模块地图

> 深渊工具 Abyss Tools · 软件规格说明书（架构）  
> 状态：**基线已实现** ｜ 优先级：**P0** ｜ 里程碑：**M0** ｜ 承载形态：**主壳内置** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `ARC-002` |
| 所属域 | 架构 |
| 承载形态 | 主壳内置 |
| 当前状态 | 基线已实现 |
| 优先级 / 里程碑 | P0 / M0 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | Gradle · 模块地图 |

## 1. 目标与范围

登记 settings.gradle.kts 中全部 include 模块（1 app + 1 vendor + 5 core + 1 sdk + 22 feature）及其职责。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/settings.gradle.kts`
- `Abyss-Tools/gradle/libs.versions.toml`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | 每个 include 必须有 build.gradle.kts 与至少一个源文件，禁止空模块。 |
| FR-002 | 新增 feature 模块必须同步 settings.gradle.kts、ToolCatalog 与 AbyssRoot 路由。 |
| FR-003 | google() 仓库仅解析 com.android.* / com.google.* / androidx.* 三类 group。 |
| FR-004 | repositoriesMode = FAIL_ON_PROJECT_REPOS，禁止模块级仓库声明。 |
| FR-005 | JDK 不满足 17 时配置期给出人话报错，不等 AGP 抛晦涩异常。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

模块清单（v0.9.5）：`:app` `:vendor:backdrop` `:core:common` `:core:designsystem`
`:core:update` `:core:plugin` `:core:shell` `:plugin-sdk:plugin-api`
`:feature:{dashboard,navhub,plugins,disguise,onboarding,settings,appmgr,shizukukit,
qrcraft,blueprint,island,wallpaper,terminal,scriptlab,sandbox,media,netaudit,backup,
syspower,widgets}`（settings.gradle.kts 注释另含 `feature:syspower` 等）。

## 5. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 拒绝并给人话错误，不猜测、不静默降级。
- 远端数据统一走 `UiState`（Loading / Error / Data），加载中禁止展示假数据。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | 模块数与 settings.gradle.kts include 行数一致。 |
| AC-002 | 每个模块在 filelist 中有对应源码目录。 |

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

