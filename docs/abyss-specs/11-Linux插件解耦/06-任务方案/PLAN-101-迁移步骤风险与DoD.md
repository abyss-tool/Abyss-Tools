# PLAN-101 · 逐仓迁移步骤、风险登记册与完成定义

> 深渊工具 Abyss Tools · 任务方案（迁移与治理）
> 状态：**设计中 · 待评审** ｜ 优先级：**P0** ｜ 编制日期：**2026-11-22**

| 项 | 值 |
| --- | --- |
| 规格编号 | `PLAN-101` |
| 当前状态 | 设计中 · 待评审 |
| 优先级 / 里程碑 | P0 / M0 |
| 上游依据 | `SRS-102`、`HUB-005`、`HUB-014`、`ADR-100 §5`、`RM-006`、`ENG-100 §8` |
| 用途 | 实施当天的操作手册 + 风险台账 + 最终验收清单 |

## 1. 逐仓文件级改造清单（按顺序执行）

### ① `Abyss-Tools`（主壳，需发版一次）

| 文件 / 目录 | 动作 | 验收 |
| --- | --- | --- |
| `plugin-sdk/plugin-api/.../PluginContract.kt` | `HOST_API_VERSION=2`；增 `ACTION_LINUX_RUNTIME` / `META_CAPABILITIES` | 单测断言旧版拒绝 minHost=2 |
| `plugin-sdk/plugin-api/.../PluginManifest.kt` | 增 `provides/depends/capabilities/assetChannel`（可选，ignoreUnknownKeys） | DC-100 AC-003 |
| `core/common/.../` | 新增 `RuntimeDomain` / `SessionState` / `CapabilityId` 常量 | SRS-101 AC-003 |
| `core/plugin/.../` | 新增 `PluginBroker` / `DependencyResolver` / `CapabilityBinder` / `SessionGrantIssuer` | ARC-102 AC-001~005 |
| `core/plugin/.../aidl/` | 新增 `IAbyssPluginHost.getCapability`（沿用 N-PLG-002） | 契约副本唯一 |
| `core/shell/.../ShizukuCommands.kt` | 增 Linux 会话启动 / 探测模板（不执行用户命令） | SEC-003 单测 |
| `core/common/.../ToolCatalog.kt` | 新增 PLUGIN 条目 `abyss-plugin-linux` | SRS-004 AC-001 |
| `feature/plugins/` | 详情页新增「依赖 / 被依赖 / 能力 / 授权归属」卡片与安装序引导 | APP-100 AC-002 |
| `app/` | Broker 的 Android 侧注入（PackageManager / Binder / DeathRecipient） | 手动 DI，不引 Hilt |
| `docs/PLUGIN_SDK.md`、`DC-006`、`PLG-002`、`SDK-001` | 同步 v2 字段与兼容期说明 | spec-lint |
| `docs/abyss-specs/05-新增能力/N-VM-*`、`07-数据契约/DC-012` | 标注「由 LIN-002 / DC-102 承接」 | ADR-100 AC-001 |
| `src/test/resources/` | 新增 v2 manifest、能力描述、资产清单、错误码夹具 | ENG-100 §4 |

### ② `Abyss-Plugins`（插件中心）

| 文件 | 动作 |
| --- | --- |
| `tools/sync_index.py` | `REPOS` 增 `abyss-plugin-linux`、移除 `abyss-plugin-virtual-machine`；透传 `depends/provides/capabilities/assetChannel/minHost` |
| `tools/validate_hub.py` | 3 条目、depends 指向存在且未归档、capability 匹配、SemVer 区间、patchChannel 正则 |
| `plugins.json` | hubVersion 0.4.0；3 活跃条目；VM 条目移除；`apk=null` 期间展示「资产未发布」 |
| `docs/ADDING_PLUGINS.md` | 增「带 depends 的插件如何准入」章节 |
| `.github/workflows/sync-index.yml` | 单仓故障保留旧条目并告警 |

### ③ `abyss-plugin-linux`（新建）

按 `ADDING_PLUGINS.md` 7 步建仓，额外新增：
`assets/index.json`（abyss-asset-v1）、`tools/validate_assets.py`、`tools/scan_runtime.py`、`:runtime-api` AIDL 目录、`docs/ARCHITECTURE.md`（从构建器迁入底座章节并扩写双执行域）、`LICENSES.md`（Alpine / PRoot / QEMU 许可清单）。
`plugin.json`：v0.2.0、`minHost=2`、`provides=[linux-runtime-v1]`、`shizuku.ownAuth=true`、`assetChannel`、roadmap v0.2–v0.5。

### ④ `abyss-plugin-app-builder`（瘦化）

| 文件 | 动作 |
| --- | --- |
| `plugin.json` | 升 0.2.0；minHost=2、minApp=0.10.0；增 depends；**删 shizuku**；summary 改写 |
| `CHANGELOG.md` / `patches/index.json` | 同步 v0.2.0（三处一致性） |
| `tools/validate_plugin.py` | shizuku 断言反转为「出现即失败」；增 depends / minHost / 静态扫描 |
| `docs/ARCHITECTURE.md` | 删除第 1 章底座层；改写为「会话消费架构」，引用 Linux 插件仓 |
| `ROADMAP.md` | v0.2–v0.6 改为依赖握手 / 三路径 / 交互层 |
| `.github/workflows/plugin-ci.yml` | 增 Gradle assemble + 单测 + 静态扫描 + apksigner 指纹门禁 |
| `src/` | 新增 `:runtime-client`、`:precheck`、`:paths`、`:delivery`；不保留任何底座实现 |
| `README.md` | 安装说明改为「需先安装 Linux 插件」，删除「自带 Alpine rootfs」表述 |

### ⑤ `abyss-plugin-virtual-machine`（归档，HUB-014 纪律）

| 步 | 动作 |
| --- | --- |
| 1 | 停止一切补丁发布，`patches/index.json` 保持现状不再滚动 |
| 2 | `plugins.json` 移除该条目（Hub CI 校验不含归档 id） |
| 3 | `sync_index.py REPOS` 移除 |
| 4 | GitHub 设置 `archived=true`，仓库转只读 |
| 5 | 发布归档公告：能力去向 `QEMU_VM 执行域 → abyss-plugin-linux`；对应规格 LIN-002 / DC-102 |
| 6 | 主仓 `ToolCatalog` / `PluginFrameworkTest.retired` 登记该 id（HUB-014 FR-004） |
| 7 | 历史 manifest 旧组织字段按历史漂移登记，不被运行时索引采纳 |

### ⑥ `Abyss-Tools-Release` / `abyss-plugin-virus-guard`

发布仓三件套纪律不变（`versionCode` 单调、写后回读）；病毒拦截插件不改动，仅回归验证 HOST_API v2 兼容期。

## 2. 实施顺序与回滚点

```
[R0] M0 契约定稿 ──► 回滚点：全部为文档/契约，直接 revert
[R1] 主壳 v2 + Broker ──► 回滚点：feature flag 关闭 v2 能力，v1 插件照常
[R2] Linux 插件 PRoot 域可独立运行 ──► 回滚点：不下发索引，用户不可见
[R3] 构建器 v0.2 上线索引 ──► 回滚点：索引回退到 v0.1 条目，旧骨架仍可展示
[R4] QEMU 域 + VM 仓归档 ──► 回滚点：按 A-1 裁剪，VM 仓暂缓归档
[R5] 全量灰度 ──► 回滚点：灰度开关按插件 id 关停能力绑定
```

每个回滚点必须留：索引快照、三件套清单快照、已发布 APK 的 sha256 与签名指纹、资产清单版本。

## 3. 风险登记册

| R-ID | 风险 | 概率 / 影响 | 缓解 | 责任规格 | 触发回滚 |
| --- | --- | --- | --- | --- | --- |
| R-01 | ColorOS / MagicOS 拦截 ptrace，PRoot 域不可用 | 高 / 高 | Shizuku uid2000 + 用户确认切 QEMU 域 + ROM 清单 | LIN-001/005、LIN-004 | R4 裁剪为仅 QEMU |
| R-02 | QEMU TCG 过慢，路径三不可用 | 高 / 中 | 默认不切域；MID 档 + workers.max=1；如实性能提示 | LIN-002、APP-101 | 放弃 QEMU 构建兜底 |
| R-03 | aapt2 glibc/musl/Bionic 混用崩溃 | 中 / 高 | ARM64 专用编译版 + `ldd` 全量验证门禁 | LIN-001、APP-101 | 路径二/三暂下线 |
| R-04 | 手机端 Gradle OOM | 中 / 高 | JVM 堆 2048m、并发 2、vfs.watch=false、OOM 降档 | APP-101 | 降低默认档位 |
| R-05 | 跨插件 Binder 死亡 / grant 竞态 | 中 / 高 | DeathRecipient + TTL + 撤销 + E2E-12 | ARC-102、LIN-004 | 退回宿主代理执行模式 |
| R-06 | 数 GB 镜像下载失败 / 存储不足 | 中 / 中 | 分卷续传 + 配额 + 预检所需空间 + LRU | LIN-003 | R4 裁剪 |
| R-07 | 用户只装构建器、未装 Linux 插件 | 高 / 中 | 依赖解析 + 一并安装 + 四类引导 | APP-100 | 索引回退 |
| R-08 | 双执行域假设与用户真实意图不符（用户跳过澄清） | 中 / 高 | README A-1 明确裁剪路径；M0 评审必须复核 | README、ADR-100 | 按 A-1 删减规格与任务 |
| R-09 | HOST_API v2 主壳发版受阻 | 中 / 高 | 兼容期 + feature flag；v1 插件照常 | ARC-102、ADR-100 §5 | R1 |
| R-10 | 索引 / 版本账本漂移 | 中 / 中 | 三处一致性门禁、写后回读、versionCode 单调 | ENG-100、DC-100 | 索引快照回退 |
| R-11 | 镜像 / 工具链许可合规 | 低 / 高 | `license` 字段、LICENSES.md、Windows 不分发 | SEC-100、LIN-003 | 下架对应资产 |
| R-12 | 签名密钥 / 产物签名混淆 | 低 / 高 | 发布签名与产物签名隔离、仓库零密钥副本 | APP-102、SEC-001 | 暂停发布 |
| R-13 | 归档 VM 仓被误当现网插件 | 低 / 中 | Hub CI 校验不含归档 id、retired 集合登记 | SRS-102、PLAN-101 §1⑤ | 重新归档 |
| R-14 | 16KB 页对齐 / targetSdk 28 执行域迁移遗漏 | 中 / 中 | CI readelf 门禁；升级前先验证执行域 | LIN-001、SEC-007 | 暂缓升级 |

## 4. 完成定义（DoD，最终签核表）

- [ ] 23 份子规格 + README + CHANGELOG 全量评审通过，spec-lint 无告警
- [ ] 20 仓矩阵与 GitHub API 状态一致；VM 仓 `archived=true`
- [ ] `plugins.json` hubVersion 0.4.0、恰好 3 活跃条目、depends 可解析
- [ ] 主壳 v2、Linux 插件、构建器三仓 CI 全绿；spec-lint / validate_assets / scan_decoupling 全绿
- [ ] E2E-01~12 全部通过并留痕（截图 / 日志 / 产物 sha256）
- [ ] 真机矩阵覆盖 Android 15 / 16 各一台 + ColorOS + MagicOS + 4/6/8GB 内存档
- [ ] 性能基线实测达标；未达标项登记缓解与用户预期文案
- [ ] 安全用例 SEC-100 AC-001~005 与失败注入清单全部通过
- [ ] 授权动作计数验证：从安装到跑通构建，Shizuku 仅授权 1 次
- [ ] 构建器仓静态扫描：0 底座实现、0 shizuku 声明、0 `ILinuxRuntime` 引用
- [ ] 发布链演练通过：固定签名 / apksigner / sync_index / 分卷资产 / 三件套回读
- [ ] 回滚路径四条触发条件各演练一次并留痕
- [ ] 发布说明、CHANGELOG、归档公告、需求追溯矩阵齐备且版本一致
- [ ] 用户可见文案无「root / 刷内核 / 绕过安全策略 / Termux 依赖」等误导表述

## 5. 评审节点

| 节点 | 参与 | 必须决策 |
| --- | --- | --- |
| M0 评审 | 架构 / 安全 / 发布 | 复核 A-1~A-5 五个假设，尤其是双执行域与 VM 收编 |
| M1 出口 | 框架 / 引擎 | 会话契约与 grant 模型冻结（之后变更走能力版本） |
| M2 出口 | 构建器 / QA | 三路径验收与产物签名方案定稿 |
| M3 出口 | 全员 | QEMU 域性能是否达标、VM 仓是否归档 |
| M4 签核 | 全员 | DoD 逐项打勾、灰度比例与回滚负责人 |

## 6. 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| 1.0 | 2026-11-22 | 创建；逐仓迁移清单、6 个回滚点、14 条风险与最终 DoD。 |
