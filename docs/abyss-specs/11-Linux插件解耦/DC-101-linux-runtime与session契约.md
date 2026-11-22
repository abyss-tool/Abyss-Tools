# DC-101 · linux-runtime-v1 能力描述与 RuntimeSession 契约

> 深渊工具 Abyss Tools · 设计规格（数据契约）
> 状态：**设计中 · 待评审** ｜ 优先级：**P0** ｜ 里程碑：**M1** ｜ 编制日期：**2026-11-22**

| 项 | 值 |
| --- | --- |
| 规格编号 | `DC-101` |
| 当前状态 | 设计中 · 待评审 |
| 优先级 / 里程碑 | P0 / M1 |
| 上游依据 | `ARC-101`、`ARC-102`、`PLG-001`、`SHL-001`、`N-PLG-002` |
| 契约形态 | 能力描述 JSON（索引侧）+ AIDL 数据类（运行时侧） |

## 1. 目标与范围

定义 `linux-runtime-v1` 能力的机器可读描述、会话数据结构、跨进程接口的数据类与版本演进规则。消费方只依赖本契约，不依赖 Linux 插件内部实现。

## 2. 能力描述（索引侧，`capabilities["linux-runtime-v1"]`）

```json
{
  "capability": "linux-runtime-v1",
  "provider": "abyss-plugin-linux",
  "since": "0.2.0",
  "hostApi": 2,
  "domains": [
    {"id": "PROOT_USERLAND", "kernel": "shared-android", "coldStartMs": 8000,
     "features": ["EXEC", "PTY", "FILE_IO", "PKG_INSTALL"], "assets": ["rootfs", "toolchain"]},
    {"id": "QEMU_VM", "kernel": "guest-linux", "coldStartMs": 180000,
     "features": ["EXEC", "PTY", "FILE_IO", "PKG_INSTALL", "VNC", "SNAPSHOT", "PORT_FORWARD"],
     "assets": ["image"]}
  ],
  "arch": ["arm64"],
  "limits": {"maxMemoryMb": 2048, "maxExecTimeoutSec": 1800, "maxOutputBytes": 1048576,
             "maxConcurrentExec": 1, "defaultQuotaGb": 8},
  "auth": {"shizuku": "ownAuth", "grantTtlSec": 300, "mediator": "PluginBroker"},
  "featuresNegotiation": "grantedFeatures 查询；不满足返回 E_FEATURE_UNSUPPORTED，不隐藏失败"
}
```

| 字段 | 消费方用途 |
| --- | --- |
| `domains[].coldStartMs` | 切域确认弹层的耗时预期 |
| `domains[].features` | 预检阶段判断能力可用性 |
| `arch` | 非 arm64 设备明确不支持 |
| `limits` | 构建器不得越权提高限额 |
| `auth.mediator` | 声明必须经宿主中介，禁止直连 |
| `grantTtlSec` | 客户端刷新策略 |

## 3. 运行时数据类（AIDL / Parcelable）

| 数据类 | 字段 | 说明 |
| --- | --- | --- |
| `RuntimeInfo` | `pluginVersion`, `hostApi`, `capability`, `domains[]`, `assetsReady`, `shizukuState`, `probeSummary`, `engineVersions{}` | `getCapability` 后第一帧快照 |
| `SessionSpec` | `preferredDomain`, `allowDomainFallback`, `rootfsId`, `imageId`, `workspaceSizeMb`, `env`, `mounts[]`, `limits`, `requestedFeatures`, `clientTag` | 见 ARC-101 §2 |
| `RuntimeSession` | `id`, `domain`, `state`, `spec`, `createdAt`, `refCount`, `grantedFeatures`, `diagnostic` | `getState()` 返回 |
| `ExecCommand` | `argv[]`, `cwd`, `env{}`, `timeoutSec`, `stage`, `workdirRelative` | **argv 数组，禁止字符串命令** |
| `ExecResult` | `exitCode`, `truncated`, `stdoutDigest`, `stderrSummary`, `durationMs`, `stage`, `signal` | exitCode==0 才算成功 |
| `SessionGrant` | `callerPackage`, `callerSignerSha256`, `capability`, `sessionId`, `nonce`, `issuedAt`, `expiry`, `hostSignature` | TTL ≤300s |
| `Diagnostic` | `code`, `domain`, `hint`, `romHint`, `recoverable`, `suggestedAction` | 错误码见 DC-102 |
| `MountSpec` | `source`, `targetRelative`, `readOnly` | 白名单源 + canonical 断言 |
| `PtyHandle` | `ptyId`, `cols`, `rows`, `fd` | 窗口大小同步 |

## 4. 接口方法契约（摘要，完整 AIDL 见 ARC-102 §5）

| 接口 | 方法 | 前置条件 | 后置条件 |
| --- | --- | --- | --- |
| `ILinuxRuntime` | `getRuntimeInfo()` | 宿主已绑定 | 返回快照，不启动会话 |
| `ILinuxRuntime` | `openSession(spec, grant)` | grant 有效、资产 READY、探测通过 | 返回 `ILinuxSession`，refCount+1 |
| `ILinuxRuntime` | `ping()` | — | 存活探测，超时 10s |
| `ILinuxSession` | `getState()` | 会话存在 | 当前状态 + diagnostic |
| `ILinuxSession` | `exec(cmd, grant, sink)` | state=READY、grant 有效、无并发 exec | BUSY → READY，返回 ExecResult |
| `ILinuxSession` | `openPty(cols, rows, grant)` | 支持 PTY | 返回 PtyHandle |
| `ILinuxSession` | `writeFile(rel, pfd, grant)` | 路径合法、空间充足 | 文件落在工作区 |
| `ILinuxSession` | `readFile(rel, grant)` | 文件存在 | ParcelFileDescriptor |
| `ILinuxSession` | `registerCallback(cb)` | — | 状态/输出/退出推送 |
| `ILinuxSession` | `close(reason, grant)` | — | CLOSING → CLOSED |

**不变式**
- 所有方法都必须携带 `SessionGrant`；服务端每次校验签名、包名、nonce、expiry、sessionId 五元组。
- `exec` 在会话内串行；第二个并发调用返回 `E_CONCURRENT_LIMIT`。
- 输出单流上限 1MB，`truncated` 如实回传（SHL-001/003）。
- 任何方法失败返回带 `code` 的 `Diagnostic`，不抛异常穿透 UI。

## 5. 版本演进规则

| 变更类型 | 处理 |
| --- | --- |
| 新增可选字段 | 保持 `linux-runtime-v1`，`ignoreUnknownKeys=true` |
| 新增方法 | 保持 v1，AIDL 接口版本化，旧方法保留 |
| 删除 / 改语义字段或方法 | 新开 `linux-runtime-v2`；v1 保留兼容期 ≥2 个插件版本 |
| 破坏性变更 | 同步提升 `HOST_API_VERSION`，旧插件 fail-closed（SDK-001 FR-005） |
| 能力主版本变化 | `depends.capability` 必须显式改写，`validate_hub.py` 校验匹配 |

## 6. 功能需求

| 编号 | 需求描述 |
| --- | --- |
| FR-001 | 能力描述 JSON 可被纯 JVM 解析并单测，零 Android 依赖。 |
| FR-002 | `SessionSpec` / `RuntimeSession` / `ExecResult` 全部 Parcelable，字段变更走版本升级。 |
| FR-003 | `ExecCommand` 只接受 argv 数组，服务端拒绝任何含 shell 元字符的单字符串命令。 |
| FR-004 | `Diagnostic.code` 必须来自 DC-102 错误码表，禁止自由文本当错误码。 |
| FR-005 | `grantedFeatures` 与能力描述一致；不支持的 Feature 返回明确错误码。 |
| FR-006 | 契约文件在 `:runtime-api` 单一事实源，宿主与构建器 compileOnly 引用，禁止拷贝。 |
| FR-007 | 测试夹具镜像（`src/test/resources`）覆盖正常与畸形契约各 ≥3 例。 |

## 7. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed。
- 状态与 UI 统一 `UiState` 三态，加载中禁止假数据。
- 不暴露 rootfs 绝对路径、host shell 命令拼装入口、Shizuku Binder。

## 8. 验收标准

| 编号 | 验收条件 |
| --- | --- |
| AC-001 | 能力描述 JSON 与 `RuntimeInfo` 字段逐项一致，单测覆盖域/特性/限额。 |
| AC-002 | 构造 3 类畸形 AIDL 数据（缺 grant / 非法路径 / 非 argv 命令）全部被拒。 |
| AC-003 | 新增一个可选字段后，旧客户端仍可正常 exec。 |
| AC-004 | 契约副本数检查：组织中 `ILinuxRuntime.aidl` 仅存在于 `:runtime-api`。 |

## 9. 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| 1.0 | 2026-11-22 | 创建；定义能力描述、会话数据类与版本演进规则。 |
