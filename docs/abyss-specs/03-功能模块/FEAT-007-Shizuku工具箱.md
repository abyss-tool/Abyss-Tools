# FEAT-007 · Shizuku 工具箱

> 深渊工具 Abyss Tools · 软件规格说明书（功能）  
> 状态：**基线已实现** ｜ 优先级：**P1** ｜ 里程碑：**M0** ｜ 承载形态：**主壳内置** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `FEAT-007` |
| 所属域 | 功能 |
| 承载形态 | 主壳内置 |
| 当前状态 | 基线已实现 |
| 优先级 / 里程碑 | P1 / M0 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | ShizukuKit · appops |

## 1. 目标与范围

appops 查改 / settings 读写 / dumpsys 白名单摘要面板 / 指令历史 50 条。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/feature/shizukukit/src/main/kotlin/com/abyss/tools/feature/shizukukit/KitCommands.kt`
- `Abyss-Tools/feature/shizukukit/src/main/kotlin/com/abyss/tools/feature/shizukukit/AppOpsPanel.kt`
- `Abyss-Tools/feature/shizukukit/src/main/kotlin/com/abyss/tools/feature/shizukukit/SettingsPanel.kt`
- `Abyss-Tools/feature/shizukukit/src/main/kotlin/com/abyss/tools/feature/shizukukit/HistoryPanel.kt`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | 指令模板统一经 KitCommands（纯 JVM 可单测），禁止 UI 拼命令。 |
| FR-002 | dumpsys 只输出白名单摘要，禁止无限制透传全量输出。 |
| FR-003 | 历史 50 条上限，支持复制与清空。 |
| FR-004 | 每个动作记录到会话日志并可撤销（若系统支持逆操作）。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

沿用宿主既有契约（`PluginContract.HOST_API_VERSION = 1`、`SemVer`、`UiState` 三态），本单元不新增跨模块接口。

## 5. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 拒绝并给人话错误，不猜测、不静默降级。
- 远端数据统一走 `UiState`（Loading / Error / Data），加载中禁止展示假数据。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | KitCommandsTest 覆盖 appops / settings / dumpsys 三类命令构造与转义。 |

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
| 0.2 | 2026-11-27 | B-14 收口：`KitCommands.requireSafeToken` 从元字符黑名单收敛为复用 `ShizukuCommands.requireSafeToken` 白名单（`^[A-Za-z0-9._:-]+$`）—— 黑名单漏拦 `/` 路径穿越与 `-` 开头选项形态（`--help` 可被当 appops op 解析）。putSetting 的 shellQuote 值域与 dumpsys 精确白名单不变。回归用例：`KitCommandsTest` B-14 两例。 |

