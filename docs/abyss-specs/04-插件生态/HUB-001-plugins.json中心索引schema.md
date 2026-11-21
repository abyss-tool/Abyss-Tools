# HUB-001 · plugins.json 中心索引 schema

> 深渊工具 Abyss Tools · 软件规格说明书（插件生态）  
> 状态：**基线已实现** ｜ 优先级：**P0** ｜ 里程碑：**M0** ｜ 承载形态：**插件中心** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `HUB-001` |
| 所属域 | 插件生态 |
| 承载形态 | 插件中心 |
| 当前状态 | 基线已实现 |
| 优先级 / 里程碑 | P0 / M0 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | 索引 · schema |

## 1. 目标与范围

abyss-plugin-index-v1：hubVersion 0.3.0 + 3 个引擎级插件条目；apk 字段当前全为 null。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Plugins/plugins.json`
- `Abyss-Plugins/README.md`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | 至少 3 个引擎级插件，id 唯一。 |
| FR-002 | 每条目含 id/name/repo/category/version/minApp/summary/icon/permissions/shizuku/apk/patchChannel/roadmap。 |
| FR-003 | shizuku 声明即 ownAuth=true 且 reason 必填。 |
| FR-004 | apk 为 null 时客户端必须展示「资产未发布」而非隐藏条目。 |
| FR-005 | patchChannel 形态符合 ChannelPolicy 正则。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

```json
{ "schema": "abyss-plugin-index-v1", "generatedAt": "...", "hubVersion": "0.3.0",
  "plugins": [ { "id": "abyss-plugin-app-builder", "apk": null, "...": "..." } ] }
```

## 5. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 拒绝并给人话错误，不猜测、不静默降级。
- 远端数据统一走 `UiState`（Loading / Error / Data），加载中禁止展示假数据。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | validate_hub.py 在 CI 校验通过；3 个条目均可被 ChannelPolicy 接受。 |

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

