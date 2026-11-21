# FEAT-006 · 应用管理器 AppMgr

> 深渊工具 Abyss Tools · 软件规格说明书（功能）  
> 状态：**基线已实现** ｜ 优先级：**P1** ｜ 里程碑：**M0** ｜ 承载形态：**主壳内置** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `FEAT-006` |
| 所属域 | 功能 |
| 承载形态 | 主壳内置 |
| 当前状态 | 基线已实现 |
| 优先级 / 里程碑 | P1 / M0 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | AppMgr · SearchBar · Shizuku |

## 1. 目标与范围

列表 / 搜索 / 排序 / 冻结（pm disable-user）/ 隐藏（pm hide）/ 强停（am force-stop）/ 提取 APK 分享；v0.9.0 升级 M3 SearchBar。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/feature/appmgr/src/main/kotlin/com/abyss/tools/feature/appmgr/AppManagerScreen.kt`
- `Abyss-Tools/feature/appmgr/src/main/kotlin/com/abyss/tools/feature/appmgr/AppDetailScreen.kt`
- `Abyss-Tools/feature/appmgr/src/main/kotlin/com/abyss/tools/feature/appmgr/AppManagerCommands.kt`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | SearchBar 展开态记录最近 6 条关键词历史并落盘，匹配行直达详情，系统返回键先收搜索视图。 |
| FR-002 | 危险动作（冻结 / 隐藏 / 强停）需二次确认并支持逐项撤销。 |
| FR-003 | 指令编排抽到 AppManagerCommands（纯 JVM 可单测）。 |
| FR-004 | 应用详情只序列化包名键，恢复后经 AppInfoProvider 异步重挂。 |
| FR-005 | 提取 APK 走 MediaStore / 分享双路径，输出路径不可穿越。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

沿用宿主既有契约（`PluginContract.HOST_API_VERSION = 1`、`SemVer`、`UiState` 三态），本单元不新增跨模块接口。

## 5. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 拒绝并给人话错误，不猜测、不静默降级。
- 远端数据统一走 `UiState`（Loading / Error / Data），加载中禁止展示假数据。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | AppManagerCommandsTest 覆盖冻结/解冻/隐藏/强停命令文本与参数白名单。 |

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

