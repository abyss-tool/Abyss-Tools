# SRS-106 · 最终裁定：保留独立 Linux 底座插件（B 方案）

> 深渊工具 Abyss Tools · 设计规格（总纲 · **v1.4 最终裁定稿**）
> 状态：**已裁定，取代 v1.3 的 A′** ｜ 优先级：**P0** ｜ 编制日期：**2026-11-27**
> 触发：用户「**独立 Linux 底座其他插件真的能使用吗，主软件能使用吗，如果能就要这种**」
> 效力：**覆盖 `SRS-105`（A′ 单 APK 全内置）的全部判定**；`SRS-104`（300MB = APK 体积）口径不变；`SRS-103`（RAM 误读）继续作废。v1.0 的 23 份、v1.1 的 2 份、v1.2/v1.3 的 4 份全部保留为决策留痕。

| 项 | 值 |
| --- | --- |
| 规格编号 | `SRS-106` |
| 当前状态 | 已裁定（v1.4 B 方案 · 保留独立 Linux 底座插件） |
| 优先级 / 里程碑 | P0 / M0 |
| 配套新增 | `ARC-104-独立底座插件的宿主与消费方接入契约.md` |
| 复核基线 | 2026-11-27 重新克隆 20 仓；逐文件核对 `AndroidManifest.xml` / `LinuxRuntimeService.kt` / `ILinuxRuntime.aidl` / `IOutputSink.aidl` / `CapabilityBinder.kt` / `DependencyResolver.kt` / `SessionGrantIssuer.kt` / `ChannelPolicy.kt` |
| 一句话 | **能用，所以要这种**：保留 `abyss-plugin-linux` 独立 APK 作为全生态共享底座；主壳经自有 `PluginBroker` 绑定它，其他插件经宿主中介消费它，主壳内部模块补一条 `getInternalSession()` 通道。 |

---

## 0. 对用户问题的直接回答

| 问题 | 回答 | 依据 |
| --- | --- | --- |
| 其他插件真的能使用独立底座吗 | ✅ **能** | v1.0 已完整设计并落地成代码：`depends` → `DependencyResolver` → `CapabilityBinder` 四重校验 → `SessionGrant` → 宿主 `bindService(ACTION_LINUX_RUNTIME)` → 受限 `ILinuxSession` 代理。`ARC-102 §4` 时序图、`app-builder/plugin.json` 现状、`Abyss-Plugins/plugins.json` hubVersion 0.4.0 三方对得上 |
| 主软件能使用吗 | ✅ **能** | ① 作为宿主管理/绑定底座：`PluginBroker` 就在主壳进程里，本来就是唯一合法绑定方向；② 作为功能模块消费会话：能，但需补 `getInternalSession()`（现有 `isConsumerPlugin` 白名单只认索引里的插件，会把宿主自己拒掉）—— 详见 `ARC-104 §4` |
| 那就要这种 | ✅ **采纳 B 方案** | 本规格全文 |

**同时必须说清的一句**：架构、契约、代码骨架三层都成立，但当前仓库是 **M0 骨架期** —— `openSession()` 一律抛 `E_RUNTIME_UNAVAILABLE`、三个插件 `apk=null`、0 个二进制资产、`assetsReady=false`。所以是「链路已通、实现待填」，不是「现在装上就能跑」。完整边界见 `ARC-104 §6`。

---

## 1. 裁定

| 编号 | 裁定 |
| --- | --- |
| **D-1** | **采纳 B 方案**：`abyss-plugin-linux` 保持**独立 APK、独立仓库、独立发版**，是全生态唯一的 Linux 引擎持有方（rootfs / 镜像 / PRoot / UML / QEMU / Shizuku 授权）。 |
| **D-2** | 主壳**不内置 Linux 引擎**，只保留并强化 `PluginBroker` / `DependencyResolver` / `CapabilityBinder` / `SessionGrantIssuer` / `ChannelPolicy`；主壳 APK 维持轻量（≈15MB）。 |
| **D-3** | **应用构建器保持独立 APK**，继续 `depends → abyss-plugin-linux`，已删 `shizuku` 不动。 |
| **D-4** | **新增 UML 真内核域到独立底座插件**（现状只有 PRoot + QEMU）：服务器插件要容器 / systemd / 独立网络栈，PRoot 做不到 [citation:12][citation:20]；UML 只多 ≈13MB 包体。三域统一 `RuntimeSession` 抽象不变。 |
| **D-5** | **补「宿主自消费通道」** `PluginBroker.getInternalSession()`：主壳内部模块（内置终端 / 脚本实验室 / 未来主壳功能）可消费底座能力，但签名校验、能力收敛、审计一项不少，且底座未装时 fail-closed 引导（`ARC-104 §4`）。 |
| **D-6** | **补 `onBind` 服务端硬门禁**：`LinuxRuntimeService` 是 `exported=true`，当前只校验 action；必须加「调用方签名 == 宿主签名 + grant 五元组有效」，否则返回 null（`ARC-104 §6 G-1`，M0 阻塞项）。 |
| **D-7** | 后续 `abyss-plugin-phone-server` 做成**薄消费方插件**，`depends` 指向**真实 pluginId** `abyss-plugin-linux`（不是 v1.3 的 `host-builtin` 伪 id）；不自带引擎、不单独 Shizuku。 |
| **D-8** | **300MB 规则按「每个 APK 各自判定」**：主壳 ≈15MB、底座插件 ≈220MB、其余插件各几 MB，**每一个都 < 300MB**，合规。用户「后续没事」继续覆盖 rootfs / 镜像 / 工具链数据 / 运行时内存。 |
| **D-9** | 全部「人日 / 工期 / 难度 / 裁剪成本」论证继续废止。 |

---

## 2. 为什么 B 方案在 300MB 口径下依然成立（而且更稳）

### 2.1 体积账（W^X 归类不变）

| APK | 内容 | 体积 | 判定 |
| --- | --- | --- | --- |
| **主壳 `Abyss-Tools`** | 现状 14.16MB + Broker / 内部通道 / 体积门禁 | **≈15MB** | ✅ |
| **`abyss-plugin-linux`（底座）** | PRoot+Alpine ≈4MB + UML 内核/payload ≈13MB + QEMU 全套 ≈200MB + Kotlin/AIDL/工具脚本 | **≈220MB** | ✅ 余量 ≈80MB |
| `abyss-plugin-app-builder` | 预检 / 三路径编排 / 产物闭环（纯 Kotlin） | 几 MB | ✅ |
| `abyss-plugin-virus-guard` | 现状骨架 | 几 MB | ✅ |
| `abyss-plugin-phone-server`（后续） | 服务模板 / 端口规则 UI / 状态面板（纯 Kotlin） | 几 MB | ✅ |
| ❌ 不进任何 APK（后续下载） | Alpine/Debian/Ubuntu rootfs、UML ~512MB ext4、QEMU 2–64GB 稀疏盘、用户 `apk add` 的包、工具链的数据部分 | 数 GB | 不受限 |

**W^X 约束不因拆分而改变**：Android 10+ / targetSdk ≥ 29 禁止 `execve()` 私有可写目录里的 ELF [citation:3][citation:7][citation:9]，所以引擎二进制仍必须打进**底座插件 APK** 的 `lib/`（只读 `nativeLibraryDir`）或走 `system_linker_exec`；「后续下载」的只能是数据。换句话说，拆插件 ≠ 把引擎改成下载，这条线没动。

### 2.2 B 相比 A′ 多出来的真实收益（不是难度，是性质）

| 收益 | 说明 |
| --- | --- |
| **底座可独立发版** | QEMU / UML / rootfs 升级不必等主壳发版；主壳 15MB 的更新包不用带 200MB 引擎 |
| **可单独卸载** | 用户不玩 Linux 就装主壳一个 APK，200MB 引擎不占他存储 |
| **天然扩展点** | 若未来 QEMU 升级把底座推过 300MB，可把 QEMU 域再拆成第二个插件；A′ 只能往主壳里继续塞或整体重构 |
| **授权边界清晰** | Shizuku per-app 授权只落在底座一个进程；主壳与其他插件 0 次（`LIN-005` / `abyss-plugin-linux/plugin.json` 的 `shizuku.ownAuth=true` 与 app-builder 已删 `shizuku` 字段） |
| **已写的代码几乎不用动** | 7 模块 / 13 AIDL / 单测 / CI / `plugins.json` 全部原地保留；A′ 反而要把它们整体搬进主壳 |
| **服务器插件不用改** | `SRV-100` 的能力增量、`LIN-007` 的常驻会话与端口转发全部落在底座插件里，消费方只发请求 |

### 2.3 代价（如实写，不劝退）

| 代价 | 缓解 |
| --- | --- |
| 用户要装 2–4 个 APK 而不是 1 个 | 索引一键「一并安装」+ 拓扑序（`DependencyResolver` 已实现） |
| **主壳核心 Linux 能力依赖一个可选安装的插件**（宿主反向依赖） | `SRS-106 §5` 降级链：未装 → 明确不可用 + 一键引导，绝不谎报；主壳 v0.8.0 已内置的终端/脚本实验室不受影响 |
| 独立进程可能被系统回收 | `DeathRecipient` 双向 + 重连 + 重新签 grant（`ARC-102` 已设计，`ARC-104 AC-004` 补集成测试） |
| `exported=true` 有被第三方直连的攻击面 | `ARC-104 §6 G-1` 列为 M0 阻塞门禁 |
| 两个 APK 需各自过签名/体积门禁 | CI `apk_size_gate` 对两个产物都生效 |

---

## 3. 目标架构（覆盖 SRS-105 §3）

```
┌────────────── 主壳 Abyss-Tools · APK ≈15MB（宿主，持有 Broker）──────────────┐
│  PluginBroker / DependencyResolver / CapabilityBinder                        │
│  SessionGrantIssuer / ChannelPolicy / apk_size_gate                          │
│                                                                             │
│  ┌─ getCapability() ──── ① 消费方插件（跨进程）                             │
│  ├─ getInternalSession() ★ ② 主壳内部模块（新增，ARC-104 §4）               │
│  └─ PackageManager 查询 ACTION_LINUX_RUNTIME → bindService                  │
│                                  ③ 宿主→提供方（唯一合法绑定方向）           │
└────────────────────────────────────┬────────────────────────────────────────┘
                                     │ AIDL Binder：ILinuxRuntime → ILinuxSession
                                     ▼
┌──────────── 独立 APK · abyss-plugin-linux · ≈220MB（唯一引擎持有方）────────┐
│  LinuxRuntimeService (exported=true, onBind 校验签名 + grant)               │
│   ├─ PROOT_USERLAND   Alpine 用户空间，≈4MB，默认，冷启瞬时                 │
│   ├─ UML_GUEST  ★新增  真内核 + 独立网络栈，≈13MB，384MB 运行内存          │
│   ├─ QEMU_VM          SLIRP hostfwd + VNC + 快照，≈200MB                   │
│   ├─ abyss-asset-v1   rootfs / 镜像 / 工具链数据（首启下载）                │
│   └─ Shizuku ownAuth  per-app 授权 1 次，uid 2000 拉起引擎                  │
└──────────────────────────────────┬──────────────────────────────────────────┘
                                   │ 受限会话（非 ILinuxRuntime 全权、非 Shizuku Binder）
        ┌──────────────────────────┼──────────────────────────┐
        ▼                          ▼                          ▼
 app-builder 插件          virus-guard 插件           phone-server 插件（后续）
 depends=linux             depends（按需声明）         depends=linux
 无 shizuku                无引擎                      无引擎 / 无 shizuku
                                                     RESIDENT_SESSION / PORT_FORWARD
                                                     / PRIVATE_NETSTACK（SRV-100）
```

**三条纪律不变**：引擎唯一持有方、不静默切域、APK 体积 / 首次下载量 / 运行时内存三栏分开展示。

---

## 4. 20 仓迁移映射（B 方案 = 几乎不动）

| 仓库 | 现状 | v1.4 处置 |
| --- | --- | --- |
| `Abyss-Tools` | v0.9.19/code 42；已有 Broker 四件套 + `ToolCatalog` 的 `linux-runtime` PLUGIN 条目 | **保留全部**；新增 `getInternalSession()`、`apk_size_gate`、`DeathRecipient` 集成测试；`linux-runtime` 条目保持 PLUGIN 不改 |
| `abyss-plugin-linux` | 89 文件 / 7 模块 / 13 AIDL / CI / 工具脚本；`assets/index.json` 空 | **原地保留、继续开发**：① `onBind` 加签名+grant 门禁；② 新增 `domain-uml` 模块（`LIN-006`）；③ `openSession` 由 `E_RUNTIME_UNAVAILABLE` 落到真实会话（M1）；④ 资产清单填 rootfs/镜像；⑤ 发布首个 APK |
| `abyss-plugin-app-builder` | v0.2.0、`depends` linux、已删 shizuku | **原地保留**，不改 manifest；随底座首个功能版本联调 |
| `abyss-plugin-virtual-machine` | `archived=true` | **保持归档**，不复活 |
| `abyss-plugin-virus-guard` | 活跃骨架 | 保留；作为「消费方插件」样板验证授权链路（若不用底座能力可不声明 depends） |
| `Abyss-Plugins` | hubVersion 0.4.0，3 条目 | 加 `abyss-plugin-phone-server` 占位（`status=planned` / `apk=null`）；`hubVersion → 0.5.0`；`depends` 语义保持「消费方 → 独立底座插件」，**不引入 `host-builtin` 伪 id** |
| `Abyss-Tools-Release` | 三件套 | `download.sizeBytes` 与 CI 一致；新增 `apkSizeGate` 与底座插件版本联动检查 |
| 其余 13 个归档仓 | 已归档 | 不动 |

> 对比 v1.3 的 A′ 迁移表：那里要「合入主壳 + 归档 linux/app-builder 两仓 + 改 ToolCatalog 为 BUILT_IN」；B 方案下这些**全部取消**。这就是「不推翻已落地代码」的最彻底版本。

---

## 5. 降级链（宿主反向依赖的关键治理）

```
请求 Linux 能力（插件或主壳内部）
 ├─ 底座插件未安装        → E_RUNTIME_UNAVAILABLE +「安装 Linux 运行环境」一键引导
 ├─ 已装但版本 < minVersion → E_DEP_VERSION + 引导升级（DependencyResolver 已实现）
 ├─ 已装但 Shizuku 未授权  → 明确提示授权；不谎报 READY
 ├─ 资产未就绪（首启下载中）→ 进度可见，getState() 不得返回 READY
 ├─ onBind 校验失败 / 签名不符 → null / E_SIGNATURE_MISMATCH，fail-closed
 ├─ 进程被杀（DeathRecipient）→ 会话 CLOSED + 撤销 grant + UI Error + 一键重连
 ├─ 域探测：UML 可用 → 服务器默认用 UML
 │            UML 不可用 → QEMU（用户确认）
 │            声明 PRIVATE_NETSTACK 但只有 PRoot → E_DOMAIN_NO_REAL_KERNEL，不静默降级
 └─ ptrace 被 ROM 拦截 → Shizuku uid2000 重试 → 仍失败 → UNAVAILABLE + ROM 清单
```

| 编号 | 要求 |
| --- | --- |
| FR-001 | 底座未安装时，主壳所有依赖入口显示「需安装 Linux 运行环境」+ 跳转，不得出现半可用状态。 |
| FR-002 | 主壳 v0.8.0 已内置的终端 / 脚本实验室继续走原路径，不因底座未装而失效。 |
| FR-003 | 主壳不得因「自己是宿主」而免除签名 / 能力 / 审计校验（`ARC-104 §4`）。 |
| FR-004 | 两个 APK 各自过 `apk_size_gate`；任一超 300MB 红灯。 |
| FR-005 | 底座插件升级覆盖安装后，旧 `SessionGrant` 因代际翻转全部失效。 |

---

## 6. 对既有规格的逐份差异（v1.3 → v1.4）

| 文件 | 处置 |
| --- | --- |
| `SRS-105` | **整体被本规格覆盖**（A′ 判定作废）；保留为留痕，其「可翻转开关」已执行 |
| `SRS-104` | **保留**：300MB = APK 体积的口径、20 仓实测、W^X 归类全部有效；§5 的 Q-1~Q-6 收敛 |
| `SRS-103` | 保留 Linux 方案全景与 UML 技术事实；按 RAM 分流结论继续作废 |
| `ARC-104` | **新增**：三类调用方接入契约、宿主自消费通道、M0 门禁 |
| `ARC-100/102/103` | **保留**：三层架构、PluginBroker 时序、瘦客户端重构全部原样适用（v1.0 本来就是 B 方案） |
| `ARC-101` | 保留；`RuntimeDomain` 增 `UML_GUEST` |
| `LIN-001/002/003/004/005` | **全部保留**，归属从「拟合入主壳」改回「独立底座插件原地开发」 |
| `LIN-006 UML` | 保留技术方案；形态改为「底座插件第三个执行域」 |
| `LIN-007` | 保留；`HostCapabilityGateway` 的 `host-builtin` 伪 id 分支**删除**，改回标准插件分支；常驻会话 / 端口转发 / FGS 合规全部落在底座插件进程 |
| `SRV-100` | 保留；`depends.pluginId` 由 `host-builtin` 改回 `abyss-plugin-linux`，删伪 id 说明；其余（capability 增量、平台硬约束、安全边界、非目标）一字不改 |
| `DC-100/101/102` | 保留；`plugin.json v2` 的 `provides/depends/capabilities/assetChannel` 语义不变 |
| `SEC-100 / ENG-100` | 保留；补 `ARC-104 §6 G-1` 的 `onBind` 门禁验收 |
| `PLAN-100/101` | 重排：M1 改为「底座插件原地实现 + 宿主内部通道 + onBind 门禁」，取消「模块合入主壳」与两仓归档步骤 |
| `README / CHANGELOG` | 升级 v1.4 |

---

## 7. 验收门禁

| 编号 | 条件 |
| --- | --- |
| AC-001 | 两个 APK（主壳 / 底座插件）分别量签名后字节数，各自 ≤ 300MB，报告含底座逐组件体积清单（PRoot / UML / QEMU / 工具链 ELF）。 |
| AC-002 | `onBind` 门禁：第三方伪造 APK 绑定返回 null；宿主绑定成功；非法 action 返回 null。 |
| AC-003 | `getInternalSession()` 契约测试通过：主壳内部模块拿到受限会话；签名不符 / 能力越权 / 底座未装三种情况均返回人话错误码。 |
| AC-004 | 消费方插件集成测试：app-builder 经 `depends` → 宿主 → 底座 → 受限 `ILinuxSession` 全链路通；越权能力被拒。 |
| AC-005 | `DeathRecipient`：强杀底座进程后会话 CLOSED、grant 撤销、UI Error 且可一键重连。 |
| AC-006 | 底座未安装时，主壳全部依赖入口 fail-closed 引导；内置终端 / 脚本实验室仍可用。 |
| AC-007 | `SRV-100` 的 5 项 capability 在底座 `provides` 中可声明、可收敛；`phone-server` 占位条目通过索引 CI（`apk=null` / `status=planned` 不进可安装列表）。 |
| AC-008 | 13 个 AIDL 方法签名 diff 为空；7 模块单测全绿。 |
| AC-009 | APK 体积 / 首次下载量 / 运行时内存在 UI 分三栏展示，互不混算。 |

---

## 8. 待确认项（已收敛，只剩实施性选择）

| # | 待确认 | 默认 |
| --- | --- | --- |
| Q-1 | 底座插件的 `minApp` 由哪个主壳版本起支持 | 立项时按主壳合入 `getInternalSession()` 后的实际发版号填（现状开发中 0.9.19 / code 42） |
| Q-2 | `domain-uml` 与 `domain-qemu` 是否共用同一份 `assets/index.json` | 共用，按 `assetId + domain` 分流 |
| Q-3 | 底座插件是否需要自带一个极简设置/状态 Activity | 需要（授权状态、资产进度、域探测结果可见），不承载业务逻辑 |
| Q-4 | 标准 Gradle 工具链中 `java` 等 ELF 的加载路径 | M0 技术验证项（`LIN-007 §7 V-1/V-2`），不阻塞裁定 |

---

## 9. 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| 1.0 | 2026-11-27 | 创建；按用户「如果能就要这种」执行 v1.3 预留的翻转开关，正式采纳 B 方案（独立 Linux 底座插件）；补充宿主自消费通道、onBind 门禁、逐仓迁移映射与验收。 |
