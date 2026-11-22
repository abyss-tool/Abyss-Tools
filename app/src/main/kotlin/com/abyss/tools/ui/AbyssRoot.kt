package com.abyss.tools.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abyss.tools.ServiceRegistry
import com.abyss.tools.core.common.AppInfo
import com.abyss.tools.core.common.ToolCatalog
import com.abyss.tools.core.designsystem.component.AbyssIcons
import com.abyss.tools.core.designsystem.component.NAV_TRANSITION_MS
import com.abyss.tools.core.designsystem.component.rememberReducedMotion
import com.abyss.tools.core.designsystem.glass.AbyssBottomBarHeight
import com.abyss.tools.core.designsystem.glass.AbyssGlassBottomBar
import com.abyss.tools.core.designsystem.glass.AbyssNavItem
import com.abyss.tools.core.designsystem.glass.LocalGlassIntensity
import com.abyss.tools.core.designsystem.glass.LocalGlassLensEnabled
import com.abyss.tools.core.designsystem.glass.abyssBackdropSource
import com.abyss.tools.core.designsystem.glass.rememberAbyssBackdrop
import com.abyss.tools.core.shell.ShizukuStatus
import com.abyss.tools.feature.appmgr.AppDetailScreen
import com.abyss.tools.feature.appmgr.AppManagerScreen
import com.abyss.tools.feature.appmgr.AbyssAppInfo
import com.abyss.tools.feature.backup.BackupScreen
import com.abyss.tools.feature.blueprint.BlueprintScreen
import com.abyss.tools.feature.blueprint.BlueprintTab
import com.abyss.tools.feature.dashboard.DashboardScreen
import com.abyss.tools.feature.dashboard.ShizukuUi
import com.abyss.tools.feature.disguise.BatterySpoofScreen
import com.abyss.tools.feature.disguise.LocationSpoofScreen
import com.abyss.tools.feature.disguise.ModelSpoofScreen
import com.abyss.tools.feature.disguise.SignalSpoofScreen
import com.abyss.tools.feature.island.IslandScreen
import com.abyss.tools.feature.media.MediaLabScreen
import com.abyss.tools.feature.navhub.NavHubScreen
import com.abyss.tools.feature.navhub.ToolScreen
import com.abyss.tools.feature.netaudit.NetAuditScreen
import com.abyss.tools.feature.plugins.PluginDetailScreen
import com.abyss.tools.feature.plugins.PluginHubScreen
import com.abyss.tools.feature.qrcraft.QrCraftScreen
import com.abyss.tools.feature.sandbox.SandboxScreen
import com.abyss.tools.feature.scriptlab.ScriptLabScreen
import com.abyss.tools.feature.settings.SettingsScreen
import com.abyss.tools.feature.settings.SettingsSectionScreen
import com.abyss.tools.feature.settings.SettingsSections
import com.abyss.tools.feature.shizukukit.ShizukuKitScreen
import com.abyss.tools.feature.syspower.SysControlScreen
import com.abyss.tools.feature.terminal.TerminalScreen
import com.abyss.tools.feature.wallpaper.WallpaperScreen
import com.abyss.tools.feature.widgets.WidgetStudioScreen
import com.kyant.backdrop.backdrops.LayerBackdrop

/**
 * 屏幕路由（自维护返回栈 —— 框架期不引 navigation-compose，跳转语义
 * 全部收敛在 AbyssBackStack，后续接深链 / 多任务恢复时再升级）。
 *
 * v0.6.0 起返回栈可序列化（评审 #10）：旋转 / 深浅色切换 / 进程重建后
 * 经 rememberSaveable 恢复。AppManagerDetail 只序列化包名键，恢复后由
 * AbyssRoot 经 AppInfoProvider 异步重挂（查不到则诚实留在列表页）。
 */
sealed interface Screen {
    data object Dashboard : Screen
    data object NavHub : Screen
    data object Settings : Screen
    data object PluginHub : Screen
    data class PluginDetail(val pluginId: String) : Screen
    data class Tool(val toolId: String) : Screen

    /** 设置分区子页（FEAT-035）：sectionId ∈ SettingsSections，未知 id fail-closed 丢弃。 */
    data class SettingsSection(val sectionId: String) : Screen

    /** 应用管理器 → 应用详情（v0.4：冻结 / 强停 / 提取等真实动作）。 */
    data class AppManagerDetail(val app: AbyssAppInfo) : Screen
}

/**
 * 屏幕的 SaveableStateProvider 键（v0.8.5 滚动位置保持）：与序列化 token
 * 同源 —— 每个屏幕实例一个稳定键，推入详情页再返回时列表滚动位置 /
 * 入场动画已播标记等 rememberSaveable 状态全部原位恢复（不再跳回顶部）。
 */
private fun Screen.stateKey(): String = when (this) {
    Screen.Dashboard -> "dashboard"
    Screen.NavHub -> "navhub"
    Screen.Settings -> "settings"
    Screen.PluginHub -> "pluginhub"
    is Screen.SettingsSection -> "settings:$sectionId"
    is Screen.PluginDetail -> "plugin:$pluginId"
    is Screen.Tool -> "tool:$toolId"
    is Screen.AppManagerDetail -> "appmgr:${app.packageName}"
}

/**
 * 导航动作（v0.7.6）：屏幕转场动效的方向依据 —— push 新页自右滑入、
 * pop 反向滑出、tab 淡切。运行时状态，不参与序列化 —— 进程恢复后
 * 首帧按 TAB 处理（恢复现场不播放推入动画）。
 */
enum class NavAction { PUSH, POP, TAB }

/**
 * 深渊工具主壳：
 *
 *  ┌────────────────────────────┐
 *  │ 内容层（玻璃采样源）        │  ← 仪表盘 / 导航 / 设置 / 插件页 / 伪装四件套
 *  │ …内容从玻璃件下方滚过…      │
 *  ├────────────────────────────┤
 *  │ ╭─ 液态玻璃 Dark 底栏 ╮    │  ← blur + lens 真实折射背后内容
 *  │ ╰──────────────────╯      │
 *  └────────────────────────────┘
 *
 * 底栏仅在顶层 tab（stack.size == 1）显示；推入详情页后沉浸全屏。
 * 主题（AbyssTheme）在 MainActivity 层统一包裹 —— 首启新手引导与
 * 主壳共享同一套色彩体系。
 */
class AbyssBackStack internal constructor(initialStack: List<Screen>) {

    private var stack by mutableStateOf(initialStack.ifEmpty { listOf(Screen.Dashboard) })

    /** 最近一次导航动作（v0.7.6）：转场动效的方向依据，随动作即时更新。 */
    var lastAction by mutableStateOf(NavAction.TAB)
        private set

    /** 恢复出的待重挂应用详情包名（一次性；查不到则丢弃，诚实降级）。 */
    var pendingAppDetailPackage: String? = null

    val current: Screen get() = stack.last()
    val canPop: Boolean get() = stack.size > 1
    val showBottomBar: Boolean get() = stack.size == 1

    val tabIndex: Int
        get() = when (current) {
            Screen.Dashboard -> 0
            Screen.NavHub -> 1
            Screen.PluginHub -> 2
            Screen.Settings -> 3
            // 详情页（FEAT-034 FR-002）：底栏隐藏（showBottomBar == false），
            // 无 tab 语义 —— fail-closed 返回越界值，绝不误高亮任何一格。
            is Screen.PluginDetail, is Screen.Tool, is Screen.AppManagerDetail,
            is Screen.SettingsSection,
            -> -1
        }

    fun push(screen: Screen) {
        lastAction = NavAction.PUSH
        stack = stack + screen
    }

    fun pop() {
        if (canPop) {
            lastAction = NavAction.POP
            stack = stack.dropLast(1)
        }
    }

    fun selectTab(index: Int) {
        // FEAT-034 FR-003：显式覆盖 0/1/2/3；非法索引 fail-closed 直接 return
        // —— 不改栈、不切页、不抛（替代旧 else -> Settings 的猜测行为）。
        val target = when (index) {
            0 -> Screen.Dashboard
            1 -> Screen.NavHub
            2 -> Screen.PluginHub
            3 -> Screen.Settings
            else -> return
        }
        lastAction = NavAction.TAB
        stack = listOf(target)
    }

    /** 序列化（rememberSaveable 消费；AppManagerDetail 只存包名键）。 */
    fun serialize(): List<String> = stack.map { screen ->
        when (screen) {
            Screen.Dashboard -> TOKEN_DASHBOARD
            Screen.NavHub -> TOKEN_NAVHUB
            Screen.Settings -> TOKEN_SETTINGS
            Screen.PluginHub -> TOKEN_PLUGINHUB
            is Screen.SettingsSection -> "$PREFIX_SETTINGS${screen.sectionId}"
            is Screen.PluginDetail -> "$PREFIX_PLUGIN${screen.pluginId}"
            is Screen.Tool -> "$PREFIX_TOOL${screen.toolId}"
            is Screen.AppManagerDetail -> "$PREFIX_APPMGR${screen.app.packageName}"
        }
    }

    companion object {
        private const val TOKEN_DASHBOARD = "dashboard"
        private const val TOKEN_NAVHUB = "navhub"
        private const val TOKEN_SETTINGS = "settings"
        private const val TOKEN_PLUGINHUB = "pluginhub"
        private const val PREFIX_SETTINGS = "settings:"
        private const val PREFIX_PLUGIN = "plugin:"
        private const val PREFIX_TOOL = "tool:"
        private const val PREFIX_APPMGR = "appmgr:"

        /** 恢复（fail-closed：未知 token 丢弃，不猜；空栈回 Dashboard）。 */
        fun restore(tokens: List<String>): AbyssBackStack {
            val screens = mutableListOf<Screen>()
            var pending: String? = null
            for (token in tokens) {
                when {
                    token == TOKEN_DASHBOARD -> screens += Screen.Dashboard
                    token == TOKEN_NAVHUB -> screens += Screen.NavHub
                    token == TOKEN_SETTINGS -> screens += Screen.Settings
                    token == TOKEN_PLUGINHUB -> screens += Screen.PluginHub
                    // FEAT-035：设置分区子页 —— 未知 sectionId 整 token 丢弃（fail-closed）
                    token.startsWith(PREFIX_SETTINGS) -> {
                        val id = token.removePrefix(PREFIX_SETTINGS)
                        if (SettingsSections.byId(id) != null) screens += Screen.SettingsSection(id)
                    }
                    token.startsWith(PREFIX_PLUGIN) -> {
                        val id = token.removePrefix(PREFIX_PLUGIN)
                        if (id.isNotBlank()) screens += Screen.PluginDetail(id)
                    }
                    token.startsWith(PREFIX_TOOL) -> screens += Screen.Tool(token.removePrefix(PREFIX_TOOL))
                    token.startsWith(PREFIX_APPMGR) -> {
                        val pkg = token.removePrefix(PREFIX_APPMGR)
                        if (pkg.isNotBlank()) pending = pkg
                    }
                    // 未知 token：丢弃（fail-closed）
                }
            }
            if (screens.isEmpty()) screens += Screen.Dashboard
            return AbyssBackStack(screens).apply { pendingAppDetailPackage = pending }
        }
    }
}

// FEAT-034：底栏 3 tab → 4 tab，「插件」为独立入口（index 2，直达顶层
// 插件中心）；等宽槽位与液滴步长由 AbyssGlassBottomBar 按 items.size 参数化。
private val bottomItems = listOf(
    AbyssNavItem(AbyssIcons.icon("dashboard"), "仪表盘"),
    AbyssNavItem(AbyssIcons.icon("explore"), "功能"),
    AbyssNavItem(AbyssIcons.icon("extension"), "插件"),
    AbyssNavItem(AbyssIcons.icon("settings"), "设置"),
)

@Composable
fun AbyssRoot(
    shizukuStatus: ShizukuStatus,
    onRequestShizukuPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onReopenOnboarding: () -> Unit,
    glassEnabled: Boolean = true,
    lensEnabled: Boolean = true,
) {
    val registry = ServiceRegistry
    // v0.9.6：glassEnabled 保留参数位用于 API 能力降级；单次会话内 runCatching
    // 单项降级（blur/lens/renderEffect 各自守卫），不再由跨会话断路器控制。
    val backdrop: LayerBackdrop? = if (glassEnabled) rememberAbyssBackdrop() else null
    // 返回栈持久化（评审 #10）：旋转 / 进程重建后原位恢复；应用详情经
    // Provider 异步重挂（包名查不到则诚实留在列表页，绝不展示猜测数据）。
    val backStack = rememberSaveable(
        saver = listSaver<AbyssBackStack, String>(
            save = { it.serialize() },
            restore = { tokens -> AbyssBackStack.restore(tokens) },
        ),
    ) { AbyssBackStack(listOf(Screen.Dashboard)) }
    val settings = registry.settings

    // 恢复出的待重挂应用详情（一次性消费）
    LaunchedEffect(backStack.pendingAppDetailPackage) {
        val pkg = backStack.pendingAppDetailPackage ?: return@LaunchedEffect
        backStack.pendingAppDetailPackage = null
        val onAppManager = (backStack.current as? Screen.Tool)?.toolId == ToolCatalog.ID_APP_MANAGER
        if (!onAppManager) return@LaunchedEffect
        val app = registry.appInfoProvider.installedApps()
            .firstOrNull { it.packageName == pkg }
        if (app != null) backStack.push(Screen.AppManagerDetail(app))
    }

    // 插件本机状态版本号：安装 / 卸载 / 补丁应用后自增 —— 驱动插件中心
    // 「我的插件」分区、安装徽章与仪表盘统计重读磁盘。
    var pluginStateVersion by remember { mutableStateOf(0) }
    val bumpPluginState = { pluginStateVersion += 1 }
    val installedPluginCount = remember(pluginStateVersion) {
        registry.pluginInstaller.installed().size
    }

    BackHandler(enabled = backStack.canPop) { backStack.pop() }

    // 屏幕级状态保持（v0.8.5）：每个屏幕在 SaveableStateHolder 下组合 ——
    // push 详情页时旧屏的 rememberSaveable（列表滚动位置 / 入场已播标记）
    // 自动存入 holder；pop 回来时原位恢复。此前 AnimatedContent 直接换
    // 内容会销毁旧屏，返回后列表一律跳回顶部（用户明确反馈的痛点）。
    val stateHolder = rememberSaveableStateHolder()

    val bottomClearance = AbyssBottomBarHeight + 24.dp

    // 系统移除动画（无障碍）→ 屏幕转场直切；卡片入场 / 按压反馈由
    // Motion.kt 同款开关各自把关。
    val reducedMotion = rememberReducedMotion()

    CompositionLocalProvider(
        LocalGlassIntensity provides settings.glassIntensity,
        // 分级降级（v0.7.8）：NO_LENS 档全玻璃件跳过 AGSL lens，其余效果保留
        LocalGlassLensEnabled provides lensEnabled,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            // 内容层 = 玻璃采样源：内容滚过底栏下方时被真实采样（采样源
            // 本体保留 —— 底栏折射需要它；页内玻璃卡不接 backdrop）。
            // （安全模式下无采样源 —— 修饰符链保持空操作，玻璃件全 Frosted）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (backdrop != null) Modifier.abyssBackdropSource(backdrop) else Modifier),
            ) {
                // 屏幕转场（v0.7.6）：方向随返回栈语义 —— push 新页自右
                // 滑入（旧页视差左移让位），pop 反向，tab 平级淡切；
                // 系统移除动画时直切（无障碍，见 rememberReducedMotion）。
                AnimatedContent(
                    targetState = backStack.current,
                    transitionSpec = {
                        if (reducedMotion) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else {
                            when (backStack.lastAction) {
                                NavAction.PUSH ->
                                    (slideInHorizontally(tween(NAV_TRANSITION_MS)) { it / 4 } + fadeIn(tween(NAV_TRANSITION_MS))) togetherWith
                                        (slideOutHorizontally(tween(NAV_TRANSITION_MS)) { -it / 6 } + fadeOut(tween(NAV_TRANSITION_MS)))
                                NavAction.POP ->
                                    (slideInHorizontally(tween(NAV_TRANSITION_MS)) { -it / 6 } + fadeIn(tween(NAV_TRANSITION_MS))) togetherWith
                                        (slideOutHorizontally(tween(NAV_TRANSITION_MS)) { it / 4 } + fadeOut(tween(NAV_TRANSITION_MS)))
                                NavAction.TAB ->
                                    fadeIn(tween(NAV_TRANSITION_MS)) togetherWith fadeOut(tween(NAV_TRANSITION_MS / 2))
                            }
                        }
                    },
                    label = "abyss-screen",
                ) { screen ->
                    stateHolder.SaveableStateProvider(key = screen.stateKey()) {
                        when (screen) {
                            Screen.Dashboard -> DashboardScreen(
                                // 玻璃采样收敛（v0.8.0）：真实液态玻璃仅底栏一处 ——
                                // 页内玻璃卡全 Frosted（GPU 驱动崩溃排查中，页内玻璃
                                // 后续再设计；恢复 = 本参数传回 backdrop 即可）
                                backdrop = null,
                                pluginRepository = registry.pluginRepository,
                                updateManager = registry.updateManager,
                                onOpenTool = { backStack.push(Screen.Tool(it)) },
                                // FEAT-034 FR-010：仪表盘插件入口统一切到「插件」
                                // 顶层 tab（stack.size == 1），不再产生二级同名页。
                                onOpenPlugins = { backStack.selectTab(2) },
                                bottomClearance = bottomClearance,
                                installedPluginCount = installedPluginCount,
                                autoCheckUpdates = settings.checkOnLaunch,
                                shizukuUi = shizukuStatus.toShizukuUi(),
                                installPermGranted = registry.installPermGranted(),
                            )

                            Screen.NavHub -> NavHubScreen(
                                bottomClearance = bottomClearance,
                                onOpenTool = { backStack.push(Screen.Tool(it)) },
                            )

                            Screen.Settings -> SettingsScreen(
                                settings = registry.settings,
                                // FEAT-035：分区入口 → push 子页（栈深 2、底栏隐藏）
                                onOpenSection = { backStack.push(Screen.SettingsSection(it)) },
                                launcherIcons = registry.launcherIconGateway,
                                bottomClearance = bottomClearance,
                            )

                            is Screen.SettingsSection -> SettingsSectionScreen(
                                sectionId = screen.sectionId,
                                settings = registry.settings,
                                updateManager = registry.updateManager,
                                versionName = AppInfo.VERSION_NAME,
                                launcherIcons = registry.launcherIconGateway,
                                onClearPluginCache = { registry.pluginInstaller.clearCache() },
                                onRestartOnboarding = onReopenOnboarding,
                                onBack = { backStack.pop() },
                            )

                            // FEAT-034 FR-004：插件中心升为顶层 tab —— onBack = null
                            //（无返回箭头），标题与 tab 文案一致；详情页语义零变化。
                            Screen.PluginHub -> PluginHubScreen(
                                repository = registry.pluginRepository,
                                channelClient = registry.channelClient,
                                installer = registry.pluginInstaller,
                                onBack = null,
                                onOpenPlugin = { backStack.push(Screen.PluginDetail(it)) },
                                stateVersion = pluginStateVersion,
                                onPluginsChanged = bumpPluginState,
                                mirrorRouter = registry.mirrorRouter,
                                mirrorPreference = settings.downloadMirror,
                                onMirrorPreference = { settings.downloadMirror = it },
                                title = "插件",
                                // FEAT-037：深渊agent 置顶直达
                                agentRelease = registry.agentRelease,
                            )

                            is Screen.PluginDetail -> PluginDetailScreen(
                                pluginId = screen.pluginId,
                                repository = registry.pluginRepository,
                                channelClient = registry.channelClient,
                                installer = registry.pluginInstaller,
                                // 玻璃采样收敛（v0.8.0）：同 Dashboard —— 页内玻璃全 Frosted
                                backdrop = null,
                                onBack = { backStack.pop() },
                                onPluginsChanged = bumpPluginState,
                            )

                            is Screen.AppManagerDetail -> AppDetailScreen(
                                app = screen.app,
                                provider = registry.appInfoProvider,
                                gateway = registry.shellGateway,
                                shizukuStatus = shizukuStatus,
                                onRequestShizukuPermission = onRequestShizukuPermission,
                                onOpenShizuku = onOpenShizuku,
                                onBack = { backStack.pop() },
                            )

                            is Screen.Tool -> when (screen.toolId) {
                                // ── 伪装四件套（v0.3 内置化：真实功能页）──────────
                                ToolCatalog.ID_BATTERY_DISGUISE -> BatterySpoofScreen(
                                    shizukuStatus = shizukuStatus,
                                    gateway = registry.shellGateway,
                                    settings = registry.disguiseSettings,
                                    onRequestShizukuPermission = onRequestShizukuPermission,
                                    onOpenShizuku = onOpenShizuku,
                                    onBack = { backStack.pop() },
                                )

                                ToolCatalog.ID_MODEL_DISGUISE -> ModelSpoofScreen(
                                    shizukuStatus = shizukuStatus,
                                    gateway = registry.shellGateway,
                                    settings = registry.disguiseSettings,
                                    onRequestShizukuPermission = onRequestShizukuPermission,
                                    onOpenShizuku = onOpenShizuku,
                                    onBack = { backStack.pop() },
                                )

                                ToolCatalog.ID_SIGNAL_DISGUISE -> SignalSpoofScreen(
                                    shizukuStatus = shizukuStatus,
                                    gateway = registry.shellGateway,
                                    settings = registry.disguiseSettings,
                                    onRequestShizukuPermission = onRequestShizukuPermission,
                                    onOpenShizuku = onOpenShizuku,
                                    onBack = { backStack.pop() },
                                )

                                ToolCatalog.ID_LOCATION_DISGUISE -> LocationSpoofScreen(
                                    shizukuStatus = shizukuStatus,
                                    gateway = registry.shellGateway,
                                    settings = registry.disguiseSettings,
                                    onRequestShizukuPermission = onRequestShizukuPermission,
                                    onOpenShizuku = onOpenShizuku,
                                    onBack = { backStack.pop() },
                                )

                                // ── v0.4：七个内置工具落地真实功能页 ──────────────────
                                ToolCatalog.ID_APP_MANAGER -> AppManagerScreen(
                                    provider = registry.appInfoProvider,
                                    gateway = registry.shellGateway,
                                    shizukuStatus = shizukuStatus,
                                    onRequestShizukuPermission = onRequestShizukuPermission,
                                    onOpenShizuku = onOpenShizuku,
                                    onBack = { backStack.pop() },
                                    onOpenApp = { backStack.push(Screen.AppManagerDetail(it)) },
                                )

                                ToolCatalog.ID_SHIZUKU_KIT -> ShizukuKitScreen(
                                    gateway = registry.shellGateway,
                                    shizukuStatus = shizukuStatus,
                                    onRequestShizukuPermission = onRequestShizukuPermission,
                                    onOpenShizuku = onOpenShizuku,
                                    onBack = { backStack.pop() },
                                )

                                ToolCatalog.ID_QR_CRAFT -> QrCraftScreen(
                                    onBack = { backStack.pop() },
                                )

                                ToolCatalog.ID_TASK_BLUEPRINT -> BlueprintScreen(
                                    gateway = registry.shellGateway,
                                    shizukuStatus = shizukuStatus,
                                    onRequestShizukuPermission = onRequestShizukuPermission,
                                    onOpenShizuku = onOpenShizuku,
                                    onBack = { backStack.pop() },
                                )

                                ToolCatalog.ID_AUTOMATION -> BlueprintScreen(
                                    gateway = registry.shellGateway,
                                    shizukuStatus = shizukuStatus,
                                    onRequestShizukuPermission = onRequestShizukuPermission,
                                    onOpenShizuku = onOpenShizuku,
                                    onBack = { backStack.pop() },
                                    initialTab = BlueprintTab.AUTOMATION,
                                )

                                ToolCatalog.ID_DYNAMIC_ISLAND -> IslandScreen(
                                    onBack = { backStack.pop() },
                                )

                                ToolCatalog.ID_LIVE_WALLPAPER -> WallpaperScreen(
                                    onBack = { backStack.pop() },
                                )

                                // ── v0.8.0：六插件内置化 + 新能力落地真实功能页 ──────
                                ToolCatalog.ID_TERMINAL -> TerminalScreen(
                                    shizukuStatus = shizukuStatus,
                                    gateway = registry.shellGateway,
                                    onRequestShizukuPermission = onRequestShizukuPermission,
                                    onOpenShizuku = onOpenShizuku,
                                    onBack = { backStack.pop() },
                                )

                                ToolCatalog.ID_SCRIPT_LAB -> ScriptLabScreen(
                                    shizukuStatus = shizukuStatus,
                                    gateway = registry.shellGateway,
                                    onRequestShizukuPermission = onRequestShizukuPermission,
                                    onOpenShizuku = onOpenShizuku,
                                    onBack = { backStack.pop() },
                                )

                                ToolCatalog.ID_SANDBOX_SPACE, ToolCatalog.ID_DUAL_OPEN -> SandboxScreen(
                                    shizukuStatus = shizukuStatus,
                                    gateway = registry.shellGateway,
                                    onRequestShizukuPermission = onRequestShizukuPermission,
                                    onOpenShizuku = onOpenShizuku,
                                    onBack = { backStack.pop() },
                                )

                                ToolCatalog.ID_MEDIA_LAB -> MediaLabScreen(
                                    onBack = { backStack.pop() },
                                )

                                ToolCatalog.ID_NET_AUDIT -> NetAuditScreen(
                                    shizukuStatus = shizukuStatus,
                                    gateway = registry.shellGateway,
                                    onBack = { backStack.pop() },
                                )

                                ToolCatalog.ID_BACKUP -> BackupScreen(
                                    onBack = { backStack.pop() },
                                )

                                ToolCatalog.ID_SYS_CONTROL -> SysControlScreen(
                                    shizukuStatus = shizukuStatus,
                                    gateway = registry.shellGateway,
                                    onRequestShizukuPermission = onRequestShizukuPermission,
                                    onOpenShizuku = onOpenShizuku,
                                    onBack = { backStack.pop() },
                                )

                                ToolCatalog.ID_WIDGET_STUDIO -> WidgetStudioScreen(
                                    onOpenTool = { backStack.push(Screen.Tool(it)) },
                                    onBack = { backStack.pop() },
                                )

                                // ── 其余工具：通用占位页（内置卡 / 插件卡 / 路线图卡）──
                                else -> ToolScreen(
                                    toolId = screen.toolId,
                                    onOpenPlugin = { pluginId ->
                                        if (pluginId.isBlank()) {
                                            // FEAT-034 AC-007：全 app 只有一个插件中心
                                            // 深度 —— 统一落顶层「插件」tab，不再 push
                                            // 二级插件中心页。
                                            backStack.selectTab(2)
                                        } else {
                                            backStack.push(Screen.PluginDetail(pluginId))
                                        }
                                    },
                                    onBack = { backStack.pop() },
                                )
                            }
                        }
                    }
                }
            }

            // 液态玻璃底栏（悬浮于内容之上，真实折射背后内容）
            if (backStack.showBottomBar) {
                AbyssGlassBottomBar(
                    backdrop = backdrop,
                    items = bottomItems,
                    selectedIndex = backStack.tabIndex,
                    onSelect = { backStack.selectTab(it) },
                    // FEAT-033：滑动指示器双样式（FROSTED 默认 / CLEAR 清透玻璃）；
                    // 档位只换 droplet 绘制参数，不进 motion 的 remember key。
                    indicatorStyle = settings.navIndicatorStyle,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** core:shell 状态 → 仪表盘徽章语义。 */
private fun ShizukuStatus.toShizukuUi(): ShizukuUi = when (val s = this) {
    ShizukuStatus.NotRunning -> ShizukuUi.NotRunning
    is ShizukuStatus.Running ->
        if (s.authorized) ShizukuUi.Authorized else ShizukuUi.RunningUnauthorized
}
