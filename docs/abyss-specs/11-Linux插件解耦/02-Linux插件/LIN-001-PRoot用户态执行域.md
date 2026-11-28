# LIN-001 · PROOT_USERLAND 用户态执行域（Alpine + PRoot）

> 深渊工具 Abyss Tools · 设计规格（Linux 插件）
> 状态：**设计中 · 待评审** ｜ 优先级：**P0** ｜ 里程碑：**M1** ｜ 编制日期：**2026-11-22**

| 项 | 值 |
| --- | --- |
| 规格编号 | `LIN-001` |
| 当前状态 | 设计中 · 待评审 |
| 优先级 / 里程碑 | P0 / M1 |
| 承接 | `N-APP-002`、`abyss-plugin-app-builder/docs/ARCHITECTURE.md §1`、`N-TERM-002` |
| 定位 | 默认执行域：轻、快、免 Root、复用 Android 内核 |

## 1. 目标与范围

实现自包含 Alpine 用户空间：PRoot 引擎嵌入、rootfs 解包、ptrace 探测、Shizuku 降级、工具链运行约束。不依赖 Termux，不改内核，不要求 root。

## 2. 组成与选型

| 组件 | 选型 | 理由 / 约束 |
| --- | --- | --- |
| PRoot 引擎 | `proot-distro-nolib` 类方案，以 AAR / 原生库打包进 Linux 插件 APK | 已把 PRoot 引擎 + Alpine 解压与管理逻辑封装，底座约 4MB |
| 根文件系统 | Alpine minirootfs（arm64，musl），版本号写入资产清单 | 体积小、包管理 `apk` 可用；musl 与 aapt2 选型强相关 |
| 执行机制 | `ptrace` 用户态模拟 chroot，仅用 App 私有目录 | 免 Root；不创建真实 mount namespace |
| 启动宿主 | 优先 Shizuku shell 上下文（uid 2000）；降级 App 沙箱纯 PRoot | ColorOS / MagicOS 对普通应用 ptrace 有限制，见 LIN-005 |
| 工作区 | `/workspace/build-<timestamp>`，缓存 `~/.gradle` / pip 只读优先共享 | 沿用原 ARCHITECTURE §1.4 隔离纪律 |
| 工具链 | 由构建器以资产 id 请求，落在本域独立子目录 | 路径一 Python 包 / 路径二 aapt2 / 路径三 JDK+SDK+Gradle 互不覆盖 |

## 3. 启动链路

```
openSession(PROOT_USERLAND)
 ├─ 资产检查：rootfsId 对应分卷已校验且整像 sha256 匹配（LIN-003）
 ├─ 解包到 filesDir/runtime/<rootfsId>/（canonical 断言；已存在则校验后复用）
 ├─ 探测：极轻量 ptrace 自测（只读、无副作用、超时 5s，N-APP-002 FR-002）
 ├─ 选宿主：
 │    ① Shizuku 运行中且已授权 → uid 2000 拉起 PRoot（推荐）
 │    ② 纯 PRoot 模式（App 沙箱内直接拉起）
 │    ③ 两者失败 → DEGRADED/UNAVAILABLE + 已知 ROM 清单
 ├─ 建立会话：绑定 rootfs、env、mounts、limits、工作区
 └─ READY，返回 ILinuxSession
```

## 4. 关键工程约束（逐条落地）

| 编号 | 约束 | 处置 |
| --- | --- | --- |
| C-1 | aapt2 的 libc 坑：官方/Termux 版可能链接 glibc，Alpine 用 musl、Android 用 Bionic，混用会 `undefined symbol` | 选型为 Android/Bionic 或 musl 明确编译的 ARM64 aapt2；底座内 `ldd` 验证动态依赖全部可解析；验证不过拒绝就绪 |
| C-2 | Target SDK 35 的 `android-35.jar` 与可用 aapt2 兼容问题 | compileSdk 暂锁 34，导入提示中告知用户 |
| C-3 | Google 官方构建工具多为 x86_64 | `android.aapt2FromMavenOverride=/path/to/arm64/aapt2`；NDK 用社区 ARM64 编译版；build-tools ≥ 33.0.0 |
| C-4 | 手机端 Gradle OOM | `org.gradle.jvmargs=-Xmx2048m`、`org.gradle.vfs.watch=false`（PRoot 内 inotify 不可靠）、`org.gradle.workers.max=2`、`android.builder.sdkDownload=false` |
| C-5 | Maven 依赖重复下载 | 底座内本地 Maven 缓存 / 私有仓库，首次联网解析后复用；`~/.gradle` 纳入 App 持久化管理 |
| C-6 | targetSdk 28 下 W^X 执行域约束 | 原生库与可执行物必须放 App 私有可加载路径；先于任何 targetSdk 升级验证（N-TERM-002 FR-004） |
| C-7 | 16KB 页对齐 | 所有 `.so` 按 N-CMP-003 做 16KB 对齐，CI 用 `zipalign`/`readelf` 门禁 |
| C-8 | ptrace 平台限制 | ColorOS 15/16 约 1 分钟后安全警告并杀进程；荣耀 MagicOS 内核拒绝。属平台限制，不靠代码优化解决 |

## 5. 能力探测与降级（与 LIN-005 联动）

| 探测项 | 方法 | 失败结果 |
| --- | --- | --- |
| ptrace 可用性 | 只读自测调用，5s 超时 | `PROOT_BLOCKED`，尝试 Shizuku 上下文 |
| Shizuku 状态 | 版本 / 运行中 / per-app 授权 | 未授权 → 引导（不假定常可用） |
| 架构 | `Build.SUPPORTED_ABIS`，arm64 优先 | 非 arm64 → 明确不支持 |
| 存储 | 私有目录剩余空间 ≥ rootfs + 工作区 + 工具链 | `E_STORAGE_INSUFFICIENT`，给出所需空间 |
| 动态依赖 | 底座内 `ldd` 全量可解析 | `E_TOOLCHAIN_LIBC`，不进入构建 |

## 6. 功能需求

| 编号 | 需求描述 |
| --- | --- |
| FR-001 | 底座以 AAR / 原生库形式打包进 Linux 插件 APK，仅使用 App 私有目录（N-APP-002 FR-001）。 |
| FR-002 | 探测命令必须只读、无副作用、超时 5s。 |
| FR-003 | Shizuku 已授权 → uid 2000 拉起；不可用 → 尝试纯 PRoot；两者失败 → 明确不可用，不进入注定失败的构建。 |
| FR-004 | 底座状态（未初始化 / 就绪 / 降级 / 不可用）持久化并展示在 Linux 插件首页。 |
| FR-005 | 三路径工具链各自独立子目录，互不覆盖；每次构建独立临时目录。 |
| FR-006 | 同一时间只允许一个构建任务占用底座；切换路径先取消在跑任务。 |
| FR-007 | stdout/stderr 并发读取、单流 1MB 上限、超时真终止进程并回收资源。 |
| FR-008 | 会话关闭时终止 PRoot 子进程、清理临时目录；断电重启后启动自检清理残留。 |
| FR-009 | 执行域对外只暴露 `ILinuxSession`，不得暴露 rootfs 绝对路径或 host shell 命令拼装入口。 |
| FR-010 | rootfs / 工具链资产升级走 `abyss-asset-v1`，不得写进 `abyss-patch-v1`。 |

## 7. 失败语义与边界

- Shizuku 不是 root：不能 `su`、不能任意突破 SELinux；只解决「PRoot 被沙箱限制」。
- 平台限制导致的失败必须给已知 ROM 清单与可执行建议（启用 Shizuku / 切换 QEMU 域），不得反复重试。
- 不依赖 Termux；用户只需装宿主 + Linux 插件 + 构建器。
- 性能现实：调试构建 1–2 分钟到数分钟甚至更长；本域定位「没有电脑时的可行方案」，不是桌面替代品。

## 8. 验收标准

| 编号 | 验收条件 |
| --- | --- |
| AC-001 | 冷启动到 READY ≤8s（arm64 / 6GB RAM 基线，ENG-100 §7）。 |
| AC-002 | `session.exec("apk --version")` 与 `exec("echo ok")` 均 exitCode=0。 |
| AC-003 | 在受限 ROM 模拟器上探测返回 `PROOT_BLOCKED` 且不启动构建（N-APP-002 AC-001）。 |
| AC-004 | `ldd` 验证不通过时会话不进入 READY，且不触碰工作区。 |
| AC-005 | 连续 20 次 open/close 无孤儿进程、无目录泄漏。 |

## 9. 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| 1.0 | 2026-11-22 | 创建；底座从应用构建器迁入 Linux 插件，补齐探测、libc、内存与页对齐约束。 |
