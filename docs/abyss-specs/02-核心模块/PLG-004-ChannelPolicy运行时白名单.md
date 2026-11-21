# PLG-004 · ChannelPolicy 运行时白名单

> 深渊工具 Abyss Tools · 软件规格说明书（核心）  
> 状态：**基线已实现** ｜ 优先级：**P0** ｜ 里程碑：**M0** ｜ 承载形态：**纯 JVM** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `PLG-004` |
| 所属域 | 核心 |
| 承载形态 | 纯 JVM |
| 当前状态 | 基线已实现 |
| 优先级 / 里程碑 | P0 / M0 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | 白名单 · fail-closed · 安全 |

## 1. 目标与范围

逐条校验 id 形态 / repo 归属 / 补丁通道 URL / 资产 host + sha256；不合规整条拒绝展示。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/core/plugin/src/main/kotlin/com/abyss/tools/core/plugin/ChannelPolicy.kt`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | repo 必须匹配 ^abyss-tool/abyss-plugin-[a-z0-9]+(-[a-z0-9]+)*$ 且末段等于 id。 |
| FR-002 | patchChannel 必须匹配 raw.githubusercontent.com/abyss-tool/<repo>/main/patches/index.json。 |
| FR-003 | APK 资产与补丁条目的 sha256 必须非空且为 64 位 hex。 |
| FR-004 | 资产域白名单：raw.githubusercontent.com / github.com / objects.githubusercontent.com / release-assets.githubusercontent.com。 |
| FR-005 | 任何校验失败返回人话原因，不抛异常穿透 UI。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

```kotlin
fun validateEntry(entry: PluginIndexEntry): String?   // null = 通过
fun validatePatch(patch: PluginPatch): String?
```

## 5. 失败语义与边界

- 绝不「拉到什么用什么」；索引被投毒最多造成条目不展示，不能引导到外域资产。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | PluginFrameworkTest 覆盖 repo 归属错误、sha256 缺失、非 https、外域 host 等拒收分支。 |

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

