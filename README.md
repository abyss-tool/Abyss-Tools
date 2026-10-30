<div align="center">

# 深渊工具 Abyss Tools

### 🕳 面向极客的 Android 系统级超级工具箱 —— 液态玻璃 M3 主壳 · 十三大插件仓库 · 补丁热更新

**A system-level geek toolbox for Android — liquid glass M3 shell · 13 plugin repos · patch updates.**

[![CI](https://github.com/Ultra-Guru/Abyss-Tools/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Ultra-Guru/Abyss-Tools/actions/workflows/ci.yml)
![Kotlin](https://img.shields.io/badge/Kotlin-2.3.21-7F52FF?logo=kotlin&logoColor=white)
![AGP](https://img.shields.io/badge/AGP-8.13.2-3DDC84?logo=android&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack_Compose-BOM_2026.02.00-4285F4?logo=jetpackcompose&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-8.14-02303A?logo=gradle&logoColor=white)
![JDK](https://img.shields.io/badge/JDK-17-orange?logo=openjdk&logoColor=white)

</div>

---

## 这是什么

深渊工具是一个「主壳轻、能力重」的 Android 超级工具箱框架：

- **主壳（本仓库）** 只做三件事：液态玻璃 Material 3 界面、工具目录导航、插件与更新的框架底座；
- **全部硬核能力**（终端 / Python / 沙箱双开 / 设备伪装 / 媒体处理 / 自动化…）以**独立插件仓库**的形式存在 —— 十三个大插件，每个独立仓库、独立 CI、独立补丁通道；
- **更新体系**采用双仓库模式：开发仓（本仓库）→ 发布仓（[Abyss-Tools-Release](https://github.com/Ultra-Guru/Abyss-Tools-Release)，`version.json` / `patches.json` / `changelog.json`）→ 客户端增量补丁。

> 当前版本 **v0.1.0 框架预览**：主界面 / 分类导航 / 插件框架 / 补丁更新引擎已成型；插件本体与 APK 资产按路线图自 v0.2 起发布。

## 主界面框架

```
┌────────────────────────────────────────────┐
│  仪表盘 Dashboard                           │  状态总览 · 补丁更新卡（液态玻璃）
│  ────────────────────────────────────────  │
│  导航 Navigation（工具分类目录）              │  插件永远排第一分类
│    ┌ 插件 ──→ 插件中心 ──→ 具体插件详情 ──→ 安装 AP 流水线
│    ├ 系统工具 / 终端与脚本 / 沙箱与多开       │
│    ├ 伪装与隐私 / 媒体工具 / 自动化          │
│    └ 桌面美化 / 网络守护 / 数据与备份        │
│  ────────────────────────────────────────  │
│  设置 Settings                               │  玻璃强度 · 更新渠道 · 免责声明
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
| `:plugin-sdk:plugin-api` | 插件契约（宿主 ↔ 插件共享 ABI） |
| `:feature:*` | dashboard / navhub / plugins / settings 四个界面模块 |

详见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 仓库矩阵（双仓库 + 插件仓库群）

| 仓库 | 角色 |
| --- | --- |
| [Ultra-Guru/Abyss-Tools](https://github.com/Ultra-Guru/Abyss-Tools) | 开发仓库：源码 + CI 门禁（本仓库） |
| [Ultra-Guru/Abyss-Tools-Release](https://github.com/Ultra-Guru/Abyss-Tools-Release) | 发布仓库：version.json / patches.json / changelog.json + Releases 资产 |
| [Ultra-Guru/Abyss-Plugins](https://github.com/Ultra-Guru/Abyss-Plugins) | 插件中心：plugins.json 索引 + 插件规范 |
| [Ultra-Guru/abyss-plugin-*](https://github.com/Ultra-Guru/Abyss-Plugins) × 13 | 十三个大插件仓库（终端 / Python / 沙箱 / 伪装 / 媒体 / 自动化 / 灵动岛 / 壁纸 / 网络 / 二维码 / 备份 / Shizuku / 应用管理） |

## 构建（不在本地编译）

本仓库**不做本地编译验证**：全部构建走 GitHub Actions（`ubuntu-latest` 自带 Android SDK，Gradle 8.14 wrapper 自动拉取）。

```bash
git clone https://github.com/Ultra-Guru/Abyss-Tools.git
# 本地跑只需 JDK 17 + Android SDK（Android Studio 自带）：
./gradlew assembleDebug
```

CI 内容（[.github/workflows/ci.yml](.github/workflows/ci.yml)）：
`assembleDebug` → `test`（纯 JVM 单测：SemVer / 补丁应用 / 更新检查 / 插件索引）→ `validate_manifests.py`（通道清单 schema 门禁）→ APK artifact。

## 路线图（对齐总体 SRS §6）

- [x] **Phase 1a 框架成型**（v0.1.0）：三 tab 主界面 / 分类导航 / 插件框架 / 补丁更新引擎
- [ ] **Phase 1b 终端成型**：伪终端渲染内核 + Shizuku 首条指令（`abyss-plugin-terminal`）
- [ ] **Phase 2 任务系统与插件内核**：任务蓝图调用链 + DexClassLoader 插件加载
- [ ] **Phase 3 硬核功能接入**：沙箱双开 / Python 引擎 / 悬浮球与灵动岛
- [ ] **Phase 4 完善与结项**：Web 控制台 / 设备管理器 / 图标伪装

## 许可与免责声明

本软件为受限许可（UGRL v1.0）软件：仅供个人学习与安全研究，禁止再分发与商业使用。内置系统级能力（Shell 执行 / 沙箱虚拟化 / 设备伪装），滥用可能违反你所在司法辖区的法律，风险由使用者独自承担。完整条款见 [LICENSE](LICENSE)，App 内亦内置同款声明。
