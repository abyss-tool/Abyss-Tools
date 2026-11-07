package com.abyss.tools.feature.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

/**
 * 场景几何纯逻辑单测：全部跑在纯 JVM 上 —— buildScene / orbCenter 不碰
 * android.graphics（那是 drawFrame 的事，由真机肉眼验收），
 * 这里只验确定性 / 规格区间 / 漂移值域 / 视差。
 */
class WallpaperSceneTest {

    private val w = 1080
    private val h = 2400

    // ── buildScene ────────────────────────────────────────────────────────

    @Test
    fun `buildScene 同种子同场景 确定性`() {
        val theme = WallpaperThemes.ABYSS_TEAL
        val a = buildScene(theme, w, h)
        val b = buildScene(theme, w, h)
        assertEquals(a, b)
        // 默认种子即 42
        assertEquals(a, buildScene(theme, w, h, randomSeed = 42L))
        // 换种子 → 布局应不同
        assertNotEquals(a, buildScene(theme, w, h, randomSeed = 7L))
    }

    @Test
    fun `光斑数与主题色板数一致 色索引不越界`() {
        for (theme in WallpaperThemes.presets) {
            val orbs = buildScene(theme, w, h)
            assertEquals("主题 ${theme.id} 光斑数", theme.colors.size, orbs.size)
            for (orb in orbs) {
                assertTrue("主题 ${theme.id} 色索引越界", orb.colorIndex in theme.colors.indices)
            }
        }
    }

    @Test
    fun `半径与锚点落在规格区间`() {
        val theme = WallpaperThemes.EMBER
        val orbs = buildScene(theme, w, h)
        val expectedRadius = min(w, h) * ORB_RADIUS_BASE_FACTOR * theme.glowRadiusFactor
        for (orb in orbs) {
            assertEquals("光斑半径", expectedRadius, orb.radius, 1e-3f)
            assertTrue("cx0=${orb.cx0} 越界", orb.cx0 in (w * 0.20f - 1f)..(w * 0.80f + 1f))
            assertTrue("cy0=${orb.cy0} 越界", orb.cy0 in (h * 0.15f - 1f)..(h * 0.70f + 1f))
            assertTrue("phase=${orb.phase} 越界", orb.phase in 0f..((2.0 * Math.PI).toFloat() + 1e-4f))
            assertTrue("speed=${orb.speed} 越界", orb.speed in ORB_SPEED_MIN..ORB_SPEED_MAX)
        }
    }

    // ── orbCenter ─────────────────────────────────────────────────────────

    @Test
    fun `漂移值域 横向±0.06w 纵向±0.045h`() {
        val theme = WallpaperThemes.VERDANT
        for (orb in buildScene(theme, w, h)) {
            var t = 0f
            while (t <= 120f) {
                val (cx, cy) = orbCenter(orb, t, w, h)
                assertTrue(
                    "cx=${cx} 超出锚点 ±0.06w",
                    abs(cx - orb.cx0) <= w * ORB_DRIFT_X_FACTOR + 1e-2f,
                )
                assertTrue(
                    "cy=${cy} 超出锚点 ±0.045h",
                    abs(cy - orb.cy0) <= h * ORB_DRIFT_Y_FACTOR + 1e-2f,
                )
                t += 0.37f // 非整周期步长采样，避免恰好踩在极值间
            }
        }
    }

    @Test
    fun `视差只横移 cx 且比例正确`() {
        val theme = WallpaperThemes.CRIMSON
        val orb = buildScene(theme, w, h).first()
        val (cx0, cy0) = orbCenter(orb, 3.3f, w, h, parallax = 0f)
        val (cx1, cy1) = orbCenter(orb, 3.3f, w, h, parallax = 0.25f)
        assertEquals("视差横向偏移 = 0.25 × w × 0.08", w * PARALLAX_FACTOR * 0.25f, cx1 - cx0, 1e-3f)
        assertEquals("视差不影响纵向", cy0, cy1, 1e-4f)
    }

    // ── 预设色板纪律 ──────────────────────────────────────────────────────

    @Test
    fun `预设色板 深色底 全不透明光斑`() {
        for (theme in WallpaperThemes.presets) {
            assertTrue("主题 ${theme.id} 底色偏亮", maxChannel(theme.base) < 0x20L)
            assertTrue("主题 ${theme.id} 光斑数不足", theme.colors.size >= 3)
            for (color in theme.colors) {
                assertEquals(
                    "主题 ${theme.id} 光斑色 alpha 不满",
                    0xFFL,
                    (color shr 24) and 0xFFL,
                )
            }
        }
    }

    @Test
    fun `byId 未知id兜底深渊青`() {
        assertEquals(WallpaperThemes.ABYSS_TEAL, WallpaperThemes.byId("不存在的主题"))
        assertEquals(WallpaperThemes.ABYSS_TEAL, WallpaperThemes.byId(null))
        assertEquals(WallpaperThemes.EMBER, WallpaperThemes.byId("ember"))
    }

    private fun maxChannel(argb: Long): Long = maxOf(
        (argb shr 16) and 0xFFL,
        (argb shr 8) and 0xFFL,
        argb and 0xFFL,
    )
}
