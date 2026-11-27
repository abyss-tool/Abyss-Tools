# PLG-005 · PluginInstaller 安装、卸载与版本账本

> 深渊工具 Abyss Tools · 软件规格说明书（核心）  
> 状态：**基线已实现** ｜ 优先级：**P0** ｜ 里程碑：**M0** ｜ 承载形态：**纯 JVM + 网关** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `PLG-005` |
| 所属域 | 核心 |
| 承载形态 | 纯 JVM + 网关 |
| 当前状态 | 基线已实现 |
| 优先级 / 里程碑 | P0 / M0 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | 安装器 · 账本 · PackageInstaller |

## 1. 目标与范围

installDataPlugin（校验 + 原子落盘）/ installApkPlugin 一条龙 / uninstall 清目录 / 安装成功后同步版本账本。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/core/plugin/src/main/kotlin/com/abyss/tools/core/plugin/PluginInstaller.kt`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | 数据插件：id 形态 / SemVer / minHost 与 minApp 下限校验 → manifest 原子落盘。 |
| FR-002 | APK 插件：下载 → SHA-256 → PackageInstallerApkGateway（同签名门禁 + 确认页转发 + 结果广播等待 + 超时 abandon）→ 版本账本同步。 |
| FR-003 | 版本账本必须与索引版本对齐，否则补丁通道 from 比对会算错。 |
| FR-004 | 卸载清目录；APK 形态附带系统卸载引导（缓存 APK 解析包名直达系统卸载页）。 |
| FR-005 | 卸载 / 补丁应用入口做 canonical 路径断言（穿越即拒）。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

沿用宿主既有契约（`PluginContract.HOST_API_VERSION = 1`、`SemVer`、`UiState` 三态），本单元不新增跨模块接口。

## 5. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 拒绝并给人话错误，不猜测、不静默降级。
- 远端数据统一走 `UiState`（Loading / Error / Data），加载中禁止展示假数据。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | 安装 APK 成功后 updatablePatch 的 from 命中新版本。 |
| AC-002 | 路径穿越的 plugin id 被拒且不创建目录。 |

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
| 0.2 | 2026-11-27 | B-12 收口：`installDataPlugin` 落盘改「备份 → 改名 → 失败回滚」三段原子语义 —— 旧实现 delete+rename 之间存在登记缺位窗口且无回滚。回归用例：升级受阻（备份位 EISDIR 注入）旧 manifest 完好 + 成功路径无 staging/bak 残留。 |

