# CORE-004 · MirrorCatalog 镜像目录与改写

> 深渊工具 Abyss Tools · 软件规格说明书（核心）  
> 状态：**基线已实现** ｜ 优先级：**P1** ｜ 里程碑：**M0** ｜ 承载形态：**纯 JVM** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `CORE-004` |
| 所属域 | 核心 |
| 承载形态 | 纯 JVM |
| 当前状态 | 基线已实现 |
| 优先级 / 里程碑 | P1 / M0 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | 镜像 · 下载 |

## 1. 目标与范围

DIRECT / PREFIX / HOST_SWAP 三种策略；4 个 ghproxy 前缀镜像 + raw.gitmirror.com 域替换 + GitHub 直连恒为兜底。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/core/common/src/main/kotlin/com/abyss/tools/core/common/MirrorCatalog.kt`
- `Abyss-Tools/core/common/src/test/kotlin/com/abyss/tools/core/common/MirrorCatalogTest.kt`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | 镜像只承担搬运，资产仍强制 SHA-256 校验（fail-closed）。 |
| FR-002 | PREFIX 形态递归校验内嵌 GitHub 白名单 URL，禁止借道外传任意域。 |
| FR-003 | assetKindOf 只识别 github.com（RELEASE）与 raw.githubusercontent.com（RAW）。 |
| FR-004 | originalUrlOf 能把镜像 URL 还原为原始 URL 供重定向落点复检。 |
| FR-005 | 镜像死亡时从目录单点删除即下线，不改业务代码。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

```kotlin
enum class MirrorStrategy { DIRECT, PREFIX, HOST_SWAP }
data class DownloadMirror(val id: String, val label: String, val strategy: MirrorStrategy,
    val prefix: String? = null, val swapHost: String? = null,
    val appliesTo: Set<MirrorAssetKind> = MirrorAssetKind.ALL)
object MirrorCatalog { val all: List<DownloadMirror>; fun rewrite(...); fun originalUrlOf(...) }
```

## 5. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 拒绝并给人话错误，不猜测、不静默降级。
- 远端数据统一走 `UiState`（Loading / Error / Data），加载中禁止展示假数据。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | MirrorCatalogTest 覆盖 PREFIX 改写、HOST_SWAP 仅对 RAW 生效、DIRECT 恒等。 |

> 验收方法：纯 JVM 单测（`./gradlew test`）/ CI 门禁脚本 / 真机回归清单（Android 15 与 16 各一台）/ 通道清单校验，按单元性质择一或组合。

## 7. 依赖与风险

**依赖**

- `core:common`（SemVer / UiState / 渠道常量）

**风险**

- 公共镜像可用性不稳定；需延迟实测 + 失败冷却记忆，避免全链重复超时。

## 8. 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| 0.1 | 2026-11-21 | 基于 abyss-tool 组织 19 仓库克隆研究创建；对齐 v0.9.5 基线。 |

