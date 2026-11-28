# FEAT-038 · 仪表盘权限分级与问号解释

> 深渊工具 Abyss Tools · 软件规格说明书（功能）  
> 状态：**已实现（v0.10.2）** ｜ 优先级：**P1** ｜ 里程碑：**M1** ｜ 承载形态：**主壳内置** ｜ 基线：**v0.10.1 / versionCode 47** ｜ 编制日期：**2026-11-28**

| 项 | 值 |
| --- | --- |
| 规格编号 | `FEAT-038` |
| 所属域 | 功能 |
| 承载形态 | 主壳内置 |
| 当前状态 | 已实现（v0.10.2） |
| 优先级 / 里程碑 | P1 / M1 |
| 标签 | 仪表盘 · 五档徽章 · 小问号 · 能力明细 · 授权直达 |
| 关联规格 | `ARC-011`（能力与档位模型）、`FEAT-039`（功能级门禁）、`FEAT-001`（仪表盘）、`ARC-009`（首启引导）、`QA-008` |
| 建议落盘路径 | `docs/abyss-specs/03-功能模块/FEAT-038-仪表盘权限分级与问号解释.md` |

---

## 1. 目标与范围

仪表盘首屏必须让**任何档位**的用户一眼读懂三件事：我现在是什么档位、这个档位能做什么、去哪里升档。

1. **档位徽章（TierBadge）**：文字徽章显示 `snapshot.tier.label`（普通用户 / 进阶用户 / 高级用户 / abyss-max / abyss-ultra），五档五色；max 档缺 `INSTALL_UNKNOWN` 时徽章旁提示「待补 1 项」。
2. **小问号**：普通用户档位旁**常驻**问号图标；其余档位徽章本体可点。点击弹出「权限档位说明」对话框：首段即需求原文「当前未授予任何权限，只能启动极少功能」+ 五档说明表（每档条件 + ✓ / 待补状态）+「去授权」按钮（按缺失能力优先级跳转）。
3. **能力明细行**：Shizuku / 无障碍 / 设备管理 / 安装未知 / 悬浮窗 五枚可交互状态片，每枚 = 8dp 状态圆点 + 短标签，按能力态着色，点击直达对应授权页。
4. **本机可达提示**：`environmentNotes` 非空时在状态区下方以次要文案显示（Android 15+ 受限设置 / Android 16 高级保护）。

**范围内**：`core:designsystem` 新增 `InteractiveStatusChip` / `TierBadge` / `HelpIconButton` / `TierExplanationDialog`；`DashboardScreen` 状态区重做；`AbyssRoot` 接线四个授权跳转。
**范围外**：`StatusChip` 本体（40+ 调用点，UI-001 圆点纪律不改）；门禁卡片见 `FEAT-039`；引导页档位中心见 `ARC-009` 修订。

---

## 2. 现状锚点（克隆研究实测，v0.10.1）

- `DashboardScreen.kt:161-178`：状态行 = `StatusChip("Shizuku · …")` + `StatusChip("安装未知应用 · …")`，`Components.kt:74-88` 的 `StatusChip` **不渲染文字**，用户连「Shizuku · 未运行」都看不见；
- `DashboardScreen.kt:80-92`：参数只有 `shizukuUi` / `installPermGranted`，无任何授权跳转回调；`AbyssRoot.kt` 已有 `onRequestShizukuPermission` / `onOpenShizuku` 但只接到二级功能页；
- 全仓无 `PrivilegeSnapshot` 消费方，仪表盘对「无障碍 / 设备管理员 / Dhizuku / 悬浮窗」零感知。

---

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | 仪表盘状态区顶部渲染 `TierBadge(snapshot)`，文字 = `snapshot.tier.label`，色调按五档固定（BASIC 灰 / ADVANCED 蓝 / SENIOR 绿 / MAX 橙 / ULTRA 紫）；`snapshot == null`（旧调用）时退回旧两枚圆点，零破坏。 |
| FR-002 | BASIC 档徽章右侧常驻 `HelpIconButton`；任一档位点击徽章或问号均弹出 `TierExplanationDialog`。对话框首行对 BASIC 档必须显示「当前未授予任何权限，只能启动极少功能」。 |
| FR-003 | 对话框正文列出五档说明表：每档一行 = 圆点 + 档名 + 达成条件 + 状态（已达成 ✓ / 待补：缺失能力名）。 |
| FR-004 | 对话框「去授权」按钮目标 = 缺失能力按 `Capability.guidePriority` 取首个（SHIZUKU_SHELL > ACCESSIBILITY > INSTALL_UNKNOWN > OVERLAY > DHIZUKU_ACTIVE > DEVICE_ADMIN_ACTIVE）；无缺失时按钮隐藏。 |
| FR-005 | 能力明细行五枚 `InteractiveStatusChip`：Shizuku（已授权 / 未授权 / 未运行）、无障碍（已开启 / 未开启）、设备管理（已就绪 / 仅管理员 / 仅 Dhizuku / 未激活）、安装未知（已允许 / 未允许）、悬浮窗（已允许 / 未允许）；点击分别触发 `onOpenCapability(cap)`。触控区 ≥ 24dp，`contentDescription` = 「标签 · 状态」。 |
| FR-006 | `snapshot.environmentNotes` 非空时在能力行下方逐条以 `bodySmall` 次要色显示。 |
| FR-007 | `AbyssRoot` 实现 `onOpenCapability`：SHIZUKU_SHELL → 运行未授权时 `onRequestShizukuPermission()`，否则 `onOpenShizuku()`；ACCESSIBILITY → `ACTION_ACCESSIBILITY_SETTINGS`；DEVICE_ADMIN_ACTIVE → `ACTION_ADD_DEVICE_ADMIN`；DHIZUKU_ACTIVE → `Dhizuku.requestPermission`；INSTALL_UNKNOWN → `ACTION_MANAGE_UNKNOWN_APP_SOURCES`；OVERLAY → `ACTION_MANAGE_OVERLAY_PERMISSION`。跳转失败 `runCatching` 吞掉不崩。 |
| FR-008 | 旧参数 `shizukuUi` / `installPermGranted` 保留默认值；`snapshot != null` 时不再渲染旧状态行。 |

---

## 4. 契约与接口

```kotlin
// core:designsystem
fun TierBadge(snapshot: PrivilegeSnapshot, onClick: () -> Unit, modifier)
fun HelpIconButton(onClick: () -> Unit, modifier, contentDescription = "权限说明")
fun InteractiveStatusChip(label: String, status: String, tone: ChipTone, onClick: (() -> Unit)?, modifier)
fun TierExplanationDialog(snapshot: PrivilegeSnapshot, onDismiss, onOpenCapability: (Capability) -> Unit)

// feature:dashboard
fun DashboardScreen(..., snapshot: PrivilegeSnapshot? = null,
                    onOpenCapability: (Capability) -> Unit = {},
                    shizukuUi: ShizukuUi = ShizukuUi.NotRunning,
                    installPermGranted: Boolean = false)
```

`ChipTone` 新增 `MAX`（橙）/ `ULTRA`（紫）两档，既有五档不动。

---

## 5. 失败语义与边界

- `snapshot.reasons[cap]` 非空时，能力片的长按 / 朗读语义附带原因文案；视觉仍按能力态着色；
- 系统设置跳转 `ActivityNotFoundException`（OEM 裁剪）→ 吞掉，UI 不崩，不反复弹；
- 对话框「去授权」不做自动循环：一次点击只跳一个目标，用户返回后由 `onResume` 刷新真值；
- 不在仪表盘执行任何高危动作，不信任缓存快照做放行（放行逻辑见 `FEAT-039`，二级页执行前另有二次校验）。

---

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | 普通用户首屏可见「普通用户」文字徽章 + 问号；点击弹出对话框且首行为「当前未授予任何权限，只能启动极少功能」。 |
| AC-002 | 授权 Shizuku 后返回 App：徽章变「高级用户」且为绿色；Shizuku 能力片变 OK 色；无需手动刷新。 |
| AC-003 | 五枚能力片逐一点击分别到达：Shizuku 授权 / 无障碍设置 / 设备管理员激活 / 未知来源设置 / 悬浮窗设置。 |
| AC-004 | 对话框五档表逐行状态与 `TierCalculator.requirements` 一致；ULTRA 行显示「待补：Dhizuku、设备管理员」而非空白。 |
| AC-005 | `snapshot == null` 的旧调用（预览 / 单测）编译通过且渲染旧状态行。 |
| AC-006 | CI 全绿；`ui_text_budget` 通过（对话框文案以 `keep-text: 授权引导` 标注）。 |

---

## 7. 依赖与风险

**依赖**

- `ARC-011` 模型与探测层；`ARC-009` 引导页档位中心（共用 `TierBadge`）

**风险**

- UI-001 文案精简纪律：徽章与能力片只允许短标签（≤ 6 字），说明文字全部进对话框；对话框属「授权引导」豁免类别；
- 五档五色须从 `ChipTone` 派生，禁止业务页手写 ARGB。

---

## 8. 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| 0.1 | 2026-11-28 | 创建（v0.10.2）：TierBadge + 小问号对话框 + 五枚可交互能力片 + 四路授权直达；替代 PR #30 首版「仅徽章 + 无原因码」实现。 |
