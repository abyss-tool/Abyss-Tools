# UPD-006 · HttpTransport 与 FetchHostPolicy

> 深渊工具 Abyss Tools · 软件规格说明书（核心）  
> 状态：**基线已实现** ｜ 优先级：**P0** ｜ 里程碑：**M0** ｜ 承载形态：**纯 JVM** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `UPD-006` |
| 所属域 | 核心 |
| 承载形态 | 纯 JVM |
| 当前状态 | 基线已实现 |
| 优先级 / 里程碑 | P0 / M0 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | 白名单 · fail-closed · 安全 |

## 1. 目标与范围

OkHttpTransport 请求前与重定向后双重域校验；白名单 raw.githubusercontent.com / github.com / objects.githubusercontent.com（+ release-assets）。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/core/update/src/main/kotlin/com/abyss/tools/core/update/HttpTransport.kt`
- `Abyss-Tools/docs/SECURITY.md`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | 非 https 一律拒绝。 |
| FR-002 | 重定向落点不在白名单即终止请求。 |
| FR-003 | PREFIX 镜像 URL 需递归校验内嵌 GitHub 白名单 URL。 |
| FR-004 | 响应体量上限与超时必须显式配置。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

允许域：raw.githubusercontent.com / github.com / objects.githubusercontent.com /
release-assets.githubusercontent.com（ChannelPolicy 资产域白名单同构）

## 5. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 拒绝并给人话错误，不猜测、不静默降级。
- 远端数据统一走 `UiState`（Loading / Error / Data），加载中禁止展示假数据。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | 构造 302 到 evil.example.com 的响应，请求被拒绝。 |

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
| 0.2 | 2026-11-27 | B-04 收口：`MirroredTransport` 重写 `getBytes` —— 逐镜调 `base.getBytes(rewrite(mirror,url))` 返回网络原文字节；缺省接口实现的 String decode/re-encode 往返（CRLF/BOM/非法 UTF-8 → U+FFFD）曾整链绕过 N-SEC-001「原始字节验签」。`SignedHttpTransport` 签名文件同步走字节路径。回归用例：`MirroredTransportTest`（字节保真/全链回退/非 GitHub 直通/端到端验签）。 |

