# N-PLG-002 · AIDL 双向桥

> 深渊工具 Abyss Tools · 软件规格说明书（新增能力）  
> 状态：**规划中 · 待实现** ｜ 优先级：**P1** ｜ 里程碑：**M3** ｜ 承载形态：**新增能力** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `N-PLG-002` |
| 所属域 | 新增能力 |
| 承载形态 | 新增能力 |
| 当前状态 | 规划中 · 待实现 |
| 优先级 / 里程碑 | P1 / M3 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | Phase 2 · AIDL · 跨进程 |

## 1. 目标与范围

Phase 2：IAbyssPlugin / IAbyssPluginHost 双向 AIDL + PackageManager service 发现（ACTION_PLUGIN）+ 同签名信任门。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/docs/PLUGIN_SDK.md`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | AIDL 接口版本化，HOST_API 升级时保留旧方法兼容期。 |
| FR-002 | service 发现只接受同签名 + 白名单包名的插件服务。 |
| FR-003 | 宿主侧能力经 IAbyssPluginHost 显式暴露（ShellGateway 受限代理 / 配置读写），不直接给 Binder 全权。 |
| FR-004 | 跨进程调用需超时与死亡回调（DeathRecipient）处理。 |
| FR-005 | 插件崩溃不得导致宿主重启。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

本单元为规划新增，具体接口在实现评审时定稿；与既有系统的边界必须沿用：

- `PluginContract.HOST_API_VERSION = 1`（只增不减）与 `PluginManifest` schema。
- `AbyssChannels` 渠道常量 + `ChannelPolicy` / `FetchHostPolicy` 运行时白名单。
- `SemVer` 版本比较、`UiState` 三态、`Result.toUiState()` 统一出口。
- 资产一律 SHA-256 校验 + 备份 → 应用 → 失败回滚（`abyss-patch-v1` 纪律）。
- 纯 JVM 可测边界：指令编排 / 解析 / 排程数学 / 防护逻辑零 Android 依赖。

## 5. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 拒绝并给人话错误，不猜测、不静默降级。
- 远端数据统一走 `UiState`（Loading / Error / Data），加载中禁止展示假数据。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | 杀掉插件进程后宿主收到死亡通知并清理会话。 |

> 验收方法：纯 JVM 单测（`./gradlew test`）/ CI 门禁脚本 / 真机回归清单（Android 15 与 16 各一台）/ 通道清单校验，按单元性质择一或组合。

## 7. 依赖与风险

**依赖**

- N-PLG-001

**风险**

- 无新增外部依赖；行为变更需回归既有单测。

## 8. 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| 0.1 | 2026-11-21 | 基于 abyss-tool 组织 19 仓库克隆研究创建；对齐 v0.9.5 基线。 |

