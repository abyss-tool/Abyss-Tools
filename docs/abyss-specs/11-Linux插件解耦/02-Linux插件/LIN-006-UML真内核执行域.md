# LIN-006 · UML_GUEST 真内核执行域（User-Mode Linux）

> 深渊工具 Abyss Tools · 设计规格（Linux 重域插件）
> 状态：**新增 · 待评审** ｜ 优先级：**P0** ｜ 编制日期：**2026-11-27**
> 触发：`SRS-103 D-6` —— 真内核域首选 UML，不再首选 QEMU TCG

| 项 | 值 |
| --- | --- |
| 规格编号 | `LIN-006` |
| 当前状态 | 新增 · 待评审 |
| 优先级 / 里程碑 | P0 / M1 |
| 定位 | 「整个 Linux」的最小可用实现：**真内核 + 384MB 常驻 + 免 Root + 任意 arm64** |
| 承接 | `LIN-002`（QEMU 域降级为备选）、`ARC-101 RuntimeSession`、`DC-101/102` |
| 形态判定 | 384MB > 300MB → **独立插件**（SRS-103 D-3） |

## 1. 为什么是它

| 对比项 | PRoot（内置侧） | **UML_GUEST** | QEMU TCG（备选） |
| --- | --- | --- | --- |
| 内核 | 复用 Android 内核 | **自带真 Linux 内核，作为普通进程运行** | 自带客户机内核，全机仿真 |
| 真 `/proc` / 真 ptrace / 真块设备 / 独立网络栈 | ❌ | ✅ [citation:17] | ✅ |
| 免 Root | ✅ | ✅ | ✅ |
| 需要 AVF / pKVM / SoC 虚拟化 | ❌ | ❌ [citation:17] | ❌（TCG 纯软件） |
| 常驻 RAM | 数十 MB | **384MB 固定** [citation:17] | 512MiB 最低 / 1024 默认 / 1536 legacy [citation:3] |
| 首次磁盘 | rootfs 约 3.8MB [citation:26] | ~512MB 解包、~536MB ext4、需 ~600MB 空闲 [citation:17][citation:8] | APK ~200MB + 2–64GB 稀疏盘 [citation:12][citation:10] |
| 冷启动 | 瞬时 [citation:17] | 10–20s [citation:17] | 分钟级（v1.0 目标 ≤180s） |
| 计算性能 | 接近原生（仅 syscall 拦截） | **接近原生**：纯计算不掉速，syscall 密集 1.5–2×，4K 随机读约 12× [citation:20] | 计算慢 13–36×、4K 随机读约 120× [citation:20] |
| 快照 / VNC / 跨架构 | ❌ | ❌（无快照）[citation:17] | ✅ |
| 判定 | 内置 | **插件（首选重域）** | 插件（兼容备选） |

> 结论：UML 用「一个真内核进程」换到了完整 Linux 语义，代价只有 384MB 和一份 ext4 盘；比 QEMU 轻一个数量级、快一个数量级。QEMU 仅在需要 VNC/快照/x86 客户机时启用。

## 2. 组成与交付形态

| 组件 | 内容 | 来源 |
| --- | --- | --- |
| `libvmlinux.so` | UML 内核，编译态约 79MB，AGP 去调试符号后约 8MB | [citation:17] |
| 原生 payload | 共 4 个文件，APK 内约 13MB | [citation:17] |
| rootfs 镜像 | Alpine aarch64 用户空间 + `apk`，约 512MB，解包为 ~536MB ext4 | [citation:17][citation:8] |
| 用户空间 | 标准 Alpine 3.22+ 仓库，可 `apk add python3 gcc git nodejs` | [citation:17] |
| 权限 | 客户机内以 root 运行；**对宿主机无任何特权** | [citation:17] |
| 资产通道 | 内核 / rootfs / 工具链走 `abyss-asset-v1`（LIN-003 / DC-102） | 本设计 |

**合规边界**：客户机内 root ≠ 宿主机 root；不得暗示可绕过厂商安全策略。Windows 客户机镜像不分发（沿用 N-VM-004 FR-002 / ADR-008）。

## 3. 启动链路

```
openSession(UML_GUEST)
 ├─ 资产检查：kernelId / rootfsId 分卷 sha256 + 整像 sha256 + 格式校验
 ├─ 首次：解包 ~512MB rootfs → App 私有目录 ext4 镜像（需 ~600MB 空闲）
 │        空间不足 → E_STORAGE_INSUFFICIENT + 所需空间数值
 ├─ 能力探测：arm64 / 剩余 RAM / 私有目录可写 / W^X 执行域
 ├─ 拉起 UML 内核进程（固定内核参数，UI 不暴露可编辑 cmdline）
 ├─ 10–20s 等待 `uml:~#` 提示符；网络 bring-up 占大半时间
 ├─ 单向共享：挂载 App 私有 `uml/share` → 客户机 `/host`（hostfs）
 │        客户机看不到手机其它数据；禁止越界挂载
 ├─ 建立 RuntimeSession（域=UML_GUEST，grantedFeatures 含 EXEC/PTY/FILE_IO/PKG_INSTALL）
 └─ READY
```

## 4. 固定资源与会话治理

| 项 | 值 | 规则 |
| --- | --- | --- |
| 每个会话 RAM | 384MB 固定，不可从 UI 扩容 [citation:17] | 多会话会叠加；UI 必须显示「当前 N 个会话共占 N×384MB」 |
| 磁盘 | ~536MB ext4，持久化，重启不丢 [citation:17] | `apk add` 占用同一份空间，剩余不足时预拒绝 |
| 快照 | 无 [citation:17] | 用「关闭=poweroff + 盘保留」替代；不提供挂起恢复 |
| 共享 | 单向 `uml/share → /host` [citation:17] | 产物只能从这个口子出来，禁止访问 App 私有目录其它部分 |
| 关闭 | 关标签 = 向客户机发 `poweroff` [citation:17] | 进程组整组回收，回落到 0 常驻 |
| 并发 | 与 `ARC-101 maxConcurrentExec=1` 一致 | 同一会话串行；不同会话可并存但内存叠加 |

## 5. 与 RuntimeSession 的适配（沿用 ARC-101）

| 原语 | UML 实现 |
| --- | --- |
| `exec` | 客户机内 argv 执行，stdout/stderr 并发读、1MB 上限、超时真终止 |
| `pty` | 串口风格控制台；`Cols/Rows` 同步；Ctrl-C/D 透传 |
| `fileIn` | 写入 App 私有 `uml/share`，再由客户机从 `/host` 取 |
| `fileOut` | 客户机写 `/host`，宿主侧从 `uml/share` 读回；canonical 路径断言 |
| `mount` | 仅允许 `hostfs` 到 `/host`，其它挂载请求返回 `E_MOUNT_DENIED` |
| `state` | 域=`UML_GUEST`、RAM 384MB、磁盘剩余、会话数 |
| `close` | poweroff + 进程组回收 + 撤销 grant |

**新增枚举**（SRS-101 扩充）：`RuntimeDomain { PROOT_USERLAND, UML_GUEST, QEMU_VM }`；`Feature` 增加 `REAL_KERNEL`、`PRIVATE_NETSTACK`。

## 6. 降级与切换链（修订 v1.0 ARC-101 §6）

```
构建器请求轻量域
 ├─ 主壳内置 PRoot 可用                     → READY（内置，<300MB）
 ├─ ptrace 被 ColorOS / MagicOS 拦截
 │    ├─ Shizuku 已授权 → uid2000 拉起 PRoot → READY（仍内置）
 │    └─ 仍失败 + 用户确认 → 请求重域插件
 │          ├─ UML_GUEST 可用（首选，384MB，10–20s）→ READY
 │          ├─ UML 不可用 / 需 VNC·快照·x86   → QEMU_VM
 │          └─ 插件未装 / 空间不足 / 用户拒绝 → UNAVAILABLE + ROM 清单
```

| 纪律 | 说明 |
| --- | --- |
| 不静默切域 | 切到 UML 必须弹层展示「384MB 常驻 / 需约 600MB 空间 / 冷启 10–20s」 |
| 不假装 READY | 资产 / 探测 / 授权任一未绿，`getState()` 不得返回 READY |
| 会话不可原地换域 | 切域 = close 旧会话 + open 新会话，产物显式搬运 |
| 平台限制如实上报 | ptrace 拦截属平台限制，给出已知 ROM 清单与可执行建议 |

## 7. 功能需求

| 编号 | 需求描述 |
| --- | --- |
| FR-001 | UML 域必须自带真 Linux 内核，不得以 PRoot / chroot 冒充。 |
| FR-002 | 不依赖 AVF、pKVM、QEMU、KVM、root 或 SoC 虚拟化支持，任意 arm64 设备可跑。 |
| FR-003 | 固定资源（384MB RAM / ~536MB 盘）在 UI 明示，多会话叠加占用必须可见。 |
| FR-004 | 客户机只能看到自己的磁盘与单向 `/host` 共享目录，禁止读取手机其它数据。 |
| FR-005 | 关闭会话必须 poweroff 并整组回收进程，回落到 0 常驻。 |
| FR-006 | 内核与 rootfs 资产走 `abyss-asset-v1`，双层 sha256 + 分卷续传 + 原子替换。 |
| FR-007 | 首次解包前预检 ≥600MB 空闲空间，不足则拒绝并给出所需空间。 |
| FR-008 | 实现 `RuntimeSession` 公共原语子集，消费方不感知域差异。 |
| FR-009 | 客户机内 root 与宿主机 root 在文案上严格区分，不得暗示越权。 |
| FR-010 | 无快照能力必须显式说明，禁止展示不可用按钮却隐藏原因。 |

## 8. 失败语义与边界

- 坏清单 / 非法版本 / 路径穿越 / sha256 不符 / 外域 URL 一律 fail-closed。
- 空间不足、RAM 不足、非 arm64 → `E_STORAGE_INSUFFICIENT` / `E_VM_RESOURCE` / 明确不支持提示。
- 内核启动超时（>60s 未出提示符）→ `E_PROBE_TIMEOUT`，回收进程，不无限等待。
- 镜像只做通路不做信任源（ADR-008）；客户机内的 `apk` 源不被视为可信软件源。

## 9. 验收标准

| 编号 | 验收条件 |
| --- | --- |
| AC-001 | 任意 arm64 真机（非 Pixel、无 AVF）拉起 UML 并在 10–20s 内出现 `uml:~#`。 |
| AC-002 | `uname -r` 返回客户机内核版本，与 Android 宿主内核版本**不同**。 |
| AC-003 | 实测单会话常驻 384MB；关闭后 PSS 回落到基线。 |
| AC-004 | 客户机尝试访问 `/host` 之外的宿主路径被拒；产物只能从 `uml/share` 取回。 |
| AC-005 | 在 ColorOS 模拟器上 ptrace 被拦后，切到 UML 域仍能跑通一条构建命令。 |
| AC-006 | 内核/rootfs 资产篡改 1 字节即被拒，目标目录零触碰。 |

## 10. 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| 1.0 | 2026-11-27 | 创建；新增 UML_GUEST 执行域，取代 QEMU 成为真内核域首选。 |
