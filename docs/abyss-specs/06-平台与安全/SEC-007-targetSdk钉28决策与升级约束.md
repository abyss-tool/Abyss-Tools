# SEC-007 · targetSdk 钉 28 决策与升级约束

> 深渊工具 Abyss Tools · 软件规格说明书（平台安全）  
> 状态：**基线已实现** ｜ 优先级：**P0** ｜ 里程碑：**M0** ｜ 承载形态：**平台兼容** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `SEC-007` |
| 所属域 | 平台安全 |
| 承载形态 | 平台兼容 |
| 当前状态 | 基线已实现 |
| 优先级 / 里程碑 | P0 / M0 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | targetSdk · W^X · SELinux |

## 1. 目标与范围

Termux 同款决策：untrusted_app_29+ 的 W^X 会击杀终端 / Python / 构建器在 app_data_file 执行二进制的能力；侧载分发不受商店 target 政策约束。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/docs/ANDROID_COMPAT.md`
- `Abyss-Tools/app/build.gradle.kts`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | 未满足 N-CMP-001 前置条件前，targetSdk 必须保持 28。 |
| FR-002 | lint 显式 disable ExpiredTargetSdkVersion 并附注释说明原因。 |
| FR-003 | Android 15/16 适配 = 在钉 28 前提下完美运行，而不是升 target。 |
| FR-004 | 升级前置四项（执行域迁移 / FGS 类型化 / RECEIVER / scoped storage）逐项留验证记录。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

沿用宿主既有契约（`PluginContract.HOST_API_VERSION = 1`、`SemVer`、`UiState` 三态），本单元不新增跨模块接口。

## 5. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 拒绝并给人话错误，不猜测、不静默降级。
- 远端数据统一走 `UiState`（Loading / Error / Data），加载中禁止展示假数据。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | 构建产物 targetSdk=28；ANDROID_COMPAT.md 逐项矩阵与真机回归结果一致。 |

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

