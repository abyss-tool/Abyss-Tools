# 架构（docs/ARCHITECTURE.md）

## 分层总览

```
┌───────────────────────────────────────────────────────────────┐
│ :app 主壳                                                      │
│   MainActivity（首启引导门：未完成新手引导不进主界面）          │
│   AbyssRoot（Crossfade 返回栈） · ServiceRegistry（手动 DI）   │
│   内容层 = 玻璃采样源（layerBackdrop）→ 玻璃件悬浮其上          │
├───────────────┬───────────────┬───────────────────────────────┤
│ :feature:dashboard │ :feature:navhub │ :feature:plugins         │
│ :feature:disguise │ :feature:onboarding │ :feature:settings    │
│ :feature:appmgr │ :feature:shizukukit │ :feature:qrcraft         │
│ :feature:blueprint │ :feature:island │ :feature:wallpaper        │
│      （纯 Compose 界面层，依赖注入经由参数，不感知 DI 容器）      │
├───────────────┴───────────────┴───────────────────────────────┤
│ :core:designsystem（M3 主题 + 液态玻璃组件）                    │
│    └── :vendor:backdrop（Kyant0/AndroidLiquidGlass，Apache-2.0）│
├───────────────────────────────────────────────────────────────┤
│ :core:plugin（索引/安装器/插件补丁通道）  :core:update（补丁引擎）│
│ :core:shell（Shizuku adb shell 网关 + 指令编排，伪装四件套共用）│
│    └── :plugin-sdk:plugin-api（契约）      └── :core:common     │
│                                                （SemVer/目录/渠道）│
└───────────────────────────────────────────────────────────────┘
```

## 依赖方向（单向，无环）

- `app → feature:* → designsystem → vendor:backdrop`
- `feature:* → core:{common,plugin,update,shell}`
- `core:plugin → core:update → core:common`；`core:plugin → plugin-sdk`
- `core:shell`：Android 库（Shizuku AAR）但指令编排（ShizukuCommands）
  零 Android 依赖可单测；接口隔离（ShellGateway）对齐 ApkInstallGateway 模式
- 纯 JVM 模块（common / update / plugin / plugin-sdk）：单测零 Android
  依赖，CI 上 `./gradlew test` 即全量行为验证。

## 伪装四件套（v0.3 内置，v0.6 信号显示伪装落地；:feature:disguise + :core:shell）

| 引擎 | 通道 | 生效范围 |
| --- | --- | --- |
| 电量伪装 | `dumpsys battery set/unplug/reset` | 全系统（官方电池测试通道，重启自恢复） |
| 定位伪装 | appops `mock_location` + `LocationManager.addTestProvider` 三源注入 | 全系统（Fake GPS 同款官方通道） |
| 信号伪装（真实开关） | `cmd connectivity` / `svc data` / `svc wifi` | 真实连接性开关（与系统快捷开关等价） |
| **信号显示伪装（v0.6）** | appops `SYSTEM_ALERT_WINDOW`（Shizuku 一键授权）+ `TYPE_APPLICATION_OVERLAY` 前台服务覆盖层 | 状态栏显示层：Assertion（5G-A/5G/4G/LTE/3G）+ 信号格 1-4 + Wi-Fi 图标，无卡可用；截图 / 屏幕共享 / 录屏同视图；停止即恢复真实显示 |
| 机型伪装 | `settings put global device_name` + 档案导出 | 设备名即刻生效；Build 字段档案由沙箱 Hook 层消费 |

诚实边界：无 Root 不改 ro.product.*（系统属性只读）；App 代码读
TelephonyManager / WifiManager 拿到的仍是真实值（Hook 属 LSPatch / 沙箱
插件领域）；覆盖层在锁屏 / 下拉快捷面板头部不显示（系统对 overlay 的
原生约束）。信号显示伪装的绘制逻辑单一实现于 `SignalStatusArt`
（覆盖层 View 与页内 Compose 预览共用，所见即所得）。Shizuku 状态经
`ShizukuMonitor` 扇出，Compose 侧唯一桥接点 `rememberShizukuStatus`。

## 内置七工具（v0.4 全部真实功能页，占位页时代结束）

| 模块 | 能力 | 通道 |
| --- | --- | --- |
| :feature:appmgr | 列表/搜索/排序 / 冻结（pm disable-user）/ 隐藏（pm hide）/ 强停（am force-stop）/ 提取 APK 分享 | PackageManager + Shizuku 双通道 |
| :feature:shizukukit | appops 查改 / settings 读写 / dumpsys 白名单摘要 / 指令历史（50 条） | Shizuku（指令编排纯 JVM 可单测） |
| :feature:qrcraft | ZXing 生成（纠错/色板/尺寸）+ 相册解析（Photo Picker）+ 历史 | 纯算法 + MediaStore 双路径保存 |
| :feature:blueprint | JSON 步骤链（shell/toast/delay）+ 引擎（成功判定/超时/continueOnFailure/{prev_output}）+ 定时调度（exact alarm + 开机重排） | 引擎纯 JVM；SHELL 步骤走 ShellGateway |
| :feature:island | overlay 悬浮球（拖拽吸边）+ 快捷面板（真实电量/时间）+ 前台服务保活 | WindowManager + ComposeView 三 Owner 桥接 |
| :feature:wallpaper | 液态玻璃光斑引擎（四主题/速度/视差）+ 预览 + 一键设为 | WallpaperService（30fps，不可见暂停） |
| 任务蓝图×自动化 | 同一 BlueprintScreen 双入口：task-blueprint → 蓝图库；automation → 调度 Tab | — |

## v0.8.0 大内置化六件套（原重型插件仓已归档，能力全部落地真实功能页）

| 模块 | 原插件仓（已归档） | 能力 | 通道 |
| --- | --- | --- | --- |
| :feature:terminal | abyss-plugin-terminal | 内置终端：指令输入 / 流式输出 / 历史（50 条）/ 快捷命令 / 字号分档 | Shizuku adb shell（gateway.exec） |
| :feature:scriptlab | abyss-plugin-python-lab | 脚本实验室：Shell/Python 编辑 → 保存 → 执行；Python 运行时探测 | printf 管道经 Shizuku（app 私有目录 shell 不可读的诚实边界） |
| :feature:sandbox | abyss-plugin-sandbox | 隔离空间：多用户引擎（pm create-user / install --user --existing）免 Root 双开 | Shizuku + pm/am 通道 |
| :feature:media | abyss-plugin-media-lab | 媒体实验室：音频提取（AAC→M4A）/ 去音轨 / 关键帧对齐裁剪 | MediaExtractor + MediaMuxer 无重编码；MediaStore 输出 |
| :feature:netaudit | abyss-plugin-network-guard | 流量审计：应用维度流量画像 / 榜单 / 双向流量条 | dumpsys netstats（解析器纯 JVM 可单测） |
| :feature:backup | abyss-plugin-backup | 备份迁移：APK 资料库 / 配置与插件包 zip / 带防护恢复 | zip 引擎（ZipGuard：路径穿越 + zip 炸弹 + 根白名单三重门） |

## v0.8.0 新能力（非插件化的新增）

| 模块 | 能力 | 通道 |
| --- | --- | --- |
| :feature:syspower | 系统控制中心：深色/亮度/DPI/分辨率/动画/休眠/Doze + **授权总管**（任意应用 AppOps allow/ignore/default + pm grant/revoke） | cmd uimode / wm / settings / appops / pm 全通道（指令编排纯 JVM 可单测） |
| :feature:widgets | 组件工坊：黑洞动态时钟桌面组件（2×2 / 4×2）+ 实时预览 | AppWidgetProvider + AlarmManager 整分自续期链 + 开机重排；绘制单一实现（BlackHoleArt）与 Compose 预览共用 |
| :core:designsystem | M3 完整规范：十五级字阶 + 形状阶梯 + 动态取色（Material You，Android 12+） | MaterialTheme typography/shapes/dynamicColorScheme |

## v0.9.0「引力场」新能力

| 模块 | 能力 | 通道 |
| --- | --- | --- |
| :feature:widgets | **组件银河四成员**：引力电池（吸积环电量刻度 + 充电极向喷流，档位热色 50/20 分档）/ 星轨日历（行星公转一年，年积日折算轨道角 + 月刻度）/ 深渊引力仪（内存·存储双引力环 + 引力尘埃粒子）；AbyssWidgetFamily 家族注册表（整分链一跳喂饱四家族，全无实例才拆链）；组件工坊四卡片画廊 | AppWidgetProvider ×5 + ACTION_BATTERY_CHANGED 粘性广播只读快照 + ActivityManager / StatFs 免权限读取；三个纯数学对象（GravityBatteryMath / StellarCalendarMath / AbyssGaugeMath）零 Android 依赖可单测；WidgetSpace 深空底共享层 |
| :feature:syspower | **隐私与管控四面板**：权限审计（dumpsys package 运行时权限解析 + 危险权限白名单交集 + 逐项 pm grant/revoke + 批量回收 executeAll）/ 敏感操作记录（appops query-op 相机·麦克风·定位·剪贴板）/ Doze 白名单（dumpsys deviceidle whitelist 读写）/ 流量策略（cmd netpolicy 全局流量节省 + 按 uid 后台流量拒绝/解除）；控制中心 M3 SegmentedButton 四页签化（会话日志全页签常驻） | dumpsys package / appops / deviceidle / cmd netpolicy（指令编排纯 JVM 可单测）；uid 经 PackageManager 解析 |
| :feature:blueprint | **双模式调度**：间隔循环（intervalMinutes ≥ 15）+ 每日定时（dailyAtMinute 0..1439，字段互斥、旧 JSON 向前兼容）；SchedulePlanner 纯排程（今天未到排今天 / 已过排明天 / 恰好此刻排明天防双触发）；到期重排按蓝图当前 schedule 收敛 | AlarmManager setExactAndAllowWhileIdle；编辑页 M3 TimePicker（ModalBottomSheet）；排程纯 JVM 可单测（含闰年 / 午夜边界） |
| :feature:appmgr / plugins / terminal / settings | **M3 组件补全**：SearchBar（展开态搜索视图 + 关键词历史落盘 + 匹配行直达）/ PullToRefreshBox（retryKey 管线复用，数据态原地刷新）/ InputChip 历史（FlowRow + 单条删除）/ TooltipBox 图标提示 / SnackbarHost 动作反馈 | material3 1.4.0 实验性 API 显式 OptIn |

Android 15/16 适配立场（16KB / edge-to-edge / RECEIVER 加固 / FGS 等
逐项矩阵）见 [ANDROID_COMPAT.md](ANDROID_COMPAT.md)。

## 导航与返回栈

框架期不引 navigation-compose：`AbyssBackStack` 自维护
`mutableStateOf(List<Screen>)`，Crossfade 切页，BackHandler 手势返回。
底栏仅顶层 tab 显示（stack.size == 1），详情页沉浸全屏 —— 玻璃采样源
在内容根上，任何页面滚过玻璃件下方都会被真实采样。

v0.9.13（FEAT-034）起顶层 tab 为四项：仪表盘 / 功能 / 插件 / 设置 ——
「插件」独立 tab 直达顶层插件中心（`onBack = null`，无返回箭头）；
`tabIndex` 精确映射 0-3，详情页返回 -1；`selectTab` 非法索引
fail-closed 直接 return（替代旧 `else -> Settings` 猜测）。

v0.6.0 起返回栈可序列化（`rememberSaveable` + listSaver）：旋转 /
深浅色切换 / 进程重建后原位恢复；AppManagerDetail 只序列化包名键，
恢复后经 AppInfoProvider 异步重挂（查不到则诚实留在列表页）。

## 液态玻璃系统（三层能力）

| 档位 | 条件 | 渲染 |
| --- | --- | --- |
| Backdrop | 有采样源 + API 31+ | blur +（API 33+）AGSL lens 折射/色散 + 霜面 tint + 边缘受光 + 内阴影 |
| Backdrop · Clear（FEAT-033） | 有采样源 + API 31+ 且指示器样式 = CLEAR | 零 blur（`blurEnabled = false`）+ 边缘带 lens/色散 + 静态 SDF rim（光带冻结中心、四角点光、相对侧暗边）+ 内阴影 |
| Frosted | 无源 或 API < 31 | 霜面渐变 + 边缘光（零 GPU 管线，诚实降级） |

纪律：`drawBackdrop` 的全部 lambda 记忆化（ModifierNodeElement 对
lambda 按身份比较 —— 重组期新建 lambda 会引发 draw 失效风暴）；效果
级 `runCatching`（OEM RenderEffect/AGSL 差异单项降级，进程存活优先）。
底栏液滴指示器支持双样式切换（FEAT-033：`AbyssIndicatorStyle`，
`blurEnabled=false` 为编译期可见布尔契约，切换不重建 `AbyssLiquidMotion`）。

## 更新体系（双仓库，全自动）

```
开发仓 Abyss-Tools（源码 + CI 门禁：双变体构建 + 单测 + 清单/版本单一事实源门禁）
    │ git tag v*（版本号只住在 AppInfo.kt，build.gradle.kts 推导）
    ▼
release.yml：assembleRelease（secrets 注入签名材料 + apksigner 指纹门禁）
    │ tools/publish_release.py（RELEASE_TOKEN；versionCode 单调门禁 + 写后自检）
    ▼
发布仓 Abyss-Tools-Release
    ├─ GitHub Release v<版本> + APK 资产（sha256 / size 入清单）
    ├─ version.json   ← 客户端「检查更新」固定读取点
    ├─ patches.json   ← latestTag 滚动（vcdiff 二进制增量 v0.2 接入）
    └─ changelog.json ← 旧 latest 下沉 history，新版本置顶
    ▼
App UpdateChecker（比对 SemVer）→ 增量命中 / 整包
    → 设置页「下载并安装」：下载（FetchHostPolicy 域白名单）→
      SHA-256 强制校验（摘要缺失即拒）→ PackageInstaller 安装
      （同签名门禁 + 发布者指纹硬校验；未知来源确认页自动转发）
```

数据层补丁（abyss-patch-v1，插件 manifest / 技能目录）**已可用**：
下载 → SHA-256 → 备份 → 应用 → 失败回滚。APK 二进制增量（vcdiff 合成 +
覆盖安装）属 v0.2 发布流水线。详见 [UPDATE_CHANNEL.md](UPDATE_CHANNEL.md)。

## 插件体系

- 中心索引：`Abyss-Plugins/plugins.json`（3 个引擎级插件聚合，v0.8.0 大内置化后：
  应用构建器 / 病毒拦截 / 虚拟机；`sync-index.yml` 每日 / 手动自动聚合，APK 资产自动探测）
- 插件仓约定：`plugin.json`（manifest）+ `patches/index.json`（补丁通道）
  + `tools/validate_plugin.py`（CI 门禁）+ `patch-release.yml`
  （数据补丁发布流水线：make_patch → publish_patch → 三处一致性门禁）
- 分界纪律（v0.2 定型，v0.3 延续，**v0.8.0 大幅收紧**）：**只有「自带
  完整引擎」的能力才做成插件**（构建底座 / 病毒特征引擎 / 整机虚拟机）；
  其余一律 `ToolStatus.BUILT_IN` 内置主壳 —— v0.8.0 将原六个重型插件
  （终端 / Python / 沙箱 / 媒体 / 网络 / 备份）全部内置化落地真实功能页
  （:feature:terminal / :feature:scriptlab / :feature:sandbox /
  :feature:media / :feature:netaudit / :feature:backup），对应六仓归档
- Shizuku 授权声明：独立 APK 插件可在 manifest 声明 `shizuku.ownAuth`（per-app 授权，不继承宿主）—— 插件详情页展示独立授权引导
- 宿主契约：`PluginContract.HOST_API_VERSION`（只增不减），
  `filesDir/plugins/<id>/plugin.json` 为安装态单一事实源
- 安装流水线（v0.1.1 起全链路真实；v0.4 收口一条龙）：
  - APK 形态：`installApkPlugin` 一条龙（下载 → SHA-256 校验 →
    `PackageInstallerApkGateway`（同签名门禁 + 未知来源确认页转发 +
    结果广播等待 + 超时 abandon）→ **版本账本同步**：安装成功后
    manifest 登记对齐索引版本，补丁通道的 from 比对不再算错）
  - 数据形态：`installDataPlugin`（id 形态 / SemVer / 宿主下限校验 →
    manifest 原子落盘）→ 版本账本（installedVersion）→ 补丁应用闭环
    （updatablePatch 选链 → applyDataPatch）→ 卸载清目录（APK 形态
    附带系统卸载引导：缓存 APK 解析包名 → 直达系统卸载页）
  - 插件中心「一键检查更新」：并发拉取全部已装插件的补丁通道，
    可更新徽章 + 摘要（v0.4）

详见 [PLUGIN_SDK.md](PLUGIN_SDK.md)。

## 安全基线

- targetSdk 钉 28（终端引擎 SELinux 红线，Termux 同款决策；
  `lint { disable += "ExpiredTargetSdkVersion" }` —— 侧载分发渠道语义不适用）
- 插件资产 fail-closed：签名 / 校验 / 路径穿越三重门 + 运行时
  ChannelPolicy 白名单（索引 / 补丁条目逐条校验，不合规整条拒收）
- 发布信任链（v0.6.0）：签名材料出库入 secrets；宿主自更新发布者指纹
  硬校验（PublisherSignatures）；更新链 fail-closed（摘要强制 +
  FetchHostPolicy 四域白名单 + 重定向落点复检 + 体量上限）；补丁引擎
  zip bomb 三重上限 + 备份区外置。详见 [SECURITY.md](SECURITY.md)
- Shizuku 通道：免引用参数统一经 requireSafeToken 白名单门禁，
  自由文本 shellQuote 转义；网关并发读管道 + 1MB 单流上限 + 30s 看门狗
- 免责声明内置 App（设置 → 许可与免责声明）+ 仓库 LICENSE（UGRL）
- R8 混淆：v0.2 发布流水线接入（框架期 minify 关闭，CI 面最小）
