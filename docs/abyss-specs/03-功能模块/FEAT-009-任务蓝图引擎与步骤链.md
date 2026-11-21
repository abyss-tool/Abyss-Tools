# FEAT-009 · 任务蓝图引擎与步骤链

> 深渊工具 Abyss Tools · 软件规格说明书（功能）  
> 状态：**基线已实现** ｜ 优先级：**P0** ｜ 里程碑：**M0** ｜ 承载形态：**主壳内置** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `FEAT-009` |
| 所属域 | 功能 |
| 承载形态 | 主壳内置 |
| 当前状态 | 基线已实现 |
| 优先级 / 里程碑 | P0 / M0 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | Blueprint · 步骤链 · 纯JVM |

## 1. 目标与范围

JSON 步骤链（SHELL / TOAST / DELAY）+ 成功判定 successProbe / 超时 / continueOnFailure / {prev_output} 占位；蓝图库原子写持久化。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/feature/blueprint/src/main/kotlin/com/abyss/tools/feature/blueprint/BlueprintEngine.kt`
- `Abyss-Tools/feature/blueprint/src/main/kotlin/com/abyss/tools/feature/blueprint/BlueprintModels.kt`
- `Abyss-Tools/feature/blueprint/src/main/kotlin/com/abyss/tools/feature/blueprint/BlueprintStore.kt`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | BlueprintParser 校验 id（小写连字符）、name 非空、steps 非空、DELAY 为 1..600000 ms。 |
| FR-002 | intervalMinutes 必须 ≥ 15（省电纪律）；dailyAtMinute ∈ 0..1439。 |
| FR-003 | schedule 两字段互斥（xor），旧 JSON 向前兼容。 |
| FR-004 | SHELL 步骤走 ShellGateway，输出可经 {prev_output} 传给下一步。 |
| FR-005 | JSON 解析错误需人话化（缺字段提示字段名）。 |
| FR-006 | 蓝图库文件原子写，写入中断不产生半截 JSON。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

```kotlin
enum class StepType { SHELL, TOAST, DELAY }
data class BlueprintStep(val type: StepType, val content: String,
    val successProbe: String? = null, val continueOnFailure: Boolean = false,
    val timeoutSeconds: Int = 30)
data class BlueprintSchedule(val intervalMinutes: Int? = null, val dailyAtMinute: Int? = null)
```

## 5. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 拒绝并给人话错误，不猜测、不静默降级。
- 远端数据统一走 `UiState`（Loading / Error / Data），加载中禁止展示假数据。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | BlueprintParserTest / BlueprintEngineTest / BlueprintStoreTest 全部通过。 |

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

