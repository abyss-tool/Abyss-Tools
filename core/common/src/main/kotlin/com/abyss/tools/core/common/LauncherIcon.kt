package com.abyss.tools.core.common

/**
 * 启动器图标三选一档位（FEAT-036）。
 *
 * 纯 JVM 类型源（零 Android 依赖）；偏好只存字符串（[storageValue]），
 * 不存 ordinal / 类名。真实图标态由 activity-alias 组件启停承载（Android
 * 侧 [LauncherIconGateway] 实现），偏好仅作 UI 显示缓存。
 */
enum class LauncherIcon(
    val storageValue: String,
    /** manifest 中 activity-alias 的简单类名（:app 实现拼 ComponentName）。 */
    val aliasComponent: String,
    /** UI 文案。 */
    val label: String,
) {
    CYAN("cyan", "LauncherCyan", "碧眼"),
    RED("red", "LauncherRed", "绯眼"),
    BLUE("blue", "LauncherBlue", "隐眼"),
    ;

    companion object {

        /** 默认档 = 键缺失 = 老用户 = 现状图标。 */
        val DEFAULT = CYAN

        /**
         * fail-closed 解析：null / 空串 / 未知值一律回退 [DEFAULT]，不抛、
         * 不写回覆盖用户原值（下次读仍回退）。
         */
        fun from(raw: String?): LauncherIcon = when (raw) {
            CYAN.storageValue -> CYAN
            RED.storageValue -> RED
            BLUE.storageValue -> BLUE
            else -> DEFAULT
        }
    }
}

/**
 * 启动器图标切换网关（FEAT-036）：Android 侧依赖隔离在接口后面
 * （AGENTS.md 纪律 #5 同构）—— core:common 只见本接口，实现在 :app
 * 经 ServiceRegistry 注入（PackageManager component enabled state）。
 */
interface LauncherIconGateway {

    /** 当前生效图标（读组件启停态推导；无法判定时回退 [LauncherIcon.DEFAULT]）。 */
    fun current(): LauncherIcon

    /**
     * 切换到目标图标：目标别名 ENABLED、其余别名 DISABLED（互斥），
     * MainActivity 组件恒 enabled 永不禁用。阻塞调用（PackageManager
     * Binder + Launcher 刷新），调用方切 IO 线程；失败给人话错误不抛。
     */
    fun apply(icon: LauncherIcon): Result<Unit>
}
