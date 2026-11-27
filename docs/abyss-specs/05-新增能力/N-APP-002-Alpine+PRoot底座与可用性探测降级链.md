# N-APP-002 · Alpine + PRoot 底座与可用性探测降级链

> 深渊工具 Abyss Tools · 软件规格说明书（新增能力）  
> 状态：**已实现（abyss-plugin-linux v0.2.0，底座迁出至 Linux 插件仓）** ｜ 优先级：**P0** ｜ 里程碑：**M1** ｜ 承载形态：**新增能力** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `N-APP-002` |
| 所属域 | 新增能力 |
| 承载形态 | 新增能力 |
| 当前状态 | 已实现（abyss-plugin-linux 仓；probe/domain-proot/shizuku 三模块，纯 JVM 可测） |
| 优先级 / 里程碑 | P0 / M1 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | M1 · PRoot · 降级链 |

## 1. 目标与范围

自包含底座（约 4MB，无 Termux 依赖），启动前执行极轻量 ptrace 探测，按 Shizuku → 纯 PRoot → 明确不可用的顺序降级。

## 2. 现状锚点（克隆研究实测）

- `abyss-plugin-app-builder/docs/ARCHITECTURE.md`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | 底座以 AAR / 原生库形式打包进插件 APK，仅使用 App 私有目录。 |
| FR-002 | 探测命令必须是只读、无副作用的 ptrace 自测，超时 5s。 |
| FR-003 | Shizuku 运行中且已授权 → 经 shell 上下文（uid 2000）拉起 PRoot（推荐）。 |
| FR-004 | Shizuku 不可用 → 尝试 App 沙箱内纯 PRoot；失败则不进入注定失败的构建。 |
| FR-005 | 两者皆失败 → 人话告知「当前设备系统限制，内置构建环境不可用」并给出已知 ROM 清单。 |
| FR-006 | 底座状态（未初始化 / 就绪 / 降级 / 不可用）持久化并展示在插件首页。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

本单元为规划新增，具体接口在实现评审时定稿；与既有系统的边界必须沿用：

- `PluginContract.HOST_API_VERSION = 1`（只增不减）与 `PluginManifest` schema。
- `AbyssChannels` 渠道常量 + `ChannelPolicy` / `FetchHostPolicy` 运行时白名单。
- `SemVer` 版本比较、`UiState` 三态、`Result.toUiState()` 统一出口。
- 资产一律 SHA-256 校验 + 备份 → 应用 → 失败回滚（`abyss-patch-v1` 纪律）。
- 纯 JVM 可测边界：指令编排 / 解析 / 排程数学 / 防护逻辑零 Android 依赖。

## 5. 失败语义与边界

- Shizuku 不是 root：不能 su、不能任意突破 SELinux；不得暗示可绕过厂商安全策略。
- ColorOS 15/16 的 ptrace 约 1 分钟后被杀、MagicOS 内核拒绝，属平台限制而非代码缺陷。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | 在受限 ROM 模拟器上探测返回不可用且不启动构建。 |
| AC-002 | Shizuku 授权前后底座状态正确切换。 |

> 验收方法：纯 JVM 单测（`./gradlew test`）/ CI 门禁脚本 / 真机回归清单（Android 15 与 16 各一台）/ 通道清单校验，按单元性质择一或组合。

## 7. 依赖与风险

**依赖**

- N-APP-001

**风险**

- ptrace 被平台级策略否决，无法靠代码优化解决。


## 0. 实现说明（架构演进 · ADR-100 D-3）

**重要架构变更（2026-11-22）**：底座实现从 `abyss-plugin-app-builder` 迁出至独立的 `abyss-plugin-linux` 仓。本规格的所有 FR/AC 由 Linux 插件的三个模块共同承担：
- **probe/**：ptrace 只读探测（5s 超时，无副作用）；降级链 `SHIZUKU_SESSION → PURE_PROOT → UNAVAILABLE`；ROM 黑名单（ColorOS 15/16、MagicOS）识别并人话告知
- **domain-proot/**：Alpine mini rootfs（约 4MB，无 Termux 依赖，仅用 App 私有目录 `filesDir/linux/`）+ PRoot 二进制
- **shizuku/**：per-app 授权检测、Shell 上下文拉起（uid 2000）；宿主授权不继承

### FR 实现对照表

| FR | 实现位置 | 说明 |
|---|---|---|
| FR-001 底座打包进插件（私有目录） | `abyss-plugin-linux/domain-proot/` + asset 模块 | Alpine mini rootfs + proot 二进制以 AAR/asset 形式打包，解压到 `filesDir/linux/alpine/`；不依赖 Termux |
| FR-002 ptrace 只读探测，5s 超时 | `abyss-plugin-linux/probe/`（纯 JVM 可单测） | 探测命令只读、无副作用；5s 硬超时返回 UNAVAILABLE |
| FR-003 Shizuku 授权→shell→proot 拉起 | `abyss-plugin-linux/shizuku/` + `domain-proot/ProotLauncher` | Shizuku 运行中且已授权时经 `sh -c` 上下文以 uid 2000 拉起 PRoot |
| FR-004 纯 PRoot 兜底 / 不可用 fail-closed | `probe/ProbeRunner` | Shizuku 不可用→沙箱内纯 PRoot；两者皆失败→UNAVAILABLE，不启动注定失败的构建 |
| FR-005 ROM 黑名单人话告知 | `probe/KnownRomPolicy` | ColorOS 15/16（约 1min 后被 ptrace 拦截）/ MagicOS（内核拒绝）识别并展示已知 ROM 清单 + 切域建议 |
| FR-006 底座状态持久化 + 首页展示 | `session/RuntimeStateStore`（持久化）+ Linux 插件首页 | 四态 UNINITIALIZED/READY/DEGRADED/UNAVAILABLE 持久化到 SharedPreferences；首页卡片实时反映 |

### AC 验证
- AC-001：受限 ROM 模拟器上 probe 返回 UNAVAILABLE 且不进入构建流程（probe 单测覆盖）。
- AC-002：Shizuku 授权前后 `shizukuState` NOT_GRANTED→GRANTED 正确切换（StateStore 单测覆盖）。

## 8. 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| 0.1 | 2026-11-21 | 基于 abyss-tool 组织 19 仓库克隆研究创建；对齐 v0.9.5 基线。 |
| 1.0 | 2026-11-27 | 底座迁出至 abyss-plugin-linux 仓；probe/domain-proot/shizuku 三模块就绪；M1 交付。 |

