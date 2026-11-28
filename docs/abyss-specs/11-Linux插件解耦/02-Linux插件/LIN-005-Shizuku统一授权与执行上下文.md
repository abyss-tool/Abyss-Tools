# LIN-005 · Shizuku 统一授权与执行上下文

> 深渊工具 Abyss Tools · 设计规格（Linux 插件）
> 状态：**设计中 · 待评审** ｜ 优先级：**P0** ｜ 里程碑：**M1** ｜ 编制日期：**2026-11-22**

| 项 | 值 |
| --- | --- |
| 规格编号 | `LIN-005` |
| 当前状态 | 设计中 · 待评审 |
| 优先级 / 里程碑 | P0 / M1 |
| 上游依据 | `plugin.json` 三处 shizuku 声明、`PLG-002 FR-004`、`N-APP-003`、`N-VM-001`、`SEC-003` |
| 核心变化 | 由「构建器 + VM 各授权一次」收敛为「Linux 插件授权一次」 |

## 1. 目标与范围

Linux 插件是唯一持有 Shizuku 授权与 shell 执行上下文的进程；构建器、终端、脚本实验室等消费方一律不申请 Shizuku 授权、不直接拼 shell 命令。

## 2. 授权模型

| 项 | 改造前 | 改造后 |
| --- | --- | --- |
| 授权主体 | 应用构建器 APK + 虚拟机 APK（各一次） | `abyss-plugin-linux` APK（一次） |
| 宿主授权继承 | 不能继承（per-app） | 仍不能继承，必须用户主动授权 Linux 插件 |
| 构建器 manifest | `shizuku.ownAuth=true` | **删除 shizuku 字段** |
| VM 插件 | `shizuku.ownAuth=true` | 归档，声明随仓失效 |
| 授权用途 | PRoot 绕 ptrace 限制 | PRoot 绕 ptrace + QEMU 绕 RLIMIT_MEMLOCK / oom_adj |

**per-app 纪律**：Shizuku 只认授权列表里的包名。宿主「深渊工具」的授权不能继承给任何插件；Linux 插件的授权也不能被构建器借用——构建器根本不持有 Binder，只通过会话间接使用执行结果。

## 3. 执行上下文链路

```
① Shizuku 运行中且 Linux 插件已授权（uid 2000 / ADB shell）
     → 普通 App → PRoot → Alpine（推荐）
     → 普通 App → QEMU TCG → 客户机内核（推荐）
② Shizuku 不可用 / 未授权
     → App 沙箱纯 PRoot（部分设备可用）
     → App 沙箱 QEMU（受 RLIMIT_MEMLOCK 限制，多数设备不可用）
③ 两者皆失败
     → 明确告知「当前设备系统限制，内置运行环境不可用」+ 已知 ROM 清单
```

| 约束 | 说明 |
| --- | --- |
| Shizuku 不是 root | uid 2000 shell 级，不能 `su`、不能任意突破 SELinux |
| 不提供越权 | 只把宿主从受限 App 沙箱换到 shell 执行上下文 |
| 需用户主动激活 | 无线调试或 ADB 启动；重启后通常要重新启动一次 |
| 不假定常可用 | 每次会话启动检测状态；未运行给引导 |
| 厂商拦截 | ColorOS 16 的「权限监控」可能拦 Shizuku 本身；文档可提示系统语言切英语 / 开发者选项关闭系统优化 / 重启 Shizuku，并明示可能影响 LTPO 刷新率与续航，由用户权衡 |

## 4. 指令安全（沿用 SEC-003 / SHL-002）

| 规则 | 落地 |
| --- | --- |
| 免引用参数 | `requireSafeToken` 白名单 `[A-Za-z0-9._:-]`，含 `;` `|` `&` `$` 反引号、空格一律就地拒绝，不尝试净化 |
| 自由文本 | `shellQuote` 单引号转义 |
| 模板集中 | Linux 会话启动 / 探测模板集中在 `:probe` / `:shizuku` 的 `LinuxCommands.kt`，禁止业务模块拼 shell 字符串 |
| 纯 JVM 可测 | 指令编排零 Android 依赖，单测覆盖非法 token / 转义 / 模板完整性 |
| 网关 | stdout/stderr 并发读、1MB 上限、30s 真超时（SHL-003） |
| 失败出口 | `ShizukuUnavailableException` → 人话引导；`failureSummary()` 统一摘要 |

> 消费方传入的 `ExecCommand` 在会话内执行，**不经过 shell 字符串拼接**：命令以 argv 数组下发，从根上消除注入面。

## 5. 授权状态与 UI

| 状态 | 展示 | 可执行动作 |
| --- | --- | --- |
| NOT_INSTALLED | 「未检测到 Shizuku」+ 安装引导 | 无 |
| NOT_RUNNING | 「Shizuku 未运行」+ 无线调试/ADB 启动引导 | 无 |
| NOT_GRANTED | 「请为 Linux 插件单独授权」+ 跳转 Shizuku 应用列表 | 授权引导 |
| GRANTED | 显示授权包名与 uid 2000 | 允许 uid2000 链路 |
| REVOKED / STALE | 「授权已失效，需重新授权」 | 重新检测 |

- 状态写入 `RuntimeInfo.shizukuState`，构建器握手时读取，不自行判断。
- 授权前后底座状态正确切换（N-APP-002 AC-002）。
- 插件详情页展示独立授权引导与 `reason`（人话说明为什么需要）。

## 6. 功能需求

| 编号 | 需求描述 |
| --- | --- |
| FR-001 | Linux 插件 `plugin.json` 必须声明 `shizuku.ownAuth=true` 且 `reason` 非空、长度 ≥10 字符（HUB-010 FR-003）。 |
| FR-002 | 应用构建器 `plugin.json` 不得声明 `shizuku`；CI 门禁显式断言。 |
| FR-003 | Shizuku AAR 只进 Linux 插件 `:shizuku` 模块，不进主壳、不进构建器。 |
| FR-004 | 每次会话启动检测 Shizuku 状态，未运行给引导，不假定常可用。 |
| FR-005 | 降级链顺序固定：Shizuku → 纯 PRoot/沙箱 QEMU → 明确不可用，不静默重试。 |
| FR-006 | 执行上下文切换不改变会话接口；消费方不感知 uid。 |
| FR-007 | 授权状态变化（授予 / 撤销 / Shizuku 停止）必须实时反映到 `RuntimeInfo`。 |
| FR-008 | 所有经 shell 上下文启动的模板命令必须过 `requireSafeToken` / `shellQuote` 与纯 JVM 单测。 |
| FR-009 | 不得在文档或 UI 中暗示可绕过厂商安全策略或获得 root。 |
| FR-010 | 审计记录只记授权状态变化与结果码，不记 shell 命令正文。 |

## 7. 失败语义与边界

- Shizuku 未运行 / 未授权 → 引导，不报错崩溃；允许纯 PRoot 尝试但必须明示「部分设备不可用」。
- ColorOS / MagicOS 的 ptrace 限制属平台限制，不得包装成代码缺陷。
- 授权被撤销时进行中的会话不强行提权：完成当前命令后标记 DEGRADED，下次启动重新探测。

## 8. 验收标准

| 编号 | 验收条件 |
| --- | --- |
| AC-001 | 用户授权动作计数：从安装到跑通一条构建，Shizuku 授权仅 1 次。 |
| AC-002 | 授权前后 `RuntimeInfo.shizukuState` 正确切换，底座状态随之切换（N-APP-002 AC-002）。 |
| AC-003 | 非法 token 指令模板单测全部拒绝，不产生任何 shell 执行。 |
| AC-004 | 构建器仓扫描无 `Shizuku` / `shizuku` 实现引用。 |
| AC-005 | ColorOS 模拟器用例：给已知 ROM 清单与切域建议，不无限重试。 |

## 9. 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| 1.0 | 2026-11-22 | 创建；收敛双授权为单一授权，明确执行上下文与注入防护。 |
