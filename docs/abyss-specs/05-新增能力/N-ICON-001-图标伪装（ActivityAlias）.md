# N-ICON-001 · 图标伪装（ActivityAlias）

> 深渊工具 Abyss Tools · 软件规格说明书（新增能力）  
> 状态：**规划中 · 待实现** ｜ 优先级：**P3** ｜ 里程碑：**M4** ｜ 承载形态：**新增能力** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `N-ICON-001` |
| 所属域 | 新增能力 |
| 承载形态 | 新增能力 |
| 当前状态 | 规划中 · low 模式骨架已落地（v0.10.2） |
| 优先级 / 里程碑 | P3 / M4 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | Phase 4 · ActivityAlias |

## 1. 目标与范围

Phase 4：通过 activity-alias 切换宿主自身启动图标与名称，支持恢复默认。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/feature/appmgr/.../AppManagerScreen.kt`
- `Abyss-Tools/app/src/main/AndroidManifest.xml`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | alias 集合在 Manifest 中静态声明，禁止运行时动态注册。 |
| FR-002 | 切换需二次确认并提示「桌面图标可能短暂消失/重排」。 |
| FR-003 | 切换后返回栈与数据不受影响。 |
| FR-004 | 一键恢复默认 alias，失败时透传系统错误。 |

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
| AC-001 | 切换后桌面图标与标签变更，重启后保持。 |

> 验收方法：纯 JVM 单测（`./gradlew test`）/ CI 门禁脚本 / 真机回归清单（Android 15 与 16 各一台）/ 通道清单校验，按单元性质择一或组合。

## 7. 依赖与风险

**依赖**

- ARC-004

**风险**

- 无新增外部依赖；行为变更需回归既有单测。

## 8. 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| 0.1 | 2026-11-21 | 基于 abyss-tool 组织 19 仓库克隆研究创建；对齐 v0.9.5 基线。 |
| 0.2 | 2026-11-28 | v0.10.2：FEAT-036 图标三选一被 `FEAT-040` 采纳为伪装 low 模式（零授权）骨架；场景化自动切换（medium，经 `AbyssAccessibilityService` 前台包名感知）与隐藏图标恢复入口仍待实现。 |

