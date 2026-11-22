> 深渊工具 Abyss Tools · 软件规格说明书（功能）  
> 状态：**已实现（v0.9.13）** ｜ 优先级：**P1** ｜ 里程碑：**M1** ｜ 承载形态：**主壳内置** ｜ 基线：**v0.9.10 / versionCode 33** ｜ 编制日期：**2026-11-22**

| 项 | 值 |
| --- | --- |
| 规格编号 | `FEAT-033` |
| 所属域 | 功能 |
| 承载形态 | 主壳内置 |
| 当前状态 | 已实现（v0.9.13） |
| 优先级 / 里程碑 | P1 / M1 |
| 组织与基线 | abyss-tool 组织主仓 `main` 520 文件 + 发布仓 7 文件全量研究；v0.9.10 / versionCode 33 |
| 标签 | 液态玻璃 · 滑动指示器 · 设置项 · Clear 档 |
| 关联规格 | `FEAT-034`（底栏四 tab · 协同边界见 §4.4）、`ARC-004`（增量修订）、`ARC-005`（增量修订）、`ARC-006`、`CORE-005`、`CORE-006`、`FEAT-005`（增量修订）、`SRS-003`、`SRS-005`、`DC-001`、`DC-003`、`N-ENG-008` |
| 建议落盘路径 | `docs/abyss-specs/03-功能模块/FEAT-033-液态玻璃导航栏滑动指示器双样式切换.md` |

---

## 1. 目标与范围

在「设置 → 外观」新增**滑动指示器（液滴小椭圆）UI 样式**二选一开关；两种样式同时保留、随时互切、即时生效、重启保持。

| 档位 | 枚举值 | UI 文案 | 视觉定义 |
| --- | --- | --- | --- |
| A · 霜面液滴（现状） | `FROSTED` | 霜面液滴 | 4dp 磨砂模糊 + 1.5/7dp 透镜折射 + 液滴单色膜 + 速度驱动流光。**当前默认，零回归** |
| B · 清透玻璃（新增） | `CLEAR` | 清透玻璃 | **零模糊**（不挂 `vibrancy` / `blur`）；背景 100% 清晰透出；胶囊边缘一圈静态玻璃高光（specular rim + Fresnel + 四角镜面点光 + 相对侧轻暗边）；仅极薄白/黑纱（≤0.08 alpha）与内阴影/外投影塑形；保留边缘带轻微折射与色散 |

**范围内**

- 底部导航栏 **④ 液滴小椭圆**的渲染档位、设置项、持久化、降级矩阵、验收。
- 设置页新增一个 M3 单选控件与说明文案。
- `ARC-005` 渲染契约由「Backdrop / Frosted 双档」扩为含 **Clear 档**的三档说明。

**范围外（硬边界）**

- **① 整栏玻璃壳不改**：`rememberBarStyle()`（blur 14dp / lens 2.5-12dp / 135° 对角渐变）保持原样，用户已确认整栏液态玻璃效果可保留。
- **③ 可见 Base 层 / ② 图标副本源 / 底栏几何与手势不改**：`AbyssBottomBarHeight = 76.dp`、`BarBodyHeight = 72.dp`、`DropletHeight = 56.dp`、`BarHorizontalPadding = 12.dp`、`SlotSpacing = 4.dp` 全部冻结。
- 不改 `AbyssLiquidMotion` 的弹簧、动量外推、拖拽吸附；**切换样式不得重建 `motion`**（重建会清空拖动状态、弹簧从头起跳，属 `AbyssLiquidMotion` 已登记教训）。
- **不改底栏 tab 项数、顺序与路由**：由 `FEAT-034` 定义为 4 项（仪表盘 / 功能 / 插件 / 设置）。本规格不修改 `bottomItems`、不修改 `AbyssBackStack`；`AbyssGlassBottomBar` 的槽宽 / 步长 / 弹簧已按 `items.size` 参数化，3→4 天然兼容。
- 不引入新外部依赖、不改 `minSdk 26 / targetSdk 28 / compileSdk 36`、不动发布签名与通道白名单。

---

## 2. 现状锚点（克隆研究实测）

> 研究口径：2026-11-22 对 `github.com/abyss-tool` 主仓 `main` 分支（520 blobs，含 `docs/abyss-specs/` 全量规格集）与 `Abyss-Tools-Release` 发布仓（7 blobs）全量拉取；并横向比对 Android Compose 液态玻璃 Clear / Ultra-Clear 档的公开实现与调参记录（Kyant0 Backdrop 2.0.1、PrismalAGSL、Liquid AGSL、miuix-blur `Highlight`、iOS 26 Liquid Glass 材质解剖）。

**源码锚点**

| 路径 | 关键事实 |
| --- | --- |
| `core/designsystem/.../glass/AbyssGlassBottomBar.kt` | 四层结构：`① AbyssGlassSurface(barStyle)` → `②` alpha=0 的 `layerBackdrop(iconsBackdrop)` 图标副本源 → `③` 可见 Base 层 → `④` 液滴 Box（`4a` 玻璃透镜 / `4b` 选中色薄膜 / `4c` 放大图标副本 / `4d abyssLiquidRim(motion)`）。`rememberDropletStyle()` 当前硬编码 `blurRadius = 4.dp, lensHeight = 1.5.dp, lensAmount = 7.dp, chromaticAberration = false, solidTint = palette.dropletTint, edgeAlpha = 1f` |
| `core/designsystem/.../glass/Glass.kt` | `AbyssGlassStyle` 单一调节面；`effectsBlock` 顺序为 `vibrancy() → blur() → lens()`；`highlightBlock` 用 `Highlight(width=0.8.dp, blurRadius=1.2.dp, alpha=style.edgeAlpha, HighlightStyle.Plain(...))`；`innerShadowBlock` 6dp / DpOffset(0,3)；`shadow(12.dp)`；`hasLiveSource = backdrop != null && canSampleLive`，否则走 **M3 卡面档**（`Surface` + `surfaceContainerHigh`，非透明玻璃） |
| `core/designsystem/.../glass/AbyssLiquidRim.kt` | `RIM_AGSL`：`sdCapsule` SDF → 数值差分法线 → `rim = exp(-inner/(h*0.18))` + `pow(dot(n,L),9)` 镜面 + `pow(1-n.z,3)` Fresnel + 速度驱动高斯光带；API<33 走 `rimGradient`。**静止时 band=1，边缘 rim 本就可见**；严禁 `infiniteTransition` / `withFrameMillis`（实机 ~45fps 全屏重采样、~400mA 教训已写入文件头） |
| `core/designsystem/.../glass/AbyssLiquidMotion.kt` | `visualIndex` 连续索引单一驱动源；状态只在 draw / graphicsLayer 阶段读取；静止零 invalidate |
| `core/designsystem/.../glass/GlassPalette.kt` | `AbyssGlassPalette.Light / Dark` + `derivedGlassPalette()`；含 `dropletTint`、`dropletIndicatorAlpha`（浅 0.20 / 深 0.14）、`rimColor`、`highlightBlendMode` |
| `core/designsystem/.../theme/Theme.kt` | `AbyssTheme` 注入 `LocalAbyssGlassPalette`；明暗 + 动态取色双通道 |
| `feature/settings/.../SettingsScreen.kt` | `SettingsRepository`（`SharedPreferences("abyss_settings")` + Compose `mutableStateOf/FloatState` 直读直写）；「外观」区 `GlassIntensityCard`（玻璃强度滑杆 + 动态取色 Switch）；错峰入场纪律：**标题必须在 Box 外**，卡片内不得新增顶层堆叠 Box |
| `app/.../ui/AbyssRoot.kt` | `CompositionLocalProvider(LocalGlassIntensity / LocalGlassLensEnabled)`；`AbyssGlassBottomBar(backdrop, bottomItems, tabIndex, onSelect, modifier)`；`bottomClearance = AbyssBottomBarHeight + 24.dp`。**当前 `bottomItems` 为 3 项；`FEAT-034` 后为 4 项，本规格调用点只多一个 `indicatorStyle` 参数** |
| `app/.../MainActivity.kt` | `AbyssTheme` → `AbyssRoot(glassEnabled = true, lensEnabled = true)` |
| `core/common/.../GlassProbe.kt`、`RenderSafeMode.kt` | 效果级 `runCatching` 单项降级；探针里程碑 `session-start → vibrancy → blur → lens → first-frame` |

**规格/发布锚点**

- `ARC-005` 当前只声明 Backdrop / Frosted 双档；`FEAT-005` 当前外观项只列玻璃强度 / 动态取色。
- `AppInfo.VERSION_NAME = "0.9.10"`、`VERSION_CODE = 33`（`app/build.gradle.kts` 正则推导）；发布仓 `version.json` 同为 0.9.10 / 33，`patches.json` 为 `{"schema":"abyss-patches-v1","latestTag":"v0.9.10","patches":[]}`。
- `SRS-003`：升版只改 `AppInfo.kt` + 打 `v*` tag；`publish_release.py` 自动滚动发布仓三件套并写后自检回读；`changelog.json` 旧 latest 下沉 history。
- `N-ENG-008`：新增/变更能力先写 spec → 评审 → 实现 → 用例 → 更新 spec 状态与 `SPEC-INDEX.md`。

---

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
| --- | --- |
| FR-001 | 设置页「外观」区提供**且仅提供**两个互斥档位：`FROSTED` / `CLEAR`，默认 `FROSTED`，升级用户外观与 v0.9.10 完全一致。 |
| FR-002 | 切换后**当前帧即时生效**，无需重启 Activity、无需重建 `AbyssLiquidMotion`、不重置 `visualIndex` / 拖动状态 / 选中 tab。 |
| FR-003 | 偏好持久化到 `abyss_settings`；进程重启、旋转、深浅色切换后保持；未知/损坏值 **fail-closed 回退 `FROSTED`**，不猜、不崩。 |
| FR-004 | `CLEAR` 档效果链**不得包含** `vibrancy()` 与 `blur()`；`blurRadius` 语义上恒为 0，背景像素不被模糊、不被饱和度增强。 |
| FR-005 | `CLEAR` 档保留胶囊边缘一圈**静态可见**的玻璃高光：specular rim + Fresnel + 四角镜面点光 + 相对侧轻暗边；静止（velocity=0 / stretch=0）时高光仍可见，且光带不得随速度位移。 |
| FR-006 | `CLEAR` 档可保留**仅边缘带**的 AGSL `lens` 折射/色散（`chromaticAberration = true`），但中心区域必须清晰；`lensEnabled = false` 或 API<33 时跳过 lens，只留绘制层高光，不崩溃。 |
| FR-007 | `CLEAR` 档选中色薄膜 alpha 与液滴 tint 必须显著低于 `FROSTED`（见 §4.2），保证背景文字/图标清晰可读；`4c` 放大图标副本（`iconScale = 1.18f`）两档均保留。 |
| FR-008 | `FROSTED` 档参数、几何、动效、降级路径**逐字不变**；`rememberBarStyle()` 与整栏 ① 不随开关变化。 |
| FR-009 | `LocalGlassIntensity`（0.4~1.6）继续驱动 `FROSTED` 的 blur / lens；对 `CLEAR` 只缩放 lens 与 rim 强度，**绝不重新引入模糊**。 |
| FR-010 | 静止时两档均零常驻 invalidate：禁止 `infiniteTransition` / `withFrameMillis` / 传感器驱动高光；`RuntimeShader` 只在 `drawWithCache` 构建一次，每帧仅 `setFloatUniform`。 |
| FR-011 | 降级矩阵：API<31 零 GPU 采样；API 31–32 用渐变描边近似；API 33+ 用 AGSL；`GlassProbe` 里程碑与 `RenderSafeMode` 的 `runCatching` 单项守卫对 `CLEAR` 同样生效。 |
| FR-012 | 业务 UI 一律经由 `core:designsystem.glass` 包；设置页枚举/`from()` 放 `core:common` 保持纯 JVM 可单测；不新增 Gradle 依赖、不新增 `Color(0x…)` 硬编码（色值一律进 `AbyssGlassPalette`）。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

---

## 4. 契约与接口

### 4.1 数据契约（偏好键 / 枚举 / 迁移）

| 项 | 值 |
| --- | --- |
| SharedPreferences 文件 | `abyss_settings`（沿用 `SettingsRepository`） |
| 新增键 | `nav_indicator_style`（`KEY_NAV_INDICATOR_STYLE`） |
| 值域 | `"frosted"` / `"clear"`（**只存字符串，不存 ordinal / 类名**） |
| 默认 | `"frosted"`（键缺失 = 老用户 = 现状外观） |
| 迁移 | 无需数据迁移：键缺失即默认；任何其它字符串 → `FROSTED` |
| 类型源 | `core/common/.../AbyssIndicatorStyle.kt`（纯 JVM，零 Android 依赖） |

### 4.2 渲染契约（两档参数表，数值为基线，实机可调）

| 参数 | `FROSTED`（冻结） | `CLEAR`（新增） |
| --- | --- | --- |
| `blurEnabled`（新增字段） | `true` | `false` → 跳过 `vibrancy()` + `blur()` |
| `blurRadius` | `4.dp` | `0.dp`（不参与计算） |
| `lensHeight` / `lensAmount` | `1.5.dp` / `7.dp` | `2.0.dp` / `10.dp`（只走边缘带） |
| `chromaticAberration` | `false` | `true`（边缘轻微 RGB 偏移） |
| `solidTint` | `palette.dropletTint` | `palette.clearDropletTint`：浅 `White@0.06` / 深 `0x0A0E13@0.04` |
| `edgeAlpha` | `1f` | `1f`（`Highlight` 不变，rim 增益在 AGSL 内） |
| `4b` 选中色薄膜 alpha | `palette.dropletIndicatorAlpha + press*0.08` | `palette.clearIndicatorAlpha + press*0.04`：浅 `0.08` / 深 `0.06` |
| `4c` 放大图标 | `iconScale = 1.18f` | 同左（不动） |
| `4d` 边缘光 | `AbyssRimMode.FLOW`（速度甩光带） | `AbyssRimMode.CLEAR`（光带冻结中心、rim 增益 1.45、四角点光、相对侧暗边） |
| 内阴影 / 外投影 | `InnerShadow(6dp, (0,3))` + `shadow(12.dp)` | 同左（玻璃「厚度 + 悬浮」不可省，见附录 A） |
| 背景采样源 | `iconsBackdrop`（② 图标副本层） | 同左（只用于 lens 边缘折射，**不模糊内容层**） |

> `AbyssGlassPalette` 增量：新增 `clearDropletTint: Color` 与 `clearIndicatorAlpha: Float` 两个字段，`Light` / `Dark` / `derivedGlassPalette()` 三处全部补齐；禁止在 `AbyssGlassBottomBar.kt` 写 `Color(0x…)`。

### 4.3 组件接口

AGSL 增量（在既有 `RIM_AGSL` 上加一个 uniform，**不新建着色器、不新增常驻循环**）：

`rimGradient`（API 31–32）在 `CLEAR` 下改为**静态三段式**：上缘 1dp 亮线（`palette.rimColor`）+ 下缘暗边 + 左右水平渐隐；不读 `motion.velocity` 做位移，保持静止零 invalidate。

> `remember` key 纪律：`dropletStyle` 的 `remember(indicatorStyle, palette)` 只换参数对象，**不得把 `indicatorStyle` 塞进 `rememberAbyssLiquidMotion` 的 key**。

### 4.4 注入链路

`MainActivity` / `ServiceRegistry` / `LocalGlassIntensity` / `LocalGlassLensEnabled` 装配**无需改动**。

**与 `FEAT-034` 的协同边界（合并顺序硬约束）**：两份 spec 都落在 `AbyssRoot.kt` 的同一个 `AbyssGlassBottomBar(...)` 调用点，但改动互不重叠——FEAT-034 改 `bottomItems`（3→4 项）/ `tabIndex` / `selectTab` / Screen 路由；本规格只加一行 `indicatorStyle`。`core:designsystem.glass` 包内**只有本规格改**，`SettingsScreen.kt` 也只有本规格改，无文件级冲突。合并顺序：先合 FEAT-034，再叠加本参数；或一次写成终态：

> 注意区分两件会重建 `motion` 的事：**tab 数 3→4**（`itemCount` 进 `remember` key，首次组合时重建，属预期）与**样式切换**（不进 key，绝不重建）。FR-002 约束的是后者；AC-006 / AC-010 需联合覆盖 4 tab 下切样式不打断拖拽。

### 4.5 设置页 UI 契约

- 位置：`SettingsScreen` → 外观 item → `GlassIntensityCard` 内部，在「玻璃强度滑杆」之后加 `HorizontalDivider(Modifier.padding(vertical = 12.dp))` 再插入新行；**不新增顶层 `Box`**，避免 v0.9.x 已修复的「标题与卡片同框文字重叠」病灶。
- 控件：M3 `SingleChoiceSegmentedButtonRow` + 2 个 `SegmentedButton`，`shape = SegmentedButtonDefaults.itemShape(index, 2)`；`selected = settings.navIndicatorStyle == style`；`onClick = { settings.navIndicatorStyle = style }`。
- 标签：`霜面液滴` / `清透玻璃`；说明文案：`切换底部导航滑动指示器的材质：霜面液滴带轻微磨砂与折射；清透玻璃无模糊、仅保留边缘玻璃高光，背景清晰透出`。
- 回显：`StatusChip` 显示当前档（可选，`ChipTone.INFO`）；切换后由 `SnackbarHost` 给一句人话回执（对齐 `FEAT-005` FR-001 的无反馈动作纪律，本项有即时视觉反馈，回执可省略但建议保留）。
- `@OptIn(ExperimentalMaterial3Api::class)` 复用仓库既有 SegmentedButton 的集中登记处，不新开 OptIn 散点（对齐 `ARC-006` FR-004）。

### 4.6 降级矩阵

| 环境 | `FROSTED` | `CLEAR` |
| --- | --- | --- |
| API ≥ 33 + `lensEnabled` | blur + lens + 流光（现状） | **零 blur** + 边缘 lens/色散 + 静态 rim |
| API 31–32 | blur + lens 跳过 + 渐变流光（现状） | 零 blur + 静态渐变描边（上亮下暗） |
| API < 31 / 安全模式 | M3 卡面档（现状） | M3 卡面档 + 纯 paint 静态描边；**零 backdrop 采样、零 RenderEffect** |
| `lensEnabled = false` | 保留 blur | 只留 `Highlight` / `InnerShadow` / 静态 rim，仍无模糊 |
| AGSL `RuntimeShader` 编译失败 | `runCatching` 跳过 lens | 同上，且 `GlassProbe` 落 `lens-skipped` |

> 关键：`CLEAR` **不是**把 `backdrop` 传 `null`。传 `null` 会命中 `AbyssGlassSurface` 的 M3 卡面档（不透明 `surfaceContainerHigh`），那是「卡面」不是「纯液态玻璃」。必须保留 `lensBackdrop` 并只关闭 effects 链里的模糊项。

### 4.7 发布契约

| 动作 | 内容 |
| --- | --- |
| 升版 | 与 `FEAT-034` **同批发布**：共用 `AppInfo.VERSION_NAME = "0.9.13"` / `VERSION_CODE = 36`（`build.gradle.kts` 正则推导，不动字面量），一次升版、两条 spec 同时标「已实现（v0.9.13）」；versionCode 单调由 `SRS-003` 门禁兜底 |
| 发布要点 | 新建 `docs/release-notes/v0.9.13.md`，一行一条；含本规格编号 `FEAT-033` 与 `FEAT-034` |
| 打 tag | `v0.9.13` → `auto-release.yml` → `assembleRelease` + 签名指纹门禁 → `publish_release.py` |
| 发布仓 | `version.json` / `patches.json` / `changelog.json` **由 CI 滚动，禁止手改**；`patches.json` 维持 `patches: []`，`latestTag` → `v0.9.13`（客户端 vcdiff 合成引擎未接入，仍整包安装） |
| 规格同步 | 修订 `ARC-005`（新增 Clear 档行）、`FEAT-005`（外观项增列）、`SPEC-INDEX.md`（新增条目 + 状态）、`docs/ARCHITECTURE.md`（玻璃组件小节）；本文件状态改「已实现（v0.9.13）」 |

---

## 5. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 拒绝并给人话错误，不猜测、不静默降级。
- 远端数据统一走 `UiState`（Loading / Error / Data），加载中禁止展示假数据。
- **偏好值**：`nav_indicator_style` 读出任意非 `frosted`/`clear` 字符串（含空串、旧版残留、被篡改）→ `AbyssIndicatorStyle.from()` 回退 `FROSTED`，不抛异常、不写回覆盖用户原值（下次读仍回退）。
- **渲染**：`vibrancy` / `blur` / `lens` / `RuntimeShader` 各自 `runCatching` 单项守卫；`CLEAR` 档任一效果失败只丢该效果，绝不整体禁用玻璃系统、绝不回退成不透明卡面来「假装」清透。
- **可读性下限**：`CLEAR` 档薄膜 alpha 与 tint 有硬下限/上限（§4.2），不得为了「更透」把对比度压到 0；花哨壁纸下的自适应亮度感知列为后续可选（见 §7），本期用固定低 alpha + 外投影 + 放大图标三重保障。
- **静止零耗电**：`CLEAR` 档高光为静态几何光，不得因「玻璃要流动」引入无限缓动；`uEdgeMode` 只在 draw 阶段读，uniform 更新不进重组。
- **无模糊硬断言**：`CLEAR` 档 `blurEnabled = false` 是编译期可见的布尔契约，禁止后续用 `blurRadius = 0.dp` 之外的方式「隐式关模糊」（0 半径仍可能产生一次 GPU pass）。

---

## 6. 验收标准

| 编号 | 验收条件 |
| --- | --- |
| AC-001 | 设置页「外观」区可见且仅有两个互斥档；点击后底栏液滴当前帧切换，无需重启；`Snackbar`/选中态回显正确。 |
| AC-002 | 全新安装与从 v0.9.10 覆盖升级，默认均为 `FROSTED`；与升级前截图对比整栏与液滴观感一致（零回归）。 |
| AC-003 | 杀进程重启 / 旋转 / 深浅色切换 / 动态取色开关切换后，档位保持；`adb shell` 查看 `abyss_settings` 值为 `frosted` 或 `clear` 字符串。 |
| AC-004 | `CLEAR` 档代码断言：`effectsBlock` 未调用 `vibrancy()` / `blur()`；实机截图透过液滴可清晰读出背后列表文字（无磨砂晕开）。 |
| AC-005 | `CLEAR` 档静止时胶囊边缘一圈高光可见（上缘亮线 + 四角点光 + 相对侧暗边）；快速切 tab 时光带**不**随速度甩尾。 |
| AC-006 | 两档拖动 / 松手动量外推 ~0.4 tab / spring 回弹 / 选中吸附完全一致；切换档位时 `visualIndex` 连续、正在进行的拖拽不被打断。 |
| AC-007 | 整栏 ① 在两档下像素一致：`rememberBarStyle()` 未被读取 `indicatorStyle`；`AbyssBottomBarHeight` 等 5 个几何常量未改。 |
| AC-008 | 玻璃强度滑杆：`FROSTED` 下 blur / lens 随 0.4~1.6 变化；`CLEAR` 下任何强度都无模糊，仅 lens / rim 强度变化。 |
| AC-009 | 降级矩阵逐格真机/模拟验证：API 30 / 31–32 / 33+ × `lensEnabled` true/false，无崩溃、无黑块、`GlassProbe` 里程碑合理。 |
| AC-010 | 静置 60s 帧计数：两档均无常驻 invalidate（对齐 `AbyssLiquidRim` 文件头 400mA 教训）；`RuntimeShader` 实例数不随时切换增长。 |
| AC-011 | 纯 JVM 单测：`AbyssIndicatorStyle.from(null/""/"CLEAR"/"bogus"/"frosted"/"clear")` 六例；偏好键默认值与 fail-closed 回退；`./gradlew test` 全绿。 |
| AC-012 | CI 全绿：`assembleDebug` + `assembleRelease` + `test` + `validate_manifests.py` + 双 APK artifact；`version.json` 与 `changelog.json` 版本号一致；真机回归 Android 15 与 16 各一台 × 深浅色 × 动态取色开/关。 |
| AC-013 | 与 `FEAT-034` 合并后：底栏 4 格下两档样式即时生效、`CLEAR` 仍无模糊、液滴槽宽与第 3 格「插件」对齐；切样式不重建 `motion`、不打断 0→3 全行程拖拽；旋转恢复后「插件」tab 高亮与当前档位同时保持。 |

> 验收方法：纯 JVM 单测（`./gradlew test`）/ CI 门禁脚本 / 真机回归清单（Android 15 与 16 各一台）/ 通道清单校验，按单元性质择一或组合。

---

## 7. 依赖与风险

**依赖**

- `core:common`（`AbyssIndicatorStyle` 枚举 / `SemVer` / `UiState` 三态 / 渠道常量）
- `core:designsystem`（`glass` 包 + `glass` 色板 + `AbyssTheme`）
- `feature:settings`（`SettingsRepository` / `SettingsScreen`）
- `:vendor:backdrop`（既有 vendored Kyant0 底座，**不新增外部依赖**）
- `SRS-005` 工具链冻结：Kotlin 2.3.21 / AGP 8.13.2 / Gradle 8.14 / Compose BOM 2026.02.00 / JDK 17 / compileSdk 36 / minSdk 26 / targetSdk 28

**风险与缓解**

| 风险 | 缓解 |
| --- | --- |
| 纯透档在花哨壁纸/高对比列表上文字压不住 | 固定低 alpha 薄膜 + `InnerShadow` + 12dp 外投影 + `4c` 放大图标三重保障；AC-004 实机截图卡关。背景亮度感知自适应（`adaptiveLegibility`）列 M2 后续，本期不引入 GPU→CPU 像素回读 |
| 误把 `CLEAR` 实现成 `backdrop = null`（退化成不透明 M3 卡面） | §4.6 硬边界写入代码评审 checklist；AC-004 断言 effects 链而非仅看截图 |
| 切换档位触发 `motion` 重建导致弹簧跳变 | FR-002 + `remember` key 纪律；AC-006 拖拽中切换专项用例 |
| 为「流动感」给静态高光加无限缓动 → 待机耗电 | FR-010 / FR-005；复用现有 `RIM_AGSL` 与零耗电注释，不新开 `infiniteTransition` |
| `lens` 边缘折射在部分 OEM GPU 上失败 | 沿用 `runCatching` 单项守卫 + `LocalGlassLensEnabled`；失败后 `CLEAR` 仍是「无模糊 + 绘制层高光」，语义不破 |
| 老用户 pref 被写入未知值 | `from()` fail-closed 回退；不写回、不迁移、不抛 |
| 新增字段破坏 `AbyssGlassPalette` 三处构造 | `Light` / `Dark` / `derivedGlassPalette()` 同步补齐；编译期即暴露，AC-012 门禁兜底 |

---

## 8. 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| 0.1 | 2026-11-22 | 基于 abyss-tool 组织主仓 / 发布仓全量研究与液态玻璃 Clear 档横向调研创建；对齐 v0.9.10 / versionCode 33 基线；按 `N-ENG-008` 先写 spec 后实现。 |
| 0.2 | 2026-11-22 | 用户补充需求「底栏新增插件 tab、功能页插件转移到插件里」→ 新增并行规格 `FEAT-034`；本文件补：关联规格、§1 范围外 4 tab 硬边界、§2 锚点、§4.4 合并终态代码与「tab 数变化 vs 样式切换」区分、§4.7 同批/分批升版策略、AC-013 联合验收、附录 B/C 同步。渲染参数与两档定义**不变**。 |
| 0.3 | 2026-11-22 | v0.9.11 / code 34 已被当日计划外的应用图标更换发版占用（`SRS-003` versionCode 单调约束）→ §4.7 / 附录 B 的发布契约整体顺延为 **v0.9.13 / 36**。 |
| 0.4 | 2026-11-22 | v0.9.12 / code 35 已被 N-SEC-001/002 批次（PR #12）占用 → 本批次发布契约整体顺延 **v0.9.13 / 36**。 |

---

## 附录 A · 调研结论与取舍（为什么这么做）

**1. 现有实现已经把「滑动指示器」独立出来了，改造面比想象小。**  
`AbyssGlassBottomBar` 的 ④ 是四层叠画（透镜 / 薄膜 / 放大图标 / 边缘光），`rememberDropletStyle()` 与 `rememberBarStyle()` 已经分离。因此「只换指示器、整栏不动」不需要动导航结构、不需要动 `AbyssLiquidMotion`、不需要动采样源拓扑 —— 只需要把 droplet 的 style 从一个常量变成**由枚举选出的两套参数**。

**2. 「无模糊」≠「不透明卡面」，也 ≠「关掉玻璃」。**

- 直接 `backdrop = null` 会命中 `AbyssGlassSurface` 的 M3 卡面档（`Surface` + `surfaceContainerHigh`），是一块不透明色块，与「纯液态玻璃」相反。
- 业界 Clear / Ultra-Clear 档的共同做法是：**把视觉预算从大半径模糊挪到边缘折射、SDF 法线、Fresnel/specular rim 与克制的色散**，中心保持近乎透明；`blur` 取 0~2px 而非 0 半径 pass。iOS 26 材质解剖同样把「Regular」与「Clear」的差别定义在 blur sigma 与 rim 强度，而不是有没有玻璃。
- 因此本规格定稿为：**保留 `lensBackdrop` 采样供边缘 AGSL lens 用，但 effects 链里彻底不挂 `vibrancy` / `blur`**；玻璃的「存在感」由 `Highlight` + 静态 SDF rim + `InnerShadow` + 外投影承担。

**3. 边缘高光复用现有 `RIM_AGSL`，不新造着色器。**  
`AbyssLiquidRim.kt` 已有 `sdCapsule` SDF、数值差分法线、`pow(dot(n,L),9)` 镜面、`pow(1-n.z,3)` Fresnel，且**静止时 band=1，rim 本就可见**。新增 `uEdgeMode` 一个 uniform 即可得到 `CLEAR` 档：冻结光带、rim 增益 1.45、四角点光、相对侧暗边。这比新写一个 shader 更省、更符合「零常驻 invalidate / RuntimeShader 只构建一次」的既有纪律。

**4. 三条候选路线与定稿**

| 路线 | 做法 | 结论 |
| --- | --- | --- |
| ① 传 `backdrop = null` | 走 M3 卡面档 | ❌ 退化成不透明卡面，否 |
| ② 新建纯绘制 Modifier | 完全不调 `drawBackdrop`，纯 `drawBehind` 画渐变+rim | ⚠️ 可行但丢失边缘真实折射，且要新维护一套 SDF；列为备选 |
| ③ 参数化现有链路（定稿） | 保留 `lensBackdrop` + `AbyssGlassSurface`，`blurEnabled=false` 跳过模糊，`AbyssRimMode.CLEAR` 强化静态 rim | ✅ 改动最小、复用最多、降级路径天然兼容 |

**5. 三条必须带进代码的实机教训**

- **静止不跑循环**：常驻底栏的无限缓动高光会让采样背景每帧重录（公开复盘实测约 45fps 全屏重采样、~400mA），最终方案是把高光角度/光带冻结；本规格的 `CLEAR` 档把光带直接冻结在中心。
- **同心与厚度**：玻璃必须靠「一圈 rim + 内阴影 + lift shadow」与内容分离，去掉模糊后这三层反而更重要；`clearDropletTint` 只给 ≤0.08 的白/黑纱，不能再往上加不透明填充。
- **一个连续索引驱动所有视觉**：`visualIndex` 是浮点单一事实源，样式切换只是换绘制参数，绝不能变成第二条动画时间线。

---

## 附录 B · 改动文件清单

| 路径 | 动作 | 要点 |
| --- | --- | --- |
| `core/common/.../AbyssIndicatorStyle.kt` | 新增 | 纯 JVM 枚举 + `from()` fail-closed |
| `core/common/src/test/.../AbyssIndicatorStyleTest.kt` | 新增 | 六例纯 JVM 单测 |
| `core/designsystem/.../glass/Glass.kt` | 改 | `AbyssGlassStyle` 增 `blurEnabled`；`effectsBlock` 条件跳过模糊 |
| `core/designsystem/.../glass/GlassPalette.kt` | 改 | 增 `clearDropletTint` / `clearIndicatorAlpha`，三处色板补齐 |
| `core/designsystem/.../glass/AbyssLiquidRim.kt` | 改 | `AbyssRimMode` + `uEdgeMode`；渐变档静态三段式 |
| `core/designsystem/.../glass/AbyssGlassBottomBar.kt` | 改 | 签名增 `indicatorStyle`；两套 droplet style；`4b` alpha / `4d` mode 分支；①②③ 与 `rememberBarStyle()` 不动 |
| `feature/settings/.../SettingsScreen.kt` | 改 | `SettingsRepository` 增偏好；`GlassIntensityCard` 内增 `SingleChoiceSegmentedButtonRow` |
| `app/.../ui/AbyssRoot.kt` | 改 | `AbyssGlassBottomBar` 加 `indicatorStyle` 一行；**`bottomItems` / 路由由 `FEAT-034` 改，本规格不动**（合并终态见 §4.4） |
| `core/common/.../AppInfo.kt` | 改 | `0.9.13` / `36` |
| `docs/release-notes/v0.9.13.md` | 新增 | 发布要点 |
| `docs/abyss-specs/03-功能模块/FEAT-033-….md` | 新增 | 本文件（0.2 已补 FEAT-034 协同边界，0.3 重定版本） |
| `docs/abyss-specs/03-功能模块/FEAT-034-….md` | 新增 | 底栏四 tab / 插件独立入口（并行 spec） |
| `docs/abyss-specs/01-架构/ARC-005-….md` | 改 | 契约表增 Clear 档 |
| `docs/abyss-specs/03-功能模块/FEAT-005-….md` | 改 | 外观项增列 |
| `docs/abyss-specs/SPEC-INDEX.md` | 改 | 新增条目与状态 |
| `docs/ARCHITECTURE.md` | 改 | 玻璃组件小节 |
| 发布仓三件套 | **CI 自动** | 禁止手改 |

---

## 附录 C · 实现顺序建议

1. `core:common` 枚举 + 单测（可独立合入，零 UI 风险）。
2. `Glass.kt` / `GlassPalette.kt` / `AbyssLiquidRim.kt` 三个底层增量，跑 `./gradlew test`。
3. `AbyssGlassBottomBar.kt` 双 style 分支 → 先在代码里临时固定 `CLEAR` 做实机调参（rim 增益 / tint alpha / lens 高度）。
4. `SettingsScreen.kt` 接入偏好与 SegmentedButton，去掉临时固定值。
5. **与 `FEAT-034` 合并**：先落 4 tab 与路由，再按 §4.4 终态补 `AbyssRoot.kt` 的 `indicatorStyle` 一行；联合跑 AC-006 / AC-010 / AC-013。
6. 跑 §6 全量 AC（含静置帧计数与降级矩阵）→ 按 §4.7 与 FEAT-034 协调升版 → 修订既有 spec → 打 tag。
