# ARC-102 · 跨插件契约、PluginBroker 与 HOST_API v2

> 深渊工具 Abyss Tools · 设计规格（架构）
> 状态：**设计中 · 待评审** ｜ 优先级：**P0** ｜ 里程碑：**M1** ｜ 编制日期：**2026-11-22**

| 项 | 值 |
| --- | --- |
| 规格编号 | `ARC-102` |
| 当前状态 | 设计中 · 待评审 |
| 优先级 / 里程碑 | P0 / M1 |
| 上游依据 | `PLG-001/002/004/005`、`SDK-001`、`N-PLG-002 AIDL 双向桥`、`SEC-001/003`、`ADR-100 D-5` |
| 地位 | 本套设计的安全核心：插件间**永不直连** |

## 1. 目标与范围

定义 HOST_API v2 的增量、依赖解析算法、能力绑定时序、AIDL 接口、令牌模型与版本兼容策略。目标是让「应用构建器依赖 Linux 插件」成为**可校验、可拒绝、可审计**的契约，而不是靠包名约定的隐式调用。

## 2. HOST_API v2 增量

| 项 | v1 | v2 | 破坏性 |
| --- | --- | --- | --- |
| `HOST_API_VERSION` | 1 | 2 | 否（只增不减） |
| `PluginContract` 常量 | manifest / 目录 / 索引 | 增 `ACTION_LINUX_RUNTIME`、`ACTION_PLUGIN`（沿用 N-PLG-002）、`META_CAPABILITIES` | 否 |
| `PluginManifest` 字段 | 15 字段 | 增 `provides` / `depends` / `capabilities` / `assetChannel`（均可选） | 否（`ignoreUnknownKeys=true`） |
| 插件生命周期 | manifest 发现 + 补丁通道 | 增依赖解析、能力绑定、令牌签发 | 新增职责 |
| `AbyssPlugin` 接口 | onInstalled/onEnabled/onDisabled | 不变 | 否 |

**升级规则（SDK-001 FR-001/005）**
- `minHost=1` 插件在 v2 宿主照常加载；`minHost=2` 插件在 v1 宿主 fail-closed 拒绝并提示「请升级深渊工具到 v0.10+」。
- 兼容期：宿主 v0.10 同时接受 v1/v2 插件，持续 ≥2 个宿主版本；期间 `depends` 字段在 v1 宿主被忽略，但索引层会标注「需新版宿主」。
- ABI 破坏性变更必须再次提升 HOST_API 并让旧插件 fail-closed，禁止原地改方法签名。

## 3. 依赖解析（DependencyResolver）

输入：`plugins.json` 全量条目 + 本地安装账本；输出：安装/启用拓扑序或拒绝原因。

```
resolve(targetId):
  1. 收集 target 的 depends[]，逐条校验：
     a. capId 必须等于 "linux-runtime-v1"（当前唯一能力）
     b. pluginId 存在于索引且未被归档
     c. versionRange 用 SemVer 解析（禁止 split('.')）
     d. 索引条目的 provides[].capability 含该 capId
  2. 建 DAG（pluginId → 被依赖 pluginId），环检测 → E_DEPENDENCY_CYCLE
  3. 拓扑排序 → installOrder = [abyss-plugin-linux, abyss-plugin-app-builder]
  4. 对 installOrder 每项校验 minHost ≤ 2、minApp ≤ 宿主版本、repo/patchChannel 过 ChannelPolicy
  5. 任一项不满足 → 整批拒绝 + 人话原因，不安装半成品
```

| 场景 | 结果 |
| --- | --- |
| Linux 插件未安装 | 「应用构建器需要 Linux 插件（提供运行环境），请先安装」+ 一键跳转 |
| Linux 插件版本 < `minVersion` | 「Linux 插件需升级到 ≥x.y.z」+ 跳转补丁/Release |
| Linux 插件已安装但未授权 Shizuku | 允许安装构建器，启动时给授权引导（APP-100） |
| 依赖插件被卸载 | 卸载确认页展示「应用构建器将不可用」，提供同时卸载 / 保留但禁用 / 取消 |
| 循环依赖 | `E_DEPENDENCY_CYCLE`，索引 CI 即拦截 |
| 第三方伪造同名插件 | 同签名 + 发布者指纹不匹配 → `E_SIGNATURE_MISMATCH`（SEC-001） |

## 4. 能力绑定时序（PluginBroker）

```
应用构建器进程             主壳进程                    Linux 插件进程
     │                        │                            │
     │ ① getCapability(       │                            │
     │   "linux-runtime-v1")  │                            │
     ├───────────────────────►│                            │
     │                        │ ② 校验调用方：             │
     │                        │  · Binder.getCallingUid    │
     │                        │  · 包名 ∈ 白名单           │
     │                        │  · 签名集 == 发布者指纹     │
     │                        │  · depends 声明匹配        │
     │                        │ ③ PackageManager 查询      │
     │                        │   ACTION_LINUX_RUNTIME 服务│
     │                        ├────── bindService ────────►│
     │                        │◄──── ILinuxRuntime binder ─┤
     │                        │ ④ 签发 SessionGrant        │
     │                        │   (nonce + expiry 5min)    │
     │◄── 受限代理 ───────────┤                            │
     │ ⑤ openSession(spec, grant) ────────────────────────►│
     │                        │                  ⑥ 校验 grant：
     │                        │                   · 签名/包名/nonce/expiry
     │                        │                   · 与宿主核对（回调校验）
     │◄──── ILinuxSession 句柄 ────────────────────────────┤
     │ ⑦ exec / fileIn / close（全部携带 sessionId + grant）│
```

**纪律**
- 构建器拿到的是 `ILinuxSession` **受限代理**，不是 `ILinuxRuntime` 全权，更不是 Shizuku Binder（N-PLG-002 FR-003「不直接给 Binder 全权」）。
- Linux 插件的 `LinuxRuntimeService` 虽为 exported（跨 APK 必需），但 `onBind` 必须校验 `SessionGrant`；无 grant 直接返回 null。
- 宿主与 Linux 插件双向注册 `DeathRecipient`（N-PLG-002 FR-004）：任一方死亡 → 会话标记 CLOSED、撤销 grant、UI 转 Error。
- 每次 `openSession` 用新 nonce；grant 不可跨会话复用、不可持久化。

## 5. AIDL 接口（概念定义，落地以 `:runtime-api` 为准）

```aidl
// ILinuxRuntime.aidl —— 宿主可见，消费方不可直接持有
interface ILinuxRuntime {
    RuntimeInfo getRuntimeInfo();                       // 插件版本 / 域 / 资产状态 / 授权状态
    ILinuxSession openSession(in SessionSpec spec, in SessionGrant grant);
    int ping();                                        // 存活探测
}

// ILinuxSession.aidl —— 消费方唯一可持有的句柄
interface ILinuxSession {
    RuntimeSession getState();
    ExecResult exec(in ExecCommand cmd, in SessionGrant grant, IOutputSink sink);
    PtyHandle openPty(int cols, int rows, in SessionGrant grant);
    void writeFile(String relativePath, ParcelFileDescriptor pfd, in SessionGrant grant);
    ParcelFileDescriptor readFile(String relativePath, in SessionGrant grant);
    void registerCallback(ILinuxSessionCallback cb);
    void close(String reason, in SessionGrant grant);
}

// IAbyssPluginHost.aidl（宿主侧新增方法，沿用 N-PLG-002）
interface IAbyssPluginHost {
    IBinder getCapability(String capability, in SessionGrantRequest req);  // 返回受限代理
    void reportDiagnostic(String pluginId, in Diagnostic d);
}
```

| 数据结构 | 关键字段 |
| --- | --- |
| `SessionGrant` | `callerPackage` / `callerSignerSha256` / `capability` / `sessionId` / `nonce` / `issuedAt` / `expiry` / `hostSignature` |
| `RuntimeInfo` | `pluginVersion` / `hostApi` / `domains[]` / `assetsReady` / `shizukuState` / `probeSummary` |
| `ExecResult` | `exitCode` / `truncated` / `stdoutDigest` / `stderrSummary` / `durationMs` / `stage` |
| `Diagnostic` | `code`（DC-102 错误码）/ `domain` / `hint` / `romHint` / `recoverable` |

## 6. 功能需求

| 编号 | 需求描述 |
| --- | --- |
| FR-001 | `getCapability` 必须做四重校验：调用方包名白名单、签名集等于发布者指纹、索引中存在匹配 `depends`、`minHost` 满足。 |
| FR-002 | 任一校验失败返回人话错误码，不抛异常穿透 UI，不返回半可用 Binder。 |
| FR-003 | 依赖解析、SemVer 区间、环检测、拓扑排序全部纯 JVM 可单测。 |
| FR-004 | `ILinuxRuntime` 不得暴露给消费方；静态检查构建器源码不得出现该类型。 |
| FR-005 | grant 有效期 ≤5 分钟，可撤销；会话心跳续期需宿主重新签发。 |
| FR-006 | 所有跨进程调用设超时（默认 10s，exec 除外）与 DeathRecipient。 |
| FR-007 | 插件崩溃不得导致宿主重启；宿主重启后所有旧 grant 失效。 |
| FR-008 | 依赖解析结果写入本地账本，供卸载级联提示与「我的插件」展示。 |
| FR-009 | 新增 AIDL 接口必须版本化；旧方法保留兼容期，禁止原地改签名（SDK-001 FR-001）。 |
| FR-010 | 审计日志只记 sessionId / capability / 结果码，不记命令正文与项目内容（SEC-009 数据最小化）。 |

## 7. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符 / 依赖缺失一律 fail-closed。
- 直连尝试（无 grant bind）→ `E_GRANT_INVALID`；过期 → `E_GRANT_EXPIRED`；伪造 nonce → `E_GRANT_REPLAY`。
- 服务端不可用 → `E_RUNTIME_UNAVAILABLE`，构建器展示「Linux 插件未就绪」并给安装/启动入口。
- 严禁把 Shizuku 授权状态当作「Linux 可用」的充分条件：还要看资产、探测、会话状态。

## 8. 验收标准

| 编号 | 验收条件 |
| --- | --- |
| AC-001 | 依赖解析单测覆盖：缺失 / 版本过低 / 环 / 归档 id / 伪造签名 / 拓扑序 6 类。 |
| AC-002 | 用一个未签名第三方 APK 冒充构建器调用 `getCapability`，被拒且宿主无状态变化。 |
| AC-003 | grant 过期、重放、跨会话复用三类用例全部拒绝。 |
| AC-004 | 杀掉 Linux 插件进程后 10s 内构建器收到 CLOSED 回调且 UI 转 Error。 |
| AC-005 | v1 宿主加载 `minHost=2` 插件被拒并展示升级提示；v2 宿主加载 v1 插件正常。 |

## 9. 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| 1.0 | 2026-11-22 | 创建；HOST_API v2、依赖解析、能力绑定与令牌模型。 |
