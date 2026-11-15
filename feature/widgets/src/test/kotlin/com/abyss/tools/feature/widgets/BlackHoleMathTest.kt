package com.abyss.tools.feature.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 黑洞时钟纯数学层单测：旋转角 / 整分边界 / 确定性星场 / 闪烁范围。
 * 绘制层（android.graphics）由真机肉眼验收，不进单测 —— 与壁纸模块同纪律。
 */
class BlackHoleMathTest {

    @Test
    fun `吸积盘旋转角每小时一整圈`() {
        val t0 = 1_700_000_000_000L
        // 60 分钟后恰为一整圈（模 360 归零）
        val a0 = BlackHoleMath.diskRotationDeg(t0)
        val a60 = BlackHoleMath.diskRotationDeg(t0 + 60 * 60_000)
        assertEquals(a0, a60, 0.001f)
    }

    @Test
    fun `每分钟推进 6 度`() {
        val t0 = 0L
        assertEquals(0f, BlackHoleMath.diskRotationDeg(t0), 0.001f)
        assertEquals(6f, BlackHoleMath.diskRotationDeg(60_000L), 0.001f)
        assertEquals(354f, BlackHoleMath.diskRotationDeg(59 * 60_000L), 0.001f)
    }

    @Test
    fun `整分边界落在下一分钟`() {
        val t = 1_700_000_012_345L // 第 n 分 + 12.345s
        val next = BlackHoleMath.nextMinuteBoundary(t)
        assertTrue(next > t)
        assertEquals(0L, next % 60_000)
        assertEquals(t / 60_000 + 1, next / 60_000)
    }

    @Test
    fun `星场确定性：同 seed 同一片星空`() {
        val a = BlackHoleMath.starField(50)
        val b = BlackHoleMath.starField(50)
        assertEquals(a, b)
        assertTrue(a.size == 50)
    }

    @Test
    fun `星点坐标落在 0 到 1`() {
        BlackHoleMath.starField(200).forEach { (x, y) ->
            assertTrue("x 越界: $x", x in 0f..1f)
            assertTrue("y 越界: $y", y in 0f..1f)
        }
    }

    @Test
    fun `闪烁亮度始终在 0_30 到 1_0`() {
        for (i in 0 until 30) {
            for (t in longArrayOf(0, 123_456, 1_700_000_000_000, 987_654_321)) {
                val a = BlackHoleMath.twinkleAlpha(i, t)
                assertTrue("亮度越界: $a", a in 0.30f..1.0f)
            }
        }
    }

    @Test
    fun `星点半径三档错落`() {
        assertEquals(1.2f, BlackHoleMath.starRadius(0), 0.001f)
        assertEquals(1.8f, BlackHoleMath.starRadius(1), 0.001f)
        assertEquals(2.6f, BlackHoleMath.starRadius(2), 0.001f)
        assertEquals(1.2f, BlackHoleMath.starRadius(3), 0.001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `负星点数就地拒绝`() {
        BlackHoleMath.starField(-1)
    }

    @Test
    fun `主题档位回退默认深渊青`() {
        assertEquals(BlackHoleTheme.ABYSS, BlackHoleTheme.fromOrdinalOrDefault(-1))
        assertEquals(BlackHoleTheme.ABYSS, BlackHoleTheme.fromOrdinalOrDefault(99))
        assertEquals(BlackHoleTheme.DARK_MATTER, BlackHoleTheme.fromOrdinalOrDefault(1))
    }
}
