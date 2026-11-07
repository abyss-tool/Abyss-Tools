<div align="center">

# 深渊工具 Abyss Tools

### 🕳 面向极客的 Android 系统级超级工具箱 —— 液态玻璃 M3 主壳 · 轻功能内置（全部真实可用）· 七大重型插件 · 伪装四件套 · 补丁热更新 · Android 15/16 适配

**A system-level geek toolbox for Android — liquid glass M3 shell · built-in tools (incl. disguise kit) + 7 heavyweight plugins · patch updates.**

[![CI](https://github.com/abyss-tool/Abyss-Tools/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/abyss-tool/Abyss-Tools/actions/workflows/ci.yml)
![Kotlin](https://img.shields.io/badge/Kotlin-2.3.21-7F52FF?logo=kotlin&logoColor=white)
![AGP](https://img.shields.io/badge/AGP-8.13.2-3DDC84?logo=android&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack_Compose-BOM_2026.02.00-4285F4?logo=jetpackcompose&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-8.14-02303A?logo=gradle&logoColor=white)
![JDK](https://img.shields.io/badge/JDK-17-orange?logo=openjdk&logoColor=white)

</div>

---

## 这是什么

深渊工具是一个「主壳轻、能力重」的 Android 超级工具箱框架。**v0.2 起承载形态定型为三分法，v0.3 伪装四件套落地，v0.4 内置工具全部真实功能页：**

- **轻功能一律内置且全部真实可用**（v0.4 占位页时代结束）：应用管理器（冻结/隐藏/提取 APK）/ Shizuku 工具箱（appops·settings·dumpsys 面板）/ 任务蓝图（JSON 步骤链引擎 + 定时调度）/ 自动化编排（精确闹钟 + 开机恢复）/ 灵动岛悬浮球（overlay + 真实电量）/ 动态壁纸（液态玻璃光斑引擎）/ 二维码工坊（ZXing 生成 + 相册解析）+ **伪装四件套 —— 机型 / 电量 / 信号 / 定位（v0.3，Shizuku 免 Root 实时生效）**，全部直接写进主仓库（`ToolStatus.BUILT_IN`），随版本更新获得，零安装成本；
- **只有「重」的能力才做成插件**：终端 / Python / 沙箱双开 / 媒体处理 / 网络守护 / 备份迁移 / **应用构建器**（旗舰）—— 原生工具链或独立进程级能力，独立仓库、独立 CI、独立补丁通道，部分插件可声明**独立 Shizuku 授权**；
- **更新体系**采用双仓库模式：开发仓（本仓库）→ 发布仓（[Abyss-Tools-Release](https://github.com/abyss-tool/Abyss-Tools-Release)，`version.json` / `patches.json` / `changelog.json`）→ 客户端增量补丁。

> 当前版本 **v0.4.0**：七个内置工具落地真实功能页（应用管理 / Shizuku 面板 / 任务蓝图 / 自动化 / 灵动岛 / 壁纸 / 二维码）；插件 APK 安装一条龙收口（安装成功即同步版本账本）+ 插件中心一键检查更新 + APK 本体卸载引导；Android 15/16 适配矩阵落地（16KB / RECEIVER 加固，见 [docs/ANDROID_COMPAT.md](docs/ANDROID_COMPAT.md)）。

## 主界面框架

```
┌────────────────────────────────────────────┐
│  首启新手引导（v0.3）                        │  免责声明 → Shizuku 授权 →
│  完成前不进主界面                             │  安装未知应用 → 可选权限
├────────────────────────────────────────────┤
│  仪表盘 Dashboard                           │  状态总览 · 补丁更新卡（液态玻璃）
│  ────────────────────────────────────────  │
│  导航 Navigation（工具分类目录）              │  插件永远排第一分类
│    ┌ 插件 ──→ 插件中心 ──→ 具体插件详情 ──→ 安装 AP 流水线
│    ├ 系统工具 / 终端与脚本 / 沙箱与多开       │
│    ├ 伪装与隐私（四件套已内置） / 媒体 / 自动化│
│    └ 桌面美化 / 网络守护 / 数据与备份        │
│  ────────────────────────────────────────  │
│  设置 Settings                               │  玻璃强度 · 更新渠道 · 权限复查 · 免责声明
│                                             │
│        ╭────────────────────────────╮       │
│        │  🌊 液态玻璃 Dark 底栏      │       │  blur + lens 真实折射背后内容
│        │  仪表盘 · 导航 · 设置        │       │  （Kyant0/AndroidLiquidGlass）
│        ╰────────────────────────────╯       │
└────────────────────────────────────────────┘
```

## 模块地图

| 模块 | 职责 |
| --- | --- |
| `:app` | 主壳：玻璃底栏三 tab 主界面、返回栈、手动 DI（ServiceRegistry） |
| `:vendor:backdrop` | 液态玻璃底层（[Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) vendored，Apache-2.0 + 参考仓热修） |
| `:core:designsystem` | M3 深渊色板 + 液态玻璃组件系统（GlassSurface / 玻璃底栏 / 通用组件） |
| `:core:common` | 纯 Kotlin：SemVer / 工具目录 / 渠道常量 / UiState |
| `:core:update` | 补丁更新引擎：version.json 客户端 + abyss-patch-v1 数据补丁（备份/回滚） |
| `:core:plugin` | 插件框架：中心索引 / 安装器 / 插件补丁通道 |
| `:core:shell` | Shizuku 执行层：adb shell 网关 + 指令编排（可单测）|
| `:plugin-sdk:plugin-api` | 插件契约（宿主 ↔ 插件共享 ABI） |
| `:feature:*` | dashboard / navhub / plugins / **disguise（伪装四件套）** / **onboarding（新手引导）** / settings / **appmgr（应用管理器）** / **shizukukit（Shizuku 工具箱）** / **qrcraft（二维码工坊）** / **blueprint（任务蓝图 + 自动化）** / **island（灵动岛悬浮球）** / **wallpaper（动态壁纸）** |

详见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 仓库矩阵（双仓库 + 插件仓库群）

| 仓库 | 角色 |
| --- | --- |
| [abyss-tool/Abyss-Tools](https://github.com/abyss-tool/Abyss-Tools) | 开发仓库：源码 + CI 门禁 + **内置工具实现**（本仓库） |
| [abyss-tool/Abyss-Tools-Release](https://github.com/abyss-tool/Abyss-Tools-Release) | 发布仓库：version.json / patches.json / changelog.json + Releases 资产 |
| [abyss-tool/Abyss-Plugins](https://github.com/abyss-tool/Abyss-Plugins) | 插件中心：plugins.json 索引 + 插件规范 |
| [abyss-tool/abyss-plugin-*](https://github.com/abyss-tool/Abyss-Plugins) × 7 | 七个重型插件仓库（终端 / Python / 沙箱 / 媒体 / 网络 / 备份 / **应用构建器**） |

> v0.2 内置化重构：`abyss-plugin-{app-manager, shizuku-kit, automation, island, wallpaper, qr-craft}` 六仓已归档；**v0.3 伪装内置化：`abyss-plugin-disguise` 亦归档** —— 能力以伪装四件套形态并入主壳（dumpsys / svc / appops / 设备名 + 档案库，全部 Shizuku 免 Root），点击导航页「伪装与隐私」分类直达功能页。

## 构建（不在本地编译）

本仓库**不做本地编译验证**：全部构建走 GitHub Actions（`ubuntu-latest` 自带 Android SDK，Gradle 8.14 wrapper 自动拉取）。

```bash
git clone https://github.com/abyss-tool/Abyss-Tools.git
# 本地跑只需 JDK 17 + Android SDK（Android Studio 自带）：
./gradlew assembleDebug
```

CI 内容（[.github/workflows/ci.yml](.github/workflows/ci.yml)）：
`assembleDebug` + `assembleRelease`（入库 keystore 恒定签名 —— CI 绿即代表可发布）→ `test`（纯 JVM 单测：SemVer / 补丁应用 / 更新检查 / changelog / 插件安装链）→ `validate_manifests.py`（通道清单 schema / 版本三处同步 / 脚本语法门禁）→ 双 APK artifact。

## 发布与更新通道（全自动）

```
tag v* ──→ release.yml ──→ assembleRelease（入库 keystore 签名）
                              │
                              ▼ tools/publish_release.py（RELEASE_TOKEN）
              Abyss-Tools-Release：GitHub Release + APK 资产
              version.json / patches.json / changelog.json 同步滚动
                              │
                              ▼ 客户端（安装包内的「检查补丁更新」）
              发现新版本 → 下载 → SHA-256 校验 → PackageInstaller 安装
```

- **升版纪律**：`app/build.gradle.kts` 与 `core/common/.../AppInfo.kt` 三处同步（CI 门禁校验），提交后打 `v*` tag 推送即发布；发布要点写在 `docs/release-notes/v<版本>.md`（一行一条）。
- **签名恒定**：`keystore/abyss-release.jks` 入库（GitHub Releases 侧载分发的标准做法，Termux / F-Droid 同款），补丁覆盖安装无签名冲突；`ABYSS_KEYSTORE_*` 环境变量可覆盖。
- **secret**：仓库 secret `RELEASE_TOKEN`（PAT，发布仓写入凭据）。
- **插件侧同构**：每个 `abyss-plugin-*` 仓的 `patch-release.yml` 发布 abyss-patch-v1 数据补丁；`Abyss-Plugins` 的 `sync-index.yml` 每日 / 手动聚合 `plugins.json`。

## 路线图（对齐总体 SRS §6）

- [x] **Phase 1a 框架成型**（v0.1.0）：三 tab 主界面 / 分类导航 / 插件框架 / 补丁更新引擎
- [x] **框架闭环完善**（v0.1.1）：真实 APK 安装网关 / 自更新流水线 / 插件数据安装与补丁应用闭环 / 全仓发布工作流
- [x] **内置化重构**（v0.2.0）：七个轻量工具并入主壳 / 应用构建器登记插件中心 / 插件 Shizuku 独立授权契约
- [x] **伪装四件套内置 + 新手引导**（v0.3.0）：电量（dumpsys）/ 定位（Fake GPS 官方通道）/ 信号（svc / cmd connectivity）/ 机型（设备名 + 档案库）四引擎落地；首启权限引导页
- [x] **内置七工具真实功能页 + 插件链路收口 + Android 15/16 适配**（v0.4.0）：应用管理器（冻结/隐藏/提取 APK/强停）/ Shizuku 工具箱（appops·settings·dumpsys 面板）/ 任务蓝图（JSON 步骤链引擎 + 定时调度 + 开机恢复）/ 自动化编排 / 灵动岛悬浮球（overlay + 真实电量）/ 动态壁纸（液态玻璃光斑引擎）/ 二维码工坊（ZXing 生成 + Photo Picker 解析）；`installApkPlugin` 一条龙（安装成功即同步版本账本）+ 插件中心一键检查更新 + APK 本体卸载引导；Android 15/16 适配矩阵（16KB / RECEIVER 加固 / docs/ANDROID_COMPAT.md）
- [ ] **Phase 1b 终端成型**：伪终端渲染内核 + Shizuku 首条指令（`abyss-plugin-terminal`）
- [ ] **Phase 2 任务系统与插件内核**：任务蓝图调用链 + DexClassLoader 插件加载
- [ ] **Phase 3 硬核功能接入**：沙箱双开（机型档案 Hook 层消费方）/ Python 引擎
- [ ] **Phase 4 完善与结项**：Web 控制台 / 设备管理器 / 图标伪装

## 许可与免责声明

本软件为受限许可（UGRL v1.0）软件：仅供个人学习与安全研究，禁止再分发与商业使用。内置系统级能力（Shell 执行 / 沙箱虚拟化 / 设备伪装），滥用可能违反你所在司法辖区的法律，风险由使用者独自承担。完整条款见 [LICENSE](LICENSE)，App 内亦内置同款声明。
