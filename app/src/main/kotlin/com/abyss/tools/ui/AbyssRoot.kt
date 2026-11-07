package com.abyss.tools.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abyss.tools.ServiceRegistry
import com.abyss.tools.core.common.AppInfo
import com.abyss.tools.core.common.ToolCatalog
import com.abyss.tools.core.designsystem.component.AbyssIcons
import com.abyss.tools.core.designsystem.glass.AbyssBottomBarHeight
import com.abyss.tools.core.designsystem.glass.AbyssGlassBottomBar
import com.abyss.tools.core.designsystem.glass.AbyssNavItem
import com.abyss.tools.core.designsystem.glass.LocalGlassIntensity
import com.abyss.tools.core.designsystem.glass.abyssBackdropSource
import com.abyss.tools.core.designsystem.glass.rememberAbyssBackdrop
import com.abyss.tools.core.shell.ShizukuStatus
import com.abyss.tools.feature.appmgr.AppDetailScreen
import com.abyss.tools.feature.appmgr.AppManagerScreen
import com.abyss.tools.feature.appmgr.AbyssAppInfo
import com.abyss.tools.feature.blueprint.BlueprintScreen
import com.abyss.tools.feature.blueprint.BlueprintTab
import com.abyss.tools.feature.dashboard.DashboardScreen
import com.abyss.tools.feature.dashboard.ShizukuUi
import com.abyss.tools.feature.disguise.BatterySpoofScreen
import com.abyss.tools.feature.disguise.LocationSpoofScreen
import com.abyss.tools.feature.disguise.ModelSpoofScreen
import com.abyss.tools.feature.disguise.SignalSpoofScreen
import com.abyss.tools.feature.island.IslandScreen
import com.abyss.tools.feature.navhub.NavHubScreen
import com.abyss.tools.feature.navhub.ToolScreen
import com.abyss.tools.feature.plugins.PluginDetailScreen
import com.abyss.tools.feature.plugins.PluginHubScreen
import com.abyss.tools.feature.qrcraft.QrCraftScreen
import com.abyss.tools.feature.settings.SettingsScreen
import com.abyss.tools.feature.shizukukit.ShizukuKitScreen
import com.abyss.tools.feature.wallpaper.WallpaperScreen

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

    /** 应用管理器 → 应用详情（v0.4：冻结 / 强停 / 提取等真实动作）。 */
    data class AppManagerDetail(val app: AbyssAppInfo) : Screen
}

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

    /** 恢复出的待重挂应用详情包名（一次性；查不到则丢弃，诚实降级）。 */
    var pendingAppDetailPackage: String? = null

    val current: Screen get() = stack.last()
    val canPop: Boolean get() = stack.size > 1
    val showBottomBar: Boolean get() = stack.size == 1

    val tabIndex: Int
        get() = when (current) {
            Screen.Dashboard -> 0
            Screen.NavHub -> 1
            else -> 2
        }

    fun push(screen: Screen) {
        stack = stack + screen
    }

    fun pop() {
        if (canPop) stack = stack.dropLast(1)
    }

    fun selectTab(index: Int) {
        val target = when (index) {
            0 -> Screen.Dashboard
            1 -> Screen.NavHub
            else -> Screen.Settings
        }
        stack = listOf(target)
    }

    /** 序列化（rememberSaveable 消费；AppManagerDetail 只存包名键）。 */
    fun serialize(): List<String> = stack.map { screen ->
        when (screen) {
            Screen.Dashboard -> TOKEN_DASHBOARD
            Screen.NavHub -> TOKEN_NAVHUB
            Screen.Settings -> TOKEN_SETTINGS
            Screen.PluginHub -> TOKEN_PLUGINHUB
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

private val bottomItems = listOf(
    AbyssNavItem(AbyssIcons.icon("dashboard"), "仪表盘"),
    AbyssNavItem(AbyssIcons.icon("explore"), "导航"),
    AbyssNavItem(AbyssIcons.icon("settings"), "设置"),
)

@Composable
fun AbyssRoot(
    shizukuStatus: ShizukuStatus,
    onRequestShizukuPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onReopenOnboarding: () -> Unit,
) {
    val registry = ServiceRegistry
    val backdrop = rememberAbyssBackdrop()
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

    val bottomClearance = AbyssBottomBarHeight + 24.dp

    CompositionLocalProvider(LocalGlassIntensity provides settings.glassIntensity) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.material3.MaterialTheme.colorScheme.background),
        ) {
            // 内容层 = 玻璃采样源：内容滚过底栏 / 玻璃卡下方时被真实采样
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .abyssBackdropSource(backdrop),
            ) {
                Crossfade(
                    targetState = backStack.current,
                    label = "abyss-screen",
                ) { screen ->
                    when (screen) {
                        Screen.Dashboard -> DashboardScreen(
                            backdrop = backdrop,
                            pluginRepository = registry.pluginRepository,
                            updateManager = registry.updateManager,
                            onOpenTool = { backStack.push(Screen.Tool(it)) },
                            onOpenPlugins = { backStack.push(Screen.PluginHub) },
                            bottomClearance = bottomClearance,
                            installedPluginCount = installedPluginCount,
                            autoCheckUpdates = settings.checkOnLaunch,
                            shizukuUi = shizukuStatus.toShizukuUi(),
                            installPermGranted = registry.installPermGranted(),
                        )

                        Screen.NavHub -> NavHubScreen(
                            bottomClearance = bottomClearance,
                            onOpenTool = { backStack.push(Screen.Tool(it)) },
                            onOpenPluginHub = { backStack.push(Screen.PluginHub) },
                        )

                        Screen.Settings -> SettingsScreen(
                            settings = registry.settings,
                            updateManager = registry.updateManager,
                            versionName = AppInfo.VERSION_NAME,
                            onClearPluginCache = { registry.pluginInstaller.clearCache() },
                            bottomClearance = bottomClearance,
                            onRestartOnboarding = onReopenOnboarding,
                        )

                        Screen.PluginHub -> PluginHubScreen(
                            repository = registry.pluginRepository,
                            channelClient = registry.channelClient,
                            installer = registry.pluginInstaller,
                            onBack = { backStack.pop() },
                            onOpenPlugin = { backStack.push(Screen.PluginDetail(it)) },
                            stateVersion = pluginStateVersion,
                            onPluginsChanged = bumpPluginState,
                        )

                        is Screen.PluginDetail -> PluginDetailScreen(
                            pluginId = screen.pluginId,
                            repository = registry.pluginRepository,
                            channelClient = registry.channelClient,
                            installer = registry.pluginInstaller,
                            backdrop = backdrop,
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

                            // ── 其余工具：通用占位页（内置卡 / 插件卡 / 路线图卡）──
                            else -> ToolScreen(
                                toolId = screen.toolId,
                                onOpenPlugin = { pluginId ->
                                    if (pluginId.isBlank()) {
                                        backStack.push(Screen.PluginHub)
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

            // 液态玻璃 Dark 底栏（悬浮于内容之上，真实折射背后内容）
            if (backStack.showBottomBar) {
                AbyssGlassBottomBar(
                    backdrop = backdrop,
                    items = bottomItems,
                    selectedIndex = backStack.tabIndex,
                    onSelect = { backStack.selectTab(it) },
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
