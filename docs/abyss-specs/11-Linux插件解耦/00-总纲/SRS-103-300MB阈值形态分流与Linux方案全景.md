# SRS-103 · 300MB 内存阈值形态分流与 Linux 方案全景

> 深渊工具 Abyss Tools · 设计规格（总纲 · v1.1 增量）
> 状态：**覆盖 v1.0 的 A-1 / A-2，待确认** ｜ 优先级：**P0** ｜ 编制日期：**2026-11-27**
> 触发：用户指令「难度不用管；内存小于 300MB 就直接内置在软件里，大于的话做成插件；还有其他 Linux 吗」

| 项 | 值 |
| --- | --- |
| 规格编号 | `SRS-103` |
| 当前状态 | RAM 口径已被 SRS-104 覆盖 · 技术全景留痕 |
| 优先级 / 里程碑 | P0 / M0 |
| 效力 | **优先于** `README §2 A-1`、`ADR-100 D-2`、`SRS-102`、`LIN-002`、`PLAN-100 M3`；其余 v1.0 规格仍有效 |
| 关联新增 | `LIN-006 UML 真内核执行域` |
| 明确废止 | 全部「人日 / 工期 / 实现难度 / 裁剪成本」类论证不作为形态决策依据 |

## 1. 决策（一句话）

**不再按「双执行域都做插件」设计，改为按常驻内存阈值分流：< 300MB 的 Linux 能力直接做主壳 BUILT_IN；> 300MB 的做独立插件。难度、工期、工作量一律不参与判定。**

| 编号 | 决策 |
| --- | --- |
| D-1 | 判定量 = **底座常驻 RAM**（会话空闲工作集），不是磁盘镜像体积、不是 APK 体积、不是构建峰值。 |
| D-2 | `< 300MB` → 主壳 `BUILT_IN` feature 模块，随主壳发版，不建仓、不装 APK、不单独授权。 |
| D-3 | `> 300MB` → 独立插件 APK，可独立安装/更新/卸载。 |
| D-4 | `= 300MB` → 归插件侧（阈值取严格小于），并在规格里标注可下调配额重判。 |
| D-5 | 上一版 v1.0 的「PRoot 域也做成插件」作废；PRoot/Alpine 改为主壳内置。 |
| D-6 | 真内核域首选 **UML（User-Mode Linux）**，不再首选 QEMU TCG；QEMU 降为兼容性备选。理由见 §4。 |
| D-7 | 应用构建器按 `SRS-004` 三分法重新判定：底座进主壳后它不再「自带完整引擎」，**应回主壳做 BUILT_IN**；是否仍保留独立 APK 由用户另行指定，不再由架构推导。 |

## 2. 先把「300MB」量什么说死（否则整套逻辑自相矛盾）

| 口径 | 含义 | 是否作为判定量 | 原因 |
| --- | --- | --- | --- |
| **A. 底座常驻 RAM** | 会话 READY 但空闲时的进程工作集（PRoot / UML 内核 / QEMU 进程 + shell + 基础服务） | ✅ **是** | 这是「内置 Linux」真正长期占掉的内存 |
| B. rootfs / 镜像磁盘体积 | Alpine minirootfs、qcow2、ext4 镜像的字节数 | ❌ 否 | 磁盘 ≠ 内存。Alpine minirootfs aarch64 官方压缩包仅约 3.8–3.9MB [citation:26]，UML 却要 ~512MB 磁盘 + 384MB 内存 [citation:17] |
| C. APK 体积 | 引擎二进制打进安装包的大小 | ❌ 否 | PRoot 约 4MB、UML 内核 strip 后约 8MB（APK payload 约 13MB）[citation:17]，与运行内存无关 |
| D. 构建期峰值 RAM | 跑 Gradle / JDK / d8 时的瞬时峰值 | ❌ 否（但必须单独治理） | 标准 Gradle JVM 堆上限就是 2048m，必然超 300MB；这是**按需拉起的临时进程**，不是底座常驻 |

**推论（重要）**：如果按口径 D 判，标准 Gradle 路径永远 > 300MB，"内置"就不成立；本规格采用口径 A，同时要求把重构建任务做成**用完即回收的临时进程**，并在 UI 明示「构建期内存会临时升高」。若用户本意是「整个构建过程总内存也必须 < 300MB」，则路径三（标准 Gradle）必须移出内置侧、只保留 Anpyra 与 aapt2 两条 —— 此为待确认项 Q-3。

## 3. Linux 方案全景（回答「还有其他 Linux 吗」）

先说一个最容易误判的事实：**Android 本身跑的就是 Linux 内核**，`uname -r` 报 Linux、`/proc` `/sys` 都在；它没有的是 GNU 用户空间 —— 用 Bionic 替 glibc、toybox/mksh 替 coreutils、没有 `/usr/bin`、没有 systemd [citation:21]。所以「内置 Linux」真正要加的是**一套用户空间**，不是「装个内核」。

### 3.1 按「形态」分（这才是真选项）

| # | 形态 | 内核 | 常驻 RAM | 免 Root | 任意 arm64 | 严格算「整个 Linux」 | 判定 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 0 | Android 自身（Bionic + toybox） | Android Linux 内核 | 0（已在） | — | ✅ | 内核是、用户态不是 | 基线，无需内置 |
| 1 | **不跑发行版：原生工具链** `aapt2 compile/link` + `javac` + `d8` + `zipalign` + `apksigner` | 无独立环境 | 近 0，按需进程 | ✅ | ✅ | ❌ | **内置** ✅ |
| 2 | **Alpine minirootfs + PRoot**（musl 用户空间，ptrace 路径改写） | 复用 Android 内核 | 数十 MB 量级 | ✅ | ✅（Android 8+） | ❌ 只是用户态容器，不是完整系统 | **内置** ✅ |
| 2′ | Debian / Ubuntu / Arch / Kali / Void / Fedora / openSUSE / CentOS + PRoot | 复用 Android 内核 | 与 2 同量级 | ✅ | ✅ | ❌ | 内置（可选 rootfs 变体） |
| 3 | **UML 真内核**（User-Mode Linux，内核作为普通进程） | **自带真 Linux 内核** | **384MB 固定** | ✅ | ✅（不需 AVF/QEMU/SoC 虚拟化） | ✅ | **插件**（仅超 84MB） |
| 4 | QEMU TCG 完整虚拟机 | 自带客户机内核 | 最低 512MiB，常见 1024–2048MB | ✅ | ✅ | ✅ | **插件** |
| 5 | AVF / pKVM（Pixel Debian Terminal） | 系统级 hypervisor | 默认约系统可用 RAM 的一半 | ✅（系统提供） | ❌ 仅 Pixel 6/8+ 等、Android 16+ | ✅ | ❌ 普通 App 不可直接调用完整 API，端口/vsock 常需 root [citation:25][citation:18][citation:12] |

### 3.2 按「发行版」分（换发行版不改变内存量级）

PRoot 生态可跑的发行版很多：Ubuntu / Debian / Arch / Kali / CentOS [citation:2]，Alpine / Debian / Arch / Void [citation:8]；`proot-distro` 是官方 wrapper，rootfs 直接由各发行版仓库生成、只保证 aarch64 [citation:4]；Andronix、AnLinux、UserLAnd 底层也都是 PRoot [citation:6][citation:7][citation:23]。

**但它们共用 Android 内核，内存取决于「跑了哪些进程」而不是「装了哪个发行版」**。所以：

> 换 Alpine ↔ Debian ↔ Ubuntu **不是换形态**，只是换 rootfs 与 libc（musl ↔ glibc），对 300MB 判定几乎没有影响。真正跨过阈值的是 2 → 3/4（从「复用内核」到「自带内核」）。

### 3.3 被排除 / 需澄清的形态

| 形态 | 结论 |
| --- | --- |
| `chroot` / Linux Deploy / 真容器（Docker/Podman/LXC） | 需 root 或真内核命名空间；PRoot 只虚拟化文件系统视图与 uid、伪造 root 成功返回，**不提供任何真实权限提升**，也没有 cgroups/netfilter/systemd [citation:15][citation:16] |
| gVisor / runsc / KVM | 普通 App 拿不到 `/dev/kvm`，无输出即按免 Root 方案规划 [citation:14] |
| BusyBox / Toybox 极简 rootfs | 磁盘 ~3MB（BusyBox 静态 0.8–1.5MB、Toybox 0.3–0.6MB、Buildroot 30–80MB、Ubuntu-base ~600MB）[citation:22][citation:24][citation:27]；可内置，但工具覆盖不足以构建 APK |
| postmarketOS / 刷 mainline 内核 | 不是 App 能做的事，排除 [citation:29] |
| Termux 原生包环境 | 本身已是「Android 上的 Linux 用户态」，但要用户另装 App；本设计坚持自包含、不依赖 Termux |

### 3.4 为什么 UML 是这轮最值得新增的一个

上一版 v1.0 只列了 PRoot 与 QEMU 两域，漏掉了中间这一档。UML 的特征组合正好卡在用户阈值边缘：

- **真 Linux 内核**：真 ptrace、真 `/proc`、真块设备、独立网络栈，容器内以 root 运行，PRoot 会踩的模拟天花板（调试器、挂载、`/proc` 检查）它都没有 [citation:17]；
- **无需 QEMU / 无需 AVF / 无需 root / 无需 SoC 虚拟化**：内核就是一个普通进程，任意 arm64 设备可跑 [citation:17]；
- **固定 384MB RAM + ~536MB ext4 磁盘**，首次解包约 512MB rootfs、需约 600MB 空闲空间，10–20 秒出 `uml:~#` 提示符 [citation:17][citation:8]；
- **性能接近原生**：纯计算不掉速，syscall 密集约 1.5–2×、4K 随机读约 12×；而 QEMU TCG 计算慢 13–36×、随机读约 120× [citation:20]；
- 代价：384MB 常驻、无快照、磁盘与内存不可从 UI 扩容、多个标签会叠加占内存 [citation:17]。

对比 QEMU：最低档 profile 就是 512MiB（1 vCPU / wifi-only），balanced 768MiB，default 1024MiB，legacy 1536MiB [citation:3]；Android 上常见 `-m 2048` [citation:14]；Podroid 用 QEMU TCG + Alpine（基础系统约 30MB）+ Podman，APK 约 200MB [citation:10][citation:12]。**QEMU 不但更重，还更慢**，所以 v1.0 把 QEMU 当「完整系统域首选」是次优解。

## 4. 按阈值分流后的目标架构

```
┌────────────── 主壳 Abyss-Tools（BUILT_IN，常驻 < 300MB）──────────────┐
│ feature:linuxlite        内置轻量 Linux 能力                          │
│   ├─ Alpine minirootfs + PRoot 静态二进制 + ELF Loader（塞进 APK）   │
│   │    已有先例：nanoMuse 把三者直接打进 app/src/main/assets [citation:19]
│   ├─ 原生工具链路径：aapt2 / d8 / zipalign / apksigner（Bionic/arm64）│
│   ├─ 内置终端 / 脚本实验室直接复用这套会话                            │
│   └─ feature:appbuilder  应用构建器（预检 / 三路径 / 产物闭环）       │
│        不再自带引擎 → 按 SRS-004 应回主壳 BUILT_IN                    │
└──────────────────────────┬───────────────────────────────────────────┘
                           │ 仅当用户需要「真内核 / 完整系统」时
                           ▼
┌──────────── 独立插件（常驻 > 300MB，可装可卸）──────────────────────┐
│ abyss-plugin-linux-vm                                                 │
│   ├─ 域 A（首选）：UML 真内核，384MB 固定常驻        → LIN-006      │
│   ├─ 域 B（兼容）：QEMU TCG + 镜像 + VNC + 快照      → 原 LIN-002    │
│   ├─ 资产通道 abyss-asset-v1（镜像 / 内核 / 工具链）  → LIN-003      │
│   └─ 统一 RuntimeSession / linux-runtime-v1          → ARC-101/DC-101│
└──────────────────────────────────────────────────────────────────────┘
```

| 变化点 | v1.0 | v1.1（本规格） |
| --- | --- | --- |
| PRoot/Alpine 归属 | 独立 `abyss-plugin-linux` | **主壳内置 feature** |
| 应用构建器 | 独立 APK，`depends` Linux 插件 | **主壳内置 feature**（除非用户明确仍要独立 APK） |
| 插件数量 | Linux + 构建器 + 病毒拦截 = 3 | 重域插件 + 病毒拦截 = **2**（构建器若保留独立则 3） |
| 用户安装 | 宿主 + 2 插件 = 3 个 APK | 宿主 1 个即可用轻量能力；需真内核才装第 2 个 |
| Shizuku 授权 | Linux 插件 1 次 | 内置侧走主壳自有 shell 网关；插件侧仍单独授权（per-app 不继承） |
| 真内核域首选 | QEMU TCG | **UML**（384MB / 近原生 / 任意 arm64） |
| HOST_API v2 | 为插件依赖插件而升 | **可降级回 v1**：依赖变成主壳内部调用，`PluginBroker` 的依赖解析职责大幅收缩；若构建器仍做独立 APK 则保留 v2 |
| QEMU 域 | M3 主线 | 降为兼容备选，仅在 UML 不可用或需要 VNC/快照/跨架构时启用 |

> 关键推论：用户最初要的「Linux 与应用构建器拆成两个插件、构建器依赖 Linux 插件」，在 300MB 规则下**可能一个插件都不需要** —— 因为两者都掉到阈值以下了。这不是删需求，是阈值把 ADR-001「小功能内置、只有自带完整引擎才插件化」量化成了具体数字。

## 5. 内存判定与验收方法

| 编号 | 要求 |
| --- | --- |
| FR-001 | 形态判定必须基于**实测常驻 RAM**（`dumpsys meminfo` / `/proc/<pid>/smaps_rollup` PSS），不得用镜像体积或 APK 体积代替。 |
| FR-002 | 判定场景固定：会话 READY 后空闲 60s，无构建任务、无下载任务、无 VNC 连接。 |
| FR-003 | 阈值 `T = 300MB`；`< T` 内置、`≥ T` 插件；边界值重复测 3 次取最大。 |
| FR-004 | 构建期峰值单独记录并展示在 UI，不参与形态判定；重进程用完即回收，回收后回落到常驻基线。 |
| FR-005 | 多个会话并发时按**单会话**判定，但 UI 必须提示「每个会话各占 X MB」（UML 多标签会叠加 [citation:17]）。 |
| FR-006 | 内置侧所有原生库须满足 16KB 页对齐、`PIE`、W^X 执行域约束（沿用 N-CMP-003 / SEC-007）。 |
| FR-007 | 换发行版（Alpine ↔ Debian 等）不触发形态重判，只登记 rootfs 资产变体。 |
| FR-008 | 若实测 `UML` 可下配到 < 300MB，允许把 UML 域一并内置，插件只留 QEMU；需重新走 D-3 判定并留痕。 |

| AC | 验收条件 |
| --- | --- |
| AC-001 | 内置侧实测常驻 RAM < 300MB，报告含设备型号 / Android 版本 / PSS 快照。 |
| AC-002 | 跑完一次路径三构建并关闭会话后，内存回落到常驻基线 ±10%。 |
| AC-003 | 卸载重域插件后，主壳内置构建能力仍完整可用（E2E-01）。 |
| AC-004 | 不装任何插件时，三路径中至少路径一、路径二可产出可安装 APK。 |

## 6. 与 v1.0 规格的逐份差异

| 文件 | 处置 | 说明 |
| --- | --- | --- |
| `README` §2 A-1 / A-2 | **覆盖** | 双执行域 + VM 收编的假设改由本规格 D-1~D-7 取代 |
| `ADR-100` D-2 / D-4 / §3 备选 E | **修订** | QEMU 首选改 UML；新增「按内存阈值分流」决策 |
| `SRS-100` G-001/G-003/G-005 | **修订** | 目标改为「轻量内置 + 重域插件」，安装数与授权数随之变化 |
| `SRS-101` 执行域枚举 | **扩充** | 新增 `UML_GUEST`；能力矩阵增列 384MB 档 |
| `SRS-102` 仓库矩阵 | **修订** | 20 仓改回 19 仓（不必新建 linux 插件仓，改为改造现有 VM 仓） |
| `ARC-100/102/103` | **条件保留** | 构建器若回主壳，则跨插件 AIDL / Broker / HOST_API v2 大幅简化；若仍独立则原样保留 |
| `ARC-101` RuntimeSession | **保留** | 统一会话抽象依然适用，只是实现方从插件变主壳 |
| `LIN-001` PRoot 域 | **保留 + 改归属** | 内容几乎不变，改为主壳 `feature:linuxlite` |
| `LIN-002` QEMU 域 | **降级** | 从主线改兼容备选 |
| `LIN-003` 资产通道 | **保留** | 内置侧 rootfs 也可走同一份资产清单 |
| `LIN-004/005` | **保留** | 会话治理与统一授权仍适用 |
| `LIN-006` UML 域 | **新增** | 见同目录新文件 |
| `DC-100/101/102` | **条件保留** | 插件 manifest v2 契约仍用；主壳内部调用不再需要 `depends` |
| `SEC-100 / ENG-100` | **保留 + 补 FR-001~008** | 增加内存实测验收 |
| `PLAN-100 / PLAN-101` | **重排** | M1 改「主壳内置」，M3 改「UML 域 + QEMU 备选」；全部工时/难度论证删除 |

## 7. 待确认项（只列真分歧，不列难度）

| # | 待确认 | 影响 |
| --- | --- | --- |
| Q-1 | 300MB 是「常驻」还是「含构建峰值」？ | 若含峰值，路径三必须移出内置侧 |
| Q-2 | 达标后是否仍坚持「应用构建器做独立 APK」？ | 决定 HOST_API v2 / PluginBroker 是否保留 |
| Q-3 | 真内核域选 UML（384MB，>300 做插件）还是尝试压到 <300 一并内置？ | 决定插件是否还需要存在 |
| Q-4 | PRoot 内置侧选 Alpine（musl，3.8MB rootfs）还是 Debian（glibc）？ | 影响 aapt2 的 libc 选型与 `ldd` 验证口径 |
| Q-5 | 原 `abyss-plugin-virtual-machine` 仓：改造为重域插件，还是归档后新建？ | SRS-102 仓库矩阵 |

## 8. 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| 1.0 | 2026-11-27 | 创建；按用户 300MB 阈值重写形态分流，补充 UML 真内核形态与 Linux 方案全景，覆盖 v1.0 的 A-1/A-2。 |
