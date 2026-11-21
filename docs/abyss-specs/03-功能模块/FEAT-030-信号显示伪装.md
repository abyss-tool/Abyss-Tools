# FEAT-030 · 信号显示伪装

> 深渊工具 Abyss Tools · 软件规格说明书（功能）  
> 状态：**基线已实现** ｜ 优先级：**P1** ｜ 里程碑：**M0** ｜ 承载形态：**主壳内置** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `FEAT-030` |
| 所属域 | 功能 |
| 承载形态 | 主壳内置 |
| 当前状态 | 基线已实现 |
| 优先级 / 里程碑 | P1 / M0 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | overlay · 状态栏 |

## 1. 目标与范围

appops SYSTEM_ALERT_WINDOW + TYPE_APPLICATION_OVERLAY 前台服务覆盖层：5G-A/5G/4G/LTE/3G + 信号格 1-4 + Wi-Fi 图标；无卡可用，停止即恢复。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/feature/disguise/src/main/kotlin/com/abyss/tools/feature/disguise/SignalSpoofScreen.kt`
- `Abyss-Tools/feature/disguise/src/main/kotlin/com/abyss/tools/feature/disguise/SignalOverlayService.kt`
- `Abyss-Tools/feature/disguise/src/main/kotlin/com/abyss/tools/feature/disguise/SignalStatusArt.kt`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | 绘制逻辑单一实现于 SignalStatusArt，覆盖层 View 与页内 Compose 预览共用。 |
| FR-002 | 截图 / 屏幕共享 / 录屏同视图；锁屏与下拉快捷面板头部不显示属系统原生约束，需明示。 |
| FR-003 | Shizuku 一键授权 + 停止即恢复真实显示。 |
| FR-004 | 配置变更经 ACTION_CONFIGURATION_CHANGED 重建布局参数。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

沿用宿主既有契约（`PluginContract.HOST_API_VERSION = 1`、`SemVer`、`UiState` 三态），本单元不新增跨模块接口。

## 5. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 拒绝并给人话错误，不猜测、不静默降级。
- 远端数据统一走 `UiState`（Loading / Error / Data），加载中禁止展示假数据。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | 截图核验与页内预览一致；停止服务后状态栏恢复真值。 |

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

