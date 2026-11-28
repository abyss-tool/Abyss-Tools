# FEAT-040 · 设备伪装五级模式

> 深渊工具 Abyss Tools · 软件规格说明书（功能）  
> 状态：**已实现（v0.10.2）** ｜ 优先级：**P1** ｜ 里程碑：**M1** ｜ 承载形态：**主壳内置** ｜ 基线：**v0.10.1 / versionCode 47** ｜ 编制日期：**2026-11-28**

| 项 | 值 |
| --- | --- |
| 规格编号 | `FEAT-040` |
| 所属域 | 功能 |
| 承载形态 | 主壳内置 |
| 当前状态 | 已实现（v0.10.2） |
| 优先级 / 里程碑 | P1 / M1 |
| 标签 | 伪装模式 · low / medium / high / max / ultra · 生效真值 · 诚实边界 |
| 关联规格 | `ARC-011`（能力与档位模型）、`FEAT-027`（伪装四件套）、`FEAT-031`（机型伪装）、`FEAT-036`（图标切换）、`N-ICON-001`、`N-DO-001`、`N-SBX-001`、`QA-008` |
| 建议落盘路径 | `docs/abyss-specs/03-功能模块/FEAT-040-设备伪装五级模式.md` |

---

## 1. 目标与范围

把「设备伪装 low / medium / high / max / ultra」落成**绑定能力集合**的五级模式：每级有明确门槛、明确动作、明确的「诚实边界」；UI **如实标注**「已生效 / 仅生成档案 / 不可用」，绝不把「导出了档案」显示成「已生效」。

**范围内**：`core:common` 新增 `DisguiseMode` 枚举 + `DisguiseModes.effect()` 纯函数（纯 JVM 可单测）；`feature:disguise` 的 `DisguiseSettings.disguiseMode` 偏好（UI 缓存）；`DisguiseModeCard` 组件接入 `ModelSpoofScreen`；`CapabilityGuardBanner` 泛化（旧 `ShizukuGuardBanner` 保留签名做包装）。
**范围外**：Hook 层消费方（`N-SBX-001`）、设备策略动作本体（`N-DO-001`）、无障碍代点的具体业务流程。

---

## 2. 现状锚点（克隆研究实测，v0.10.1）

- `DeviceProfiles.kt:76-92`：`SpoofLevel` 三级（NAME_ONLY / STANDARD / DEEP）是**技术分级**；`FEAT-031 FR-003` 明确「Build 字段档案当前无消费方（Hook 层属 Phase 3）」；
- `DisguiseShared.kt:72-102`：`ShizukuGuardBanner` 只认 Shizuku 一维；
- `FEAT-036` 图标三选一 = 现成的零授权「low」骨架（`PackageLauncherIconGateway`）；
- 全仓无 `ACCESSIBILITY` / `DHIZUKU_ACTIVE` 消费方。

---

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | `DisguiseMode` 五级，每级绑定能力集合：LOW = `SELF_BRANDING`；MEDIUM = `ACCESSIBILITY`；HIGH = `SHIZUKU_SHELL`；MAX = `SHIZUKU_SHELL ∧ ACCESSIBILITY`；ULTRA = `SHIZUKU_SHELL ∧ DHIZUKU_ACTIVE ∧ DEVICE_ADMIN_ACTIVE`。 |
| FR-002 | `DisguiseModes.effect(mode, snapshot): ModeEffect` 为纯函数：能力不足 → `UNAVAILABLE(缺失能力)`；LOW → `EFFECTIVE`；MEDIUM → `EFFECTIVE`（前台包名感知 + 手势原语可用）；HIGH → `EFFECTIVE`（四件套命令）；MAX → `EFFECTIVE`（HIGH + MEDIUM 组合可执行）但 `note` 如实标注「档案仅导出，待 Hook 层」（STANDARD / DEEP 档案无消费方）；ULTRA → 当前恒 `PROFILE_ONLY`（设备策略面板 `N-DO-001` 未落地，DEEP 档案无注入通道）—— 「已生成档案 ≠ 已生效」。 |
| FR-003 | `DisguiseSettings.disguiseMode` 持久化偏好键 `disguise_mode`（枚举名，坏值 fail-closed 回 `HIGH`）；**生效模式以 `PrivilegeSnapshot` 实时计算为准**，偏好只作 UI 缓存（与 `FEAT-036 FR-002` 同款纪律）。 |
| FR-004 | `DisguiseModeCard`：五枚 `SelectableChip`（low / medium / high / max / ultra），能力不足者置灰且点击弹说明而非切换；每级右侧短标签 = 当前 `ModeEffect`（生效 / 仅档案 / 不可用）；顶部一行「当前生效：{按实时能力算出的最高 EFFECTIVE 模式}」。 |
| FR-005 | 切换到能力不足的模式：不写偏好、不崩，显示「缺 {能力}」短提示；点击「去授权」经 `onOpenCapability(cap)` 跳对应授权页。 |
| FR-006 | `CapabilityGuardBanner(required, snapshot, …)`：`required ⊆ capabilities` 时不渲染；否则渲染缺失能力横幅（缺失短标签 + 去授权）；`ShizukuGuardBanner` 保留原签名并委托给它（快照缺省时退回旧 Shizuku 单维语义），6 个既有页面零破坏。 |
| FR-007 | 诚实边界固定文案（短句）：HIGH「不改 ro.product，重启掉档」；MAX「档案仅导出，待 Hook 层」；ULTRA「设备策略面板待落地」；MEDIUM「Android 15+ 需允许受限设置」。 |

---

## 4. 契约与接口

```kotlin
// core:common（纯 JVM）
enum class DisguiseMode(val label: String, val required: Set<Capability>, val boundary: String)
enum class ModeState { EFFECTIVE, PROFILE_ONLY, UNAVAILABLE }
data class ModeEffect(val state: ModeState, val missing: Set<Capability>, val note: String)
object DisguiseModes {
    fun effect(mode: DisguiseMode, snapshot: PrivilegeSnapshot): ModeEffect
    fun highestEffective(snapshot: PrivilegeSnapshot): DisguiseMode   // 按实时能力
}

// feature:disguise
DisguiseSettings.disguiseMode: DisguiseMode

@Composable internal fun DisguiseModeCard(snapshot: PrivilegeSnapshot, preferred: DisguiseMode,
                                          onPrefer: (DisguiseMode) -> Unit, onOpenCapability: (Capability) -> Unit)
@Composable internal fun CapabilityGuardBanner(required: Set<Capability>, snapshot: PrivilegeSnapshot?,
                                               fallbackStatus: ShizukuStatus?, onRequestPermission, onOpenShizuku,
                                               onOpenCapability: (Capability) -> Unit = {})
ModelSpoofScreen(..., privilegeSnapshot: PrivilegeSnapshot? = null, onOpenCapability: (Capability) -> Unit = {})
```

---

## 5. 失败语义与边界

- `snapshot == null`：模式卡按「全部能力缺失」保守渲染（只有 LOW 生效），不猜；
- 偏好指向的模式与实时能力不一致 → 以实时为准显示，偏好保留（用户补齐能力后自动回到偏好模式）；
- ULTRA 在 `N-DO-001` 落地前**恒为 PROFILE_ONLY**，即使三项能力齐全 —— 「已生成档案 ≠ 已生效」（`FEAT-031 FR-003` / `ARC-011` 风险 7）；
- 无障碍在 Android 16 高级保护设备上不可达 → MEDIUM 显示不可用 + `environmentNotes` 说明，不卡流程。

---

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | 纯 JVM 单测（`core:common` 侧 `DisguiseModeEffectTest` 或并入 `PrivilegeTierTest`）：五级 × 能力组合 ≥ 8 例，ULTRA 三项齐全仍为 `PROFILE_ONLY`。 |
| AC-002 | 普通用户进机型伪装页：模式卡只有 low 标「生效」，medium 及以上置灰；点击 high 弹「缺 Shizuku」提示。 |
| AC-003 | Shizuku 已授权用户：high 标「生效」，max 标「不可用 · 缺 无障碍」，ultra 标「不可用 · 缺 Dhizuku、设备管理员」。 |
| AC-004 | 四件套页面顶部横幅行为与 v0.10.1 完全一致（已授权不显示 / 未运行显示打开 Shizuku / 未授权显示立即授权）。 |
| AC-005 | CI 全绿；`ui_text_budget` 通过（边界短句 ≤ 18 字且无长文本标点）。 |

---

## 7. 依赖与风险

**依赖**

- `ARC-011` 模型；`AbyssAccessibilityService`（`ARC-011` FR-007 声明）

**风险**

- 无障碍服务首次声明即进入系统「已安装的服务」列表：必须如实 `isAccessibilityTool="false"`、最小能力集（仅手势 + 窗口事件），不申请 `canRetrieveWindowContent`；
- 「生效」语义依赖各能力的真实可用性 —— 以 `PrivilegeRepository` 实时快照为准，不缓存。

---

## 8. 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| 0.1 | 2026-11-28 | 创建（v0.10.2）：五级模式 + 生效真值纯函数 + 模式卡接入机型伪装页 + 守卫横幅泛化。 |
