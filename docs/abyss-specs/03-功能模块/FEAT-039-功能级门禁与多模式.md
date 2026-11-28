# FEAT-039 · 功能级门禁与多模式

> 深渊工具 Abyss Tools · 软件规格说明书（功能）  
> 状态：**已实现（v0.10.2）** ｜ 优先级：**P1** ｜ 里程碑：**M1** ｜ 承载形态：**主壳内置** ｜ 基线：**v0.10.1 / versionCode 47** ｜ 编制日期：**2026-11-28**

| 项 | 值 |
| --- | --- |
| 规格编号 | `FEAT-039` |
| 所属域 | 功能 |
| 承载形态 | 主壳内置 |
| 当前状态 | 已实现（v0.10.2） |
| 优先级 / 里程碑 | P1 / M1 |
| 标签 | 门禁 · 权限矩阵 · 多模式 · GatedToolCard · 受限进入 |
| 关联规格 | `ARC-011`（能力与档位模型）、`FEAT-038`（仪表盘档位）、`ARC-010`（工具目录）、`N-APP-003`、`N-DO-001`、`QA-008` |
| 建议落盘路径 | `docs/abyss-specs/03-功能模块/FEAT-039-功能级门禁与多模式.md` |

---

## 1. 目标与范围

把「每项功能最低可用权限」从口头约定变成数据契约：`ToolCatalog` 27 条工具逐条声明 `ToolAccess(required, modes)`，仪表盘快捷工具、功能页全量目录、工具详情页三处消费**同一份**快照与同一份矩阵，杜绝多处门禁不一致。

**范围内**：`ToolDef.access` 字段 + 27 条矩阵登记；`core:designsystem` 新增 `GatedToolCard` / `CapabilityGuardCard` / `ToolModesCard`；`DashboardScreen` 快捷工具、`NavHubScreen` 全量目录、`ToolScreen` 门禁说明页；`AbyssRoot` 路由层统一裁决（锁定 → 门禁说明页；受限模式 → 显式进入）。
**范围外**：各功能页内部的按钮级 enabled 逻辑（既有 `AppDetailScreen` / 四件套 `ShizukuGuardBanner` 保留）；执行前二次校验沿用既有网关。

---

## 2. 现状锚点（克隆研究实测，v0.10.1）

- `DashboardScreen.kt:207-221`：`items(count = 4)` + `listOf("system-control","terminal","media-lab","app-builder")` 双源，`onClick = { onOpenTool(tool.id) }` 零门禁；
- `NavHubScreen.kt:90-115`：全量 27 张 `ToolRow` 零门禁；
- `AbyssRoot.kt:568-575`：`else -> ToolScreen(...)` 仅承接无真实页面的工具；
- `ToolCatalog.tools` 无权限字段，`ToolCatalogTest` 无门禁断言。

---

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | `ToolAccess(required: Set<Capability>, modes: List<ToolMode>, guide: ToolGuide)` 挂到 `ToolDef`，默认 `ToolAccess()`（零权限）—— 既有构造零改动。 |
| FR-002 | **门禁判定唯一公式**：`unlocked = snapshot.allows(tool.access.required)`。档位标签**不得**参与放行；`INSTALL_UNKNOWN` / `OVERLAY` 作为独立能力参与超集判定但不升档。 |
| FR-003 | 锁定卡（`GatedToolCard`）：右侧渲染锁形图标 + 「需 {缺失能力短标签、…}」短文案（≤ 12 字），`contentDescription` = 「需 {最低档位}：{缺失能力}」；整卡 alpha 0.62。 |
| FR-004 | 锁定卡点击 = 进入**门禁说明页**（`ToolScreen` 门禁模式），不直接进功能页、不静默无响应。说明页顶部 `CapabilityGuardCard` 列缺失能力 + 「去授权」（按 `Capability.guidePriority` 取首个缺失）；中部 `ToolModesCard` 逐条列出该工具的模式（名称 / 门槛 / 当前 ✓ 或 ✗）。 |
| FR-005 | **受限模式进入**：当该工具存在任一模式 `modes.any { snapshot.allows(it.required) }` 时，说明页显示「以受限模式进入」按钮；点击 `backStack.replace(Screen.Tool(id, ungated = true))` 进入真实功能页（页面内既有守卫横幅继续兜底）。无可用模式时不显示按钮。 |
| FR-006 | `AbyssRoot` 路由裁决：`is Screen.Tool(id, ungated)` → `!ungated && !allows(required)` 时渲染门禁说明页，否则按既有 `when (toolId)` 落真实页；`ungated` 随返回栈序列化（token 前缀 `tool!:`），进程重建后语义不丢。 |
| FR-007 | 仪表盘快捷工具改为单一数据源：`QUICK_TOOL_IDS.mapNotNull(ToolCatalog::byId)`，`items(count = list.size, key = id)`，消灭 count / listOf 双源。 |
| FR-008 | `NavHubScreen` 全量 `ToolRow` 换 `GatedToolCard`，点击语义同 FR-004（统一经 `onOpenTool` 由 `AbyssRoot` 裁决）。 |
| FR-009 | 27 条矩阵按下表登记；`ToolCatalogTest` 新增 fail-closed 断言：`required` 只含合法能力、`modes` 非空时至少一条、`modes` 的 required 与总 required 不矛盾（任一模式 required ⊆ required ∪ 可选能力）、零权限工具集精确匹配。 |

### 3.1 权限矩阵（27 条，与 `Abyss-Tools-权限矩阵.csv` 对齐）

| 组 | 工具 id | `required` | `modes`（名称 = 门槛） |
|---|---|---|---|
| 零权限 | `qr-craft` `media-lab` `backup-master` `widget-studio` `live-wallpaper` `network-audit` `battery-doctor` `code-editor` `privacy-guard` `time-shift` | ∅ | 备份：zip/APK 库 = ∅，恢复安装 = INSTALL_UNKNOWN；流量审计：只读 = ∅，细粒度 = SHIZUKU_SHELL |
| 特殊授权 | `dynamic-island` | OVERLAY | 手动授权 = OVERLAY；一键授予 = SHIZUKU_SHELL |
| 只读 / 执行双模式 | `app-manager` `system-control` `shizuku-kit` `terminal` `script-lab` `task-blueprint` `battery-disguise` `location-disguise` `signal-disguise` `model-disguise` | SHIZUKU_SHELL | 只读 / 预览 = ∅；执行 = SHIZUKU_SHELL；自动确认 = SHIZUKU_SHELL+ACCESSIBILITY；状态栏覆盖层 = OVERLAY；档案注入 = DHIZUKU_ACTIVE+DEVICE_ADMIN_ACTIVE |
| Shizuku 主力 | `sandbox-space` `dual-open` | SHIZUKU_SHELL | 多用户基础 = SHIZUKU_SHELL；DO 托管 = DHIZUKU_ACTIVE+DEVICE_ADMIN_ACTIVE |
| 组合档 | `automation` | SHIZUKU_SHELL+ACCESSIBILITY | 编排编辑 = ∅；命令编排 = SHIZUKU_SHELL；代点 = ACCESSIBILITY |
| 引擎级插件 | `app-builder` `linux-runtime` `virus-guard` | SHIZUKU_SHELL+INSTALL_UNKNOWN（病毒拦截另加 APP_OBSERVE） | 构建 / 安装 / 扫描 / 处置 / PRoot 域 = ∅ / Shizuku 域 / root 域 分列 |

> `display_tier` 是面向用户的推荐档位；门禁以 `required` 超集判定为准。两者在 `INSTALL_UNKNOWN` / `OVERLAY` 上允许不一致（特殊授权不升档）。

---

## 4. 契约与接口

```kotlin
// core:common
data class ToolAccess(val required: Set<Capability> = emptySet(),
                      val modes: List<ToolMode> = emptyList(),
                      val guide: ToolGuide = ToolGuide.ONBOARDING)
data class ToolMode(val name: String, val required: Set<Capability>, val note: String)

// core:designsystem
fun GatedToolCard(tool: ToolDef, snapshot: PrivilegeSnapshot, onOpen: () -> Unit, modifier)
fun CapabilityGuardCard(required: Set<Capability>, snapshot: PrivilegeSnapshot,
                        onOpenCapability: (Capability) -> Unit, modifier)
fun ToolModesCard(tool: ToolDef, snapshot: PrivilegeSnapshot, modifier)
fun lockedLabel(missing: Set<Capability>): String   // "需 Shizuku、无障碍"

// feature:navhub
fun ToolScreen(toolId, onOpenPlugin, onBack, snapshot: PrivilegeSnapshot? = null,
               onOpenCapability: (Capability) -> Unit = {}, onEnterUngated: (() -> Unit)? = null)

// app
data class Tool(val toolId: String, val ungated: Boolean = false) : Screen
AbyssBackStack.replace(screen)
```

---

## 5. 失败语义与边界

- 未知工具 id（`ToolCatalog.byId == null`）：`required` 视为 ∅ 且渲染「未知工具」—— 不猜权限、不崩溃；
- `snapshot == null`（旧调用 / 预览）：全部视为解锁（与 v0.10.1 行为一致），门禁组件退化为普通 `ListRowCard`；
- 受限模式进入后的真实功能页仍受既有守卫横幅与网关二次校验保护 —— 门禁说明页**不是**安全边界，只是 UX 层；
- 进程重建：`tool!:` token 恢复为 `ungated = true`；门禁状态以恢复时的实时快照重新裁决（不信任旧快照）。

---

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | 普通用户：功能页 `terminal` 行显示「需 Shizuku」+ 锁图标；点击进入门禁说明页（非终端页）；说明页有「以受限模式进入」按钮（查看历史 = ∅）。 |
| AC-002 | 普通用户点击 `qr-craft`：直接进入二维码工坊，无任何门禁痕迹。 |
| AC-003 | `app-builder` 对仅授权 Shizuku 的用户显示「需 安装未知应用」而非「需 abyss-max」。 |
| AC-004 | 授权 Shizuku 后返回功能页：所有 SHIZUKU_SHELL 单依赖工具解锁，无需重启。 |
| AC-005 | `ToolCatalogTest` 新增用例全绿；`PrivilegeTierTest` 的 `allows()` 超集语义用例覆盖 `INSTALL_UNKNOWN` 不升档。 |
| AC-006 | 进程死亡重建：在受限模式下重建后仍进入真实页（`tool!:` token）；非受限下重建回门禁说明页。 |
| AC-007 | CI 全绿；`ui_text_budget` 通过。 |

---

## 7. 依赖与风险

**依赖**

- `ARC-011` 模型；`FEAT-038` 对话框与能力片；`ARC-004` 返回栈序列化

**风险**

- 27 条矩阵若与真实页面能力不符（如某页实际需更多权限）→ 以页面内守卫为准并回写矩阵（CI 断言只校验合法性，不校验真实性）；
- 门禁说明页多一跳：以「受限模式进入」兜底，保证零权限只读能力不被门禁误伤。

---

## 8. 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| 0.1 | 2026-11-28 | 创建（v0.10.2）：ToolAccess 矩阵 27 条 + GatedToolCard + 门禁说明页 + 受限模式进入 + 返回栈 `ungated` 序列化；替代 PR #30 首版「requiredCapabilities 与 display_tier 强制对齐」的档位驱动门禁。 |
