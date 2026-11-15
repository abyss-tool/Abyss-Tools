package com.abyss.tools.feature.widgets

import android.content.Context

/**
 * 组件工坊偏好（SharedPreferences：主题档位 / 分钟级重绘开关）。
 *
 * 工坊页写入 → provider 每次重绘读取 —— 主题切换下一分钟生效（或手动
 * 「立即刷新」即刻生效）。命名收敛在一个对象，杜绝魔法字符串散落。
 */
object WidgetStudioPrefs {

    private const val PREFS = "abyss_widgets"
    private const val KEY_THEME = "bh_theme_ordinal"
    private const val KEY_MINUTE_TICK = "bh_minute_tick"

    fun of(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun theme(context: Context): BlackHoleTheme =
        BlackHoleTheme.fromOrdinalOrDefault(of(context).getInt(KEY_THEME, BlackHoleTheme.ABYSS.ordinal))

    fun setTheme(context: Context, theme: BlackHoleTheme) {
        of(context).edit().putInt(KEY_THEME, theme.ordinal).apply()
    }

    fun minuteTickEnabled(context: Context): Boolean =
        of(context).getBoolean(KEY_MINUTE_TICK, true)

    fun setMinuteTickEnabled(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_MINUTE_TICK, enabled).apply()
    }
}

/** 扩展糖（provider / ticker 内联可读）。 */
internal fun Context.minuteTickEnabled(): Boolean = WidgetStudioPrefs.minuteTickEnabled(this)
