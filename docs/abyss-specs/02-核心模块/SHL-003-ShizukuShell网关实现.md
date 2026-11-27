# SHL-003 · ShizukuShell 网关实现

> 深渊工具 Abyss Tools · 软件规格说明书（核心）  
> 状态：**基线已实现** ｜ 优先级：**P0** ｜ 里程碑：**M0** ｜ 承载形态：**Android 库** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `SHL-003` |
| 所属域 | 核心 |
| 承载形态 | Android 库 |
| 当前状态 | 基线已实现 |
| 优先级 / 里程碑 | P0 / M0 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | Shizuku · 并发读管道 · 超时 |

## 1. 目标与范围

Shizuku AAR 只进网关实现；并发消费 stdout/stderr，1MB 单流上限，30s 真超时看门狗，消除大输出管道死锁面。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/core/shell/src/main/kotlin/com/abyss/tools/core/shell/ShizukuShell.kt`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | stdout 与 stderr 并发读取，禁止串行等待造成死锁。 |
| FR-002 | 单流 1MB 上限，超限置 truncated 并停止读取。 |
| FR-003 | 30s 真超时（不是仅设置 connectTimeout），超时终止进程并回收资源。 |
| FR-004 | Shizuku 未运行 / 未授权时给引导，不假定常可用。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

沿用宿主既有契约（`PluginContract.HOST_API_VERSION = 1`、`SemVer`、`UiState` 三态），本单元不新增跨模块接口。

## 5. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 拒绝并给人话错误，不猜测、不静默降级。
- 远端数据统一走 `UiState`（Loading / Error / Data），加载中禁止展示假数据。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | 大输出命令（如 dumpsys 全量）不挂死且 truncated 如实标注。 |

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
| 0.2 | 2026-11-27 | B-08 收口：`ShellResult` 新增 `timedOut`/`streamAbandoned` 字段；看门狗触发（fired 标志 + elapsed 双信号）→ `timedOut=true` 且 `isSuccess` 恒 false（超时 ≠ 成功）；`CappedFdReader.await()` 放弃流时截断位置 true（旧实现 truncated=false 空文本 = fail-open，与类注释「结果如实标注」相悖）；终端/脚本实验室 UI 对两者显示明确系统行。回归用例：`ShellResultSemanticsTest`。 |

