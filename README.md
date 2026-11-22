<div align="center">

# 深渊工具 Abyss Tools

### 🕳 面向极客的 Android 系统级超级工具箱 —— 液态玻璃 M3 · 大内置化（仅三引擎级插件）· 隐私与管控四面板 · 组件银河四桌面组件 · 补丁热更新 · Android 15/16 适配

**A system-level geek toolbox for Android — liquid glass M3 · mass built-in-ization (3 engine-grade plugins only) · privacy & control panels · 4-widget "gravity family" home screen · patch updates.**

[![CI](https://github.com/abyss-tool/Abyss-Tools/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/abyss-tool/Abyss-Tools/actions/workflows/ci.yml)
![Kotlin](https://img.shields.io/badge/Kotlin-2.3.21-7F52FF?logo=kotlin&logoColor=white)
![AGP](https://img.shields.io/badge/AGP-8.13.2-3DDC84?logo=android&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack_Compose-BOM_2026.02.00-4285F4?logo=jetpackcompose&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-8.14-02303A?logo=gradle&logoColor=white)
![JDK](https://img.shields.io/badge/JDK-17-orange?logo=openjdk&logoColor=white)

</div>

---

## 这是什么

深渊工具是一个「主壳轻、能力重」的 Android 超级工具箱框架。**v0.2 起承载形态定型为三分法，v0.3 伪装四件套落地，v0.4 内置工具全部真实功能页，v0.8.0 大内置化 + M3 全面进化，v0.9.0「引力场」组件银河 + 隐私与管控：**

- **除构建器外全部内置且真实可用**（v0.8.0 大内置化）：应用管理器 / Shizuku 工具箱 / 任务蓝图（v0.9.0 双模式调度：间隔循环 + 每日定时 TimePicker）/ 自动化编排 / 灵动岛悬浮球 / 动态壁纸 / 二维码工坊 / 伪装四件套（机型·电量·信号·定位）+ **原六个重型插件全部并入主壳** —— 终端（Shizuku adb shell 真实执行）/ 脚本实验室（Shell+Python）/ 隔离空间（多用户双开）/ 媒体实验室（MediaMuxer 无重编码）/ 流量审计（netstats 画像）/ 备份迁移（APK 库 + zip 引擎），外加**系统控制中心**（v0.9.0 四域：显示 / 电源 / 授权总管 / **隐私与管控** —— 权限审计 / 敏感操作记录 / Doze 白名单 / 流量策略）与**组件工坊**（v0.9.0 组件银河：黑洞时钟 + 引力电池 + 星轨日历 + 深渊引力仪）；
- **只有「自带完整引擎」的能力才做成插件**（v0.8.0 分界收紧）：应用构建器（旗舰）/ 病毒拦截 / 虚拟机 —— 独立仓库、独立 CI、独立补丁通道，可声明**独立 Shizuku 授权**；
- **M3 全面落地**：十五级字阶 + 形状阶梯 + 动态取色（Material You，Android 12+）+ SearchBar / TimePicker / PullToRefresh / InputChip / Tooltip / SegmentedButton / ModalBottomSheet / ListItem / Badge 组件体系真实应用；
- **更新体系**采用双仓库模式：开发仓（本仓库）→ 发布仓（[Abyss-Tools-Release](https://github.com/abyss-tool/Abyss-Tools-Release)，`version.json` / `patches.json` / `changelog.json`）→ 客户端增量补丁。

> 当前版本 **v0.9.10 / versionCode 33**（`AppInfo.kt` 单一事实源）：v0.9.0「引力场」（组件银河 / 隐私与管控 / 每日定时调度 / M3 组件补全）→ v0.9.6 **规格说明书全集落库**（207 份规格入 `docs/abyss-specs/`）→ v0.9.7 白色液态玻璃导航栏 → v0.9.9 **N-SEC-001 客户端清单验签接线 + N-EXP-003 诊断日志 + 工程门禁**（spec-lint / 发布说明门禁入 CI）→ v0.9.10 **N-EXP-006 插件权限透明化面板**。M1（v0.10）进行中：通道清单 detached 签名闭环（发布侧）+ RELEASE_TOKEN 最小权限 + 三插件首个可用切片 —— 见 [SPEC-INDEX](docs/abyss-specs/SPEC-INDEX.md) 与 `docs/abyss-specs/深渊工具_整体增强方案.md`。

## 主界面框架

```
┌────────────────────────────────────────────┐
│  首启新手引导（v0.8.5 七步）                  │  免责声明 → 认识 Shizuku（变体） →
│  完成前不进主界面                             │  启动授权 → 安装未知应用 → 可选权限 → 功能导览
├────────────────────────────────────────────┤
│  仪表盘 Dashboard                           │  状态总览 · 补丁更新卡（液态玻璃）
│  ────────────────────────────────────────  │
│  功能 Features（工具分类目录）                │  引擎级插件由「插件」独立 tab 承载
│    ┌ 系统工具 / 终端与脚本 / 沙箱与多开       │
│    ├ 伪装与隐私（四件套已内置） / 媒体 / 自动化│
│    └ 桌面美化 / 网络守护 / 数据与备份        │
│  ────────────────────────────────────────  │
│  插件 Plugins（独立 tab）                    │  插件中心 ──→ 具体插件详情 ──→ 安装 AP 流水线
│  ────────────────────────────────────────  │
│  设置 Settings                               │  分区导航：权限 / 界面（图标三选一·指示器·玻璃）/ 更新 / 关于
│                                             │
│        ╭────────────────────────────╮       │
│        │  🌊 液态玻璃 Dark 底栏      │       │  blur + lens 真实折射背后内容
│        │  仪表盘 · 功能 · 插件 · 设置 │       │  （Kyant0/AndroidLiquidGlass）
│        ╰────────────────────────────╯       │
└────────────────────────────────────────────┘
```

## 模块地图

| 模块 | 职责 |
| --- | --- |
| `:app` | 主壳：玻璃底栏四 tab 主界面（仪表盘 / 功能 / 插件 / 设置）、返回栈、手动 DI（ServiceRegistry） |
| `:vendor:backdrop` | 液态玻璃底层（[Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) vendored，Apache-2.0 + 参考仓热修） |
| `:core:designsystem` | M3 深渊色板 + 液态玻璃组件系统（GlassSurface / 玻璃底栏 / 通用组件） |
| `:core:common` | 纯 Kotlin：SemVer / 工具目录 / 渠道常量 / UiState |
| `:core:update` | 补丁更新引擎：version.json 客户端 + abyss-patch-v1 数据补丁（备份/回滚） |
| `:core:plugin` | 插件框架：中心索引 / 安装器 / 插件补丁通道 |
| `:core:shell` | Shizuku 执行层：adb shell 网关 + 指令编排（可单测）|
| `:plugin-sdk:plugin-api` | 插件契约（宿主 ↔ 插件共享 ABI） |
| `:feature:*` | dashboard / navhub / plugins / **disguise（伪装四件套）** / **onboarding（新手引导）** / settings / **appmgr（应用管理器）** / **shizukukit（Shizuku 工具箱）** / **qrcraft（二维码工坊）** / **blueprint（任务蓝图 + 自动化，v0.9 双模式调度）** / **island（灵动岛悬浮球）** / **wallpaper（动态壁纸）** / **terminal（内置终端）** / **scriptlab（脚本实验室）** / **sandbox（隔离空间）** / **media（媒体实验室）** / **netaudit（流量审计）** / **backup（备份迁移）** / **syspower（系统控制中心，v0.9 四域 + 隐私与管控）** / **widgets（组件工坊，v0.9 组件银河四成员）** |

详见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 仓库矩阵（双仓库 + 插件仓库群）

| 仓库 | 角色 |
| --- | --- |
| [abyss-tool/Abyss-Tools](https://github.com/abyss-tool/Abyss-Tools) | 开发仓库：源码 + CI 门禁 + **内置工具实现**（本仓库） |
| [abyss-tool/Abyss-Tools-Release](https://github.com/abyss-tool/Abyss-Tools-Release) | 发布仓库：version.json / patches.json / changelog.json + Releases 资产 |
| [abyss-tool/Abyss-Plugins](https://github.com/abyss-tool/Abyss-Plugins) | 插件中心：plugins.json 索引 + 插件规范 |
| [abyss-tool/abyss-plugin-*](https://github.com/abyss-tool/Abyss-Plugins) × 3 | 三个引擎级插件仓库（**应用构建器** / **病毒拦截（新）** / **虚拟机（新）**） |

> v0.2 内置化重构：`abyss-plugin-{app-manager, shizuku-kit, automation, island, wallpaper, qr-craft}` 六仓已归档；v0.3 伪装内置化：`abyss-plugin-disguise` 亦归档；**v0.8.0 大内置化：`abyss-plugin-{terminal, python-lab, sandbox, media-lab, network-guard, backup}` 六仓全部归档** —— 终端 / 脚本 / 隔离空间 / 媒体 / 流量审计 / 备份迁移以真实功能页并入主壳（Shizuku / 多用户 / MediaMuxer / netstats / zip 引擎），插件位次让给真正的引擎级能力（病毒拦截 / 虚拟机）。

## 构建（不在本地编译）

本仓库**不做本地编译验证**：全部构建走 GitHub Actions（`ubuntu-latest` 自带 Android SDK，Gradle 8.14 wrapper 自动拉取）。

```bash
git clone https://github.com/abyss-tool/Abyss-Tools.git
# 本地跑只需 JDK 17 + Android SDK（Android Studio 自带）：
./gradlew assembleDebug
```

CI 内容（[.github/workflows/ci.yml](.github/workflows/ci.yml)）：
`assembleDebug` + `assembleRelease`（签名材料经 secrets 注入，指纹恒定 + apksigner 实测校验 —— CI 绿即代表可发布）→ `test`（纯 JVM 单测）→ `validate_manifests.py`（通道清单 schema / 版本单一事实源 / 脚本语法门禁）→ 双 APK artifact。

## 发布与更新通道（全自动）

```
tag v* ──→ release.yml ──→ assembleRelease（secrets 注入签名材料）
                              │  ↓ 发布前强制门禁：apksigner 指纹 == PublisherSignatures 常量
                              ▼ tools/publish_release.py（RELEASE_TOKEN）
              Abyss-Tools-Release：GitHub Release + APK 资产
              version.json / patches.json / changelog.json 同步滚动
              （versionCode 单调门禁 + 写后自检回读）
                              │
                              ▼ 客户端（安装包内的「检查补丁更新」）
              发现新版本 → 下载 → SHA-256 强制校验 → 域白名单 → PackageInstaller 安装
```

- **升版纪律**：版本号只住在 `core/common/.../AppInfo.kt`（v0.6.0 起单一事实源，`build.gradle.kts` 经推导），提交后打 `v*` tag 推送即发布；发布要点写在 `docs/release-notes/v<版本>.md`（一行一条）。
- **签名信任链**：v0.6.0 起 keystore / 口令**出库**（经 GitHub Actions secrets `ABYSS_KEYSTORE_B64` 等注入；历史入库副本视为公开的取舍与轮换路径见 [docs/SECURITY.md](docs/SECURITY.md)）；宿主自更新在「同签名门禁」之上额外硬校验发布者指纹常量。
- **secret**：`ABYSS_KEYSTORE_B64` / `ABYSS_KEYSTORE_PASSWORD` / `ABYSS_KEY_ALIAS` / `ABYSS_KEY_PASSWORD`（签名）+ `RELEASE_TOKEN`（发布仓写入，建议 fine-grained PAT 只授目标仓 Contents 写权限）。
- **插件侧同构**：每个 `abyss-plugin-*` 仓的 `patch-release.yml` 发布 abyss-patch-v1 数据补丁；`Abyss-Plugins` 的 `sync-index.yml` 每日 / 手动聚合 `plugins.json`（运行时另有 ChannelPolicy 白名单兑底）。

## 路线图（对齐总体 SRS §6）

- [x] **Phase 1a 框架成型**（v0.1.0）：三 tab 主界面（v0.9.13 起四 tab，插件独立入口）/ 分类导航 / 插件框架 / 补丁更新引擎
- [x] **框架闭环完善**（v0.1.1）：真实 APK 安装网关 / 自更新流水线 / 插件数据安装与补丁应用闭环 / 全仓发布工作流
- [x] **内置化重构**（v0.2.0）：七个轻量工具并入主壳 / 应用构建器登记插件中心 / 插件 Shizuku 独立授权契约
- [x] **伪装四件套内置 + 新手引导**（v0.3.0）：电量（dumpsys）/ 定位（Fake GPS 官方通道）/ 信号（svc / cmd connectivity）/ 机型（设备名 + 档案库）四引擎落地；首启权限引导页
- [x] **内置七工具真实功能页 + 插件链路收口 + Android 15/16 适配**（v0.4.0）：应用管理器（冻结/隐藏/提取 APK/强停）/ Shizuku 工具箱（appops·settings·dumpsys 面板）/ 任务蓝图（JSON 步骤链引擎 + 定时调度 + 开机恢复）/ 自动化编排 / 灵动岛悬浮球（overlay + 真实电量）/ 动态壁纸（液态玻璃光斑引擎）/ 二维码工坊（ZXing 生成 + Photo Picker 解析）；`installApkPlugin` 一条龙（安装成功即同步版本账本）+ 插件中心一键检查更新 + APK 本体卸载引导；Android 15/16 适配矩阵（16KB / RECEIVER 加固 / docs/ANDROID_COMPAT.md）
- [x] **伪装四件套全面深化**（v0.5.0）：机型伪装三级程度（L1 设备名 / L2 标准档案 / L3 深度全字段 + 蓝牙名）+ 档案库 10 → 26 款（v2 全量 Build 字段）；电量伪装持续保持守护（防 ROM 回写，显示恒为伪装值 + 一键恢复）；定位伪装移动模拟（静止/微漂移/步行/骑行/驾车 —— 轨迹 + 速度 + 航向 + 精度自然化）+ 漂移幅度三档 + 坐标收藏夹 + 26 城市预设；签名换用 meng411722 身份
- [x] **信号显示伪装 + 发布信任链 P0 治理**（v0.6.0）：状态栏覆盖层（5G-A / 5G / 4G + 信号格 1-4 + Wi-Fi 图标伪造，无卡可用，Shizuku 一键授权，停止即恢复真实显示）；签名材料出库入 secrets + 发布者指纹硬校验 + versionCode 单调门禁；更新链 fail-closed（摘要强制 + GitHub 四域白名单）+ 插件索引运行时白名单（ChannelPolicy）+ 补丁引擎 zip bomb 防线；Shizuku 网关并发读管道 + 1MB 上限 + 30s 真超时；版本单一事实源（AppInfo）；返回栈持久化
- [x] **大内置化 + M3 全面进化**（v0.8.1「黑洞」）：六重型插件内置化（终端 / 脚本实验室 / 隔离空间 / 媒体实验室 / 流量审计 / 备份迁移，全部真实功能页）；插件中心收缩为三大引擎级插件（构建器 / 病毒拦截 / 虚拟机）；**系统控制中心**（授权总管 AppOps + pm grant/revoke + 显示 / 电源 / Doze）；**组件工坊**（黑洞动态时钟桌面组件 2×2 / 4×2 + 整分自续期重绘 + Compose 实时预览）；M3 完整规范（十五级字阶 / 形状阶梯 / 动态取色 Material You）
- [x] **组件银河 + 隐私与管控 + M3 补全**（v0.9.0「引力场」）：组件工坊扩为四成员银河（黑洞时钟 + **引力电池**（吸积环电量刻度 + 充电喷流）+ **星轨日历**（行星公转一年）+ **深渊引力仪**（内存·存储双环），AbyssWidgetFamily 家族注册表一跳喂饱全部）；系统控制中心新增**隐私与管控**四面板（权限审计批量回收 / appops query-op 敏感操作记录 / Doze 白名单 / netpolicy 流量策略）+ 标签化四页签；任务蓝图**双模式调度**（每日定时 M3 TimePicker + SchedulePlanner 纯排程）；M3 组件真实落地（SearchBar / PullToRefreshBox / InputChip / TooltipBox / SnackbarHost）
- [ ] **Phase 1b 终端深化**：伪终端渲染内核（VT/ANSI）+ 本地 bootstrap
- [ ] **Phase 2 任务系统与插件内核**：任务蓝图调用链 + DexClassLoader 插件加载
- [ ] **Phase 3 硬核功能接入**：沙箱 Hook 层（机型档案消费方）/ Python 完整引擎
- [ ] **Phase 4 完善与结项**：Web 控制台 / 设备管理器 / 图标伪装

## 许可与免责声明

本软件为受限许可（UGRL v1.0）软件：仅供个人学习与安全研究，禁止再分发与商业使用。内置系统级能力（Shell 执行 / 沙箱虚拟化 / 设备伪装），滥用可能违反你所在司法辖区的法律，风险由使用者独自承担。完整条款见 [LICENSE](LICENSE)，App 内亦内置同款声明。
