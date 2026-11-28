# PLAN-100 · 里程碑、进入/退出判据与 WBS 任务分解

> 深渊工具 Abyss Tools · 任务方案（规划）
> 状态：**设计中 · 待评审** ｜ 优先级：**P0** ｜ 编制日期：**2026-11-22**

| 项 | 值 |
| --- | --- |
| 规格编号 | `PLAN-100` |
| 当前状态 | 设计中 · 待评审 |
| 优先级 / 里程碑 | P0 / M0 |
| 上游依据 | `RM-001 里程碑总览`、`RM-006 DoD`、`SRS-100`、`ADR-100`、`ENG-100` |
| 估时口径 | 「人日」为单人等效规划估计（不含等待 CI/真机排期），用于排期与关键路径识别，非承诺 |

## 1. 里程碑总览

| 里程碑 | 主题 | 进入判据 | 退出判据（任一不满足不得进入下一阶段） | 估时小计 |
| --- | --- | --- | --- | --- |
| M0 | 契约与骨架 | 本套规格评审接受 | HOST_API v2 / DC-100/101/102 定稿；20 仓就位；CI 与 spec-lint 全绿 | 18 人日 |
| M1 | Linux 插件 PRoot 域 + 跨插件桥 | M0 退出 | `abyss-plugin-linux` 可独立开会话跑通 E2E-01；grant/依赖解析单测全绿 | 48 人日 |
| M2 | 构建器瘦化与三路径 | M1 退出 | 三路径在 PRoot 域各产出可安装 APK（E2E-06/07）；v2 manifest 与 CI 门禁生效 | 46 人日 |
| M3 | QEMU 完整系统域 | M2 退出 | QEMU 域跑通 E2E-09 与路径三兜底；VM 仓归档完成 | 44 人日 |
| M4 | 加固、灰度与 DoD | M3 退出 | E2E-01~12 全过、真机矩阵、性能基线、安全用例、发布链与回滚演练 | 26 人日 |
| **合计** | — | — | — | **约 182 人日** |

**关键路径**：`T-001 → T-009 → T-012 → T-014 → T-023 → T-026 → T-033 → T-042 → T-046`（契约 → Broker → 会话 → 构建器握手 → 三路径 → QEMU → 全量验收）。
**可并行**：资产通道（T-016~018）与 Broker（T-009~011）并行；文档/索引（T-004~008）与编码并行；真机矩阵准备（T-041）提前到 M2 启动。

## 2. WBS 任务表

### M0 · 契约与骨架（8 条 / 18 人日）

| ID | 任务 | 仓库 | 依赖 | 产出 / 验收 | 人日 |
| --- | --- | --- | --- | --- | --- |
| T-001 | 评审并接受 ADR-100 / SRS-100~102，补齐 ADR-001 例外条目 | Abyss-Tools | — | 决策记录与变更记录 | 2 |
| T-002 | HOST_API 1→2：`PluginContract` 常量与兼容期策略 | Abyss-Tools | T-001 | SDK-001 更新 + 单测 | 2 |
| T-003 | `PluginManifest` 增 4 可选字段、Kotlin 数据类与夹具 | Abyss-Tools | T-002 | DC-100 AC-003 | 3 |
| T-004 | 定稿 `linux-runtime-v1` 能力描述与 AIDL 数据类 | 新仓 | T-002 | DC-101 AC-001/004 | 3 |
| T-005 | 定稿 `abyss-asset-v1` 清单与统一错误码表 | 新仓 | T-002 | DC-102 AC-002 | 2 |
| T-006 | 新建 `abyss-plugin-linux` 仓骨架（ADDING_PLUGINS 7 步） | 新仓 | T-001 | Plugin CI 全绿 | 2 |
| T-007 | `Abyss-Plugins`：`sync_index.py` REPOS 增删、hubVersion 0.4.0、validate_hub 扩展 | Abyss-Plugins | T-006 | Hub CI 全绿、3 条目 | 2 |
| T-008 | 全组织 spec-lint + 测试夹具镜像 + ToolCatalog 新条目 | 全仓 | T-001~007 | SRS-100 AC-001/003 | 2 |

### M1 · Linux 插件 PRoot 域 + 跨插件桥（10 条 / 48 人日）

| ID | 任务 | 仓库 | 依赖 | 产出 / 验收 | 人日 |
| --- | --- | --- | --- | --- | --- |
| T-009 | `DependencyResolver`：DAG / 环检测 / 拓扑序 / SemVer 区间（纯 JVM） | Abyss-Tools | T-003 | ARC-102 AC-001 | 5 |
| T-010 | `CapabilityBinder` + `SessionGrantIssuer`：四重校验、TTL、撤销 | Abyss-Tools | T-009 | ARC-102 AC-002/003 | 5 |
| T-011 | `IAbyssPluginHost.getCapability` + DeathRecipient + 受限代理 | Abyss-Tools | T-010 | ARC-102 AC-004/005 | 4 |
| T-012 | `:runtime-api` / `LinuxRuntimeService` / `ILinuxRuntime` 实现 | 新仓 | T-004 | DC-101 AC-004 | 4 |
| T-013 | `:session` RuntimeSession 状态机、引用计数、限额、工作区断言 | 新仓 | T-012 | ARC-101 AC-002/003 | 6 |
| T-014 | `:domain-proot` 嵌入引擎、rootfs 解包、启动链路 | 新仓 | T-013 | LIN-001 AC-001/002 | 6 |
| T-015 | `:probe` ptrace / arch / 存储 / libc 探测与降级决策 | 新仓 | T-014 | LIN-001 AC-003/004 | 4 |
| T-016 | `:asset` 清单解析 / 分卷下载 / 双层校验 / 断点续传 | 新仓 | T-005 | LIN-003 AC-001/002 | 6 |
| T-017 | 配额、LRU 清理、ledger.json、原子安装 | 新仓 | T-016 | LIN-003 AC-004/005 | 3 |
| T-018 | `:shizuku` 唯一授权、shell 上下文、指令模板与单测 | 新仓 | T-015 | LIN-005 AC-001~004 | 5 |

### M2 · 构建器瘦化与三路径（10 条 / 46 人日）

| ID | 任务 | 仓库 | 依赖 | 产出 / 验收 | 人日 |
| --- | --- | --- | --- | --- | --- |
| T-019 | `plugin.json` 升 0.2.0：minHost=2、depends、删 shizuku | app-builder | T-003/T-007 | APP-100 AC-001 | 2 |
| T-020 | `validate_plugin.py` 反转 shizuku 断言 + depends 校验 + 静态扫描 | app-builder | T-019 | APP-100 AC-001 | 3 |
| T-021 | 删除底座相关文档/设计，迁至 Linux 插件仓 | 两仓 | T-019 | ARC-103 AC-001 | 2 |
| T-022 | `:runtime-client` AIDL 客户端、grant 消费、DeathRecipient | app-builder | T-011 | ARC-103 AC-004 | 5 |
| T-023 | 启动握手：四类失败引导 + 一并安装拓扑序 | app-builder | T-022/T-009 | APP-100 AC-002/003/004 | 5 |
| T-024 | 预检层 + 三选一交互层（推荐/二次确认/硬性标注） | app-builder | T-023 | APP-101 §6 | 6 |
| T-025 | 路径一 Anpyra 会话编排 + 工具链资产请求 | app-builder | T-023 | APP-101 AC-001 | 4 |
| T-026 | 路径二 aapt2 + 固定壳 DEX（ldd 验证门禁） | app-builder | T-023/T-016 | APP-101 AC-001/004 | 5 |
| T-027 | 路径三 Gradle（ARM64 工具链、内存约束、离线缓存） | app-builder | T-023/T-016 | APP-101 AC-001 | 7 |
| T-028 | 产物签名 / 校验 / 安装网关 / 失败切换闭环 | app-builder | T-025~027 | APP-102 AC-001~004 | 7 |

### M3 · QEMU 完整系统域与归档（9 条 / 44 人日）

| ID | 任务 | 仓库 | 依赖 | 产出 / 验收 | 人日 |
| --- | --- | --- | --- | --- | --- |
| T-029 | QEMU TCG arm64 引擎集成、能力探测与档位 | 新仓 | T-013 | LIN-002 AC-004 | 8 |
| T-030 | 镜像元数据 / 分卷资产 / 模板清单（承接 N-VM-002/004） | 新仓 | T-016 | LIN-003 AC-003 | 5 |
| T-031 | 9p/virtiofs 工作区挂载、客户机内 exec/pty 适配 | 新仓 | T-029 | LIN-002 AC-003 | 6 |
| T-032 | VNC 回环 + 随机口令 + 输入路由 + 会话级回收 | 新仓 | T-029 | LIN-002 AC-005 | 5 |
| T-033 | 快照 / 挂起恢复 / 性能档位与 `ResourceLimits` 打通 | 新仓 | T-029 | LIN-002 AC-004 | 5 |
| T-034 | 切域确认弹层（耗时 / 存储 / 性能预期）与域选择策略 | app-builder | T-023/T-029 | ARC-101 §6、APP-101 AC-002 | 4 |
| T-035 | QEMU 域跑通路径三单模块端到端用例 | 两仓 | T-031/T-034 | APP-101 AC-002 | 4 |
| T-036 | 归档 `abyss-plugin-virtual-machine`：只读、停补丁、索引移除、公告 | VM 仓 / 索引 | T-030 | SRS-102 AC-001 | 3 |
| T-037 | 主仓 N-VM-* / DC-012 规格标注「由 LIN-002 / DC-102 承接」 | Abyss-Tools | T-036 | ADR-100 AC-001 | 4 |

### M4 · 加固、灰度与 DoD（9 条 / 26 人日）

| ID | 任务 | 仓库 | 依赖 | 产出 / 验收 | 人日 |
| --- | --- | --- | --- | --- | --- |
| T-038 | 安全用例集：伪造 APK / grant 重放 / 注入 / 路径穿越 / 导出扫描 | 全仓 | T-011/T-028 | SEC-100 AC-001~005 | 4 |
| T-039 | 失败注入：Binder 死亡 / 断电重启 / 下载中断 / OOM / 并发 exec | 全仓 | T-028 | LIN-004 AC-001~005 | 4 |
| T-040 | E2E-01~12 全量执行与留痕 | 全仓 | T-035 | ENG-100 §5 | 3 |
| T-041 | 真机矩阵采购/排期：Android 15/16、ColorOS、MagicOS、内存档位 | — | M2 起并行 | ENG-100 §6 | 2 |
| T-042 | 性能基线实测与未达标项登记 | 全仓 | T-040/T-041 | ENG-100 §7 | 3 |
| T-043 | 发布链演练：固定签名、apksigner、sync_index、资产分卷、三件套回读 | 全仓 | T-016/T-028 | ENG-100 §3 | 3 |
| T-044 | 回滚演练：ADR-100 §5 四条触发条件各跑一次 | 全仓 | T-043 | ADR-100 AC-003 | 3 |
| T-045 | 发布说明 / CHANGELOG / 归档公告 / 需求追溯矩阵补齐 | 全仓 | T-040 | ENG-100 DoD | 2 |
| T-046 | 最终 DoD 逐项打勾 + 评审签核 + 灰度开关配置 | 全仓 | T-038~045 | ENG-100 §8 | 2 |

## 3. 角色与并行建议

| 角色 | 主要负责 | 可并行区间 |
| --- | --- | --- |
| 框架/主壳 | T-002/003/009/010/011/037 | 与资产通道 T-016~018 并行 |
| Linux 引擎 | T-012~018、T-029~033 | 与构建器 T-019~024 并行（靠契约夹具解耦） |
| 构建器 | T-019~028、T-034/035 | 依赖 T-011/T-023 的关键路径 |
| 发布/CI | T-006/007/008/043 | 贯穿全周期 |
| QA/真机 | T-038~042、T-041 提前 | M2 起准备设备 |

## 4. 交付物清单

| 类别 | 交付物 |
| --- | --- |
| 规格 | 本套 23 份子规格 + README + CHANGELOG（zip 交付） |
| 代码 | 主壳 v0.10（HOST_API v2）、`abyss-plugin-linux` v0.2+、`abyss-plugin-app-builder` v0.2+ |
| 契约 | `:runtime-api` AIDL、`assets/index.json` schema、错误码表、测试夹具镜像 |
| 索引 | `plugins.json` hubVersion 0.4.0（3 活跃条目）、归档公告 |
| CI | 三套 plugin-ci、Hub CI、spec-lint、validate_assets.py、scan_decoupling.py |
| 验收 | E2E 留痕、真机矩阵报告、性能基线、安全用例报告、需求追溯矩阵 |
| 发布 | Release APK 资产（apk 字段非 null）、数据补丁首链、大资产分卷首版 |

## 5. 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| 1.0 | 2026-11-22 | 创建；M0–M4 五阶段、46 条 WBS 任务、关键路径与交付物清单。 |
