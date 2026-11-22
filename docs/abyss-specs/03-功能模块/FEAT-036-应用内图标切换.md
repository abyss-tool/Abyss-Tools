> 深渊工具 Abyss Tools · 软件规格说明书（功能）  
> 状态：**已实现（v0.9.14）** ｜ 优先级：**P1** ｜ 里程碑：**M1** ｜ 承载形态：**主壳内置** ｜ 基线：**v0.9.13 / versionCode 36** ｜ 编制日期：**2026-11-22**

| 项 | 值 |
| --- | --- |
| 规格编号 | `FEAT-036` |
| 所属域 | 功能 |
| 承载形态 | 主壳内置 |
| 优先级 / 里程碑 | P1 / M1 |
| 标签 | 应用图标 · activity-alias · 个性化 |
| 关联规格 | `FEAT-035`（界面分区承载入口）、`ARC-005`（昼夜背景共用）、`N-ENG-008` |
| 建议落盘路径 | `docs/abyss-specs/03-功能模块/FEAT-036-应用内图标切换.md` |

---

## 1. 目标与范围

三款像素风「深渊之眼」启动器图标（**碧眼** cyan 现状默认 / **绯眼** red / **隐眼** blue）用户可在 **设置 → 界面 → 应用图标** 内自由切换；昼/夜双背景资源机制（`@color/abyss_icon_background`）三款共用保留。

| 键 | 别名组件 | 前景资源 | 默认 |
| --- | --- | --- | --- |
| `cyan` | `.LauncherCyan` | `ic_launcher_foreground.png`（现状） | ✔ enabled |
| `red` | `.LauncherRed` | `ic_launcher_foreground_red.png`（新增） | disabled |
| `blue` | `.LauncherBlue` | `ic_launcher_foreground_blue.png`（新增） | disabled |

**范围内**：adaptive icon 资源 ×3（`mipmap-anydpi-v26/ic_launcher{,_red,_blue}.xml` 共用昼夜背景色）、`activity-alias` ×3（MainActivity 自身 LAUNCHER filter 移除，别名接管 —— 别名互斥启停，Activity 组件恒 enabled）、`LauncherIconGateway` 接口（core:common 纯 JVM）+ PackageManager 实现（:app ServiceRegistry 注入）、偏好键 `launcher_icon`、界面区切换 UI（三张前景预览卡）。
**范围外（硬边界）**：不改前景绘制管线（复用 v0.9.11 贴纸化参数）；不改 MainActivity 代码逻辑；不新增权限。

---

## 3. 功能需求

| 编号 | 需求描述 |
| --- | --- |
| FR-001 | 桌面图标三选一互斥：切换 = 目标别名 ENABLED + 其余（含当前）DISABLED（`DONT_KILL_APP`）；MainActivity 组件恒 enabled，永不禁用 Activity 本体。 |
| FR-002 | 偏好持久化 `abyss_settings` 键 `launcher_icon`（`"cyan"/"red"/"blue"` 字符串，未知值 fail-closed 回退 `cyan`）；**显示真值以 gateway 实际组件态为准**，偏好仅作 UI 缓存。 |
| FR-003 | `LauncherIconGateway`：`current(): LauncherIcon`（读组件态推导）+ `apply(icon): Result<Unit>`（互斥启停，失败人话错误）；接口在 core:common（零 Android 依赖），实现经 ServiceRegistry 注入。 |
| FR-004 | 三款图标昼夜切换行为一致（共用 `abyss_icon_background`）；前景统一 512×512、66dp 安全区、白贴纸描边（v0.9.11 管线同参）。 |
| FR-005 | 切换后 Snackbar 人话回执（含「桌面图标刷新可能延迟数秒，部分设备会重启应用」预期管理）；失败不写偏好、不崩。 |

## 6. 验收标准

| 编号 | 验收条件 |
| --- | --- |
| AC-001 | 三档互斥切换实测：任一时刻桌面只见一枚图标；切换后杀进程重启偏好保持；`adb shell dumpsys package` 中恰一个 alias enabled。 |
| AC-002 | 昼夜模式下三款图标背景均随系统切换（night 档深底）。 |
| AC-003 | 纯 JVM：`LauncherIcon.from(null/""/"bogus"/"cyan"/"red"/"blue")` 回退与命中语义正确。 |
| AC-004 | CI 全绿；`Abyss-Tools` 桌面预览（AAPT）无资源冲突（同名 png/xml 不共存）。 |

## 8. 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| 0.1 | 2026-11-22 | 创建；三款图标素材由用户提供（WeChat 原图），红/蓝前景按 v0.9.11 图标管线（去白底 + 白贴纸边 + 66dp 安全区）生成。 |
