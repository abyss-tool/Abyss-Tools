package com.abyss.tools.feature.wallpaper

import android.content.SharedPreferences

/**
 * 动态壁纸主题与偏好。
 *
 * 纪律：色板集中于此 —— 渲染端（Engine 的 android.graphics / 预览页的
 * Compose Canvas）与 UI 端（主题 chip 色点）只从这里取色，禁止散落魔数。
 * 全部预设深色底，刻意避开蓝紫主导的俗套。
 */

/** 一套壁纸主题：底色 + 光斑色板 + 几何 / 节奏系数。 */
data class WallpaperTheme(
    /** 稳定 id（prefs 持久化用，如 "abyss-teal"）。 */
    val id: String,
    /** 中文展示名。 */
    val name: String,
    /** 深色底色（ARGB，如 0xFF06120F）。 */
    val base: Long,
    /** 光斑色板（ARGB）；建场时光斑数 = 色板长度。 */
    val colors: List<Long>,
    /** 光斑半径系数（基准半径见 WallpaperScene.ORB_RADIUS_BASE_FACTOR）。 */
    val glowRadiusFactor: Float,
    /** 漂移节奏系数（叠乘在用户速度因子之上）。 */
    val driftSpeedFactor: Float,
)

/** 预设主题（唯一色板来源）。 */
object WallpaperThemes {

    /** 深渊青（默认）：深潭底的冷调青绿光斑。 */
    val ABYSS_TEAL = WallpaperTheme(
        id = "abyss-teal",
        name = "深渊青",
        base = 0xFF06120F,
        colors = listOf(0xFF0F5B4C, 0xFF10806A, 0xFF0C3B33),
        glowRadiusFactor = 1.00f,
        driftSpeedFactor = 1.00f,
    )

    /** 余烬橙：暗夜余烬，光斑更细碎、节奏略快。 */
    val EMBER = WallpaperTheme(
        id = "ember",
        name = "余烬橙",
        base = 0xFF150D08,
        colors = listOf(0xFF5C2E0B, 0xFF7A4514, 0xFF3A1D06),
        glowRadiusFactor = 0.90f,
        driftSpeedFactor = 1.15f,
    )

    /** 绯红：暗红丝绒，光斑更大、节奏更缓。 */
    val CRIMSON = WallpaperTheme(
        id = "crimson",
        name = "绯红",
        base = 0xFF160709,
        colors = listOf(0xFF5E1220, 0xFF7E1E30, 0xFF40101A),
        glowRadiusFactor = 1.05f,
        driftSpeedFactor = 0.85f,
    )

    /** 苔原绿：夜林苔色，安静低语。 */
    val VERDANT = WallpaperTheme(
        id = "verdant",
        name = "苔原绿",
        base = 0xFF0B1108,
        colors = listOf(0xFF2E5410, 0xFF3F6B1A, 0xFF1E3A0B),
        glowRadiusFactor = 0.95f,
        driftSpeedFactor = 0.90f,
    )

    /** 全部预设（UI 展示顺序即此顺序）。 */
    val presets: List<WallpaperTheme> = listOf(ABYSS_TEAL, EMBER, CRIMSON, VERDANT)

    /** 合法 id 集合（prefs 读取校验用）。 */
    val ids: Set<String> = presets.mapTo(LinkedHashSet()) { it.id }

    /** 按 id 取主题；未知 / 空 id → 深渊青（绝不返回 null）。 */
    fun byId(id: String?): WallpaperTheme = presets.firstOrNull { it.id == id } ?: ABYSS_TEAL
}

/** 偏好读写（SharedPreferences "wallpaper_prefs"，主进程内 UI 与壁纸引擎共享）。 */
object WallpaperPrefs {
    const val PREFS_NAME = "wallpaper_prefs"

    const val DEFAULT_THEME_ID = "abyss-teal"

    const val SPEED_MIN = 0.5f
    const val SPEED_MAX = 2.0f
    const val SPEED_DEFAULT = 1.0f

    private const val KEY_THEME_ID = "theme_id"
    private const val KEY_SPEED_FACTOR = "speed_factor"

    /** 当前主题 id（未知值回落默认 —— fail-safe，不 crash 不猜测）。 */
    fun readThemeId(prefs: SharedPreferences): String =
        prefs.getString(KEY_THEME_ID, null)
            ?.takeIf { it in WallpaperThemes.ids }
            ?: DEFAULT_THEME_ID

    /** 用户速度因子（钳制在 0.5–2.0，坏数据不外泄）。 */
    fun readSpeedFactor(prefs: SharedPreferences): Float =
        prefs.getFloat(KEY_SPEED_FACTOR, SPEED_DEFAULT).coerceIn(SPEED_MIN, SPEED_MAX)

    fun writeThemeId(prefs: SharedPreferences, themeId: String) {
        prefs.edit().putString(KEY_THEME_ID, themeId).apply()
    }

    fun writeSpeedFactor(prefs: SharedPreferences, factor: Float) {
        prefs.edit().putFloat(KEY_SPEED_FACTOR, factor.coerceIn(SPEED_MIN, SPEED_MAX)).apply()
    }
}

/** 当前主题（prefs → WallpaperTheme；未知 id 兜底深渊青）。 */
fun currentTheme(prefs: SharedPreferences): WallpaperTheme =
    WallpaperThemes.byId(WallpaperPrefs.readThemeId(prefs))
