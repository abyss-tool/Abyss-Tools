# N-APP-003 · Shizuku 独立授权与执行上下文

> 深渊工具 Abyss Tools · 软件规格说明书（新增能力）  
> 状态：**已实现（abyss-plugin-linux v0.2.0，全生态唯一授权点）** ｜ 优先级：**P0** ｜ 里程碑：**M1** ｜ 承载形态：**新增能力** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `N-APP-003` |
| 所属域 | 新增能力 |
| 承载形态 | 新增能力 |
| 当前状态 | 已实现（abyss-plugin-linux 仓 shizuku/ 模块 + runtime-api/OwnAuthContract） |
| 优先级 / 里程碑 | P0 / M1 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | Shizuku · per-app |

## 1. 目标与范围

独立 APK 按 Shizuku per-app 授权机制单独授权；宿主授权不继承；插件内完成授权检测与引导。

## 2. 现状锚点（克隆研究实测）

- `abyss-plugin-app-builder/plugin.json`
- `Abyss-Tools/docs/PLUGIN_SDK.md`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | 插件启动时检测自身包名是否在 Shizuku 授权列表，未授权展示引导而非崩溃。 |
| FR-002 | 引导文案明确「宿主深渊工具的授权不能继承给本插件」。 |
| FR-003 | 进程经 Shizuku.newProcess / shell 上下文启动，统一三条路径的启动逻辑。 |
| FR-004 | Shizuku 重启后通常需重新激活，状态变化需实时反映到 UI。 |
| FR-005 | ownAuth / reason 与 plugin.json 声明一致，宿主详情页展示同一份文案。 |

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
| AC-001 | 宿主已授权但插件未授权时，插件仍展示授权引导。 |

> 验收方法：纯 JVM 单测（`./gradlew test`）/ CI 门禁脚本 / 真机回归清单（Android 15 与 16 各一台）/ 通道清单校验，按单元性质择一或组合。

## 7. 依赖与风险

**依赖**

- N-APP-002
- SHL-004

**风险**

- ColorOS 16 可能拦截 Shizuku 本身，需提供权衡提示而非静默失败。


## 0. 实现说明（架构演进 · ADR-100 D-3）

**重要架构变更（2026-11-22）**：Shizuku 授权点收归 `abyss-plugin-linux`，成为**全生态唯一授权动作**——构建器（abyss-plugin-app-builder）、终端（abyss-plugin-terminal）等下游插件通过宿主 PluginBroker 经 SessionGrant 消费运行能力，不再各自持有 Shizuku 授权（FR-001：授权次数=1）。

### FR 实现对照表

| FR | 实现位置 | 说明 |
|---|---|---|
| FR-001 启动时自检授权状态 | `abyss-plugin-linux/shizuku/ShizukuOwnAuthDetector`（纯 JVM） | 检测自身包名（`com.abyss.plugin.linux`）是否在 Shizuku 授权列表，未授权返回 NOT_GRANTED 并展示引导，不崩溃 |
| FR-002 不继承宿主授权文案 | `runtime-client/.../HandshakeGuide.FailureKind.AUTH_NEEDED` | 引导文案明确"宿主授权不继承；请为 Linux 运行环境单独授权一次（全生态唯一授权动作）" |
| FR-003 三路径启动统一 | `abyss-plugin-linux/session/LinuxSession.launch(execDomain)` | 三条路径（Shizuku shell / Pure PRoot / QEMU 完整系统）经同一个 launch() 入口拉起，底层自动选执行上下文 |
| FR-004 Shizuku 重启实时感知 | `ShizukuOwnAuthDetector` + `ShizukuStateListener` | 监听 Shizuku service death/rebirth，状态变化通过 `InstallEventBus` 式总线通知 UI；重启后需重新激活 |
| FR-005 ownAuth/reason 与 plugin.json 一致 | `abyss-plugin-linux/plugin.json` permissions[0] shizuku_shell.ownAuth=true + reason 字段 | 宿主插件详情页读取同一份 plugin.json 展示授权文案，保证两端一致（PLG-001） |

### AC 验证
- AC-001：宿主（com.abyss.tools）已在 Shizuku 授权列表但 Linux 插件（com.abyss.plugin.linux）未授权时，`ShizukuOwnAuthDetector.isGranted("com.abyss.plugin.linux")` 返回 false，插件首页展示独立授权引导（单测覆盖）。

### 风险记录（FR-005/边界）
- ColorOS 16 可能拦截 Shizuku 本身：引导页提供「ColorOS 兼容性提示」与「切至 QEMU 完整系统域」选项，不静默失败。

## 8. 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| 0.1 | 2026-11-21 | 基于 abyss-tool 组织 19 仓库克隆研究创建；对齐 v0.9.5 基线。 |
| 1.0 | 2026-11-27 | 授权点收归 abyss-plugin-linux；ownAuth 检测/引导/重启感知就绪；M1 交付。 |

