# PLG-001 · PluginContract HOST_API v1

> 深渊工具 Abyss Tools · 软件规格说明书（核心）  
> 状态：**基线已实现** ｜ 优先级：**P0** ｜ 里程碑：**M0** ｜ 承载形态：**plugin-sdk** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `PLG-001` |
| 所属域 | 核心 |
| 承载形态 | plugin-sdk |
| 当前状态 | 基线已实现 |
| 优先级 / 里程碑 | P0 / M0 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | HOST_API · 契约 |

## 1. 目标与范围

HOST_API_VERSION = 1（只增不减）；MANIFEST_FILE / PLUGINS_DIR / PATCHES_INDEX / ID_PATTERN / META_HOST_ENTRY / META_UI_ENTRIES。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/plugin-sdk/plugin-api/src/main/kotlin/com/abyss/tools/plugin/api/PluginContract.kt`
- `Abyss-Tools/docs/PLUGIN_SDK.md`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | HOST_API_VERSION 只增不减；插件 minHost > 宿主版本时 fail-closed 拒绝加载。 |
| FR-002 | 安装态单一事实源：filesDir/plugins/<id>/plugin.json 存在即已安装。 |
| FR-003 | ID_PATTERN 为 ^[a-z0-9]+(-[a-z0-9]+)+$（小写 + 连字符）。 |
| FR-004 | META_UI_ENTRIES / META_HOST_ENTRY 为 v0.2 AIDL 桥预留，当前未消费。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

```kotlin
object PluginContract {
    const val HOST_API_VERSION: Int = 1
    const val MANIFEST_FILE = "plugin.json"
    const val PLUGINS_DIR = "plugins"
    const val PATCHES_INDEX = "patches/index.json"
    val ID_PATTERN = Regex("^[a-z0-9]+(-[a-z0-9]+)+$")
    const val META_HOST_ENTRY = "com.abyss.tools.plugin.HOST_ENTRY"
    const val META_UI_ENTRIES = "com.abyss.tools.plugin.UI_ENTRIES"
}
```

## 5. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 拒绝并给人话错误，不猜测、不静默降级。
- 远端数据统一走 `UiState`（Loading / Error / Data），加载中禁止展示假数据。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | PluginFrameworkTest 断言 HOST_API_VERSION 与 minHost 校验行为。 |

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

