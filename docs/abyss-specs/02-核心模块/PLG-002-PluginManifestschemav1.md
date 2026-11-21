# PLG-002 · PluginManifest schema v1

> 深渊工具 Abyss Tools · 软件规格说明书（核心）  
> 状态：**基线已实现** ｜ 优先级：**P0** ｜ 里程碑：**M0** ｜ 承载形态：**plugin-sdk** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `PLG-002` |
| 所属域 | 核心 |
| 承载形态 | plugin-sdk |
| 当前状态 | 基线已实现 |
| 优先级 / 里程碑 | P0 / M0 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | manifest · schema · Shizuku |

## 1. 目标与范围

15 字段 manifest；shizuku 为可选声明，ownAuth=true 表示独立 APK 需单独授权。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/plugin-sdk/plugin-api/src/main/kotlin/com/abyss/tools/plugin/api/PluginManifest.kt`
- `abyss-plugin-app-builder/plugin.json`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | version / minApp 必须是合法 SemVer。 |
| FR-002 | repo 末段必须等于 id。 |
| FR-003 | category ∈ {terminal, system, sandbox, disguise, media, automation, desktop, network, data, tools, dev}。 |
| FR-004 | 声明 shizuku 即要求 ownAuth=true 且 reason 非空（人话说明为什么）。 |
| FR-005 | 运行在宿主进程内的数据插件不声明 shizuku 字段。 |
| FR-006 | parse 使用 ignoreUnknownKeys = true 保证向后兼容。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

```kotlin
@Serializable data class PluginManifest(
    val id: String, val name: String, val version: String,
    val minHost: Int = 1, val minApp: String = "0.1.0",
    val category: String = "tools", val summary: String = "",
    val icon: String? = null, val permissions: List<String> = emptyList(),
    val shizuku: PluginShizukuDecl? = null, val entry: String? = null,
    val repo: String = "", val patchChannel: String? = null,
    val roadmap: List<String> = emptyList())
```

## 5. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 拒绝并给人话错误，不猜测、不静默降级。
- 远端数据统一走 `UiState`（Loading / Error / Data），加载中禁止展示假数据。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | validate_plugin.py 三处一致性门禁（manifest / changelog / 补丁通道 latest）全绿。 |

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

