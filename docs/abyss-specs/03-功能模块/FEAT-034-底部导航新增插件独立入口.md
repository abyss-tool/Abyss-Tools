> 深渊工具 Abyss Tools · 软件规格说明书（功能）  
> 状态：**已实现（v0.9.13）** ｜ 优先级：**P1** ｜ 里程碑：**M1** ｜ 承载形态：**主壳内置** ｜ 基线：**v0.9.10 / versionCode 33** ｜ 编制日期：**2026-11-22**

| 项 | 值 |
| --- | --- |
| 规格编号 | `FEAT-034` |
| 所属域 | 功能 |
| 承载形态 | 主壳内置 |
| 当前状态 | 已实现（v0.9.13） |
| 优先级 / 里程碑 | P1 / M1 |
| 组织与基线 | abyss-tool 主仓 `main` 520 blobs + 发布仓 7 blobs 全量研究；433 个 `.kt`/`.md` 全文扫描引用点；v0.9.10 / versionCode 33 |
| 标签 | 底部导航 · 四 tab · 插件中心 · 信息架构 |
| 关联规格 | `FEAT-033`（协同边界见 §4.6）、`ARC-004`（增量修订）、`ARC-010`（增量修订）、`FEAT-002`（增量修订）、`FEAT-003`（增量修订）、`FEAT-001`（增量修订）、`SRS-004`（增量修订）、`SRS-003`、`SRS-005`、`N-ENG-008`、`DC-001`、`DC-003` |
| 建议落盘路径 | `docs/abyss-specs/03-功能模块/FEAT-034-底部导航新增插件独立入口.md` |

---

## 1. 目标与范围

底部玻璃导航栏由 **3 tab 扩为 4 tab**，新增独立入口「插件」；把原「功能」页里的插件中心入口**整体转移**到该 tab——功能页不再出现插件分类、插件门面卡、插件中心工具行。

| 索引 | tab 文案 | 图标键 | 目标 Screen | 说明 |
| --- | --- | --- | --- | --- |
| 0 | 仪表盘 | `dashboard` | `Screen.Dashboard` | 不变 |
| 1 | 功能 | `explore` | `Screen.NavHub` | 不变（仅移除插件残留） |
| 2 | **插件**（新增） | `extension` | `Screen.PluginHub` | 顶层直达插件中心 |
| 3 | 设置 | `settings` | `Screen.Settings` | 不变，保持末位 |

**范围内**

- `bottomItems` 扩为 4 项；`AbyssBackStack` 的 `tabIndex` / `selectTab` 精确映射 4 项。
- `Screen.PluginHub` 由「二级详情页」提升为「顶层 tab」，复用其既有 `stateKey()` 与 `serialize/restore` token。
- `PluginHubScreen` 支持顶层态（无返回箭头、文案与 tab 一致），详情页态保持不变。
- 功能页数据层与 UI 层的插件残留清零；新手引导文案同步。
- 仪表盘既有插件入口改为切到「插件」tab，不再产生二级同名页面。
- 相关 spec / README / 架构文档同步修订。

**范围外（硬边界）**

- **不改 `core:designsystem.glass` 包**：`AbyssGlassBottomBar` 的等宽 slot 计算、`AbyssLiquidMotion`、`AbyssLiquidRim`、`AbyssGlassStyle` 一律不动；4 项由 `items.size` 天然驱动。
- 不改 `AbyssBottomBarHeight = 76.dp` / `BarBodyHeight = 72.dp` / `DropletHeight = 56.dp` / `BarHorizontalPadding = 12.dp` / `SlotSpacing = 4.dp` / `bottomClearance = AbyssBottomBarHeight + 24.dp`。
- 不改插件中心业务逻辑（索引拉取 / ChannelPolicy / 安装闭环 / 镜像测速 / 下拉刷新），不碰 `FEAT-003` 的 6 条 FR 语义。
- 不改 `PluginDetail` / `Tool` / `AppManagerDetail` 的路由与返回栈语义；详情页仍 `stack.size == 2` 且隐藏底栏。
- 不改仪表盘其它卡片、不改动态取色 / 玻璃强度 / 更新通道；**不新增依赖、不改 SDK 矩阵**。
- 保留 `Screen.PluginHub` 这个既有类型与 token，不新增 `Screen` 子类（避免 `ARC-004` FR-005 的登记成本）。

### 决策记录（可改，但改前先读附录 A）

| 决策 | 定稿 | 备选（不采用） |
| --- | --- | --- |
| tab 顺序 | `仪表盘 / 功能 / 插件 / 设置` | `仪表盘 / 插件 / 功能 / 设置`：会让 index 1 语义变化，破坏老用户肌肉记忆 |
| 功能页插件残留 | **全部删除**（枚举 + 工具行 + 门面卡 + 路由分支） | 保留枚举仅 UI 过滤：留下死数据，分类/工具统计仍把它算进去 |
| 插件页复用方式 | 复用 `PluginHubScreen`，加可空 `onBack` + `title` | 新建 `PluginTabScreen`：重复一份 83KB 组件，双份维护 |
| 仪表盘插件入口 | 改为 `selectTab(2)` | 维持 `push(Screen.PluginHub)`：会出现「同名页面两种栈深度」 |

---

## 2. 现状锚点（克隆研究实测）

> 研究口径：2026-11-22 对 `github.com/abyss-tool` 主仓 `main` 分支 520 blobs 与发布仓 7 blobs 全量拉取；对其中 433 个 `.kt` / `.md` 全文扫描关键字，逐一定位引用点（下表为扫描结果，非推测）。

**核心有利条件**：`Screen.PluginHub` **已经是** `Screen` sealed interface 的成员，`stateKey()` 已返回 `"pluginhub"`，`serialize()` 已产出 `TOKEN_PLUGINHUB = "pluginhub"`，`restore()` 已能还原它。也就是说「提升为顶层 tab」不需要新增 Screen 类型、不需要新增序列化分支、不需要动 `listSaver`——只改 `tabIndex` / `selectTab` 与渲染分支。

| 路径 | 关键事实 |
| --- | --- |
| `app/.../ui/AbyssRoot.kt` | `bottomItems` 3 项；`tabIndex`：`Dashboard→0 / NavHub→1 / else→2`（**`PluginHub` 目前被 `else` 误归到「设置」**，因二级页隐藏底栏而从未暴露）；`selectTab`：`0/1/else→Settings`；`showBottomBar = stack.size == 1`；`Screen.PluginHub` 分支传 `onBack = { backStack.pop() }`；`ToolCatalog.ID_PLUGIN_HUB -> PluginHubScreen(...)` 遗留分支；`NavHubScreen(onOpenPluginHub = { backStack.push(Screen.PluginHub) })`；`DashboardScreen(onOpenPlugins = { backStack.push(Screen.PluginHub) })` |
| `core/common/.../ToolCatalog.kt` | `ToolCategory.PLUGINS` 为枚举首项（注释「插件永远是导航页的第一个分类」）；`categories = ToolCategory.entries.toList()` 共 **11** 分类；`tools` 首条即 `ToolDef(id = "plugin-hub", category = PLUGINS, status = READY, iconKey = "extension")`；`ID_PLUGIN_HUB` 常量；`toolsOf(category)` / `pluginDrivenCount` / `builtInCount` 数据驱动 |
| `feature/navhub/.../NavHubScreen.kt` | 对 `category == ToolCategory.PLUGINS` 走特殊 `PluginHubEntry` 门面卡分支；**紧接着仍会渲染 `toolsOf(PLUGINS)` 的 1 条「插件中心」工具行** → 功能页实际存在**两个**插件入口；页头 `${ToolCatalog.tools.size} 项工具 · ${ToolCatalog.categories.size} 个分类` 由数据驱动；`onOpenPluginHub` 参数 |
| `feature/plugins/.../PluginScreens.kt` | `PluginHubScreen(onBack: () -> Unit, ...)` **必填**；第 183–205 行页头硬编码 `IconButton(ArrowBack, onClick = onBack)` + 标题「插件中心」；作为顶层 tab 时该返回箭头语义错误 |
| `feature/onboarding/.../OnboardingScreen.kt` | 第 556 行写死「主界面分三个 tab」；第 562–567 行 `ToolCatalog.categories.filter { it != ToolCategory.PLUGINS }`；第 570 行「插件（排在功能页第一位）」——**最易漏的文案，必须同步** |
| `core/designsystem/.../component/AbyssIcons.kt` | 已有 `"extension" -> Icons.Rounded.Extension`（且 `else` 兜底也是它），新 tab 图标零新增 |
| `core/designsystem/.../glass/AbyssGlassBottomBar.kt` | `count = items.size.coerceAtLeast(1)`；`slotWidthPx = (barWidthPx - 2*padPx - spacingPx*(count-1))/count`；`stridePx = slotWidthPx + spacingPx`；`rememberAbyssLiquidMotion(selectedIndex, items.size)`——**全部按 `count` 参数化，3→4 无需改代码** |
| 规格/文档 | `ARC-004` §4 写「顶层 tab：Dashboard / Features(NavHub) / Settings」；`ARC-010` §4 写「12 分类：plugins / system / …」；`FEAT-002` FR-001 写「PLUGINS 恒为首项」、AC-001 写「12 个分类」；`FEAT-001` 与 README 写「三 tab」；`SRS-004` 写「插件永远第一分类」；`README.md` 主界面框架图写「插件永远排第一分类」；`docs/abyss-specs/深渊工具_整体增强方案.md` 写「12 分类 / 三 tab」；`SPEC-INDEX.md` 当前最大 `FEAT-032` |
| 发布 | `AppInfo.VERSION_NAME = "0.9.10"` / `VERSION_CODE = 33`；发布仓 `version.json` 同版；`patches.json` 为 `patches: []`；`SRS-003` 规定升版只改 `AppInfo.kt` + 打 `v*` tag，发布仓三件套由 CI 滚动 |

---

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
| --- | --- |
| FR-001 | `bottomItems` 精确 4 项，顺序 `仪表盘 / 功能 / 插件 / 设置`，图标键分别 `dashboard / explore / extension / settings`。 |
| FR-002 | `tabIndex` 对 4 个顶层 `Screen` 精确返回 0/1/2/3；详情页（`stack.size > 1`）底栏隐藏，兜底分支不得产生错误高亮。 |
| FR-003 | `selectTab(index)` 显式覆盖 0/1/2/3；非法索引 **fail-closed 直接 return**，不得沿用旧 `else -> Settings` 的猜测行为。 |
| FR-004 | 「插件」tab 目标为 `Screen.PluginHub` **顶层**（`stack = listOf(target)`），底栏可见、`lastAction = NavAction.TAB`、淡切转场。 |
| FR-005 | 复用既有 `Screen.PluginHub` 类型、`stateKey() = "pluginhub"`、`TOKEN_PLUGINHUB` 序列化 token；旋转 / 进程重建后落在「插件」tab 且底栏第 3 格高亮。 |
| FR-006 | `PluginHubScreen` 的 `onBack` 改为可空；顶层传 `null` 时**不渲染返回箭头**，标题显示「插件」；详情页传 `{ backStack.pop() }` 时外观、行为、标题「插件中心」与 v0.9.10 完全一致。 |
| FR-007 | 功能页插件残留清零：删除 `ToolCategory.PLUGINS` 枚举项、`plugin-hub` 工具行、`ID_PLUGIN_HUB` 常量、`PluginHubEntry` 门面卡、`onOpenPluginHub` 参数、`AbyssRoot` 内 `ToolCatalog.ID_PLUGIN_HUB ->` 分支。 |
| FR-008 | 功能页首个分类改为 `SYSTEM`（系统工具），页头统计由 `ToolCatalog.categories.size` / `tools.size` 自动变为 10 分类 / N−1 项工具，禁止手写数字。 |
| FR-009 | 新手引导文案同步：三个 tab → 四个 tab；「插件（排在功能页第一位）」→「插件（独立 tab）」；删除 `.filter { it != ToolCategory.PLUGINS }`（枚举已删）。 |
| FR-010 | `DashboardScreen` 的 `onOpenPlugins` 改为 `backStack.selectTab(2)`；仪表盘点击后停在顶层插件 tab（`stack.size == 1`），不再产生二级同名页面。 |
| FR-011 | 从「插件」tab 进入 `PluginDetail` 仍为 `push`（`stack.size == 2`、底栏隐藏），系统返回逐层出栈回到「插件」tab，不退回桌面。 |
| FR-012 | 4 tab 下 `AbyssGlassBottomBar` 等宽布局、`visualIndex` 连续索引、拖拽吸附 / 动量外推 / spring 回弹、液滴位置与选中格一一对应；`FEAT-033` 的双样式开关在 4 tab 下仍即时生效。 |
| FR-013 | 遵守 `AGENTS.md`：不引新依赖、不改工具链、`core:common` 保持纯 JVM、UI 文案中文、提交 Conventional commits + 中文描述；改动同步修订 §2 列出的全部规格与文档。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

---

## 4. 契约与接口

### 4.1 tab 顺序与索引映射

> `AbyssIcons.icon("extension")` 命中 `Icons.Rounded.Extension`，与 `plugin-hub` 工具行原图标一致，视觉语言连贯。

### 4.2 返回栈（`AbyssBackStack`）

`showBottomBar`、`stateKey()`、`serialize()`、`restore()`、`listSaver`、`BackHandler`、`AnimatedContent` 转场 **全部不动**；`TOKEN_PLUGINHUB` 已存在，顶层化对返回栈持久化零成本。

### 4.3 `PluginHubScreen` 顶层化（最小 diff）

> 三处既有调用均为**具名参数**，`onBack = { backStack.pop() }` 赋给 `(() -> Unit)?` 合法，无需调整参数顺序；`title` 缺省「插件中心」保证详情页零回归。

### 4.4 渲染分支与入口统一

`is Screen.Tool -> when (screen.toolId)` 中的 `ToolCatalog.ID_PLUGIN_HUB -> PluginHubScreen(...)` 整段删除（`ToolCatalog` 里已无该 id，属编译期必清的死分支）。

### 4.5 功能页与数据层清理

- 文件头注释「插件永远是导航页的第一个分类」→ 改为「引擎级插件能力由底部导航『插件』独立 tab 承载；工具目录只登记主壳内置 / 插件承载的工具行」。
- `categories = ToolCategory.entries.toList()` 自动 11 → **10**；`tools.size` 自动 −1；`pluginDrivenCount` 不变（三个 `PLUGIN` 状态条目仍在），`builtInCount` 不变。
- `NavHubScreen`：`item(key = "header-${category.id}")` 内 `if (category == ToolCategory.PLUGINS) … else …` 整段收敛为 `SectionHeader(...)`；删除 `PluginHubEntry` 私有 Composable 与 `onOpenPluginHub` 参数；编译期清理随之失效的 import（`AbyssGlassSurface` / `pressScale` / `MutableInteractionSource` / `clickable` / `ToolCategory` 等）。`ToolScreen`、`DetailHeader`、`ToolRow` 不变。
- `OnboardingScreen`：三处文案 + `.filter { it != ToolCategory.PLUGINS }` 删除；`ToolCategory` import 清理。

### 4.6 与 `FEAT-033` 的协同边界（**合并顺序硬约束**）

两份 spec 都改 `AbyssRoot.kt` 的 `AbyssGlassBottomBar(...)` 调用点，但**互不重叠**：

| 文件 | FEAT-033 改什么 | FEAT-034 改什么 | 冲突 |
| --- | --- | --- | --- |
| `AbyssGlassBottomBar.kt` | 加 `indicatorStyle` 参数、两套 droplet style、4b/4d 分支 | **不改** | 无 |
| `AbyssRoot.kt` | `bottomItems` 不动；仅给 `AbyssGlassBottomBar` 加 `indicatorStyle = settings.navIndicatorStyle` | 改 `bottomItems` / `tabIndex` / `selectTab` / Screen 分支 | 同一调用点，需一次合并 |
| `SettingsScreen.kt` | 加偏好与 SegmentedButton | **不改** | 无 |
| `Glass.kt` / `GlassPalette.kt` / `AbyssLiquidRim.kt` | 三处增量 | **不改** | 无 |

**合并顺序**：先合 FEAT-034（4 tab 与路由），再在已改成 4 项的 `bottomItems` 之上叠加 FEAT-033 的单行参数；或一次性按下列终态写：

> `AbyssGlassBottomBar` 的 `count = items.size` 由 3→4，`rememberAbyssLiquidMotion(selectedIndex, itemCount = 4)` 会在首次组合时建 4 格弹簧；这与 FEAT-033 的「切换样式不得重建 motion」是两件事——**样式切换不进 `remember` key**，tab 数变化才进。

### 4.7 发布契约

| 动作 | 内容 |
| --- | --- |
| 升版策略 | 若与 `FEAT-033` **同批发布**：共用 `AppInfo.VERSION_NAME = "0.9.13"` / `VERSION_CODE = 36`，一次升版、一份 release notes，两条 spec 状态同时改「已实现（v0.9.13）」；若**分批**：先发布者占 0.9.13 / 36，另一条顺延 0.9.14 / 37，**禁止重复占用 36**（`SRS-003` versionCode 单调门禁兜底） |
| 发布要点 | `docs/release-notes/v0.9.13.md` 一行一条，含 `FEAT-034`；注明「底栏四 tab / 插件独立入口 / 功能页插件分类移除」 |
| 打 tag | `v0.9.13` → `auto-release.yml` → `assembleRelease` + 签名指纹门禁 → `publish_release.py` |
| 发布仓 | `version.json` / `patches.json` / `changelog.json` 由 CI 滚动，**禁止手改**；`patches.json` 维持 `patches: []`（vcdiff 合成引擎未接入，仍整包安装） |
| 规格同步 | 修订 `ARC-004`（顶层 tab 枚举 + 4 tab）、`ARC-010`（10 分类）、`FEAT-002`（FR-001 / AC-001）、`FEAT-003`（顶层态）、`FEAT-001`（三 tab → 四 tab）、`SRS-004`（承载形态分界表述）、`FEAT-033`（0.2 协同边界）、`SPEC-INDEX.md`（新增 FEAT-034 行）、`README.md`、`docs/ARCHITECTURE.md`、`docs/abyss-specs/深渊工具_整体增强方案.md` |

---

## 5. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 拒绝并给人话错误，不猜测、不静默降级。
- 远端数据统一走 `UiState`（Loading / Error / Data），加载中禁止展示假数据；插件索引拉取在顶层 tab 与详情页态**共用同一条管线**，不改 `FEAT-003` FR-001~006。
- **非法 tab 索引**：`selectTab` 直接 return，不改栈、不切页、不抛异常（替代旧 `else -> Settings` 的猜测）。
- **返回栈恢复**：`restore()` 读到的 `pluginhub` token 仍落到 `Screen.PluginHub`；`tabIndex` 返回 2；未知 token 继续按既有 fail-closed 丢弃，不猜。
- **顶层无上级**：「插件」tab 的 `onBack = null`，不渲染返回箭头、不提供「返回」语义；`BackHandler(enabled = canPop)` 在顶层为 false，系统返回不退出页面。
- **编译期清理**：删除 `ToolCategory.PLUGINS` / `ID_PLUGIN_HUB` 后，所有残留引用（`NavHubScreen` / `OnboardingScreen` / `AbyssRoot`）必须在 `assembleDebug` 前清干净；**不允许**用「保留枚举但 UI 过滤」绕过编译错误留死数据。
- **统计数字**：功能页 / 引导页的分类数与工具数只从 `ToolCatalog` 读，禁止手写 `11` / `N−1` 常量。
- **零回归底线**：`FROSTED` / `CLEAR` 两档液滴外观、整栏 ① 玻璃、`AbyssLiquidMotion` 弹簧与拖拽在 4 tab 下与 3 tab 时参数一致，仅槽宽随 `count` 重算。

---

## 6. 验收标准

| 编号 | 验收条件 |
| --- | --- |
| AC-001 | 底栏可见且仅 4 格，文案/图标顺序为 `仪表盘 / 功能 / 插件 / 设置`；点击每格液滴滑到对应槽位、页面正确切换、`NavAction.TAB` 淡切。 |
| AC-002 | 「插件」tab 直达插件中心页，**无返回箭头**、标题「插件」、底栏可见（4 格高亮第 3 格）；从功能页进入插件详情时仍是二级页、有返回箭头、底栏隐藏。 |
| AC-003 | 从「插件」tab 点任一插件 → `PluginDetail` → 系统返回逐层出栈回「插件」tab；不退回桌面、不跳到仪表盘。 |
| AC-004 | 旋转 / 深浅色切换 / 杀进程重建后，停在「插件」tab 且第 3 格高亮；`rememberSaveable` 反序列化的 `pluginhub` token 正常消费。 |
| AC-005 | 功能页**零插件残留**：首分类为「系统工具」，无「插件」门面卡、无「插件中心」工具行、页头统计显示 10 个分类；`ToolCatalog.categories.size == 10`。 |
| AC-006 | 新手引导文案为「四个 tab」，且不再出现「排在功能页第一位」；功能分类一览列出 10 个分类且不因 `filter` 残留编译失败。 |
| AC-007 | 仪表盘点插件入口后 `stack.size == 1`、落在「插件」tab；不存在「顶层插件页 + 二级插件中心页」两种深度。 |
| AC-008 | `selectTab(-1 / 4 / Int.MAX_VALUE)` 栈不变、页面不变、无崩溃（fail-closed）；旧 `else -> Settings` 行为已移除。 |
| AC-009 | 4 tab 小屏（320dp 宽）/ 大屏 / 折叠屏下标签不换行、图标不裁切、液滴宽度与槽宽一致；拖拽横跨 0→3 全行程吸附正确、动量外推仍为 ~0.4 tab。 |
| AC-010 | 与 `FEAT-033` 合并后：4 tab 下切换 `FROSTED`/`CLEAR` 即时生效、`CLEAR` 仍无模糊、边缘高光仍静态可见；切换不重建 `motion`、不打断进行中的拖拽。 |
| AC-011 | 纯 JVM 单测：`ToolCatalog` id 唯一、`PLUGIN` 条目 `pluginId` 非空、`categories.size == 10`、`byId("plugin-hub") == null`；`./gradlew test` 全绿。 |
| AC-012 | CI 全绿：`assembleDebug` + `assembleRelease` + `test` + `validate_manifests.py`；`version.json` 与 `changelog.json` 版本一致；真机回归 Android 15 / 16 各一台 × 深浅色 × 动态取色开/关；§4.7 列出的文档全部同步。 |

> 验收方法：纯 JVM 单测（`./gradlew test`）/ CI 门禁脚本 / 真机回归清单（Android 15 与 16 各一台）/ 通道清单校验，按单元性质择一或组合。

---

## 7. 依赖与风险

**依赖**

- `core:common`（`ToolCatalog` / `ToolCategory` / `UiState` / `SemVer` / 渠道常量）
- `core:designsystem`（`AbyssIcons` / `glass` 包 / `AbyssTheme`）
- `feature:plugins`（`PluginHubScreen` 顶层态）
- `feature:navhub` / `feature:onboarding` / `feature:dashboard`（清理与入口统一）
- `:app`（`AbyssRoot` 返回栈与路由）
- `SRS-005` 冻结工具链：Kotlin 2.3.21 / AGP 8.13.2 / Gradle 8.14 / Compose BOM 2026.02.00 / JDK 17 / compileSdk 36 / minSdk 26 / targetSdk 28；**不新增外部依赖**

**风险与缓解**

| 风险 | 缓解 |
| --- | --- |
| 删枚举后 `NavHubScreen` / `OnboardingScreen` / `AbyssRoot` 残留引用编译失败 | 已用全仓 433 文件扫描定位全部 7 个引用点，列为 FR-007/009 与附录 B 强制清单；`assembleDebug` 编译期兜底 |
| 新手引导「三个 tab」等文案漏改，用户首启即看到错误说明 | §2 单列锚点 + FR-009 + AC-006 专项验收；纳入 review checklist |
| 仪表盘仍 `push(Screen.PluginHub)` 导致同名页面两种栈深度 | FR-010 + §4.4；AC-007 断言 `stack.size == 1` |
| `tabIndex` 的 `else` 把 `PluginHub` 误高亮到「设置」 | FR-002/003 精确映射；AC-001/004 覆盖 |
| 4 tab 槽宽变窄导致标签挤/液滴错位 | `AbyssGlassBottomBar` 全参数化，仅重算 `slotWidthPx`；AC-009 覆盖 320dp 小屏与全行程拖拽 |
| 与 FEAT-033 同改 `AbyssRoot` 调用点产生合并冲突 | §4.6 给出终态代码与合并顺序；AC-010 联合验收 |
| 两个能力同批发布重复占用 versionCode 34 | §4.7 明确同批共用 / 分批顺延两种策略；`SRS-003` versionCode 单调门禁兜底 |
| 插件中心顶层态被误当成新页面重写一份 | FR-006 + §4.3 最小 diff（仅签名 + 页头）；不新增 Screen 类型、不动业务管线 |

---

## 8. 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| 0.1 | 2026-11-22 | 基于 abyss-tool 主仓 / 发布仓全量研究 + 433 文件引用点扫描创建；用户补充需求「导航栏增加插件项、把功能里的插件转移到插件里」；对齐 v0.9.10 / versionCode 33 基线；按 `N-ENG-008` 先写 spec 后实现。 |
| 0.2 | 2026-11-22 | v0.9.11 / code 34 已被当日计划外的图标更换发版占用（`SRS-003` versionCode 单调约束）→ §4.7 / 附录 B 的发布契约整体顺延为 **v0.9.13 / 36**。 |
| 0.4 | 2026-11-22 | v0.9.12 / code 35 已被 N-SEC-001/002 批次（PR #12）占用 → 本批次发布契约整体顺延 **v0.9.13 / 36**；同时修正研究口径：`ToolCategory` 实际 11 项（非 12）→ 移除 PLUGINS 后 **10 分类**，测试与文档数字同步修正。 |

---

## 附录 A · 决策依据（为什么这么切）

**1. 这次改动几乎是「把已有能力扶正」，不是新建页面。**  
`Screen.PluginHub` 早已存在，`stateKey()` 返回 `"pluginhub"`，`serialize/restore` 已支持该 token，`AbyssGlassBottomBar` 的槽宽/步长/弹簧全部按 `items.size` 参数化。所以真正的代码改动集中在三处：`bottomItems` 加一项、`tabIndex`/`selectTab` 精确映射、`PluginHubScreen` 的 `onBack` 可空化。剩下都是「删除与同步」。

**2. 功能页里的插件入口其实有两个，不删干净会留双份。**  
`NavHubScreen` 对 `PLUGINS` 分类先画一张 `PluginHubEntry` 门面卡，随后 `items(count = toolsOf(category).size)` 又渲染一条「插件中心」工具行（点它走 `ToolCatalog.ID_PLUGIN_HUB -> PluginHubScreen`）。用户说「转移」，定稿就把枚举、工具行、常量、门面卡、路由分支、参数一次性删光——只留「插件」tab 一个入口。保留枚举仅做 UI 过滤会留下死数据，且 `ToolCatalog.categories.size` 仍把它算进去，页头统计永远对不上。

**3. 顶层页不该有返回箭头。**  
`PluginHubScreen` 的 `onBack` 是必填参数，页头硬编码 `IconButton(ArrowBack)`。若直接把它当 tab 用，会出现「顶层页有个返回键，点了却回到功能页」的语义错位。改成可空 + `title` 参数，一处组件服务两种深度，比复制一份 83KB 的 `PluginTabScreen` 更省、更符合 `AGENTS.md` 的单一事实源精神。

**4. tab 顺序为什么把「插件」放第 3 格。**

- 前两格 `仪表盘 / 功能` 的 index 0/1 保持不变，老用户肌肉记忆与 `rememberSaveable` 恢复的栈语义都不受影响；
- 「设置」保持末位是 Android 惯例，也避免改动 `selectTab` 之外任何兜底逻辑；
- 「插件」紧邻「功能」，正好表达「从功能里移出来」这次信息架构调整。  
  备选 `仪表盘 / 插件 / 功能 / 设置` 会让 index 1 语义漂移，不采用；若产品希望插件更靠前，只改 `bottomItems` 顺序 + `tabIndex`/`selectTab` 三处映射即可，**不要**动 `glass` 包。

**5. 仪表盘入口必须一起改。**  
`AbyssRoot` 现在给 `DashboardScreen` 传的是 `onOpenPlugins = { backStack.push(Screen.PluginHub) }`。插件升级成顶层 tab 后若维持 push，就会出现「同一个插件中心，有时是顶层、有时是二级页」——底栏时有时无、状态 holder 复用同一 key，行为很别扭。统一改成 `selectTab(2)`，全 app 只有一个插件中心深度。

**6. 文档同步不是可选项。**  
仓库把「插件永远排第一分类」写进了 `ToolCatalog.kt` 注释、`FEAT-002` FR-001、`SRS-004`、`README`、新手引导文案、`ARC-010` 的「12 分类」、`ARC-004`/`FEAT-001` 的「三 tab」。这些是 spec-lint 与 review checklist 会核对的硬约束，漏一处就会出现「代码四 tab、文档三 tab」的漂移。附录 B 已逐条列出。

---

## 附录 B · 改动文件清单

| 路径 | 动作 | 要点 |
| --- | --- | --- |
| `app/.../ui/AbyssRoot.kt` | 改 | `bottomItems` 4 项；`tabIndex` / `selectTab` 精确 4 映射；`Screen.PluginHub` 顶层分支 `onBack=null, title="插件"`；删 `ID_PLUGIN_HUB ->` 分支；删 `NavHubScreen(onOpenPluginHub=…)`；`onOpenPlugins = selectTab(2)`；与 FEAT-033 合并时加 `indicatorStyle` 一行 |
| `core/common/.../ToolCatalog.kt` | 改 | 删 `ToolCategory.PLUGINS` / `ID_PLUGIN_HUB` / `plugin-hub` ToolDef；改文件头注释 |
| `feature/navhub/.../NavHubScreen.kt` | 改 | 删 `PluginHubEntry` / `onOpenPluginHub` / PLUGINS 特殊分支；统一 `SectionHeader`；清理失效 import |
| `feature/plugins/.../PluginScreens.kt` | 改 | `onBack` 可空 + `title` 参数；页头条件渲染返回箭头与左右 padding；**业务逻辑零改动** |
| `feature/onboarding/.../OnboardingScreen.kt` | 改 | 三 tab → 四 tab 文案；删 `.filter { it != ToolCategory.PLUGINS }`；「插件（独立 tab）」；清理 import |
| `core/common/src/test/.../ToolCatalogTest.kt`（若存在） | 改 | 断言 `categories.size == 10`、`byId("plugin-hub") == null`、id 唯一、`PLUGIN.pluginId` 非空 |
| `core/common/.../AppInfo.kt` | 改 | 0.9.13 / 36（同批）或按 §4.7 顺延 |
| `docs/release-notes/v0.9.13.md` | 新增 | 发布要点含 FEAT-034（同批时并列 FEAT-033） |
| `docs/abyss-specs/03-功能模块/FEAT-034-….md` | 新增 | 本文件 |
| `docs/abyss-specs/03-功能模块/FEAT-033-….md` | 改 | 0.2：关联规格、4 tab 协同边界、附录 B 同步 |
| `docs/abyss-specs/03-功能模块/FEAT-002-分类导航NavHub.md` | 改 | FR-001「PLUGINS 恒为首项」→「首分类 SYSTEM」；AC-001 12 → 10 |
| `docs/abyss-specs/03-功能模块/FEAT-003-插件中心页PluginScreens.md` | 改 | 补「顶层 tab 态 / 详情页态」双形态契约 |
| `docs/abyss-specs/03-功能模块/FEAT-001-仪表盘Dashboard.md` | 改 | 三 tab → 四 tab；插件入口改 selectTab |
| `docs/abyss-specs/01-架构/ARC-004-导航与返回栈.md` | 改 | §4 顶层 tab 枚举补 `PluginHub`；补 4 tab 索引映射 |
| `docs/abyss-specs/01-架构/ARC-010-工具目录与分类数据字典.md` | 改 | §4「12 分类」→「10 分类」并去掉 `plugins` |
| `docs/abyss-specs/00-总纲/SRS-004-承载形态分界纪律.md` | 改 | 「插件永远第一分类」表述 |
| `docs/abyss-specs/SPEC-INDEX.md` | 改 | 新增 `FEAT-034` 行 |
| `docs/abyss-specs/深渊工具_整体增强方案.md` | 改 | 「12 分类 / 三 tab」描述 |
| `README.md` | 改 | 主界面框架图（插件独立 tab）、模块地图「三 tab → 四 tab」 |
| `docs/ARCHITECTURE.md` | 改 | 导航与底栏小节 |
| 发布仓三件套 | **CI 自动** | 禁止手改 |

---

## 附录 C · 实现顺序建议

1. **数据层先行**：`ToolCatalog.kt` 删枚举 / 常量 / 工具行 → 编译必然报出 7 处残留引用，按附录 B 逐个清理（这是最可靠的「不漏改」保障）。
2. **组件双形态**：`PluginScreens.kt` 改 `onBack` 可空 + `title`，先跑 `./gradlew test` 确认插件业务逻辑零回归。
3. **导航装配**：`AbyssRoot.kt` 改 `bottomItems` / `tabIndex` / `selectTab` / Screen 分支 / 仪表盘入口；此时 4 tab 已可跑。
4. **文案同步**：`OnboardingScreen` 与全部规格 / README / 架构文档一次性改完，避免文档漂移。
5. **与 FEAT-033 合并**：按 §4.6 终态代码叠加 `indicatorStyle` 一行，联合跑 AC-010。
6. 全量 AC（含 320dp 小屏、旋转恢复、拖拽全行程、两档样式）→ 升版 → 修订既有 spec 状态 → 打 tag。
