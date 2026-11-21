# UPD-008 · PatchEngine / ZipPatchApplier 数据补丁

> 深渊工具 Abyss Tools · 软件规格说明书（核心）  
> 状态：**基线已实现** ｜ 优先级：**P0** ｜ 里程碑：**M0** ｜ 承载形态：**纯 JVM** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `UPD-008` |
| 所属域 | 核心 |
| 承载形态 | 纯 JVM |
| 当前状态 | 基线已实现 |
| 优先级 / 里程碑 | P0 / M0 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | 数据补丁 · 备份回滚 · zip bomb |

## 1. 目标与范围

abyss-patch-v1 zip：patch-manifest.json 必须在包根；备份 → 应用 → 失败回滚；路径穿越 / zip 炸弹 / 载荷缺失整包拒绝。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/core/update/src/main/kotlin/com/abyss/tools/core/update/PatchEngine.kt`
- `Abyss-Tools/core/update/src/test/kotlin/com/abyss/tools/core/update/UpdateEngineTest.kt`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | 无 manifest / schema 不符 / target 空 → 整包拒绝。 |
| FR-002 | 路径穿越（../ 或绝对路径）→ 整包拒绝且目标目录零触碰。 |
| FR-003 | 任意写失败 → 从备份回滚全部已改文件。 |
| FR-004 | manifest 未列 files → 按包内载荷默认 replace（幂等友好）。 |
| FR-005 | action 支持 replace / add / delete，删除路径同样需 canonical 断言。 |
| FR-006 | zip 累计解压上限 64MB，条目数与压缩比双重防线。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

```json
{ "schema": "abyss-patch-v1", "target": "abyss-plugin-terminal",
  "fromVersion": "0.1.0", "toVersion": "0.1.1",
  "files": [ { "path": "plugin.json", "action": "replace" } ] }
```

## 5. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 拒绝并给人话错误，不猜测、不静默降级。
- 远端数据统一走 `UiState`（Loading / Error / Data），加载中禁止展示假数据。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | UpdateEngineTest 覆盖穿越、缺载荷、schema 错、回滚与 PatchReport 计数。 |

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

