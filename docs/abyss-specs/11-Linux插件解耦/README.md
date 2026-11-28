# 深渊工具 · Linux 插件与应用构建器解耦设计规格（全集索引）

> 版本：Spec Set **v1.4（最终裁定稿）** ｜ 初版 2026-11-22 ｜ v1.1 / v1.2 / v1.3 2026-11-27 ｜ **v1.4 2026-11-27（保留独立 Linux 底座插件 · B 方案）**
> 研究口径：2026-11-22 首轮拉取 19 仓；2026-11-27 按实时 API 重新克隆全部 20 仓，核对 Release 资产体积、manifest、CI、主壳源码；v1.4 逐文件核查独立底座的 Service 暴露、AIDL 与宿主绑定链路。

> ✅ **v1.4 最终裁定（先读这一条，取代 v1.3 的 A′ 单 APK 全内置）**
> 用户「**独立 Linux 底座其他插件真的能使用吗，主软件能使用吗，如果能就要这种**」→ 逐文件核实后确认**能**，正式采纳 **B 方案：保留 `abyss-plugin-linux` 独立 APK 作为全生态共享底座**。
> - **其他插件能用**：v1.0 已落地 `depends → DependencyResolver → CapabilityBinder 四重校验 → SessionGrant → 宿主 bindService(ACTION_LINUX_RUNTIME) → 受限 ILinuxSession 代理`，`ARC-102 §4` 时序图、`app-builder/plugin.json`、`plugins.json` 0.4.0 三方对得上；
> - **主软件能用**：① 作为宿主绑定/管理底座（`PluginBroker` 就在主壳进程，本来就是唯一合法方向）；② 作为功能模块消费会话需补 `getInternalSession()` —— 现有 `isConsumerPlugin` 白名单只认索引里的插件、会把宿主自己拒掉；
> - **跨进程传得动**：`IOutputSink` oneway 分片绕开 Binder 1MB 事务、`ParcelFileDescriptor` 传文件、`DeathRecipient` 双向、argv 数组杜绝注入；
> - **300MB 仍合规**：主壳 ≈15MB、底座插件 ≈220MB、其余各几 MB，**每个 APK 各自 < 300MB**；W^X 决定引擎二进制仍进底座插件 APK 的 `lib/`，「后续没事」只覆盖数据；
> - **补两项 M0 门禁**：`onBind` 加签名+grant 校验（现仅校验 action，且 `exported=true`）、`getInternalSession()` 宿主自消费通道；`SRV-100` 的 `host-builtin` 伪 id 删除，`depends` 改回真实 `abyss-plugin-linux`，**已落地代码零改动**；
> - **诚实边界**：当前是 M0 骨架期，`openSession()` 一律 `E_RUNTIME_UNAVAILABLE`、插件 `apk=null`、0 二进制资产 → 链路已通、实现待填，不是「装上就能跑」。
> 新增 `00-总纲/SRS-106-B方案最终裁定-独立Linux底座插件.md` 与 `01-架构/ARC-104-独立底座插件的宿主与消费方接入契约.md`。以下 v1.3 / v1.2 / v1.1 / v1.0 内容全部保留为决策留痕。

> ⚠️ **v1.2 覆盖声明（口径纠错，技术事实仍有效）**
> 用户澄清：**「300MB 指软件安装包 APK 大小，后续没事」** —— 是**分发体积**，不是运行内存；安装后下载的 rootfs / 镜像 / 工具链、运行时 RAM、存储都**不受限**。
> 新增 `00-总纲/SRS-104-APK体积300MB口径纠错与现状实测.md`，**优先于 v1.0 全部结论与 v1.1 的 SRS-103 分流表**：
> - **实测主壳 APK = 14.16 MB**（v0.9.18 / 14,846,101 B），距上限余量 **285.84 MB**；
> - 内置 PRoot(≈4MB) + UML(≈13MB) + QEMU(≈200MB) + 构建器 ≈ **235MB < 300MB → 全部可塞进单个主壳 APK**；
> - UML 的 384MB 是**运行内存**、QEMU 数 GB 镜像是**运行时下载**，都不进 APK，因此 `SRS-103`「因 RAM 超阈值故做插件」的推论作废；
> - 唯一会超线的是把 JDK+SDK+Gradle 全打进包 → 改按需下载即可，**不需要拆插件**；
> - 重新克隆发现：组织已 **20 仓**，`abyss-plugin-linux` **已建成且不是骨架**（完整 Gradle/AIDL/单测/CI），`abyss-plugin-virtual-machine` **已归档**，主壳已实现 `DependencyResolver` / `CapabilityBinder` / `SessionGrantIssuer`，插件 `apk` 仍全为 null、0 二进制资产 —— **v1.0 规格已被组织采纳落地成代码**。
> 逐份差异与 6 个待确认项见 `SRS-104 §3~§7`。v1.0 的 23 份 + v1.1 的 2 份均保留为决策留痕。

## 0. 一句话结论（v1.0，已被 SRS-103 覆盖）

把「Linux 底座」从应用构建器里完整抽出，新建独立引擎级插件 **`abyss-plugin-linux`**，统一对外提供 `linux-runtime-v1` 能力；应用构建器退化为只负责「项目预检 / 三路径编排 / 产物闭环」的瘦客户端，通过宿主中介的跨插件契约**依赖** Linux 插件，不再自带 PRoot、不再单独申请 Shizuku 授权。

## 1. 本套规格回答的问题

| 问题 | 结论 | 出处 |
| --- | --- | --- |
| 整个 Linux 放在哪 | 独立 APK 插件 `abyss-plugin-linux`，是唯一持有 rootfs / 镜像 / 执行引擎的进程 | SRS-102 / ADR-100 |
| 「整个 Linux」是内核还是用户态 | **双执行域**：`PROOT_USERLAND`（复用 Android 内核的 Alpine 用户空间）+ `QEMU_VM`（自带 Linux 内核的完整系统），统一抽象为 RuntimeSession | SRS-101 / ADR-100 / ARC-101 |
| 应用构建器怎么依赖 | `plugin.json v2` 声明 `depends`，宿主 `PluginBroker` 做依赖解析、同签名校验与能力绑定，返回受限 AIDL 代理 | DC-100 / ARC-102 / APP-100 |
| 原有虚拟机插件怎么办 | 收编为 Linux 插件的 QEMU 执行域，`abyss-plugin-virtual-machine` 按 HUB-014 纪律归档只读，索引移除 | SRS-102 / PLAN-101 |
| 授权怎么算 | Shizuku 由 Linux 插件**统一持有、一次授权**；应用构建器删除 `shizuku` 声明（per-app 授权不继承，也就不该重复申请） | LIN-005 / APP-100 |
| 数 GB 镜像怎么发 | 不进 `abyss-patch-v1`；新增 `abyss-asset-v1` 大资产通道（分卷 / 断点续传 / 双层 sha256 / 配额 / 原子替换） | DC-102 / LIN-003 |
| 怎么落地 | 5 个里程碑、约 46 条 WBS 任务、逐仓迁移步骤、风险登记册与 DoD | PLAN-100 / PLAN-101 |

## 2. 明确写入的假设与决策（v1.0 留痕 · 已被 SRS-103 覆盖）

用户未对「执行域形态」与「VM 插件处置」两个澄清项做选择，本套规格按下述推荐假设展开；**若实际决策不同，裁剪方式见下表**。
> v1.1 起，A-1 / A-2 不再生效：形态改由 `SRS-103 D-1~D-7` 按 300MB 常驻 RAM 判定，真内核域首选 UML。

| 编号 | 假设 | 若改为其它方案，需删除/修改的规格 |
| --- | --- | --- |
| A-1 | Linux 插件同时提供 PRoot 用户态与 QEMU 完整系统两个执行域 | 仅 PRoot：删除 LIN-002、DC-102 中镜像/快照部分、PLAN-100 的 M3；仅 VM：删除 LIN-001、并把 APP-101 默认域改为 QEMU_VM |
| A-2 | 撤销 `abyss-plugin-virtual-machine` 独立形态，收编为执行域 | 保留独立仓：需把 ARC-102 改为「跨插件两级依赖」并恢复 SEC-100 的双授权模型 |
| A-3 | 应用构建器默认走 `PROOT_USERLAND`，QEMU 为显式切换的兼容域（非默认） | 若要求默认完整系统：改 APP-101 的域选择表与 QA 性能基线 |
| A-4 | HOST_API 由 1 提升到 2（新增依赖解析与能力绑定），v1 插件保留兼容期 | 若坚持 HOST_API=1：依赖解析只能外置到索引层，安全性下降，需重新评审 SEC-100 |
| A-5 | 主壳需发版一次（新增 PluginBroker / HOST_API v2），插件侧无需宿主发版即可滚动 | — |

**基线事实（克隆实测，非假设）**：应用构建器 v0.1.0 是 15 个文件的骨架（manifest / 文档 / CI 脚本，无 Gradle 工程、无 Kotlin 源码、无 APK）；`plugins.json` 中 3 个引擎级插件 `apk` 全为 `null`；全组织 0 个二进制或镜像资产；v0.2–v0.6 路线图全部未勾选。因此本套规格是**从骨架起步的全新落地设计**，不是对既有实现的补丁式修改。

## 3. 文件清单与阅读顺序

| 顺序 | 文件 | 作用 |
| --- | --- | --- |
| 1 | `00-总纲/SRS-100-总体目标范围与决策摘要.md` | 目标、非目标、成功判据、决策摘要 |
| 2 | `00-总纲/SRS-101-术语表与执行域定义.md` | 统一语言：域 / 会话 / 底座 / 资产 / 能力 |
| 3 | `00-总纲/SRS-102-承载形态分界与仓库矩阵调整.md` | 20 仓角色表、准入纪律、归档流程 |
| 4 | `00-总纲/ADR-100-Linux插件解耦与双执行域决策.md` | 决策记录、备选方案、后果与回滚 |
| 5 | `01-架构/ARC-100-总体架构与模块地图.md` | 三层架构、依赖方向、模块清单 |
| 6 | `01-架构/ARC-101-执行域抽象RuntimeSession.md` | 统一会话模型、状态机、能力矩阵 |
| 7 | `01-架构/ARC-102-跨插件契约与HOST_APIv2.md` | PluginBroker、AIDL 接口、授权令牌、版本兼容 |
| 8 | `01-架构/ARC-103-应用构建器瘦客户端重构.md` | 构建器改造前后对照与边界 |
| 9 | `02-Linux插件/LIN-001-PRoot用户态执行域.md` | Alpine rootfs、PRoot、ptrace、工具链选型 |
| 10 | `02-Linux插件/LIN-002-QEMU完整系统执行域.md` | TCG、镜像、VNC、快照、共享工作区 |
| 11 | `02-Linux插件/LIN-003-rootfs镜像资产与分发.md` | abyss-asset-v1 全流程、配额、清理 |
| 12 | `02-Linux插件/LIN-004-会话生命周期与降级状态机.md` | 进程治理、PTY、引用计数、失败语义 |
| 13 | `02-Linux插件/LIN-005-Shizuku统一授权与执行上下文.md` | 授权模型、shell 上下文、降级链 |
| 14 | `03-应用构建器/APP-100-依赖声明与启动编排.md` | manifest、安装序、启动握手、失败引导 |
| 15 | `03-应用构建器/APP-101-三路径执行域适配与工具链.md` | Anpyra / aapt2 / Gradle 三路径逐域适配 |
| 16 | `03-应用构建器/APP-102-构建产物闭环.md` | 签名、校验、安装、诊断、切换 |
| 17 | `04-数据契约/DC-100-plugin.jsonv2与索引增量.md` | manifest / plugins.json schema 增量 |
| 18 | `04-数据契约/DC-101-linux-runtime与session契约.md` | 能力描述与会话 AIDL/JSON 契约 |
| 19 | `04-数据契约/DC-102-abyss-asset-v1与错误码表.md` | 大资产元数据 + 统一错误码 |
| 20 | `05-安全工程/SEC-100-信任链鉴权与合规.md` | 签名、注入、鉴权、许可合规 |
| 21 | `05-安全工程/ENG-100-CI发布测试与真机验收.md` | CI 门禁、发布链、测试金字塔、真机矩阵、性能基线 |
| 22 | `06-任务方案/PLAN-100-里程碑与WBS任务分解.md` | M0–M4 与 46 条任务（含依赖/估时/验收） |
| 23 | `06-任务方案/PLAN-101-迁移步骤风险与DoD.md` | 逐仓改造清单、风险登记册、DoD |
| **31** | **`00-总纲/SRS-106-B方案最终裁定-独立Linux底座插件.md`** | **v1.4 最终裁定·最高优先级**：保留独立底座插件、每个 APK 体积账、目标架构、20 仓迁移映射、降级链、逐份差异 |
| **32** | **`01-架构/ARC-104-独立底座插件的宿主与消费方接入契约.md`** | **v1.4 新增·直接回答用户提问**：三类调用方接入路径、已落地代码证据、宿主自消费通道、M0 门禁与诚实边界 |
| **28** | `00-总纲/SRS-105-最佳方案裁定与服务器插件扩展点.md` | v1.3 留痕：方案 A′ 判定已被 SRS-106 覆盖；W^X、UML 硬要求、平台约束等事实仍有效 |
| **29** | **`07-服务器插件/SRV-100-手机变服务器插件预留规格.md`** | v1.3 新增：服务器插件挂点、5 项 capability 增量、Android 平台硬约束、安全边界、明确非目标（仅预留不实现） |
| **30** | **`02-Linux插件/LIN-007-常驻会话与端口转发能力.md`** | v1.3 新增：引擎侧常驻会话 / FGS 合规 / 有界重启 / 端口转发 / 资源隔离 / 契约增量 / M0 六项验证 |
| **27** | `00-总纲/SRS-104-APK体积300MB口径纠错与现状实测.md` | v1.2 新增：纠正 300MB 判定量（APK 体积而非 RAM）、20 仓重新克隆实测、APK 体积账、验收门禁 |
| **25** | `00-总纲/SRS-103-300MB阈值形态分流与Linux方案全景.md` | v1.1 新增：Linux 方案全景与 UML 技术事实仍有效；「按 RAM 分流」结论已被覆盖 |
| **26** | `02-Linux插件/LIN-006-UML真内核执行域.md` | v1.1 新增：UML 真内核域技术方案继续有效，形态改为「可内置」 |
| 24 | `CHANGELOG.md` | 规格集变更记录 |

## 4. 与既有规格的关系（对齐纪律）

本套规格**沿用**既有单一事实源，不重复定义：`PLUGIN_SDK.md`（HOST_API / manifest 骨架）、`SRS-003`（版本与发布纪律）、`SRS-004`/`ADR-001`（承载形态三分法）、`SEC-001`（三层信任链）、`SEC-003`（指令注入防护）、`SEC-005`（资产 fail-closed）、`PLG-004`（ChannelPolicy 白名单）、`ADR-008`（镜像只做通路不做信任源）、`ADR-011`（数据补丁与二进制增量分离）、`HUB-001/005/010/014`（索引、准入、门禁、归档）、`DC-006/007/012`（manifest / 补丁包 / 镜像元数据）。

**增量修订点**（本套规格显式提出，需评审）：
1. `HOST_API_VERSION: 1 → 2`（只增不减，兼容期见 ARC-102）；
2. `PluginManifest` 新增 `provides` / `depends` / `capabilities` / `assetChannel` 四个可选字段（v1 解析器 `ignoreUnknownKeys = true`，天然向后兼容）；
3. `plugins.json` 索引新增 `depends` 透传与 `capabilities` 字段，`hubVersion` 由 0.3.0 → 0.4.0；
4. 新增 `abyss-asset-v1` 数据契约，独立于 `abyss-patch-v1`；
5. `abyss-plugin-virtual-machine` 归档，其 N-VM-* / DC-012 规格由本套 LIN-002 / DC-102 承接。
