# LIN-003 · rootfs / 镜像资产管理与 abyss-asset-v1 分发

> 深渊工具 Abyss Tools · 设计规格（Linux 插件）
> 状态：**设计中 · 待评审** ｜ 优先级：**P0** ｜ 里程碑：**M1/M3** ｜ 编制日期：**2026-11-22**

| 项 | 值 |
| --- | --- |
| 规格编号 | `LIN-003` |
| 当前状态 | 设计中 · 待评审 |
| 优先级 / 里程碑 | P0 / M1/M3 |
| 上游依据 | `ADR-011`（数据补丁与二进制增量分离）、`ADR-008`、`SEC-002/005`、`N-VM-002`、`DC-012` |
| 核心原则 | 大资产绝不进 `abyss-patch-v1`；插件版本与资产版本独立滚动 |

## 1. 目标与范围

定义 rootfs、VM 镜像、引擎原生库、工具链包四类大资产的清单、下载、校验、存储、升级与清理流程。资产通道由 Linux 插件独占，主壳与构建器都不自行下载二进制。

## 2. 资产分类

| 类别 | 例子 | 量级 | 归属域 | 是否随插件 APK |
| --- | --- | --- | --- | --- |
| `engine-native` | PRoot .so / qemu-system-aarch64 | 4–40MB | 两域 | ✅ 打包进 APK（签名覆盖） |
| `rootfs` | Alpine minirootfs arm64 | 30–120MB | PROOT_USERLAND | ❌ 走资产通道 |
| `image` | Alpine qcow2 / Debian qcow2 | 0.5–4GB | QEMU_VM | ❌ 走资产通道 |
| `toolchain` | Anpyra / arm64 aapt2 / JDK+SDK+Gradle | 50–600MB | PROOT_USERLAND（可复用） | ❌ 构建器请求、插件保管 |
| `template` | 镜像市场模板清单 | <100KB | QEMU_VM | ✅ 可走数据补丁 |

## 3. 通道对比（不可混用）

| 维度 | `abyss-patch-v1`（既有） | `abyss-asset-v1`（新增） |
| --- | --- | --- |
| 载荷 | plugin.json / CHANGELOG 等小数据 | rootfs / 镜像 / 工具链等大二进制 |
| 包格式 | zip 含 patch-manifest.json | 分卷二进制 + `assets/index.json` 清单 |
| 校验 | 整包 sha256 | 每卷 sha256 + 整像 sha256（双层） |
| 更新 | `from → to` 链式增量 | 版本化整像替换；可选 vcdiff 差量（M4） |
| 断点续传 | 不需要 | 必须支持 |
| 存储 | `filesDir/plugins/<id>/` | `filesDir/runtime/assets/<assetId>/<version>/` |
| 门禁 | `validate_plugin.py` 三处一致性 | 新增 `validate_assets.py` |
| 失败 | 备份 → 应用 → 回滚 | 下载到临时目录 → 全量校验 → 原子 rename → 失败删临时 |

## 4. 下载状态机

```
IDLE → CHECKING（读 assets/index.json，比对本地版本与 minPlugin）
     → NEED_UPDATE（展示体积 / 网络提示 / 用户确认）
     → DOWNLOADING（分卷并发 ≤3，逐卷 sha256，记录 offset）
     → VERIFYING（整像 sha256 + 格式魔数 + 大小上限）
     → INSTALLING（原子 rename + 更新本地账本）
     → READY
  任一失败 → FAILED（保留已完成分卷，可续传；校验失败删该卷重下）
  用户取消 → CANCELLED（保留进度，不删已校验分卷）
```

## 5. 关键流程要求

| 编号 | 要求 |
| --- | --- |
| F-1 | `assets/index.json` 必须版本化，含 `schema` / `generatedAt` / `assets[]`；每个资产含 `id / type / domain / arch / version / sizeBytes / sha256 / volumes[] / minPlugin / url / license`。 |
| F-2 | URL 必须过 `ChannelPolicy` 域白名单（raw.githubusercontent.com / github.com / objects.githubusercontent.com / release-assets.githubusercontent.com），重定向后复检 host（SEC-002）。 |
| F-3 | 每卷独立 sha256，整像再校验一次；任一不符整像不可用（DC-012 FR-002）。 |
| F-4 | 下载支持分卷 + 断点续传 + 每卷校验；中断后续传不重复已完成分卷（N-VM-002 AC-001）。 |
| F-5 | 存储不足时预先拒绝并给出所需空间（N-VM-002 FR-004）；配额默认 8GB，可在设置页调整，上限受系统剩余空间约束。 |
| F-6 | 镜像删除需二次确认并同步清理快照（N-VM-002 FR-005）。 |
| F-7 | 原子安装：临时目录 → 校验 → rename → 账本更新；绝不边下边用。 |
| F-8 | 资产版本与插件版本解耦：`minPlugin` 不满足时不展示该资产，插件升级后可复用已下载资产。 |
| F-9 | 模板清单版本化且可被 CI 校验；Windows 镜像不分发（DC-012 FR-003）。 |
| F-10 | 所有资产下载走 `UiState` 三态；加载中禁止展示假进度或假 READY。 |

## 6. 存储布局与配额

```
filesDir/runtime/
├── assets/
│   ├── index.json                     # 远端清单本地缓存（含 generatedAt）
│   ├── alpine-rootfs/3.19.1/          # 已校验整像 + 账本
│   ├── alpine-vm/3.19.1/              # qcow2 + 快照目录
│   └── toolchains/aapt2-arm64/34.0.0/
├── domains/<rootfsId>/                # 解包后的 rootfs（可由整像重建）
├── sessions/<sessionId>/workspace/    # 会话工作区，close 后清理
└── ledger.json                        # 资产版本 / 分卷 offset / sha256 / 上次校验时间
```

| 规则 | 值 |
| --- | --- |
| 配额默认 | 8GB（用户可调，上限 = 剩余空间的 70%） |
| LRU 清理 | 超过配额 90% 时按「未引用 + 最久未用」清理，保留当前会话引用资产 |
| 保留策略 | 正在被会话引用的资产禁止清理；至少保留 1 个可用 rootfs |
| 清理审计 | 每次清理写审计记录（资产 id / 版本 / 释放大小 / 原因） |

## 7. 功能需求（FR）

| 编号 | 需求描述 |
| --- | --- |
| FR-001 | 资产元数据模型纯 JVM，零 Android 依赖（DC-012 FR-001）。 |
| FR-002 | `assets/index.json` 与本地账本一致性由 `validate_assets.py` 在 CI 校验。 |
| FR-003 | 资产不可用不得导致插件崩溃；会话停在 `ASSET_*` 或 `UNAVAILABLE` 并给人话原因。 |
| FR-004 | 工具链资产由构建器按 id 请求（`requestAssets`），插件负责下载、校验、安装与缓存。 |
| FR-005 | 资产完整性校验失败一律 fail-closed，禁止「先跑起来再说」。 |
| FR-006 | 资产清单与镜像只做通路不做信任源；不得把镜像内的包管理源视为可信来源。 |
| FR-007 | 下载进度、分卷状态、校验结果可导出到诊断中心（N-EXP-003），不含用户项目内容。 |
| FR-008 | 首次资产下载必须展示体积与网络提示，移动网络下需用户确认。 |

## 8. 失败语义与边界

- 坏清单 / 非法版本 / 路径穿越 / sha256 不符 / 外域 URL 一律拒绝并给人话错误。
- 镜像格式魔数不符、超出大小上限 → `E_ASSET_FORMAT`，不下发到执行域。
- 插件版本回退（versionCode 单调门禁）时，`minPlugin` 更高的资产必须自动隐藏。
- 断电 / 进程被杀后重启：账本中存在未 INSTALLING 完成的临时目录一律清理。

## 9. 验收标准

| 编号 | 验收条件 |
| --- | --- |
| AC-001 | 构造「分卷中断 → 续传 → 整像 sha256 匹配」用例通过。 |
| AC-002 | 篡改任一卷 1 字节，整像不可用且目标目录零触碰。 |
| AC-003 | 外域 URL / 重定向到外域 / 缺 sha256 / 版本倒退四类清单被拒。 |
| AC-004 | 存储不足时预先拒绝并给出所需空间数值。 |
| AC-005 | 插件从 0.2.0 升到 0.3.0 后，已下载 rootfs 无需重下即可复用。 |

## 10. 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| 1.0 | 2026-11-22 | 创建；按 ADR-011 拆分大资产通道，统一 rootfs / 镜像 / 工具链治理。 |
