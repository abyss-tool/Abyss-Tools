# ARC-101 · 执行域抽象 RuntimeSession

> 深渊工具 Abyss Tools · 设计规格（架构）
> 状态：**设计中 · 待评审** ｜ 优先级：**P0** ｜ 里程碑：**M1** ｜ 编制日期：**2026-11-22**

| 项 | 值 |
| --- | --- |
| 规格编号 | `ARC-101` |
| 当前状态 | 设计中 · 待评审 |
| 优先级 / 里程碑 | P0 / M1 |
| 上游依据 | `SRS-101`、`N-APP-002`、`N-VM-001/003/005`、`N-TERM-002`、`DC-012` |
| 核心 | 用一份会话抽象吃掉两个执行域的差异，让消费方零感知 |

## 1. 目标与范围

定义 `RuntimeSession` 的数据模型、状态机、可执行原语与能力矩阵，规定两个执行域必须实现的最小公共子集，以及差异能力的协商方式。

## 2. 数据模型

```kotlin
data class SessionSpec(
    val preferredDomain: RuntimeDomain = RuntimeDomain.PROOT_USERLAND,
    val allowDomainFallback: Boolean = true,      // 用户确认后才允许切 QEMU_VM
    val rootfsId: String? = null,                 // PROOT_USERLAND
    val imageId: String? = null,                  // QEMU_VM
    val workspaceSizeMb: Int = 512,
    val env: Map<String, String> = emptyMap(),
    val mounts: List<MountSpec> = emptyList(),    // 仅白名单路径，canonical 断言
    val limits: ResourceLimits = ResourceLimits(),
    val requestedFeatures: Set<Feature> = setOf(Feature.EXEC, Feature.FILE_IO),
    val clientTag: String = ""                    // 调用方标识，用于审计
)

data class ResourceLimits(
    val maxCpuPercent: Int = 80,
    val maxMemoryMb: Int = 2048,
    val execTimeoutSec: Int = 1800,
    val maxOutputBytes: Int = 1024 * 1024,       // 沿用 SHL-003 单流 1MB 上限
    val maxConcurrentExec: Int = 1               // 同一会话串行构建，避免交叉污染
)

enum class Feature { EXEC, PTY, FILE_IO, VNC, SNAPSHOT, PORT_FORWARD, PKG_INSTALL }

data class RuntimeSession(
    val id: String,                              // UUID，跨进程唯一
    val domain: RuntimeDomain,
    val state: SessionState,
    val spec: SessionSpec,
    val createdAt: Long,
    val refCount: Int,
    val grantedFeatures: Set<Feature>,
    val diagnostic: Diagnostic? = null           // 域级原因码，见 DC-102
)
```

## 3. 状态机

```
                 openSession()
UNINITIALIZED ───────────────► ASSET_CHECKING
                                   │ 资产缺失
                                   ▼
                            ASSET_DOWNLOADING ──失败──► UNAVAILABLE
                                   │ 校验通过
                                   ▼
                               PROBING ──平台限制──► DEGRADED ──用户拒绝降级──► UNAVAILABLE
                                   │ 探测通过            │ 用户确认切域
                                   ▼                    ▼
                                 READY ◄────────────（重建会话）
                                   │ exec/pty
                                   ▼
                                  BUSY ──完成──► READY
                                   │ close() / 引用计数归零
                                   ▼
                               CLOSING ──► CLOSED（进程回收、工作区清理）
```

| 状态 | 允许操作 | 禁止操作 |
| --- | --- | --- |
| UNINITIALIZED / ASSET_* | 查询进度、取消下载 | exec / pty / fileIn |
| PROBING | 只读探测 | 任何构建动作 |
| READY | exec / pty / fileIn / fileOut / close | 切换执行域 |
| DEGRADED | 展示原因、请求用户切域或放弃 | 静默重试 / 假装 READY |
| BUSY | 进度上报、取消 | 并发第二个 exec（FR 限额） |
| UNAVAILABLE | 展示人话原因与已知 ROM 清单 | 任何启动动作 |
| CLOSING / CLOSED | 只读审计 | 任何调用（返回 `E_SESSION_CLOSED`） |

## 4. 统一原语（两个域都必须实现）

| 原语 | 签名（概念） | 语义 | 失败码 |
| --- | --- | --- | --- |
| `exec` | `exec(cmd: ExecCommand, sink: OutputSink): ExecResult` | 在会话内执行一条命令；exitCode==0 才算成功；输出截断置 `truncated` | `E_EXEC_TIMEOUT` / `E_EXEC_TRUNCATED` |
| `pty` | `openPty(cols, rows): PtyHandle` | 交互式终端；窗口大小 `TIOCSWINSZ` 同步；Ctrl-C/D 透传 | `E_PTY_UNSUPPORTED` |
| `fileIn` | `writeFile(relativePath, pfd)` | 把项目/资产写进会话工作区；路径穿越即拒 | `E_PATH_TRAVERSAL` |
| `fileOut` | `readFile(relativePath): pfd` | 取回构建产物 / 日志 | `E_FILE_NOT_FOUND` |
| `mount` | `mount(spec)` | 白名单目录挂载（工作区 / 缓存只读） | `E_MOUNT_DENIED` |
| `state` | `getState(): RuntimeSession` | 状态与诊断查询 | — |
| `close` | `close(reason)` | 释放引用、终止进程、清理临时目录 | — |

**公共保证**
- 每次构建独占工作区 `/workspace/build-<timestamp>`，缓存（pip / Maven）只读优先共享（沿用原 ARCHITECTURE.md §1.4）。
- 同一时间只允许一个构建任务占用底座；切路径先取消在跑任务再启动。
- stdout / stderr 并发读取，单流 1MB 上限，30s 真超时看门狗（SHL-003 FR-001~003）；长构建另设会话级 `execTimeoutSec`。
- 所有原语调用都必须携带 `SessionGrant`，过期或无授权一律拒绝。

## 5. 域差异能力协商

| Feature | PROOT_USERLAND | QEMU_VM | 协商规则 |
| --- | --- | --- | --- |
| EXEC / PTY / FILE_IO | ✅ 必须 | ✅ 必须 | 公共子集，不协商 |
| PKG_INSTALL（`apk add` / `apt`） | ✅ | ✅ | rootfs 需可写层；VM 走镜像内包管理 |
| PORT_FORWARD | ⚠ 受限（用户态端口映射） | ✅ | 构建器不请求；其它消费方可请求 |
| VNC / SNAPSHOT | ❌ 返回 `E_FEATURE_UNSUPPORTED` | ✅ | 请求前先查 `grantedFeatures`，禁止隐藏失败 |
| 独立内核 / iptables / systemd | ❌ | ✅ | 只作为 QEMU 域的推荐理由展示 |

协商纪律（沿用 N-PLG-003 FR-002）：不满足的能力显示为「不可用 + 原因」，**不得隐藏失败、不得静默切域**；切域必须用户确认并展示性能预期。

## 6. 执行域选择策略

```
构建器请求 preferredDomain=PROOT_USERLAND
  ├─ Linux 插件探测 ptrace 可用 → READY(PROOT_USERLAND)
  ├─ ptrace 被平台限制，且 Shizuku 已授权 → READY(PROOT_USERLAND, via uid 2000)
  ├─ 仍失败 + allowDomainFallback=true → 弹「切到完整系统域（启动约 3 分钟、需 X GB）」
  │        └─ 用户确认 → close 旧会话 → openSession(QEMU_VM) → READY
  └─ 无镜像 / 存储不足 / 用户拒绝 → UNAVAILABLE + 已知 ROM 清单
```

## 7. 功能需求

| 编号 | 需求描述 |
| --- | --- |
| FR-001 | 域差异不得泄漏到消费方：构建器代码只依赖 `ILinuxSession`，不出现 `proot` / `qemu` 字符串常量。 |
| FR-002 | 会话 id 全局唯一，跨进程可追踪，日志与审计以 sessionId 为主键。 |
| FR-003 | `SessionSpec` 与 `RuntimeSession` 纯 JVM 可单测，零 Android 依赖。 |
| FR-004 | 工作区路径必须 canonical 断言在 App 私有目录内，禁止 `../`、绝对路径、符号链接逃逸。 |
| FR-005 | 引用计数归零后延迟 60s 回收会话；前台服务在无会话时自行停止。 |
| FR-006 | 任一 exec 失败必须保留退出码、stderr 摘要与阶段标签，禁止吞异常。 |
| FR-007 | 会话级限额（CPU / 内存 / 超时 / 输出）可被插件侧强制，消费方不可越权提高。 |
| FR-008 | 状态变化必须通过 `ILinuxSessionCallback` 推送，UI 统一走 `UiState` 三态。 |

## 8. 失败语义与边界

- 平台限制（ptrace 被杀 / SELinux 拒绝）属 `UNAVAILABLE`，不得包装成「代码缺陷」或无限重试。
- Shizuku 不是 root：不能 `su`、不能任意突破 SELinux。
- VM 域不得宣称「高性能」；TCG 慢路径必须在切域确认弹层展示耗时区间。
- 断电 / 进程被杀后重启：会话一律视为 CLOSED，残留工作区由启动自检清理。

## 9. 验收标准

| 编号 | 验收条件 |
| --- | --- |
| AC-001 | 同一份 `SessionSpec` 在两个域上跑 `exec("echo ok")` 均返回 exitCode=0。 |
| AC-002 | 状态机单测覆盖 10 个状态、至少 14 条合法/非法迁移。 |
| AC-003 | 路径穿越用例集（`../`、绝对路径、软链）全部被拒且目标零触碰。 |
| AC-004 | 并发 exec 第二个请求被 `E_CONCURRENT_LIMIT` 拒绝，不产生交叉产物。 |

## 10. 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| 1.0 | 2026-11-22 | 创建；定义统一会话模型、状态机与域差异协商。 |
