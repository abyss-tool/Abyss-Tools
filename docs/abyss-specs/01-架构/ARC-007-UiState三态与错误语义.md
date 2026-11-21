# ARC-007 · UiState 三态与错误语义

> 深渊工具 Abyss Tools · 软件规格说明书（架构）  
> 状态：**基线已实现** ｜ 优先级：**P0** ｜ 里程碑：**M0** ｜ 承载形态：**主壳内置** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `ARC-007` |
| 所属域 | 架构 |
| 承载形态 | 主壳内置 |
| 当前状态 | 基线已实现 |
| 优先级 / 里程碑 | P0 / M0 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | UiState · fail-closed |

## 1. 目标与范围

sealed interface UiState<out T>：Loading / Error(message, retryable) / Data(value)；Result<T>.toUiState() 统一出口。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/core/common/src/main/kotlin/com/abyss/tools/core/common/UiState.kt`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | 禁止「加载中显示假数据」。 |
| FR-002 | Error 必须携带人话 message 与 retryable 标志。 |
| FR-003 | 网络层出口统一 Result → toUiState()，业务层不自行吞异常。 |
| FR-004 | 失败状态必须提供重试入口（retryKey 管线可复用）。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

```kotlin
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Error(val message: String, val retryable: Boolean = true) : UiState<Nothing>
    data class Data<T>(val value: T) : UiState<T>
}
fun <T> Result<T>.toUiState(): UiState<T>
```

## 5. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 拒绝并给人话错误，不猜测、不静默降级。
- 远端数据统一走 `UiState`（Loading / Error / Data），加载中禁止展示假数据。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | 远端数据页面断网时展示 Error + 重试按钮，不展示旧数据伪装成新数据。 |

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

