# CORE-001 · SemVer 语义化版本

> 深渊工具 Abyss Tools · 软件规格说明书（核心）  
> 状态：**基线已实现** ｜ 优先级：**P0** ｜ 里程碑：**M0** ｜ 承载形态：**纯 JVM** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `CORE-001` |
| 所属域 | 核心 |
| 承载形态 | 纯 JVM |
| 当前状态 | 基线已实现 |
| 优先级 / 里程碑 | P0 / M0 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | SemVer · 纯JVM |

## 1. 目标与范围

data class SemVer(major, minor, patch, preRelease, build) : Comparable<SemVer>；全仓版本比较唯一入口。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/core/common/src/main/kotlin/com/abyss/tools/core/common/SemVer.kt`
- `Abyss-Tools/core/common/src/test/kotlin/com/abyss/tools/core/common/SemVerTest.kt`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | parse 必须严格匹配 SemVer 正则，非法字符串返回 null 而非抛异常。 |
| FR-002 | preRelease 比较遵循 SemVer 规范：数值段优先级低于字母段，段数多者更大。 |
| FR-003 | build 元数据不参与优先级比较。 |
| FR-004 | toTag() 输出 v<version>；parseTag 容忍 v/V 前缀。 |
| FR-005 | 禁止业务代码手写 split('.') 版本比较。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

```kotlin
data class SemVer(val major: Int, val minor: Int, val patch: Int,
    val preRelease: String? = null, val build: String? = null) : Comparable<SemVer>
fun parse(raw: String): SemVer?
fun parseTag(tag: String): SemVer?
fun toTag(): String = "v$this"
```

## 5. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 拒绝并给人话错误，不猜测、不静默降级。
- 远端数据统一走 `UiState`（Loading / Error / Data），加载中禁止展示假数据。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | SemVerTest 覆盖 1.0.0-alpha < 1.0.0-alpha.1 < 1.0.0 与 build 元数据相等性。 |
| AC-002 | 非法版本（01.2.3 / 1.2 / 1.2.3-）解析返回 null。 |

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

