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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abyss.tools.ServiceRegistry
import com.abyss.tools.core.common.AppInfo
import com.abyss.tools.core.designsystem.component.AbyssIcons
import com.abyss.tools.core.designsystem.glass.AbyssBottomBarHeight
import com.abyss.tools.core.designsystem.glass.AbyssGlassBottomBar
import com.abyss.tools.core.designsystem.glass.AbyssNavItem
import com.abyss.tools.core.designsystem.glass.LocalGlassIntensity
import com.abyss.tools.core.designsystem.glass.abyssBackdropSource
import com.abyss.tools.core.designsystem.glass.rememberAbyssBackdrop
import com.abyss.tools.core.designsystem.theme.AbyssTheme
import com.abyss.tools.feature.dashboard.DashboardScreen
import com.abyss.tools.feature.navhub.NavHubScreen
import com.abyss.tools.feature.navhub.ToolScreen
import com.abyss.tools.feature.plugins.PluginDetailScreen
import com.abyss.tools.feature.plugins.PluginHubScreen
import com.abyss.tools.feature.settings.SettingsScreen

/**
 * 屏幕路由（自维护返回栈 —— 框架期不引 navigation-compose，跳转语义
 * 全部收敛在 AbyssBackStack，后续接深链 / 多任务恢复时再升级）。
 */
sealed interface Screen {
    data object Dashboard : Screen
    data object NavHub : Screen
    data object Settings : Screen
    data object PluginHub : Screen
    data class PluginDetail(val pluginId: String) : Screen
    data class Tool(val toolId: String) : Screen
}

/**
 * 深渊工具主壳：
 *
 *  ┌────────────────────────────┐
 *  │ 内容层（玻璃采样源）        │  ← 仪表盘 / 导航 / 设置 / 插件页
 *  │ …内容从玻璃件下方滚过…      │
 *  ├────────────────────────────┤
 *  │ ╭─ 液态玻璃 Dark 底栏 ╮    │  ← blur + lens 真实折射背后内容
 *  │ ╰──────────────────╯      │
 *  └────────────────────────────┘
 *
 * 底栏仅在顶层 tab（stack.size == 1）显示；推入详情页后沉浸全屏。
 */
class AbyssBackStack(initial: Screen) {

    private var stack by mutableStateOf(listOf(initial))

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
}

private val bottomItems = listOf(
    AbyssNavItem(AbyssIcons.icon("dashboard"), "仪表盘"),
    AbyssNavItem(AbyssIcons.icon("explore"), "导航"),
    AbyssNavItem(AbyssIcons.icon("settings"), "设置"),
)

@Composable
fun AbyssRoot() {
    val registry = ServiceRegistry
    val backdrop = rememberAbyssBackdrop()
    val backStack = remember { AbyssBackStack(Screen.Dashboard) }
    val settings = registry.settings

    BackHandler(enabled = backStack.canPop) { backStack.pop() }

    val bottomClearance = AbyssBottomBarHeight + 24.dp

    AbyssTheme {
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
                            )

                            Screen.PluginHub -> PluginHubScreen(
                                repository = registry.pluginRepository,
                                installer = registry.pluginInstaller,
                                onBack = { backStack.pop() },
                                onOpenPlugin = { backStack.push(Screen.PluginDetail(it)) },
                            )

                            is Screen.PluginDetail -> PluginDetailScreen(
                                pluginId = screen.pluginId,
                                repository = registry.pluginRepository,
                                channelClient = registry.channelClient,
                                installer = registry.pluginInstaller,
                                backdrop = backdrop,
                                onBack = { backStack.pop() },
                            )

                            is Screen.Tool -> ToolScreen(
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
}
