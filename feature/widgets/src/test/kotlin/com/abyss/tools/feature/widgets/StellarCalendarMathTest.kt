package com.abyss.tools.feature.widgets

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 星轨日历纯数学层单测：年积日 / 年长度（闰年自适应）/ 轨道角线性映射 /
 * 月份索引 / 年进度。测试用固定 Calendar 实例构造时刻 —— 与实现共用同
 * 一默认时区，断言不受运行环境影响（正午采样避开 DST 边界极值）。
 */
class StellarCalendarMathTest {

    /** 固定时刻（默认正午 —— 远离 DST 切换的小时）。 */
    private fun msOf(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, 0, 0)
        }.timeInMillis

    @Test
    fun `年积日平年与闰年`() {
        assertEquals(1, StellarCalendarMath.dayOfYear(msOf(2023, Calendar.JANUARY, 1)))
        assertEquals(365, StellarCalendarMath.dayOfYear(msOf(2023, Calendar.DECEMBER, 31)))
        // 闰年 2 月 29 日是第 60 天
        assertEquals(60, StellarCalendarMath.dayOfYear(msOf(2024, Calendar.FEBRUARY, 29)))
    }

    @Test
    fun `年长度闰年 366 平年 365`() {
        assertEquals(365, StellarCalendarMath.daysInYear(msOf(2023, Calendar.JULY, 1)))
        assertEquals(366, StellarCalendarMath.daysInYear(msOf(2024, Calendar.JULY, 1)))
        // 年末再问一次（getActualMaximum 全年一致）
        assertEquals(366, StellarCalendarMath.daysInYear(msOf(2024, Calendar.DECEMBER, 31)))
    }

    @Test
    fun `轨道角从右侧零点线性推进`() {
        // 平年最后一天走满一整圈：365/365 × 360 = 360
        assertEquals(360f, StellarCalendarMath.orbitAngleDeg(msOf(2023, Calendar.DECEMBER, 31)), 0.01f)
        // 第一天 ≈ 1°（1/365 × 360）
        assertEquals(0.99f, StellarCalendarMath.orbitAngleDeg(msOf(2023, Calendar.JANUARY, 1)), 0.05f)
    }

    @Test
    fun `轨道角闰年按 366 天折算`() {
        // 2024-02-29（第 60 天）：60/366 × 360 ≈ 58.97°
        assertEquals(58.97f, StellarCalendarMath.orbitAngleDeg(msOf(2024, Calendar.FEBRUARY, 29)), 0.05f)
    }

    @Test
    fun `轨道角随月份单调推进`() {
        var prev = -1f
        for (m in 0 until 12) {
            val a = StellarCalendarMath.orbitAngleDeg(msOf(2023, m, 15))
            assertTrue("轨道角应单调递增: $a vs $prev", a > prev)
            prev = a
        }
    }

    @Test
    fun `月份索引 0 到 11`() {
        assertEquals(0, StellarCalendarMath.monthIndex(msOf(2024, Calendar.JANUARY, 15)))
        assertEquals(4, StellarCalendarMath.monthIndex(msOf(2024, Calendar.MAY, 15)))
        assertEquals(11, StellarCalendarMath.monthIndex(msOf(2024, Calendar.DECEMBER, 15)))
    }

    @Test
    fun `年进度年初趋近 0 年末趋近 1`() {
        val head = StellarCalendarMath.yearProgress(msOf(2023, Calendar.JANUARY, 1, 0))
        assertTrue("年初应趋近 0: $head", head in 0f..0.05f)
        val tail = StellarCalendarMath.yearProgress(msOf(2023, Calendar.DECEMBER, 31, 23))
        assertTrue("年末应趋近 1: $tail", tail in 0.999f..1f)
    }

    @Test
    fun `年进度闰年年中约为一半`() {
        // 2024-07-02 正午 = 第 184 天过半 → (183 + 0.5)/366 ≈ 0.501
        val mid = StellarCalendarMath.yearProgress(msOf(2024, Calendar.JULY, 2, 12))
        assertTrue("闰年年中应约为一半: $mid", mid in 0.495f..0.505f)
    }

    @Test
    fun `年进度全年钳制在 0 到 1`() {
        for (m in 0 until 12) {
            val p = StellarCalendarMath.yearProgress(msOf(2024, m, 15, 18))
            assertTrue("进度越界: $p", p in 0f..1f)
        }
    }
}
