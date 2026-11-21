# CORE-003 · AbyssChannels 渠道常量

> 深渊工具 Abyss Tools · 软件规格说明书（核心）  
> 状态：**基线已实现** ｜ 优先级：**P0** ｜ 里程碑：**M0** ｜ 承载形态：**纯 JVM** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `CORE-003` |
| 所属域 | 核心 |
| 承载形态 | 纯 JVM |
| 当前状态 | 基线已实现 |
| 优先级 / 里程碑 | P0 / M0 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | 渠道 · 白名单 |

## 1. 目标与范围

组织与四个固定读取点常量；业务代码禁止硬编码 raw.githubusercontent 地址。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/core/common/src/main/kotlin/com/abyss/tools/core/common/AbyssChannels.kt`
- `Abyss-Tools/docs/UPDATE_CHANNEL.md`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | VERSION_JSON / PATCHES_JSON / CHANGELOG_JSON / PLUGIN_INDEX_JSON 各自指向发布仓或插件中心 main 分支。 |
| FR-002 | pluginPatchChannel(pluginId) 由 id 推导，禁止手写拼接。 |
| FR-003 | 运行时 URL 一律过 FetchHostPolicy / ChannelPolicy 白名单。 |
| FR-004 | ORG 与仓库矩阵常量变更需全仓同步（AGENTS.md / 各仓 manifest）。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

```kotlin
object AbyssChannels {
    const val ORG = "abyss-tool"
    const val DEV_REPO = "abyss-tool/Abyss-Tools"
    const val RELEASE_REPO = "abyss-tool/Abyss-Tools-Release"
    const val PLUGIN_HUB_REPO = "abyss-tool/Abyss-Plugins"
    const val VERSION_JSON = "https://raw.githubusercontent.com/abyss-tool/Abyss-Tools-Release/main/version.json"
    // PATCHES_JSON / CHANGELOG_JSON / PLUGIN_INDEX_JSON 同构
}
```

## 5. 失败语义与边界

- 归档仓 plugin.json 中的 `Ultra-Guru/...` 与 ORG 不符，属历史遗留，不得进入运行时索引。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | 全仓 grep 无业务代码硬编码 raw.githubusercontent 地址。 |

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

