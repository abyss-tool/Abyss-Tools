# SRS-000 · 总体软件需求规格（SRS）

> 深渊工具 Abyss Tools · 软件规格说明书（总纲）  
> 状态：**基线已实现 + 演进规划** ｜ 优先级：**P0** ｜ 里程碑：**M0** ｜ 承载形态：**全系统** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `SRS-000` |
| 所属域 | 总纲 |
| 承载形态 | 全系统 |
| 当前状态 | 基线已实现 + 演进规划 |
| 优先级 / 里程碑 | P0 / M0 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | SRS · 单一事实源 · 追溯矩阵 |

## 1. 目标与范围

本文件是深渊工具总体软件需求规格的补完件。仓库文档多处引用「总体 SRS §6」作为里程碑依据，但组织内并无该文档实体；本规格与随包《整体增强方案》共同构成可执行的单一事实源。产品定位：面向中文极客的 Android 系统级免 Root 超级工具箱，侧载分发，主壳轻、能力重。

**目标**

- 把分散在 README / ARCHITECTURE / ROADMAP / release-notes 的口径收敛到一份 SRS。
- 明确 v0.9.5 基线能力矩阵与 Phase 1b–4 未完成项。
- 为 100+ 份子规格提供编号、优先级、里程碑与追溯矩阵。

**范围外（本规格不承诺）**

- 不重新定义已在 docs/SECURITY.md 定稿的签名信任链决策（仅做加固规划）。
- 不承诺 Play 商店上架；分发语义为 GitHub Releases 侧载。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/README.md`
- `Abyss-Tools/docs/ARCHITECTURE.md`
- `Abyss-Tools/settings.gradle.kts`
- `Abyss-Tools-Release/version.json`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | SRS 必须给出组织仓库矩阵：主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓，共 19 仓。 |
| FR-002 | SRS 必须声明基线版本与版本单一事实源（`AppInfo.VERSION_NAME/VERSION_CODE`）。 |
| FR-003 | SRS 必须定义承载形态三分法：BUILT_IN / PLUGIN（引擎级）/ FRAMEWORK（路线图）。 |
| FR-004 | SRS 必须包含可追溯矩阵：每条 FR → 子规格编号 → 验收用例。 |
| FR-005 | SRS 必须列出 Phase 1b、Phase 2、Phase 3、Phase 4 的完成判据。 |
| FR-006 | SRS 变更必须走 Conventional Commits 并同步 SPEC-INDEX.md。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

```
基线：v0.9.5 / versionCode 28 / Kotlin 2.3.21 / AGP 8.13.2 / Gradle 8.14 / JDK 17
SDK：compileSdk 36 / minSdk 26 / targetSdk 28（钉死，SELinux W^X 红线）
分发：GitHub Releases 侧载，签名身份 meng411722，v2+v3 签名
许可：UGRL v1.0（个人学习与安全研究，禁止再分发与商业使用）
模块：:app + :vendor:backdrop + 5×:core + :plugin-sdk:plugin-api + 22×:feature
```

## 5. 失败语义与边界

- SRS 不是实现文档；契约细节以 02/07 域规格为准，冲突时以代码与测试夹具为准。
- 任何「已完成」声明必须有 CI 绿灯或真机回归记录支撑，禁止以文档代替实现。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | SPEC-INDEX.md 收录全部子规格且编号唯一、无断号。 |
| AC-002 | 基线能力矩阵与 ToolCatalog.kt 的 tools 列表逐条一致。 |
| AC-003 | 19 个仓库的角色、归档状态与 plugins.json / 组织页面一致。 |

> 验收方法：纯 JVM 单测（`./gradlew test`）/ CI 门禁脚本 / 真机回归清单（Android 15 与 16 各一台）/ 通道清单校验，按单元性质择一或组合。

## 7. 依赖与风险

**依赖**

- docs/ARCHITECTURE.md
- docs/PLUGIN_SDK.md
- docs/SECURITY.md
- docs/UPDATE_CHANNEL.md

**风险**

- 文档与实现漂移：需由 CI 的 `validate_manifests.py` 与新增 spec-lint 双重门禁约束。

## 8. 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| 0.1 | 2026-11-21 | 基于 abyss-tool 组织 19 仓库克隆研究创建；对齐 v0.9.5 基线。 |

