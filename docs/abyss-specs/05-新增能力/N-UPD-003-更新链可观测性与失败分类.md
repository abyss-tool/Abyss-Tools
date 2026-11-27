# N-UPD-003 · 更新链可观测性与失败分类

> 深渊工具 Abyss Tools · 软件规格说明书（新增能力）  
> 状态：**已实现（v0.9.16 · UpdateTracer + UpdateFailureSheet）** ｜ 优先级：**P1** ｜ 里程碑：**M1** ｜ 承载形态：**新增能力** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `N-UPD-003` |
| 所属域 | 新增能力 |
| 承载形态 | 新增能力 |
| 当前状态 | 已实现（v0.9.16：core/update/UpdateTracer + feature/update/UpdateFailureSheet） |
| 优先级 / 里程碑 | P1 / M1 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | 可观测性 · 诊断 |

## 1. 目标与范围

对验签失败 / 摘要不符 / 域外重定向 / 镜像全失败 / 安装被拒等失败做结构化分类与本地诊断，便于定位弱网与投毒。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/core/update/.../UpdateManager.kt`
- `Abyss-Tools/core/update/.../HttpTransport.kt`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | 失败原因枚举化（NETWORK / MIRROR / DIGEST / SIGNATURE / HOST / INSTALL / SCHEMA）。 |
| FR-002 | 每次检查更新写入本地诊断记录（时间、通道、镜像、耗时、结果），不含个人隐私。 |
| FR-003 | 诊断记录可在设置页导出，导出前默认脱敏。 |
| FR-004 | 同一原因连续失败达阈值时在 UI 给出针对性建议（换镜像 / 检查签名 / 等待新版本）。 |
| FR-005 | 日志不外传，除非用户显式导出。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

本单元为规划新增，具体接口在实现评审时定稿；与既有系统的边界必须沿用：

- `PluginContract.HOST_API_VERSION = 1`（只增不减）与 `PluginManifest` schema。
- `AbyssChannels` 渠道常量 + `ChannelPolicy` / `FetchHostPolicy` 运行时白名单。
- `SemVer` 版本比较、`UiState` 三态、`Result.toUiState()` 统一出口。
- 资产一律 SHA-256 校验 + 备份 → 应用 → 失败回滚（`abyss-patch-v1` 纪律）。
- 纯 JVM 可测边界：指令编排 / 解析 / 排程数学 / 防护逻辑零 Android 依赖。

## 5. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 拒绝并给人话错误，不猜测、不静默降级。
- 远端数据统一走 `UiState`（Loading / Error / Data），加载中禁止展示假数据。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | 构造摘要不符场景，诊断记录归类为 DIGEST 且 UI 建议正确。 |

> 验收方法：纯 JVM 单测（`./gradlew test`）/ CI 门禁脚本 / 真机回归清单（Android 15 与 16 各一台）/ 通道清单校验，按单元性质择一或组合。

## 7. 依赖与风险

**依赖**

- UPD-005

**风险**

- 无新增外部依赖；行为变更需回归既有单测。

## 8. 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| 0.1 | 2026-11-21 | 基于 abyss-tool 组织 19 仓库克隆研究创建；对齐 v0.9.5 基线。 |

