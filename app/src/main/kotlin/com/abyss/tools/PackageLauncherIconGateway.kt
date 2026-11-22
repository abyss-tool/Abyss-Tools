package com.abyss.tools

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.abyss.tools.core.common.LauncherIcon
import com.abyss.tools.core.common.LauncherIconGateway

/**
 * [LauncherIconGateway] PackageManager 实现（FEAT-036）。
 *
 * 图标三选一 = activity-alias（.LauncherCyan/.LauncherRed/.LauncherBlue）
 * 互斥启停：目标 ENABLED、其余 DISABLED、DONT_KILL_APP（桌面即时刷新，
 * 进程是否重启由 ROM 决定 —— UI 侧做预期管理）。MainActivity 组件恒
 * enabled，永不禁用 Activity 本体（否则桌面点图标无响应）。
 *
 * current() 读组件态推导：恰一个 ENABLED 即命中；全部 DEFAULT 状态 /
 * 多个 ENABLED / 读态失败 → fail-closed 回退 CYAN（不猜）。
 */
class PackageLauncherIconGateway(context: Context) : LauncherIconGateway {

    private val appContext = context.applicationContext
    private val packageManager: PackageManager = appContext.packageManager

    private fun componentOf(icon: LauncherIcon): ComponentName =
        ComponentName(appContext, "${appContext.packageName}.${icon.aliasComponent}")

    override fun current(): LauncherIcon = runCatching {
        LauncherIcon.entries.filter { icon ->
            val state = packageManager.getComponentEnabledSetting(componentOf(icon))
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }.singleOrNull()
    }.getOrNull() ?: LauncherIcon.DEFAULT

    override fun apply(icon: LauncherIcon): Result<Unit> = runCatching {
        LauncherIcon.entries.forEach { candidate ->
            val target = if (candidate == icon) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            packageManager.setComponentEnabledSetting(
                componentOf(candidate),
                target,
                PackageManager.COMPONENT_ENABLED_STATE_DONT_KILL_APP,
            )
        }
    }.recoverCatching { e ->
        // 失败回执给人话（UI Snackbar 直显），不抛堆栈。
        throw IllegalStateException("图标切换失败：${e.message ?: "系统拒绝组件状态变更"}")
    }
}
