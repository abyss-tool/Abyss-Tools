# FEAT-010 · 任务蓝图双模式调度

> 深渊工具 Abyss Tools · 软件规格说明书（功能）  
> 状态：**基线已实现** ｜ 优先级：**P1** ｜ 里程碑：**M0** ｜ 承载形态：**主壳内置** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `FEAT-010` |
| 所属域 | 功能 |
| 承载形态 | 主壳内置 |
| 当前状态 | 基线已实现 |
| 优先级 / 里程碑 | P1 / M0 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | 调度 · TimePicker · AlarmManager |

## 1. 目标与范围

间隔循环（≥15min）与每日定时（M3 TimePicker）；SchedulePlanner 纯排程数学；AlarmManager setExactAndAllowWhileIdle + 开机重排。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/feature/blueprint/src/main/kotlin/com/abyss/tools/feature/blueprint/ScheduleAlarmReceiver.kt`
- `Abyss-Tools/feature/blueprint/src/main/kotlin/com/abyss/tools/feature/blueprint/BootReceiver.kt`
- `Abyss-Tools/feature/blueprint/src/test/kotlin/com/abyss/tools/feature/blueprint/SchedulePlannerTest.kt`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | 今天未到排今天 / 已过排明天 / 恰好此刻排明天（防双触发）。 |
| FR-002 | 跨月跨年经 Calendar 折算，单测覆盖闰年与午夜边界。 |
| FR-003 | 到期接收器重排按蓝图当前 schedule 收敛，intent 不携带间隔 extra。 |
| FR-004 | 模式变更 / 删除蓝图后旧闹钟自然失效，不产生孤儿任务。 |
| FR-005 | SecurityException 降级 setAndAllowWhileIdle 双保险。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

沿用宿主既有契约（`PluginContract.HOST_API_VERSION = 1`、`SemVer`、`UiState` 三态），本单元不新增跨模块接口。

## 5. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 拒绝并给人话错误，不猜测、不静默降级。
- 远端数据统一走 `UiState`（Loading / Error / Data），加载中禁止展示假数据。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | SchedulePlannerTest 覆盖 23:59 / 00:00 / 闰年 2/29。 |

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
| 0.2 | 2026-11-27 | B-13 收口：调度 Intent 增加单射 data URI（`abyss://blueprint/<id>`）参与 `Intent.filterEquals` —— 旧形态仅靠 `blueprintId.hashCode()` 做 requestCode，碰撞对（如 Aa/BB）共用 PendingIntent 被 `FLAG_UPDATE_CURRENT` 相互覆盖。回归用例：`BlueprintPendingIntentIdentityTest`（碰撞对 hashCode 相等断言 + data URI 单射）。 |

