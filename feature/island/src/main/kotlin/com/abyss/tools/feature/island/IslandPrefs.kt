package com.abyss.tools.feature.island

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.BatteryManager
import androidx.compose.ui.graphics.Color

/**
 * 灵动岛共享值类型与持久化：
 *  - [IslandPrefs] 外观配置（球大小 / 透明度 / 主题色），SharedPreferences
 *    "island_prefs" 单一数据源 —— 功能页写入、Service 监听同源实时重绘；
 *  - [IslandColorPreset] 预设主题色（避开蓝紫：深渊青 / 暖橙 / 绯红 / 墨绿）；
 *  - [BatteryInfo] 真实电量读数（系统电池广播解析，禁止假数据）。
 */
internal data class IslandPrefs(
    /** 球直径（dp），44..72。 */
    val ballSizeDp: Int,
    /** 球体不透明度，0.5..1.0。 */
    val ballAlpha: Float,
    /** 球体主题色预设。 */
    val ballColor: IslandColorPreset,
) {
    /** 球体实际渲染色（预设色 × 用户透明度）。 */
    fun ballRenderColor(): Color = ballColor.color.copy(alpha = ballAlpha)

    /** 球直径（px），Service 侧窗口定位 / 吸边计算用。 */
    fun ballSizePx(density: Float): Int = (ballSizeDp * density + 0.5f).toInt().coerceAtLeast(1)

    companion object {
        const val PREFS_NAME = "island_prefs"
        const val KEY_BALL_SIZE = "ball_size"
        const val KEY_BALL_ALPHA = "ball_alpha"
        const val KEY_BALL_COLOR = "ball_color"

        const val SIZE_MIN_DP = 44
        const val SIZE_MAX_DP = 72
        const val ALPHA_MIN = 0.5f
        const val ALPHA_MAX = 1.0f

        /** 默认外观（Service 字段初始化期的哨兵值 —— Service 构造时 baseContext
         *  尚未 attach，不能在字段初始化器里做 SharedPreferences IO，onCreate 再读真值）。 */
        internal val DEFAULT = IslandPrefs(
            ballSizeDp = 56,
            ballAlpha = 0.92f,
            ballColor = IslandColorPreset.ABYSS_TEAL,
        )

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        /** 读取并夹取到合法区间（损坏的存量值不带入渲染）。 */
        fun read(context: Context): IslandPrefs {
            val sp = prefs(context)
            val size = sp.getInt(KEY_BALL_SIZE, DEFAULT.ballSizeDp)
                .coerceIn(SIZE_MIN_DP, SIZE_MAX_DP)
            val alpha = sp.getFloat(KEY_BALL_ALPHA, DEFAULT.ballAlpha)
                .coerceIn(ALPHA_MIN, ALPHA_MAX)
            val colorKey = sp.getString(KEY_BALL_COLOR, DEFAULT.ballColor.key)
            val color = IslandColorPreset.entries.firstOrNull { it.key == colorKey }
                ?: DEFAULT.ballColor
            return IslandPrefs(size, alpha, color)
        }

        fun write(context: Context, prefs: IslandPrefs) {
            prefs(context).edit()
                .putInt(KEY_BALL_SIZE, prefs.ballSizeDp.coerceIn(SIZE_MIN_DP, SIZE_MAX_DP))
                .putFloat(KEY_BALL_ALPHA, prefs.ballAlpha.coerceIn(ALPHA_MIN, ALPHA_MAX))
                .putString(KEY_BALL_COLOR, prefs.ballColor.key)
                .apply()
        }
    }
}

/** 预设主题色（4 色，全部避开蓝紫系）。 */
internal enum class IslandColorPreset(val key: String, val label: String, val color: Color) {
    ABYSS_TEAL("abyss_teal", "深渊青", Color(0xFF14B8A6)),
    WARM_ORANGE("warm_orange", "暖橙", Color(0xFFF2762E)),
    CRIMSON("crimson", "绯红", Color(0xFFE0455A)),
    INK_GREEN("ink_green", "墨绿", Color(0xFF2F7D5B)),
}

/**
 * 真实电量读数（ACTION_BATTERY_CHANGED 解析）。
 *
 * @param percent 0..100；-1 = 广播未携带有效 level/scale（诚实显示 "--"，不猜）
 * @param charging 充电中或已充满（BATTERY_STATUS_CHARGING / FULL）
 */
internal data class BatteryInfo(val percent: Int, val charging: Boolean) {

    companion object {
        fun unknown(): BatteryInfo = BatteryInfo(percent = -1, charging = false)

        fun from(intent: Intent): BatteryInfo {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            return BatteryInfo(percent, charging)
        }
    }
}
