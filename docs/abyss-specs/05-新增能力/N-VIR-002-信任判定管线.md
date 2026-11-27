# N-VIR-002 · 信任判定管线

> 深渊工具 Abyss Tools · 软件规格说明书（新增能力）  
> 状态：**已实现（v0.9.18）** ｜ 优先级：**P0** ｜ 里程碑：**M1** ｜ 承载形态：**新增能力** ｜ 基线：**v0.9.18 / versionCode 41** ｜ 编制日期：**2026-11-21** ｜ 落地日期：**2026-11-27**

| 项 | 值 |
| --- | --- |
| 规格编号 | `N-VIR-002` |
| 所属域 | 新增能力 |
| 承载形态 | 新增能力 |
| 当前状态 | 已实现（v0.9.18） |
| 优先级 / 里程碑 | P0 / M1 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.18 / versionCode 41 |
| 标签 | 纯JVM · 判定 |

## 1. 目标与范围

判定管线纯 JVM 可单测：哈希黑名单 → 本地特征 → 云查询（按优先级短路），输出 verdict + 理由链。

## 2. 现状锚点（克隆研究实测）

- `abyss-plugin-virus-guard/ROADMAP.md`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | 判定结果枚举：CLEAN / SUSPICIOUS / MALICIOUS / UNKNOWN，UNKNOWN 不得当作 CLEAN。 |
| FR-002 | 每条判定保留可审计的理由链（命中哪条规则 / 签名 / 云结果）。 |
| FR-003 | 输入为 APK 文件路径 + SHA-256 + 包名 + 签名摘要，管线不直接做 IO。 |
| FR-004 | 规则优先级与短路策略可配置且可单测。 |
| FR-005 | 云查询不可用时降级为 UNKNOWN 并提示，不静默放行。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

```kotlin
data class ScanSubject(val apkPath: String, val sha256: String,
    val packageName: String, val signerSha256: String)
enum class Verdict { CLEAN, SUSPICIOUS, MALICIOUS, UNKNOWN }
data class VerdictReport(val verdict: Verdict, val reasons: List<String>)
```

## 5. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 拒绝并给人话错误，不猜测、不静默降级。
- 远端数据统一走 `UiState`（Loading / Error / Data），加载中禁止展示假数据。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | 纯 JVM 单测覆盖四种 verdict 与云不可达降级分支。 |

> 验收方法：纯 JVM 单测（`./gradlew test`）/ CI 门禁脚本 / 真机回归清单（Android 15 与 16 各一台）/ 通道清单校验，按单元性质择一或组合。

## 7. 依赖与风险

**依赖**

- N-VIR-001

**风险**

- 无新增外部依赖；行为变更需回归既有单测。

## 8. 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| 0.1 | 2026-11-21 | 基于 abyss-tool 组织 19 仓库克隆研究创建；对齐 v0.9.5 基线。 |
| 0.2 | 2026-11-27 | v0.9.18 落地纯 JVM 核心实现与单测，更新规格状态为已实现。 |

