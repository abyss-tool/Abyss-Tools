package com.abyss.tools.feature.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引力电池纯数学层单测：电量分数 / 档位边界 / 档位热色偏移 / 状态文案 /
 * 喷流亮度范围与脉动。绘制层（android.graphics）由真机肉眼验收，不进
 * 单测 —— 与时钟模块同纪律。
 */
class GravityBatteryMathTest {

    @Test
    fun `电量分数钳制在 0 到 1`() {
        assertEquals(0.5f, GravityBatteryMath.fraction(50), 0.001f)
        assertEquals(0f, GravityBatteryMath.fraction(0), 0.001f)
        assertEquals(1f, GravityBatteryMath.fraction(100), 0.001f)
        // 越界输入就地钳制（fail-closed：绝不画出反转或过盈的环）
        assertEquals(0f, GravityBatteryMath.fraction(-5), 0.001f)
        assertEquals(1f, GravityBatteryMath.fraction(150), 0.001f)
    }

    @Test
    fun `档位边界 50 与 20`() {
        assertEquals(GravityLevelTier.OK, GravityBatteryMath.levelTier(100))
        assertEquals(GravityLevelTier.OK, GravityBatteryMath.levelTier(50))
        assertEquals(GravityLevelTier.WARN, GravityBatteryMath.levelTier(49))
        assertEquals(GravityLevelTier.WARN, GravityBatteryMath.levelTier(20))
        assertEquals(GravityLevelTier.CRITICAL, GravityBatteryMath.levelTier(19))
        assertEquals(GravityLevelTier.CRITICAL, GravityBatteryMath.levelTier(0))
    }

    @Test
    fun `正常档位主题热色原样返回`() {
        val hot = 0xFF7EC8FF.toInt()
        assertEquals(hot, GravityBatteryMath.tierHotColor(60, hot))
        assertEquals(hot, GravityBatteryMath.tierHotColor(100, hot))
    }

    @Test
    fun `警告档位热色向琥珀偏移`() {
        val base = 0xFF7EC8FF.toInt() // R=0x7E 偏暗，B=0xFF 偏亮
        val shifted = GravityBatteryMath.tierHotColor(30, base)
        assertTrue("档位热色必须偏离主题色", shifted != base)
        assertTrue("红通道应变亮（琥珀化）: $shifted", red(shifted) > red(base))
        assertTrue("蓝通道应变暗（去冷调）: $shifted", blue(shifted) < blue(base))
        // 透明度不随档位漂移（两个锚点都不透明）
        assertEquals(0xFF, alpha(shifted))
    }

    @Test
    fun `危急档位比警告档红得更深`() {
        val base = 0xFF7EC8FF.toInt()
        val warn = GravityBatteryMath.tierHotColor(35, base)
        val critical = GravityBatteryMath.tierHotColor(10, base)
        assertTrue(
            "危急档红通道应深于警告档: $critical vs $warn",
            red(critical) > red(warn),
        )
    }

    @Test
    fun `状态文案三分支`() {
        assertEquals("放电中", GravityBatteryMath.statusLabel(charging = false, full = false))
        assertEquals("充电中", GravityBatteryMath.statusLabel(charging = true, full = false))
        // 充满优先于充电（插电满电的诚实语义）
        assertEquals("已充满", GravityBatteryMath.statusLabel(charging = true, full = true))
        assertEquals("已充满", GravityBatteryMath.statusLabel(charging = false, full = true))
    }

    @Test
    fun `喷流亮度始终在 0_35 到 0_9`() {
        // 边界时刻 + 全周期逐分钟采样（20 分钟呼吸周期）都不越界
        val samples = mutableListOf(0L, 1L, 60_000L, 123_456L, 600_000L, 1_200_000L, 1_700_000_000_000L)
        for (m in 0 until 20L) {
            samples += m * 60_000L + 999L
        }
        samples.forEach { t ->
            val a = GravityBatteryMath.jetAlpha(t)
            assertTrue("喷流亮度越界: $a (t=$t)", a in 0.35f..0.9f)
        }
    }

    @Test
    fun `喷流亮度随分钟缓慢脉动`() {
        val a0 = GravityBatteryMath.jetAlpha(0L)          // 相位 0 → 中值
        val a5 = GravityBatteryMath.jetAlpha(5 * 60_000L) // 相位 1/4 → 峰值
        assertTrue("亮度应随时间变化: $a0 vs $a5", a0 != a5)
        assertTrue("变化幅度应可感知", kotlin.math.abs(a0 - a5) > 0.05f)
    }

    @Test
    fun `喷流亮度确定性：同时刻同亮度`() {
        val t = 1_700_000_000_123L
        assertEquals(GravityBatteryMath.jetAlpha(t), GravityBatteryMath.jetAlpha(t), 0f)
    }

    private fun alpha(c: Int) = (c ushr 24) and 0xFF
    private fun red(c: Int) = (c ushr 16) and 0xFF
    private fun blue(c: Int) = c and 0xFF
}
