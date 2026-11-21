# UPD-002 · PatchesManifest 与 patches.json 链式增量索引

> 深渊工具 Abyss Tools · 软件规格说明书（核心）  
> 状态：**基线已实现（通道为空）** ｜ 优先级：**P1** ｜ 里程碑：**M0** ｜ 承载形态：**纯 JVM** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `UPD-002` |
| 所属域 | 核心 |
| 承载形态 | 纯 JVM |
| 当前状态 | 基线已实现（通道为空） |
| 优先级 / 里程碑 | P1 / M0 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | 增量更新 · vcdiff · 缺口 |

## 1. 目标与范围

abyss-patches-v1：latestTag + patches[]；v0.9.5 实测 patches 为空数组，二进制增量（vcdiff）尚未落地。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/core/update/src/main/kotlin/com/abyss/tools/core/update/UpdateModels.kt`
- `Abyss-Tools-Release/patches.json`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | latestTag 必须等于 version.json 的 tag。 |
| FR-002 | patches 条目 fromTag/toTag 必须构成可追溯链，不允许断链。 |
| FR-003 | 客户端不得因 patches 为空而误判为无更新。 |
| FR-004 | vcdiff 二进制增量落地前，插件侧仅使用 abyss-patch-v1 数据补丁。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

```json
{ "schema": "abyss-patches-v1", "generatedAt": "...", "latestTag": "v0.9.5", "patches": [] }
```

## 5. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 拒绝并给人话错误，不猜测、不静默降级。
- 远端数据统一走 `UiState`（Loading / Error / Data），加载中禁止展示假数据。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | 发布仓 validate-manifests.yml 校验 latestTag ↔ version.json.tag 一致。 |

> 验收方法：纯 JVM 单测（`./gradlew test`）/ CI 门禁脚本 / 真机回归清单（Android 15 与 16 各一台）/ 通道清单校验，按单元性质择一或组合。

## 7. 依赖与风险

**依赖**

- `core:common`（SemVer / UiState / 渠道常量）

**风险**

- 二进制增量缺失导致每次升级都需下载约 14MB 整包，弱网/流量敏感用户体验差。

## 8. 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| 0.1 | 2026-11-21 | 基于 abyss-tool 组织 19 仓库克隆研究创建；对齐 v0.9.5 基线。 |

