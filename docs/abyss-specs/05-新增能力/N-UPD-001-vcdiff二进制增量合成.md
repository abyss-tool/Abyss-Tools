# N-UPD-001 · vcdiff 二进制增量合成

> 深渊工具 Abyss Tools · 软件规格说明书（新增能力）  
> 状态：**规划中 · 待实现** ｜ 优先级：**P1** ｜ 里程碑：**M2** ｜ 承载形态：**新增能力** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `N-UPD-001` |
| 所属域 | 新增能力 |
| 承载形态 | 新增能力 |
| 当前状态 | 规划中 · 待实现 |
| 优先级 / 里程碑 | P1 / M2 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | 增量更新 · vcdiff |

## 1. 目标与范围

把 patches.json 长期为空数组的缺口补上：vcdiff 增量包生成 → 客户端合成 → 覆盖安装，减少约 14MB 整包下载。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools-Release/patches.json`
- `Abyss-Tools/docs/UPDATE_CHANNEL.md`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | 发布流水线对相邻 tag 的 APK 生成 vcdiff 差分包，写入 patches.json 的 fromTag/toTag/url/sizeBytes/sha256。 |
| FR-002 | 客户端合成前校验基版本 APK 的 sha256 与 fromTag 一致，不一致即回退整包。 |
| FR-003 | 合成后必须再次校验目标 APK 的 sha256 才允许安装。 |
| FR-004 | 合成失败自动回退整包并给出人话提示，不阻塞升级。 |
| FR-005 | 差分包体量上限与压缩比校验复用 zip bomb 防线。 |
| FR-006 | latestTag 与 version.json.tag 保持严格一致。 |

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
| AC-001 | v0.9.5 → v0.9.6 升级下载量显著低于整包且安装成功。 |

> 验收方法：纯 JVM 单测（`./gradlew test`）/ CI 门禁脚本 / 真机回归清单（Android 15 与 16 各一台）/ 通道清单校验，按单元性质择一或组合。

## 7. 依赖与风险

**依赖**

- UPD-002
- UPD-008

**风险**

- 基 APK 被篡改或用户侧安装非发布版本时合成链需安全回退。

## 8. 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| 0.1 | 2026-11-21 | 基于 abyss-tool 组织 19 仓库克隆研究创建；对齐 v0.9.5 基线。 |

