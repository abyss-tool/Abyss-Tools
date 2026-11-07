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

Android 15/16 适配立场（16KB / edge-to-edge / RECEIVER 加固 / FGS 等
逐项矩阵）见 [ANDROID_COMPAT.md](ANDROID_COMPAT.md)。

## 导航与返回栈

框架期不引 navigation-compose：`AbyssBackStack` 自维护
`mutableStateOf(List<Screen>)`，Crossfade 切页，BackHandler 手势返回。
底栏仅顶层 tab 显示（stack.size == 1），详情页沉浸全屏 —— 玻璃采样源
在内容根上，任何页面滚过玻璃件下方都会被真实采样。

v0.6.0 起返回栈可序列化（`rememberSaveable` + listSaver）：旋转 /
深浅色切换 / 进程重建后原位恢复；AppManagerDetail 只序列化包名键，
恢复后经 AppInfoProvider 异步重挂（查不到则诚实留在列表页）。

## 液态玻璃系统（三层能力）

| 档位 | 条件 | 渲染 |
| --- | --- | --- |
| Backdrop | 有采样源 + API 31+ | blur +（API 33+）AGSL lens 折射/色散 + 霜面 tint + 边缘受光 + 内阴影 |
| Frosted | 无源 或 API < 31 | 霜面渐变 + 边缘光（零 GPU 管线，诚实降级） |

纪律：`drawBackdrop` 的全部 lambda 记忆化（ModifierNodeElement 对
lambda 按身份比较 —— 重组期新建 lambda 会引发 draw 失效风暴）；效果
级 `runCatching`（OEM RenderEffect/AGSL 差异单项降级，进程存活优先）。

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

- 中心索引：`Abyss-Plugins/plugins.json`（7 个重型插件聚合，v0.3 伪装内置化后；
  `sync-index.yml` 每日 / 手动自动聚合，APK 资产自动探测）
- 插件仓约定：`plugin.json`（manifest）+ `patches/index.json`（补丁通道）
  + `tools/validate_plugin.py`（CI 门禁）+ `patch-release.yml`
  （数据补丁发布流水线：make_patch → publish_patch → 三处一致性门禁）
- 分界纪律（v0.2 定型，v0.3 延续）：**只有「重」的能力才做成插件**（原生工具链 / 独立进程 / 大型框架）；轻量功能（应用管理 / Shizuku 面板 / 任务蓝图 / 自动化 / 灵动岛 / 壁纸 / 二维码，及 v0.3 伪装四件套）一律 `ToolStatus.BUILT_IN` 内置主壳，随版本更新获得
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
