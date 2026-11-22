# 深渊工具 · Linux 插件与应用构建器解耦设计规格（全集索引）

> 版本：Spec Set v1.0 ｜ 编制日期：2026-11-22 ｜ 基线：主壳 v0.9.5 / versionCode 28（Release 仓当前 v0.9.10 / versionCode 33）
> 研究口径：对 `github.com/abyss-tool` 组织下 19 个公开仓库全量拉取（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓），逐仓核对 manifest、架构文档、数据契约、CI 门禁与发布链。

## 0. 一句话结论

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

## 2. 明确写入的假设与决策（重要）

用户未对「执行域形态」与「VM 插件处置」两个澄清项做选择，本套规格按下述推荐假设展开；**若实际决策不同，裁剪方式见下表**：

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
| 24 | `CHANGELOG.md` | 规格集变更记录 |

## 4. 与既有规格的关系（对齐纪律）

本套规格**沿用**既有单一事实源，不重复定义：`PLUGIN_SDK.md`（HOST_API / manifest 骨架）、SRS-003（版本与发布纪律）、SRS-004/ADR-001（承载形态三分法）、SEC-001（三层信任链）、SEC-003（指令注入防护）、SEC-005（资产 fail-closed）、PLG-004（ChannelPolicy 白名单）、ADR-008（镜像只做通路不做信任源）、ADR-011（数据补丁与二进制增量分离）、`HUB-001/005/010/014`（索引、准入、门禁、归档）、`DC-006/007/012`（manifest / 补丁包 / 镜像元数据）。

**增量修订点**（本套规格显式提出，需评审）：
1. `HOST_API_VERSION: 1 → 2`（只增不减，兼容期见 ARC-102）；
2. `PluginManifest` 新增 `provides` / `depends` / `capabilities` / `assetChannel` 四个可选字段（v1 解析器 `ignoreUnknownKeys = true`，天然向后兼容）；
3. `plugins.json` 索引新增 `depends` 透传与 `capabilities` 字段，`hubVersion` 由 0.3.0 → 0.4.0；
4. 新增 `abyss-asset-v1` 数据契约，独立于 `abyss-patch-v1`；
5. `abyss-plugin-virtual-machine` 归档，其 N-VM-* / DC-012 规格由本套 LIN-002 / DC-102 承接。
