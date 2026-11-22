# DC-102 · abyss-asset-v1 大资产元数据契约与统一错误码表

> 深渊工具 Abyss Tools · 设计规格（数据契约）
> 状态：**设计中 · 待评审** ｜ 优先级：**P0** ｜ 里程碑：**M1** ｜ 编制日期：**2026-11-22**

| 项 | 值 |
| --- | --- |
| 规格编号 | `DC-102` |
| 当前状态 | 设计中 · 待评审 |
| 优先级 / 里程碑 | P0 / M1 |
| 上游依据 | `ADR-011`、`ADR-008`、`DC-012`、`N-VM-002/004`、`SEC-002`、`LIN-003` |
| 契约形态 | `assets/index.json`（版本化 JSON）+ 错误码枚举 |

## 1. 目标与范围

定义大资产清单 schema（承接并扩展 DC-012 的镜像元数据），以及全链路唯一错误码表，保证任何失败都能被机器分类、被人话解释。

## 2. assets/index.json schema

```json
{
  "schema": "abyss-asset-v1",
  "generatedAt": "2026-11-22T00:00:00Z",
  "minPlugin": "0.2.0",
  "assets": [
    {
      "id": "alpine-rootfs",
      "type": "rootfs",
      "domain": "PROOT_USERLAND",
      "arch": ["arm64"],
      "version": "3.19.1",
      "sizeBytes": 67108864,
      "sha256": "<64 hex>",
      "format": "tar.gz",
      "license": "Alpine Linux / MIT & GPL 组件，见 LICENSES.md",
      "source": "https://raw.githubusercontent.com/abyss-tool/abyss-plugin-linux/main/assets/README.md",
      "volumes": [
        {"index": 0, "sizeBytes": 16777216, "sha256": "<64 hex>", "url": "https://raw.githubusercontent.com/abyss-tool/abyss-plugin-linux/main/assets/alpine-rootfs-3.19.1.vol0"},
        {"index": 1, "sizeBytes": 50331648, "sha256": "<64 hex>", "url": "https://raw.githubusercontent.com/abyss-tool/abyss-plugin-linux/main/assets/alpine-rootfs-3.19.1.vol1"}
      ],
      "minPlugin": "0.2.0",
      "url": "https://raw.githubusercontent.com/abyss-tool/abyss-plugin-linux/main/assets/alpine-rootfs-3.19.1.tar.gz"
    },
    {
      "id": "alpine-vm",
      "type": "image",
      "domain": "QEMU_VM",
      "arch": ["arm64"],
      "version": "3.19.1",
      "sizeBytes": 1073741824,
      "sha256": "<64 hex>",
      "format": "qcow2",
      "kernel": "guest-linux",
      "recommended": {"vcpu": 2, "memoryMb": 1024},
      "license": "Alpine Linux，用户自备合规镜像",
      "volumes": [
        {"index": 0, "sizeBytes": 268435456, "sha256": "<64 hex>", "url": "https://raw.githubusercontent.com/abyss-tool/abyss-plugin-linux/main/assets/alpine-vm-3.19.1.vol0"}
      ],
      "minPlugin": "0.4.0",
      "url": "https://raw.githubusercontent.com/abyss-tool/abyss-plugin-linux/main/assets/alpine-vm-3.19.1.qcow2"
    }
  ],
  "templates": [
    {"id": "alpine-min", "imageId": "alpine-vm", "arch": "arm64", "license": "开源", "distributable": true},
    {"id": "windows-xp", "distributable": false, "note": "仅用户自备镜像，插件只提供运行时"}
  ]
}
```

| 字段 | 约束 |
| --- | --- |
| `schema` | 固定 `abyss-asset-v1` |
| `type` | `rootfs` / `image` / `toolchain` / `engine-native` / `template` |
| `domain` | `PROOT_USERLAND` / `QEMU_VM` / `BOTH` |
| `sha256` | 64 位 hex，整像必填 |
| `volumes[]` | 每卷独立 `sha256` + `url`，index 从 0 连续 |
| `url` | 过 `ChannelPolicy` 域白名单；重定向后复检 host |
| `minPlugin` | 插件低于此版本不展示该资产 |
| `license` | 必填；Windows 类模板 `distributable=false` |
| `templates` | 版本化，CI 可校验（DC-012 FR-005） |

**与 DC-012 的关系**：本契约承接 DC-012 的 `id/arch/version/sizeBytes/sha256/分卷清单/许可说明`，新增 `domain / minPlugin / format / recommended / templates`，并统一 rootfs 与镜像两类资产。DC-012 标记为「由 DC-102 承接」。

## 3. 本地账本 ledger.json

```json
{
  "schema": "abyss-asset-ledger-v1",
  "assets": [
    {"id": "alpine-rootfs", "version": "3.19.1", "state": "READY",
     "installedAt": "2026-11-22T00:10:00Z", "lastVerifiedAt": "2026-11-22T00:10:00Z", "sha256": "<64 hex>",
     "completedVolumes": [0, 1], "sizeBytes": 67108864}
  ],
  "quotaBytes": 8589934592,
  "lastCleanup": "2026-11-22T00:00:00Z"
}
```

状态枚举：`IDLE / CHECKING / DOWNLOADING / VERIFYING / INSTALLING / READY / FAILED / CANCELLED`。

## 4. 统一错误码表（全链路唯一事实源）

| 分组 | 错误码 | 含义 | 人话提示要点 | 可恢复 |
| --- | --- | --- | --- | --- |
| 依赖 / 契约 | `E_DEP_MISSING` | 依赖插件未安装 | 请先安装 Linux 插件 | ✓ |
| | `E_DEP_VERSION` | 依赖插件版本过低 | 需升级到 ≥x.y.z | ✓ |
| | `E_DEPENDENCY_CYCLE` | 依赖成环 | 插件依赖配置异常 | ✗（CI 拦截） |
| | `E_CAPABILITY_MISMATCH` | capability 不匹配 | 能力版本不兼容 | ✗ |
| | `E_HOST_API_TOO_LOW` | 宿主版本过低 | 请升级深渊工具 | ✓ |
| 信任 / 令牌 | `E_SIGNATURE_MISMATCH` | 签名集/指纹不符 | 插件来源不可信 | ✗ |
| | `E_GRANT_INVALID` | 令牌缺失或非法 | 请重新打开应用 | ✓ |
| | `E_GRANT_EXPIRED` | 令牌过期 | 已自动重试 | ✓ |
| | `E_GRANT_REPLAY` | nonce 重放 | 已拒绝并记审计 | ✗ |
| | `E_DIRECT_BIND_DENIED` | 无中介直连尝试 | 请通过插件中心启动 | ✗ |
| 资产 | `E_ASSET_INDEX_BAD` | 清单格式/字段错 | 组件清单异常 | ✓ |
| | `E_ASSET_URL_DENIED` | 外域 URL / 重定向越域 | 下载地址不被允许 | ✗ |
| | `E_ASSET_VOLUME_HASH` | 分卷 sha256 不符 | 已重下该分卷 | ✓ |
| | `E_ASSET_WHOLE_HASH` | 整像 sha256 不符 | 组件校验失败 | ✓ |
| | `E_ASSET_FORMAT` | 魔数/格式/大小超限 | 组件格式不支持 | ✗ |
| | `E_STORAGE_INSUFFICIENT` | 空间不足 | 需释放 X GB | ✓ |
| | `E_ASSET_MIN_PLUGIN` | 插件版本不满足 minPlugin | 请升级 Linux 插件 | ✓ |
| 探测 / 平台 | `E_PROBE_PTRACE_BLOCKED` | ptrace 被平台限制 | 当前系统限制 + 已知 ROM 清单 | 部分 |
| | `E_PROBE_TIMEOUT` | 探测超时 | 环境检测超时 | ✓ |
| | `E_TOOLCHAIN_LIBC` | aapt2 动态依赖不可解析 | 工具链不兼容 | ✗ |
| | `E_VM_RESOURCE` | 内存/memlock 不足 | 建议降档或释放内存 | ✓ |
| | `E_VM_TCG_UNAVAILABLE` | TCG 不可用 | 完整系统域不可用 | ✗ |
| 授权 | `E_SHIZUKU_NOT_RUNNING` | Shizuku 未运行 | 请启动 Shizuku | ✓ |
| | `E_SHIZUKU_NOT_GRANTED` | 未授权 Linux 插件 | 请单独授权一次 | ✓ |
| | `E_SHIZUKU_REVOKED` | 授权被撤销 | 请重新授权 | ✓ |
| 会话 | `E_SESSION_CLOSED` | 会话已关闭 | 请重新开始 | ✓ |
| | `E_CONCURRENT_LIMIT` | 并发 exec 超限 | 已有任务在跑 | ✓ |
| | `E_EXEC_TIMEOUT` | 命令超时 | 构建超时，可降档 | ✓ |
| | `E_EXEC_TRUNCATED` | 输出被截断 | 日志不完整（如实标注） | — |
| | `E_PATH_TRAVERSAL` | 路径穿越 | 路径不合法 | ✗ |
| | `E_FEATURE_UNSUPPORTED` | 该域不支持此特性 | 当前运行环境不支持 | 部分 |
| | `E_RUNTIME_UNAVAILABLE` | Linux 插件未就绪 | 运行环境未就绪 | ✓ |
| | `E_RUNTIME_DEAD` | Binder 死亡 | 运行环境已退出 | ✓ |
| 产物 | `E_ARTIFACT_EMPTY` / `E_ARTIFACT_FORMAT` / `E_ARTIFACT_ALIGN` / `E_ARTIFACT_SIGN` / `E_ARTIFACT_DIGEST` / `E_ARTIFACT_META` | 产物校验失败 | 对应阶段人话原因 | 部分 |
| | `E_INSTALL_TIMEOUT` / `E_INSTALL_REJECTED` | 安装超时 / 被拒 | 可重试 / 签名冲突 | 部分 |

**使用纪律**
- 错误码只进日志、诊断导出与 `Diagnostic.code`；UI 一律展示人话提示 + 建议动作。
- `recoverable=true` 才允许展示「重试」按钮；平台限制类不得提供无限重试。
- 新增错误码必须同步更新本表、测试夹具与 spec-lint 枚举校验。

## 5. 功能需求

| 编号 | 需求描述 |
| --- | --- |
| FR-001 | 元数据模型纯 JVM，零 Android 依赖（DC-012 FR-001）。 |
| FR-002 | 每卷独立 sha256，整像再校验一次（DC-012 FR-002）。 |
| FR-003 | 来源 URL 过域白名单；Windows 镜像不分发（DC-012 FR-003）。 |
| FR-004 | 快照含镜像 id / 内存大小 / 创建时间 / sha256（DC-012 FR-004）。 |
| FR-005 | 模板清单版本化且可被 CI 校验（DC-012 FR-005）。 |
| FR-006 | 错误码表为唯一事实源，禁止在业务代码散落字符串字面量。 |
| FR-007 | `assets/index.json` 与 `ledger.json` 一致性由 `validate_assets.py` 校验。 |

## 6. 失败语义与边界

- 坏清单 / 非法版本 / 路径穿越 / sha256 不符 / 外域 URL 一律 fail-closed。
- 镜像只做通路不做信任源（ADR-008）。
- 加载中禁止展示假进度或假 READY。

## 7. 验收标准

| 编号 | 验收条件 |
| --- | --- |
| AC-001 | 元数据单测覆盖架构不匹配、sha256 缺失、分卷断链（DC-012 AC-001）。 |
| AC-002 | spec-lint 扫描：代码中出现的 `E_*` 全部在本表中。 |
| AC-003 | 外域 URL / 重定向越域 / 缺 sha256 / 版本倒退四类清单被拒。 |
| AC-004 | 每类错误码至少 1 条单测验证人话提示与可恢复标记。 |

## 8. 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| 1.0 | 2026-11-22 | 创建；统一大资产元数据（承接 DC-012）与全链路错误码表。 |
