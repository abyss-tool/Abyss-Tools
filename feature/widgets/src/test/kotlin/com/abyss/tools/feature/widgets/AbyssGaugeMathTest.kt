package com.abyss.tools.feature.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 深渊引力仪纯数学层单测：扫过分数钳制 / 粒子角分布与边界 / 百分比文
 * 本。绘制层（android.graphics）由真机肉眼验收，不进单测 —— 与时钟模
 * 块同纪律。
 */
class AbyssGaugeMathTest {

    @Test
    fun `扫过分数钳制在 0 到 1`() {
        assertEquals(0f, AbyssGaugeMath.gaugeSweep(-1f), 0.0001f)
        assertEquals(0.62f, AbyssGaugeMath.gaugeSweep(0.62f), 0.0001f)
        assertEquals(1f, AbyssGaugeMath.gaugeSweep(1.7f), 0.0001f)
    }

    @Test
    fun `粒子角在扫过弧段内均匀分布`() {
        // 满环 4 粒：360/5 的整数倍（不压首尾两端）
        assertEquals(listOf(72f, 144f, 216f, 288f), AbyssGaugeMath.particleAngles(1f, 4))
        // 半环 3 粒：180/4 的整数倍
        assertEquals(listOf(45f, 90f, 135f), AbyssGaugeMath.particleAngles(0.5f, 3))
    }

    @Test
    fun `粒子角始终落在扫过范围内`() {
        listOf(0.2f, 0.45f, 0.77f, 1f).forEach { f ->
            val sweep = f * 360f
            AbyssGaugeMath.particleAngles(f, 5).forEach { a ->
                assertTrue("粒子角越界: $a (sweep=$sweep)", a > 0f && a < sweep)
            }
        }
    }

    @Test
    fun `近零扫过时不画粒子`() {
        assertTrue(AbyssGaugeMath.particleAngles(0f, 4).isEmpty())
        assertTrue(AbyssGaugeMath.particleAngles(0.001f, 4).isEmpty())
    }

    @Test
    fun `非正粒子数返回空表`() {
        assertTrue(AbyssGaugeMath.particleAngles(0.8f, 0).isEmpty())
        assertTrue(AbyssGaugeMath.particleAngles(0.8f, -3).isEmpty())
    }

    @Test
    fun `百分比文本四舍五入且钳制`() {
        assertEquals("62%", AbyssGaugeMath.pctText(0.62f))
        assertEquals("0%", AbyssGaugeMath.pctText(0f))
        assertEquals("100%", AbyssGaugeMath.pctText(1f))
        // 越界输入钳制后再取整（fail-closed：不显示 160% 这类读数）
        assertEquals("0%", AbyssGaugeMath.pctText(-0.25f))
        assertEquals("100%", AbyssGaugeMath.pctText(2.5f))
        assertEquals("7%", AbyssGaugeMath.pctText(0.071f))
    }
}
