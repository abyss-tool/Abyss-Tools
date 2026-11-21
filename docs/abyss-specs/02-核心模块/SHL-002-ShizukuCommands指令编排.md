# SHL-002 · ShizukuCommands 指令编排

> 深渊工具 Abyss Tools · 软件规格说明书（核心）  
> 状态：**基线已实现** ｜ 优先级：**P0** ｜ 里程碑：**M0** ｜ 承载形态：**纯 JVM** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `SHL-002` |
| 所属域 | 核心 |
| 承载形态 | 纯 JVM |
| 当前状态 | 基线已实现 |
| 优先级 / 里程碑 | P0 / M0 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | 指令安全 · shellQuote · 纯JVM |

## 1. 目标与范围

免引用参数统一经 requireSafeToken 白名单 [A-Za-z0-9._:-]，自由文本 shellQuote 单引号转义；覆盖伪装四件套与各功能页指令。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/core/shell/src/main/kotlin/com/abyss/tools/core/shell/ShizukuCommands.kt`
- `Abyss-Tools/core/shell/src/test/kotlin/com/abyss/tools/core/shell/ShizukuCommandsTest.kt`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | 含元字符 / 空格的免引用参数就地拒绝，不尝试净化后执行。 |
| FR-002 | 自由文本（设备名 / 蓝牙名）一律 shellQuote 单引号转义。 |
| FR-003 | 指令编排零 Android 依赖，可纯 JVM 单测。 |
| FR-004 | 指令模板集中维护，禁止业务页拼 shell 字符串。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

```kotlin
fun requireSafeToken(token: String): String   // 白名单字符集，违规抛人话异常
fun shellQuote(text: String): String          // 单引号转义
```

## 5. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 拒绝并给人话错误，不猜测、不静默降级。
- 远端数据统一走 `UiState`（Loading / Error / Data），加载中禁止展示假数据。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | ShizukuCommandsTest 覆盖 `; rm -rf`、空格、单引号、Unicode 设备名。 |

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

