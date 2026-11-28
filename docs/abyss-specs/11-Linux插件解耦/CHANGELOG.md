# 规格集变更记录 · 深渊工具 Linux 插件解耦设计规格

> 本文件遵循「一版一条」纪律；每个发布版本一个 `## v*` 条目，与代码仓 CHANGELOG 纪律一致。

## v1.0（2026-11-22）

- **首版发布**：基于 `github.com/abyss-tool` 组织 19 个公开仓库全量克隆研究，产出「Linux 插件与应用构建器解耦」完整设计规格与任务方案，共 23 份子规格 + 总索引 + 变更记录。
- **核心决策**：新建 `abyss-plugin-linux`（唯一持有 rootfs / 镜像 / PRoot / QEMU / Shizuku 授权），应用构建器经 `depends` + 宿主 `PluginBroker` 依赖它；`abyss-plugin-virtual-machine` 归档收编为 QEMU 执行域。
- **架构增量**：HOST_API 1 → 2（依赖解析与能力绑定）、双执行域统一 `RuntimeSession` 抽象、受限 AIDL 代理 + `SessionGrant` 令牌、`abyss-asset-v1` 大资产通道。
- **契约增量**：`plugin.json` 增 `provides/depends/capabilities/assetChannel` 四个可选字段；`plugins.json` 升 hubVersion 0.4.0；新增能力描述、会话契约、大资产元数据与统一错误码表。
- **任务方案**：M0–M4 五里程碑、46 条 WBS 任务（约 182 人日）、逐仓迁移清单、6 个回滚点、14 条风险登记、最终 DoD 与 5 个评审节点。
- **明确假设**：用户跳过「执行域形态」与「VM 插件处置」两项澄清，按 README §2 的 A-1~A-5 推荐假设展开；M0 评审必须复核，裁剪路径已写入 README。
- **对齐纪律**：沿用 `PLUGIN_SDK.md`、`SRS-002/003/004`、`ADR-001/008/011`、`SEC-001/002/003/005`、`PLG-004`、`HUB-001/005/010/014`、`DC-006/007/012`；增量修订点集中列于 README §4。

## v1.1（2026-11-27）· 300MB 阈值形态分流

- **新约束**：用户指定「难度不用管；内存 < 300MB 直接内置软件，> 300MB 做插件」，并追问「还有其他 Linux 吗」。全部人日 / 工期 / 难度 / 裁剪成本论证废止，不再作为形态决策依据。
- **新增 2 份规格**：`SRS-103-300MB阈值形态分流与Linux方案全景.md`（覆盖 v1.0 的 A-1/A-2）、`LIN-006-UML真内核执行域.md`。原 23 份子规格保持不动，作为决策留痕。
- **判定口径定死**：量「底座常驻 RAM」（会话 READY 空闲 60s 的 PSS），不量磁盘镜像、不量 APK 体积、不量构建期峰值；`< 300MB` 内置、`≥ 300MB` 插件。
- **Linux 全景补充**：识别 5 类形态（Android 自身 / 原生 aapt2+d8 工具链 / PRoot 用户态 / **UML 真内核** / QEMU TCG / AVF·pKVM），并澄清「换发行版不改变内存量级」——Alpine、Debian、Ubuntu、Arch、Kali、Void 等共用 Android 内核，跨阈值的是「复用内核 → 自带内核」这一步。
- **关键新发现**：**UML（User-Mode Linux）** 是 v1.0 漏掉的中间档 —— 真 Linux 内核作为普通进程、固定 384MB RAM、约 512MB rootfs、10–20s 启动、免 Root / 免 AVF / 免 QEMU / 任意 arm64，性能接近原生；比 QEMU TCG（最低 512MiB、计算慢 13–36×）轻且快，故真内核域首选改为 UML，QEMU 降为兼容备选（仅在需要 VNC / 快照 / 跨架构时启用）。
- **架构推论**：Alpine+PRoot 与原生工具链均 < 300MB → 回主壳 `BUILT_IN`；应用构建器按 `SRS-004` 三分法也应回主壳内置；插件只承载 > 300MB 的重域。用户最初要的「两个插件」在新规则下可能一个都不需要，HOST_API v2 / PluginBroker 随之变为条件保留（若构建器仍坚持独立 APK 则保留）。
- **UI / 验收**：新增内存实测 FR-001~008 与 AC-001~004；构建期峰值单独记录展示，不参与判定；多会话叠加占用必须可见。

## v1.2（2026-11-27）· 口径纠错 + 重新克隆核对现状

- **纠错**：用户澄清「300MB 指**软件安装包 APK 大小**，后续没事」。v1.1 `SRS-103` 把判定量误读成「底座常驻 RAM」，据此推出的「UML 384MB / QEMU 512MB 超阈值 → 做插件」**整条作废**。新口径：唯一判定量 = 签名后 APK 字节数；安装后下载的 rootfs / 镜像 / 工具链、运行时内存、持久化存储均不受限。
- **新增 1 份规格**：`00-总纲/SRS-104-APK体积300MB口径纠错与现状实测.md`（最高优先级，覆盖 `SRS-103 §1/§2/§4/§5`）。v1.0 的 23 份 + v1.1 的 2 份全部保留为留痕。
- **重新克隆（按实时 API 全部 20 仓）**，与用户贴的组织页快照差异显著：页面显示 19 仓、VM 未归档；实测 **20 仓**、`abyss-plugin-linux` **已建成且不是骨架**（89 文件 / 完整 Gradle 工程 / 13 个 AIDL / 7 个模块单测 / CI / 工具脚本）、`abyss-plugin-virtual-machine` **`archived=true`**、插件中心 hubVersion **0.4.0** 且 3 条目（linux / app-builder / virus-guard）、app-builder 已删 shizuku 并声明 `depends`、主壳已实现 `DependencyResolver` / `CapabilityBinder` / `SessionGrantIssuer` / ToolCatalog `linux-runtime` 条目（源码注释直接引用 ARC-102 / DC-102 / SEC-100）。**v1.0 规格已被组织采纳落地成代码**。
- **实测 APK 体积**：`Abyss-Tools-Release/version.json` + GitHub Release `v0.9.18` 资产 = **14,846,101 B = 14.16 MB**；主仓开发中 v0.9.19 / code 42；三个插件仓 **无 Release 资产**、`apk` 全 null、`assets/index.json` 空清单、全仓 **0 个二进制资产**。距 300MB 余量 **285.84 MB**。
- **体积账（结论翻转）**：PRoot+Alpine ≈4MB、UML payload ≈13MB、QEMU 全套 ≈200MB、原生 aapt2/d8 数 MB → 三域 + 构建器全内置 ≈ **235MB < 300MB**。UML 的 384MB 是运行内存、QEMU 数 GB 镜像是运行时稀疏盘，**都不进 APK**；唯一会超线的是把 JDK17(~180MB)+SDK(~200MB)+Gradle 全打包 → 改按需下载即可，**容量上不需要任何插件**。
- **新增验收**：`apk_size_gate.py`（assembleRelease → 量字节数 → 超 300MB 红灯 + 与上版 delta）、引擎二进制逐项体积登记、APK 体积与首次下载量分栏展示；运行内存降级为性能指标而非形态门禁。
- **LIN-006 处置**：UML 技术方案（真内核 / 任意 arm64 / 10–20s / 近原生）继续有效，形态从「重域插件」改为「可内置执行域」。

## v1.3（2026-11-27）· 最佳方案裁定 + 手机变服务器插件扩展点

- **触发**：用户「按最佳方案，后续会开发手机变服务器插件」+「难度不用管」。前面 v1.1 的 5 项、v1.2 的 6 项待确认**一次性收敛**，只剩一个可翻转项。
- **裁定 A′**：单 APK 全内置（PRoot + **UML** + QEMU 三域 + 应用构建器，≈238MB < 300MB）+ 宿主 `linux-runtime-v1` 能力总线；后续 `abyss-plugin-phone-server` 为薄消费方插件。用户装 1 个主壳即用全部能力，要服务器才装第 2 个；Shizuku 授权收敛为主壳 1 次。
- **新增 3 份规格**：`00-总纲/SRS-105-最佳方案裁定与服务器插件扩展点.md`（裁定、体积账、20 仓迁移映射、可翻转开关）、`07-服务器插件/SRV-100-手机变服务器插件预留规格.md`（挂点 / capability 增量 / 平台硬约束 / 安全边界 / 非目标）、`02-Linux插件/LIN-007-常驻会话与端口转发能力.md`（引擎侧常驻会话、FGS 合规、端口转发、资源隔离、契约增量）。
- **关键新事实（改写体积账）**：**W^X** —— Android 10+ / targetSdk ≥ 29 不能 `execve()` 应用私有可写目录中的 ELF，可行路径仅 `lib*.so` 走只读 `nativeLibraryDir`、`system_linker_exec`（`/system/bin/linker64`）、或 `targetSdk 28` 豁免 [citation:3][citation:7][citation:9]。因此**引擎二进制必须打进 APK**，「按需下载引擎」是伪方案；用户「后续没事」实际覆盖的是 rootfs / 镜像 / 包数据 / 工具链里的数据部分。这条反过来证明全内置是唯一能跑通的路径。
- **服务器场景的硬要求**：PRoot 无 PID namespace / cgroups / D-Bus system bus，proot-distro 官方明确不支持 systemd，容器只能用 vfs + `--network host`、端口映射靠 userland-proxy [citation:12][citation:20][citation:13]。因此「手机变服务器」要跑容器 / systemd / 真端口映射，**必须给真内核域** → UML 从「可选优化」升为**硬要求**（只多 ≈13MB 包体）。QEMU 提供 SLIRP `hostfwd`（QEMU ≥7.0 已废弃 `-redir`）与运行时动态增删规则 [citation:19][citation:21][citation:11]。
- **平台约束写进规格**：Android 12+ phantom process killer（32 子进程上限）、Android 14+ FGS 类型与权限、Android 15 `dataSync`/`mediaProcessing` 24h 累计超时与 `onTimeout`、Doze / App Hibernation、普通应用不能绑 <1024 端口（实践 8022/2222/8080/5353）、手机在运营商 NAT 后无公网 IP 需内网穿透、长期插电的电池鼓包与散热风险 [citation:3][citation:8][citation:18][citation:10][citation:5][citation:14][citation:6]。
- **不推翻已落地代码**：`abyss-plugin-linux` 7 模块整体合入主壳 `feature:linux`，`app-builder` 编排逻辑合入 `feature:appbuilder`；主壳已有 `DependencyResolver` / `CapabilityBinder` / `SessionGrantIssuer` 保留，职责收缩为「宿主内部能力 + 消费方授权」。新增 `pluginId: "host-builtin"` 伪 id 让 `DependencyResolver` 跳过「插件已安装」校验、改校宿主内置能力矩阵 —— 只加一个分支，不重写。AIDL 方法签名保持不变，13 个 AIDL 与 7 模块单测全部复用。
- **明确非目标**：服务器插件不提供公网服务/内网穿透服务端、不承诺 7×24 不中断、不自带引擎、本轮不实现、不分发 Windows 客户机镜像。
- **M0 六个技术验证项**（`LIN-007 §7`）：引擎 ELF 加载路径三选一、JDK/toolchain ELF 是否进包、FGS 类型真机超时行为、phantom killer 机型实测、UML 端口映射可达性、QEMU `hostfwd` 动态增删支持。均不阻塞架构裁定。

## v1.4（2026-11-27）· 最终裁定：保留独立 Linux 底座插件（B 方案）

- **触发**：用户「独立 Linux 底座其他插件真的能使用吗，主软件能使用吗，如果能就要这种」→ 执行 v1.3 预留的翻转开关，**正式采纳 B 方案，取代 A′ 单 APK 全内置**。
- **逐文件核实结论：能**。证据链：
  - `abyss-plugin-linux/app/src/main/AndroidManifest.xml`：`LinuxRuntimeService` `exported=true`，intent-filter `com.abyss.tools.plugin.action.LINUX_RUNTIME`，meta-data `HOST_ENTRY`；`HostContractActions.kt` 中 `HOST_API_VERSION=2` 与宿主 `PluginContract` 逐字对齐；
  - `LinuxRuntimeService.kt` 注释明写「exported 为跨 APK 绑定所必需，宿主 PluginBroker 经 ACTION_LINUX_RUNTIME 发现」；
  - 主壳 `core/plugin/` 已落地 `DependencyResolver`（`depends` / SemVer 区间 / DAG 拓扑序）、`CapabilityBinder`（四重校验）、`SessionGrantIssuer`（五元组 + TTL + nonce + 代际）、`ChannelPolicy`（运行时白名单）、`ToolCatalog` 的 `linux-runtime` PLUGIN 条目，以及 `PluginBrokerTest` / `ToolCatalogPluginBindingTest`；
  - `ARC-102 §4` 时序图完整画出「消费方 → 宿主 → 底座插件」；`app-builder/plugin.json` 已声明 `depends` 且删除 shizuku；`plugins.json` hubVersion 0.4.0 的 `provides`/`depends` 对得上；
  - 跨进程数据：`IOutputSink` 为 `oneway` 分片 callback（绕开 Binder 1MB 事务上限）、`ParcelFileDescriptor` 传文件、`PtyHandleData` 句柄化、双向 `DeathRecipient`、`exec` 用 argv 数组杜绝注入。
- **主软件能用的两层含义**：① 作为宿主绑定/管理底座 —— 天然成立（PluginBroker 就在主壳进程）；② 作为功能模块消费会话 —— 能，但 `CapabilityBinder.isConsumerPlugin()` 白名单只认索引里的消费方插件，会把宿主自己以 `E_DIRECT_BIND_DENIED` 拒掉，故**新增 `PluginBroker.getInternalSession()`**：caller 为宿主包名、internal grant（TTL ≤60s）、跳过白名单但保留签名校验 / 能力收敛 / 审计 / 底座未装 fail-closed 引导。v0.8.0 已内置的终端 / 脚本实验室不受影响。
- **新增 2 份规格**：`00-总纲/SRS-106-B方案最终裁定-独立Linux底座插件.md`、`01-架构/ARC-104-独立底座插件的宿主与消费方接入契约.md`。
- **体积账（每个 APK 各自判定）**：主壳 ≈15MB、独立底座插件 ≈220MB（PRoot 4MB + UML 13MB + QEMU 200MB）、构建器 / virus-guard / phone-server 各几 MB，**每一个都 < 300MB**；rootfs / UML 512MB 盘 / QEMU 2–64GB 稀疏盘 / 用户包仍属「后续」。W^X 归类不变 —— 引擎二进制仍必须打进底座插件 APK 的 `lib/`（只读 `nativeLibraryDir`）或走 `system_linker_exec`，拆插件 ≠ 改成下载。
- **B 方案相对 A′ 的真实收益**：底座可独立发版（QEMU/UML 升级不必带 200MB 主壳更新包）、可单独卸载、未来超 300MB 可再拆第二个域插件、Shizuku 授权收敛在底座一个进程（主壳/其他插件 0 次）、**已写的代码几乎不用动**（v1.3 的「7 模块合入主壳 + 归档两仓 + ToolCatalog 改 BUILT_IN」全部取消）。
- **代价与治理**：用户装 2–4 个 APK；主壳核心 Linux 能力反向依赖可选插件 → `SRS-106 §5` 降级链（未装即 `E_RUNTIME_UNAVAILABLE` + 一键引导，绝不谎报）；独立进程被回收 → `DeathRecipient` 重连 + 重签 grant；`exported=true` 攻击面 → M0 阻塞门禁。
- **修订既有规格**：`SRV-100` 的 `depends.pluginId` 由 `host-builtin` 伪 id 改回真实 `abyss-plugin-linux`，删伪 id 说明（其余 capability 增量 / 平台硬约束 / 安全边界 / 非目标一字不改）；`LIN-007` 的 `HostCapabilityGateway` 改回标准插件分支，并明确全部常驻会话 / FGS / 端口转发能力落在**底座插件进程**。`SRS-105` 整体被覆盖、保留为留痕。`SRS-104` 的 APK 体积口径不变。
- **诚实边界（写进规格）**：当前是 M0 骨架期 —— `LinuxRuntimeBinder.openSession()` 一律抛 `E_RUNTIME_UNAVAILABLE`、`runtimeSnapshot` 标注 `assetsReady=false` / `shizukuState=NOT_INSTALLED`、三个插件 `apk=null`、无 Release 资产、0 个二进制资产；`onBind` 目前**只校验 action**，签名/grant 服务端校验「随 M1 落地」→ 列为 M0 阻塞项（`ARC-104 §6 G-1`）。所以是「架构/契约/骨架三层成立，实现待填」。
- **M0 门禁（`ARC-104 §7`）**：`onBind` 服务端校验、`getInternalSession()` 契约测试、消费方集成测试、`DeathRecipient` 双端、底座未装 fail-closed、两个 APK 各自过体积门禁、13 个 AIDL 签名 diff 为空。

### v1.4 待确认项（只剩实施性选择，详见 SRS-106 §8）

| # | 待确认 | 默认 |
| --- | --- | --- |
| Q-1 | 底座插件 `minApp` 由哪个主壳版本起支持 | 按合入 `getInternalSession()` 后的实际发版号填（现状开发中 0.9.19 / code 42） |
| Q-2 | `domain-uml` 与 `domain-qemu` 是否共用 `assets/index.json` | 共用，按 `assetId + domain` 分流 |
| Q-3 | 底座插件是否自带极简设置/状态 Activity | 需要（授权 / 资产进度 / 域探测可见），不承载业务逻辑 |
| Q-4 | Gradle 工具链中 `java` 等 ELF 的加载路径 | M0 验证项，不阻塞裁定 |

### v1.3 待确认项（只剩 1 个可翻转项，详见 SRS-105 §7；该翻转已在 v1.4 执行）

| # | 待确认 | 默认裁定 |
| --- | --- | --- |
| Q-1 | 引擎归属：主壳内置（A′）还是保留独立 Linux 插件（B） | **A′**；翻转只需改 `SRS-105 §1/§4` 两处，`SRV-100`/`LIN-007` 不受影响 |
| Q-2 | 服务器插件是否要公网访问能力 | 默认不提供，只到本机/局域网 |
| Q-3 | 标准 Gradle 工具链中 `java` 等 ELF 的加载路径 | M0 验证项，不阻塞裁定 |

### v1.2 待确认项（Q-1~Q-6，详见 SRS-104 §7）

| # | 待确认 | 影响 |
| --- | --- | --- |
| Q-1 | 300MB 是「单个 APK」还是「主壳 + 所有插件 APK 之和」 | 本版按单个 APK |
| Q-2 | 现状已落地 v1.0 代码，继续推进还是按新口径重构回单 APK | 决定是否推翻已写代码 |
| Q-3 | 是否仍坚持双插件（需非容量理由） | 决定 HOST_API v2 / PluginBroker / depends 保留与否 |
| Q-4 | 是否把 UML 真内核域加入现状的 PRoot+QEMU 双域 | 现状无 UML |
| Q-5 | 标准 Gradle 工具链按需下载还是打包 | 唯一可能突破 300MB 的变量 |
| Q-6 | 主仓 `docs/abyss-specs/11-Linux插件解耦/` 本次未检出，规格集是否正式合入主仓 | 单一事实源位置 |

### v1.1 待确认项（Q-1~Q-5，详见 SRS-103 §7）

| # | 待确认 | 影响 |
| --- | --- | --- |
| Q-1 | 300MB 是「常驻」还是「含构建峰值」 | 若含峰值，标准 Gradle 路径必须移出内置侧 |
| Q-2 | 达标后是否仍坚持构建器做独立 APK | 决定 HOST_API v2 / PluginBroker 是否保留 |
| Q-3 | 真内核域选 UML 插件，还是尝试压到 <300MB 一并内置 | 决定插件是否还需要存在 |
| Q-4 | 内置侧 rootfs 选 Alpine(musl) 还是 Debian(glibc) | 影响 aapt2 的 libc 选型与 `ldd` 验证口径 |
| Q-5 | 原 `abyss-plugin-virtual-machine` 仓改造为重域插件，还是归档新建 | SRS-102 仓库矩阵 |

### v1.0 待评审项（v1.1 之前必须闭环，A-1/A-2 已被覆盖）

| # | 待定 | 影响规格 |
| --- | --- | --- |
| 1 | A-1 双执行域是否确认为最终形态 | README、ADR-100、LIN-002、PLAN-100 M3 |
| 2 | A-2 VM 仓收编 vs 保留独立 | SRS-102、ARC-102、PLAN-101 §1⑤ |
| 3 | `minApp` 具体宿主版本号（暂用 0.10.0 占位） | APP-100、DC-100 |
| 4 | 默认配额 8GB 与 QEMU 档位数值 | LIN-002/003、DC-101 |
| 5 | 性能基线目标值需真机实测后定稿 | ENG-100 §7 |
