# SRS-104 · 口径纠错：300MB 指 APK 安装包体积，及仓库现状实测

> 深渊工具 Abyss Tools · 设计规格（总纲 · v1.2 增量）
> 状态：**纠正 SRS-103 的判定量误读，优先于 v1.0/v1.1 全部形态结论** ｜ 优先级：**P0** ｜ 编制日期：**2026-11-27**
> 触发：用户澄清「我的意思是软件安装包 apk 大小不超过 300mb 后续没事」+「你克隆看看目前 https://github.com/abyss-tool」

| 项 | 值 |
| --- | --- |
| 规格编号 | `SRS-104` |
| 当前状态 | 口径纠错已生效（300MB = APK 体积） |
| 优先级 / 里程碑 | P0 / M0 |
| 效力 | **覆盖** `SRS-103 §1/§2/§4/§5`（判定量、分流结论、验收口径）；`SRS-103 §3 Linux 方案全景` 与 `LIN-006` 的**技术事实保留**，但「因 RAM 超阈值故做插件」的推论作废 |
| 复核方式 | 2026-11-27 按 GitHub API 实时清单重新克隆组织下全部 20 个仓库，逐仓核对文件树、manifest、CI、Release 资产 |
| 关键实测 | 主壳已发布 APK **14.16 MB**；组织 **20 仓**（非 19）；`abyss-plugin-linux` 已建成且**不是骨架**；`abyss-plugin-virtual-machine` **已归档** |

---

## 1. 先把错认了

v1.1 的 `SRS-103` 把「300MB」判成了 **「底座常驻 RAM（PSS）」**，据此推出「UML 384MB > 300MB → 做插件」「QEMU 512MB → 做插件」「PRoot 数十 MB → 内置」。**这是误读。**

用户的本意是：

> **单个 APK 安装包的体积 ≤ 300MB 即可；「后续没事」= 安装之后下载的 rootfs / 镜像 / 工具链、运行时占用内存、持久化存储，都不受这 300MB 约束。**

两者是完全不同的量：

| 量 | 例子 | 是否受 300MB 约束 |
| --- | --- | --- |
| **APK 分发体积**（压缩后、签名后、含 `assets/` 的字节数） | 主壳 14.16MB、塞进包里的 PRoot/内核/QEMU 二进制 | ✅ **是，唯一判定量** |
| 首次启动后下载的 rootfs / 镜像 | Alpine minirootfs 3.8MB [citation:26]、UML ~512MB 解包 [citation:17][citation:8]、QEMU 2–64GB 稀疏盘 [citation:12] | ❌ 否（后续） |
| 运行时常驻内存 | UML 384MB [citation:17]、QEMU 512MiB 起 [citation:3]、Gradle JVM 堆 2048m | ❌ 否（后续） |
| 工具链按需下载 | JDK17 ~180MB、build-tools ~200MB | ❌ 否（后续） |

**结论翻转**：UML 的 384MB 是**运行内存**，不进 APK；QEMU 的数 GB 镜像是**运行时稀疏盘**，不进 APK。按新口径它们都不参与「内置 vs 插件」判定——**几乎全部 Linux 形态都能塞进主壳一个 APK**，`SRS-103` 里「因 RAM 超阈值所以做插件」的整条推论作废。

---

## 2. 重新克隆核到的现状（与用户贴的组织页快照不一致）

用户贴的页面显示 **19 个仓库、`abyss-plugin-virtual-machine` Public 未归档**。按实时 API 重新克隆，现状已变：

| 项 | 页面快照（用户贴） | 实时实测（2026-11-27） |
| --- | --- | --- |
| 仓库数 | 19 | **20** |
| `abyss-plugin-linux` | 不存在 | **已新建、非骨架**（89 个文件） |
| `abyss-plugin-virtual-machine` | Public 未归档 | **`archived=true`**，pushed 2026-11-22 |
| 主壳版本 | v0.9.5 / code 28（旧认知） | 已发 **v0.9.18 / code 41**；主仓开发中 **v0.9.19 / code 42** |
| 插件中心 | hubVersion 0.3.0 / 3 引擎级插件（VM 在列） | **hubVersion 0.4.0**，3 条目 = linux / app-builder / virus-guard |
| 三个插件 `apk` | null | **仍全为 null**（无已发布 APK） |
| 二进制资产 | 0 | **仍为 0**（`.so/.apk/.img/.qcow2/.tar.gz/.zip` 全仓扫描 0 命中） |

### 2.1 `abyss-plugin-linux` 已经不是骨架了

完整 Gradle 工程，模块与 v1.0 spec 的 `ARC-100 §4` 一一对应：

```
app/           LinuxRuntimeService.kt / LinuxRuntimeBinder.kt / ParcelableContracts.kt
runtime-api/   13 个 AIDL（ILinuxRuntime / ILinuxSession / IOutputSink / SessionGrantData…）
              + AbyssErrorCodes.kt / ContractEnums.kt / LinuxCapability.kt / SessionSpec.kt
asset/         AssetContracts.kt / AssetLedger.kt + 单测
probe/         DomainProbe.kt + 单测
domain-proot/  ProotDomain.kt + 单测
domain-qemu/   QemuDomain.kt + 单测
session/       SessionEngine.kt + 单测
shizuku/       ShizukuAuthMachine.kt / LinuxCommands.kt + 单测
tools/         validate_plugin / validate_assets / scan_runtime / make_patch / publish_patch
assets/index.json  →  {"schema":"abyss-asset-v1","assets":[],"templates":[]}   ← 空清单
```

`plugin.json` 完全按 `DC-100` 落地：v0.2.0、`minHost=2`、`minApp=0.10.0`、`provides=[linux-runtime-v1]`、`capabilities` 含双域与限额、`assetChannel`、`shizuku.ownAuth=true`。`docs/ARCHITECTURE.md` 第 51 行明确写着底座「约 4MB，打包进本插件 APK」，第 55 行「用户只需安装深渊工具 + Linux 插件（+ 构建器）三个 APK」。

### 2.2 主壳侧 v1.0 的 M1 也已落地

`Abyss-Tools` 工作区检出到 `core/plugin/` 下：

- `DependencyResolver.kt` —— 源码注释直接写「ARC-102 §3 —— HOST_API v2 的核心新增职责，纯 JVM 可单测」，并引用 `PluginContract.HOST_API_VERSION`；
- `CapabilityBinder.kt` / `SessionGrantIssuer.kt` —— 后者注释引用「ARC-102 §4 / SEC-100 L4」「DC-102 表」，实现 grant 五元组 + TTL + nonce + 代际；
- `PluginBrokerTest.kt` / `ToolCatalogPluginBindingTest.kt`；
- `ToolCatalog.kt:110` 已有 `id="linux-runtime"`、`pluginId="abyss-plugin-linux"` 的 PLUGIN 条目；
- `AppInfo.VERSION_NAME = "0.9.19"` / `VERSION_CODE = 42`。

`abyss-plugin-app-builder/plugin.json` 也已升 v0.2.0、`minHost=2`、**删除 shizuku**、声明对 `abyss-plugin-linux` 的非可选 `depends`（`>=0.2.0 <1.0.0`），与 `APP-100 §2` 一致。

> 也就是说：**上一轮交付的 v1.0 规格已被组织采纳并写进代码**（插件仓 README 明写「规格依据 `Abyss-Tools` 仓 `docs/abyss-specs/11-Linux插件解耦/`（ADR-100 / ARC-100~103 / LIN-001~005 / DC-100~102 等 23 份子规格）」）。但注意：本次检出中主仓 `docs/abyss-specs/` 只有 `00~05` 六个目录，**未找到 `11-Linux插件解耦/`**；且主仓 `.git` 对象不完整（`git ls-tree` 返回 0），`PluginContract.kt` 未能在工作区定位，`HOST_API_VERSION` 的确切数值待复核。

### 2.3 实测 APK 体积（这是本轮最关键的数字）

| 来源 | 值 |
| --- | --- |
| `Abyss-Tools-Release/version.json` → `download.sizeBytes` | `14,846,101` B |
| GitHub Release `v0.9.18` 资产 `abyss-tools-v0.9.18.apk` | `14,846,101` B = **14.16 MB** |
| 近三版 | v0.9.18 14.16MB / v0.9.17 14.13MB / v0.9.16 14.11MB（缓慢增长） |
| 三个插件仓 Releases | **均无 Release 资产**，`apk` 字段全 null |

**距 300MB 上限余量：约 285.84 MB。**

---

## 3. 按「APK 体积」重算：各形态到底占多少包体

关键区分：**只有打进 `assets/` 或 `lib/` 的二进制才计入 APK**；运行时下载的 rootfs / 镜像 / 工具链一律不计。

| 组件 | 进 APK 的东西 | 增量（实测/公开参考） | 累计 APK |
| --- | --- | --- | --- |
| 主壳现状 | Kotlin 代码 + 资源 | **14.16 MB**（实测） | 14.16 MB |
| PRoot 用户态域 | 静态 PRoot + Alpine minirootfs + ELF Loader | 约 4MB（本仓 ARCHITECTURE 自述）；Alpine aarch64 minirootfs 压缩包 3.8–3.9MB [citation:26]、PRoot 本体约 4MB [citation:17] | **≈ 18 MB** |
| 原生工具链路径一/二 | arm64 `aapt2` + `d8` + `apksigner` | 数 MB 量级（SDK build-tools 内的几个二进制） | ≈ 22 MB |
| **UML 真内核域** | `libvmlinux.so` + 共 4 个原生 payload | **APK 内约 13MB**（内核 strip 后约 8MB）[citation:17] | **≈ 35 MB** |
| **QEMU 完整系统域** | QEMU + kernel + initramfs + Alpine squashfs + fonts | 约 200MB（Podroid 全套 APK 实测口径）[citation:12] | **≈ 235 MB** |
| 标准 Gradle 工具链（若打包） | JDK17 + build-tools + Gradle | ~180MB + ~200MB | **> 400 MB ❌** |
| 标准 Gradle 工具链（按需下载） | 不进包 | 0 | ≈ 235 MB ✅ |

> 已有先例佐证「塞进 APK」可行：nanoMuse 把 Alpine minirootfs + 静态 PRoot + ELF Loader 三样直接放进 `app/src/main/assets/`，全程免 root [citation:19]；Haven 的 UML payload 是 4 个原生文件、APK 内约 13MB，首次连接才解包 ~512MB 镜像 [citation:17]；Podroid 把 QEMU 全套打进约 200MB 的 APK，2–64GB 稀疏盘在首次启动时才创建 [citation:12][citation:10]。

### 判定结论

| 形态 | APK 体积 | 按用户规则 |
| --- | --- | --- |
| 只内置 PRoot + 原生工具链 | ≈ 22MB | ✅ 直接内置 |
| 内置 PRoot + UML 真内核 | ≈ 35MB | ✅ 直接内置（**推翻 SRS-103 的「UML 做插件」**） |
| 内置 PRoot + UML + QEMU 三域 + 构建器 | ≈ 235MB | ✅ **仍 < 300MB，可直接内置单 APK** |
| 唯一会超线的做法 | 把 JDK+SDK+Gradle 全打进包 | ❌ 改按需下载即可，无需拆插件 |

**所以按「APK ≤ 300MB 就内置」这条规则，你最初要的「Linux 插件 + 应用构建器插件、后者依赖前者」在容量上不再必要** —— 一个主壳 APK 就能装下整套东西。是否还要保留双插件，得靠容量之外的理由（独立发版节奏、独立授权、可单独卸载、模块化），不能再用「太大装不下」来解释。

---

## 4. 目标形态（按新口径，覆盖 SRS-103 §4）

```
主壳 Abyss-Tools —— 单个 APK，目标 ≤ 300MB（当前 14.16MB，余量 285.84MB）
 ├─ 内置 Linux 轻量域：Alpine + PRoot（≈4MB）
 ├─ 内置真内核域：UML（≈13MB payload）        ← v1.2 新增建议
 ├─ 可选内置 QEMU 域（≈200MB，视是否要 VNC/快照/跨架构）
 ├─ 内置应用构建器：预检 / 三路径 / 产物闭环
 └─ 工具链 + rootfs + 镜像 → 首次启动后按需下载（不进 APK，用户「后续没事」）
        │
        └─ 只有「全量工具链也打包」才会 > 300MB；此时才考虑拆重域插件
```

| 与 v1.1 的差异 | SRS-103（错） | SRS-104（本规格） |
| --- | --- | --- |
| 判定量 | 常驻 RAM / PSS | **APK 分发体积** |
| UML 384MB | 判为「>300MB → 插件」 | 那是运行内存，**不进 APK**；13MB payload 可内置 |
| QEMU 数 GB 镜像 | 判为「重 → 插件」 | 那是运行时稀疏盘，**不进 APK**；约 200MB 引擎可内置 |
| 构建峰值 / Gradle | 单列治理 | 仍单列，但**不参与形态判定** |
| 插件必要性 | 需要（重域） | **容量上不需要**；保留与否看非容量理由 |
| 内存实测 FR/AC | 保留为性能验收 | 降级为性能指标，不再是形态门禁 |

`LIN-006` 的 UML 技术方案（真内核、任意 arm64、10–20s、近原生性能）**继续有效**，只是从「重域插件」改为「可内置的一个执行域」；其 384MB 固定 RAM 仍要在 UI 明示，但不再决定形态。

---

## 5. 现在真正可选的几条路

| 选项 | 做法 | 单 APK | 代价 / 理由 |
| --- | --- | --- | --- |
| **A. 单 APK 全内置（最贴合新规则，推荐）** | 主壳塞 PRoot + UML + QEMU + 构建器，工具链/镜像按需下载 | ≈ 235MB | 主壳变重、所有能力绑一次发版；但「≤300MB 就内置」完全兑现，用户只装 1 个 APK |
| **B. 保持现状（v1.0 已落地）** | Linux 插件 + 构建器插件 + 病毒拦截 | 主壳 14MB + 若干插件 | 容量上没必要；好处是独立更新 / 独立授权 / 可单独卸载。若要保留需明确写「非容量理由」 |
| **C. 混合** | 轻量域内置，只有把全套 JDK+SDK+QEMU 都打包才把重域拆成插件 | ≈ 235MB 或更低 | 折中，但按 A 已经不超线，C 意义不大 |
| **D. 极简内置** | 只塞 PRoot + 原生 `aapt2/d8` 链（≈22MB），UML/QEMU/工具链全做成按需下载资产（既不进 APK 也不建插件仓） | ≈ 22MB | 最轻；但「整个 Linux / 真内核」要等下载后才可用 |

**「还有其他 Linux 吗」的答案按新口径重述**：换发行版（Alpine / Debian / Ubuntu / Arch / Kali / Void / CentOS）仍是换 rootfs、不换形态，且这些 rootfs 都属于「后续下载」，**完全不占 APK 配额** [citation:2][citation:4][citation:8]；真正占包体的是引擎二进制（PRoot ≈4MB、UML ≈13MB、QEMU ≈200MB）。AVF/pKVM 那条路仍然排除：普通 App 调不了完整 API、Pixel 机型限定 [citation:25][citation:18]。

---

## 6. 验收口径（覆盖 SRS-103 §5）

| 编号 | 要求 |
| --- | --- |
| FR-001 | 形态判定量 = **签名后 APK 的字节数**（`version.json.download.sizeBytes` / Release 资产 size / `apksigner` 后实测），禁止用 PSS、镜像体积、内存代替。 |
| FR-002 | 阈值 `T = 300 * 1024 * 1024 B`；`≤ T` 内置、`> T` 才拆插件；边界值以 CI 实测为准。 |
| FR-003 | CI 增加 `apk_size_gate.py`：assembleRelease → 量字节数 → 超 300MB 直接红灯，输出与上一版 delta。 |
| FR-004 | 打进 `assets/` 的引擎二进制必须逐项登记体积（PRoot / UML / QEMU / 工具链），累计可核对。 |
| FR-005 | 运行时下载的 rootfs / 镜像 / 工具链独立记「首次下载量」，与 APK 体积分开展示，不混算。 |
| FR-006 | 运行时内存（UML 384MB、Gradle 2048m）作为性能指标与 UI 提示保留，不作为形态门禁。 |
| FR-007 | 每个 Release 的 `version.json.download.sizeBytes` 与 CI 实测值一致，写后回读校验。 |
| FR-008 | 若采纳 UML 内置，需补 16KB 页对齐 / PIE / W^X 执行域验证（沿用 SEC-007 / N-CMP-003）。 |

| AC | 验收条件 |
| --- | --- |
| AC-001 | 当前主壳 14.16MB 与 Release 资产一致，`apk_size_gate` 通过。 |
| AC-002 | 把 PRoot+UML+QEMU 全量打进包后实测 < 300MB，报告含逐项体积清单。 |
| AC-003 | 卸载/不装任何插件时，内置构建能力仍可产出可安装 APK。 |
| AC-004 | 首次下载的 rootfs/镜像体积与 APK 体积在 UI 上分两栏展示，不混淆。 |

---

## 7. 待确认（只列真分歧）

| # | 待确认 | 影响 |
| --- | --- | --- |
| Q-1 | 300MB 是「单个 APK」还是「主壳 + 所有插件 APK 之和」？ | 本规格按单个 APK；若按总和需重算 |
| Q-2 | 现状已把 v1.0 落地成代码（20 仓、双插件、Broker）了，是**继续推进**还是按新口径**重构回单 APK**？ | 决定要不要推翻已写的代码 |
| Q-3 | 既然容量允许，是否仍坚持「Linux 与构建器拆成两个插件」？（需要非容量理由） | 决定 HOST_API v2 / PluginBroker / depends 是否保留 |
| Q-4 | 要不要把 v1.2 新增的 **UML 真内核域** 加进现状的双域（PRoot + QEMU）？ | 现状无 UML；加入后真内核能力不必等 QEMU |
| Q-5 | 标准 Gradle 工具链：按需下载（推荐）还是打包进 APK？ | 唯一可能突破 300MB 的变量 |
| Q-6 | 主仓 `docs/abyss-specs/11-Linux插件解耦/` 未检出，规格集是否需要正式合入主仓？ | 影响单一事实源位置 |

---

## 8. 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| 1.0 | 2026-11-27 | 创建；纠正 SRS-103 判定量误读（RAM → APK 体积），重新克隆 20 仓核对现状，重算各形态包体与分流结论。 |
