> 深渊工具 Abyss Tools · 软件规格说明书（功能）  
> 状态：**已实现（v0.9.14）** ｜ 优先级：**P1** ｜ 里程碑：**M1** ｜ 承载形态：**主壳内置** ｜ 基线：**v0.9.13 / versionCode 36** ｜ 编制日期：**2026-11-22**

| 项 | 值 |
| --- | --- |
| 规格编号 | `FEAT-035` |
| 所属域 | 功能 |
| 承载形态 | 主壳内置 |
| 优先级 / 里程碑 | P1 / M1 |
| 标签 | 设置 · 信息架构 · 分区导航 |
| 关联规格 | `FEAT-033`（指示器样式迁移到界面区）、`FEAT-036`（应用图标切换入驻界面区）、`ARC-004`（增量修订）、`FEAT-005`（整体重构）、`N-ENG-008` |
| 建议落盘路径 | `docs/abyss-specs/03-功能模块/FEAT-035-设置页分区重构.md` |

---

## 1. 目标与范围

设置页从「单页六区平铺」重构为**分区导航**（设计语言参考 Ultra-Guru/Android-Guru-Agent 设置中心 —— 用户自有项目）：主页四张分区入口卡（**权限 / 界面 / 更新 / 关于**），点击进入对应子页；子页 = DetailHeader + 原有内容重组。单一事实源与所有既有能力零删减。

| 分区 sectionId | UI 标题 | 图标键 | 内容（全部为既有能力重组，除 FEAT-036 新卡） |
| --- | --- | --- | --- |
| `permission` | 权限 | `security` | 权限与新手引导（OnboardingSectionCard：Shizuku / 安装未知应用 / 可选权限一站式复查 + 重跑引导） |
| `appearance` | 界面 | `wallpaper` | 玻璃强度滑杆 / 动态取色 / 滑动指示器样式（FEAT-033）/ **应用图标（FEAT-036 新增）** |
| `update` | 更新 | `update` | 补丁更新卡（检查 / 下载安装 / 更新日志）/ 插件索引与本地缓存（原「插件」区并入） |
| `about` | 关于 | `info`（新增键） | 版本 / 许可与免责声明 / 更新日志弹层入口 / 诊断（崩溃取证卡片） |

**范围内**：分区主页 + 4 个子页；`Screen.SettingsSection(sectionId)` 详情页路由（stateKey `settings:<id>`、token 前缀 `settings:`）；`AbyssRoot` 路由分支登记。
**范围外（硬边界）**：不迁移任何设置项语义（偏好键 / 行为零变化）；不引 ViewModel/Hilt/抽屉导航（Guru 的框架不搬，只取分区语言）；底栏几何与 glass 包零改动；`Screen.Settings` 顶层 tab 语义不变。

---

## 3. 功能需求

| 编号 | 需求描述 |
| --- | --- |
| FR-001 | 设置主页可见且仅有四张分区入口卡（权限 / 界面 / 更新 / 关于），卡片 = 图标 + 标题 + 内容摘要 + 「进入 ›」，玻璃卡风格与全仓一致。 |
| FR-002 | 点击入口卡 push `Screen.SettingsSection(sectionId)`：栈深 2、底栏隐藏（既有详情页语义）、`NavAction.PUSH` 转场；系统返回回设置主页。 |
| FR-003 | 子页 DetailHeader 返回箭头 + 分区标题；内容与重构前逐项一致（FR-005 硬底线）。 |
| FR-004 | 分区主页与子页遵守错峰入场纪律：标题在错峰 Box 外（v0.9.x「文字重叠」病灶防线）。 |
| FR-005 | **零回归底线**：全部既有设置能力（偏好键 / 开关 / 滑杆 / 对话框 / Snackbar 反馈）在子页中语义与行为逐字不变；不新增/不删除任何 SettingsRepository 键。 |
| FR-006 | `Screen.SettingsSection` 登记三处：`stateKey()`、`serialize()/restore()`（token `settings:<sectionId>`，未知 sectionId fail-closed 丢弃）、渲染分支；`tabIndex` detail 分支覆盖返回 -1。 |

## 6. 验收标准

| 编号 | 验收条件 |
| --- | --- |
| AC-001 | 主页四卡齐备、进入/返回栈语义正确；旋转 / 进程重建后子页原位恢复（token 恢复）。 |
| AC-002 | 四个子页内容与重构前等价（外观滑杆实时生效 / 更新流水线完整 / 权限重跑引导可达 / 关于许可弹窗正常）。 |
| AC-003 | `restore()` 收到未知 `settings:` token 丢弃不猜；`tabIndex` 对 SettingsSection 返回 -1。 |
| AC-004 | CI 全绿（assembleDebug/Release + test + 清单门禁）。 |

## 8. 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| 0.1 | 2026-11-22 | 创建；设计语言参考 Ultra-Guru/Android-Guru-Agent 设置中心（用户自有项目授权借鉴），实现贴合深渊工具手动 DI 轻壳。 |
