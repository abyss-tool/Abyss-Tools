# Android 15 / 16 (API 35/36) 适配矩阵

> 深渊工具在 Android 15/16 设备上的兼容立场与逐项对策。
> 基线：compileSdk 36 / minSdk 26 / **targetSdk 28（刻意钉死）**。

## 为什么 targetSdk 钉 28（Termux 同款决策）

`untrusted_app_29+` SELinux 域自 Android 10 起强制 W^X（禁止执行
`app_data_file`）。深渊工具的内核能力 —— 终端引擎（Termux 血统）、
Python 游乐场（Chaquopy）、应用构建器（Alpine + PRoot）—— 都依赖在
应用数据目录执行二进制。升 targetSdk ≥ 29 会直接击杀这些能力的存活
空间。本项目经 GitHub Releases 侧载分发（非 Play 渠道），targetSdk
不受商店政策约束。Termux 官方至今钉 28，是同一工程判断。

**结论：Android 15/16 适配 = 「在钉 28 的前提下于新系统完美运行」，
而不是升 target。**

## 逐项矩阵（v0.5.0）

| Android 15/16 变化 | 约束对象 | 本仓状态 | 对策 |
|---|---|---|---|
| **16KB page size**（15 起部分设备强制） | 含 native 库（.so）的应用 | ✅ 自动满足 | 主壳与全部依赖链均纯 Kotlin/Compose，无 .so；`app/build.gradle.kts` 显式 `useLegacyPackaging = false` 声明前向兼容。带 .so 的插件 APK（终端/构建器）由各插件仓自行对齐 |
| **edge-to-edge 强制**（仅 targetSdk 35+） | target 35+ 应用 | ✅ 不受约束 | 钉 28 保留 legacy 装饰窗口语义；同时全量页面已按 `enableEdgeToEdge + statusBarsPadding` 主动适配 inset（玻璃底栏 `navigationBarsPadding`），未来升 target 零改动 |
| **RECEIVER_EXPORTED 强制**（14 起，仅 targetSdk 34+） | 运行时注册广播 | ✅ 已加固 | `PackageInstallerApkGateway` 在 Android 13+ 显式 `RECEIVER_NOT_EXPORTED`（广播仅收本应用 PendingIntent 定向回调），老系统降级注册。安全审计零告警，前向兼容 |
| **后台活动启动（BAL）收紧** | 所有应用 | ✅ 不涉及 | 安装确认页经 `STATUS_PENDING_USER_ACTION` 的系统 PendingIntent 转发（系统侧发起），不触发 BAL 限制 |
| **FGS 类型强制**（14 起，仅 targetSdk 34+） | 前台服务 | ✅ 不受约束 | 钉 28 无类型强制。悬浮球 / 定位伪装 / 电量守护三处前台服务保持无类型声明；升 target 时需补 `specialUse` / `location` 类型（已列入升级清单） |
| **精确闹钟需授权**（12 起，仅 targetSdk 31+） | AlarmManager exact | ✅ 自动授予 | 任务蓝图调度 `setExactAndAllowWhileIdle` 在 target<31 自动授权；代码内保留 `SecurityException → setAndAllowWhileIdle` 降级双保险 |
| **通知运行时权限**（13 起，仅 targetSdk 33+） | 通知 | ✅ 自动授予 | 钉 28 在 13+ 设备通知权限默认授予；悬浮球前台服务通知正常展示 |
| **Photo Picker** | 图片选择 | ✅ 已采用 | 二维码工坊用 `PickVisualMedia`（免存储权限），新系统走系统级 Picker |
| **scoped storage**（仅 targetSdk 29+ 强制） | 外部存储 | ✅ 不受约束 | 钉 28 走 legacy 存储；二维码保存 PNG 已做双路径（API 29+ MediaStore / 26-28 公共目录直写 + `WRITE_EXTERNAL_STORAGE maxSdk=28`） |
| **预测性返回**（16 起默认开启手势） | 返回导航 | ✅ 兼容 | 自维护返回栈 + `BackHandler` 在系统手势下按默认语义出栈；钉 28 无需 opt-in `enableOnBackInvokedCallback` |
| **ART 16 性能模式** | 运行时 | ✅ 收益 | 纯 Kotlin 应用自动获得 ART 优化，无行为破坏 |
| **`QUERY_ALL_PACKAGES` 政策**（Play 渠道） | 应用列表 | ✅ 不适用 | 本仓侧载分发；权限声明为 Android 11+ OEM 过滤行为的稳定性兜底 |

## 交付验证

- 全链路编译/单测：GitHub Actions（`ci.yml`：debug + release 双变体 +
  `test` + 通道清单校验），JDK 17 / AGP 8.13.2 / Gradle 8.14。
- 16KB 验证：主壳无 native 库即自动合规；若未来引入 .so，CI 侧加
  `zipalign -P 16` 检查（升级清单）。
- 真机回归清单（Android 15/16 各一台）：安装插件 APK（确认页转发）→
  补丁应用 → Shizuku 指令通道 → 悬浮球 overlay → 动态壁纸设置 →
  蓝图定时调度（重启恢复）。

## 升级 targetSdk 的前置条件（长期路线，非本版本范围）

1. 终端 / Python / 构建器插件的执行域迁移（proot 沙箱内自包含执行，
   绕开 `untrusted_app_29+` 的 W^X 约束）；
2. FGS 类型化（悬浮球 `specialUse` + 定位伪装 `location`）；
3. 通知 / 精确闹钟 / RECEIVER_EXPORTED 的 target 级强制项启用
   （代码已按上文矩阵预置）；
4. scoped storage 全量迁移（二维码双路径已预演）。
