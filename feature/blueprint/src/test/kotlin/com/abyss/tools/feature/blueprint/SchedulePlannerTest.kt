package com.abyss.tools.feature.blueprint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * SchedulePlanner 纯数学单测（零 Android 依赖）：
 * 每日模式的「今天 / 明天」折算边界、格式化与调度标签。
 *
 * 时区注意：nextDailyAt 经 Calendar 使用运行环境默认时区 —— 断言同样
 * 经 Calendar 折算期望值（期望值与实现同源走 Calendar，不手算时间戳）。
 */
class SchedulePlannerTest {

    // ── formatDailyMinute ─────────────────────────────────────────────

    @Test
    fun `日内分钟格式化为 HH-mm`() {
        assertEquals("00:00", SchedulePlanner.formatDailyMinute(0))
        assertEquals("08:05", SchedulePlanner.formatDailyMinute(8 * 60 + 5))
        assertEquals("23:59", SchedulePlanner.formatDailyMinute(23 * 60 + 59))
    }

    @Test
    fun `越界日内分钟被拒绝`() {
        assertTrue(
            runCatching { SchedulePlanner.formatDailyMinute(-1) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { SchedulePlanner.formatDailyMinute(1440) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }

    // ── nextDailyAt ───────────────────────────────────────────────────

    /** 以默认时区构造某日某时刻的时间戳（与实现同源）。 */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `今天还没到排今天`() {
        val now = at(2026, Calendar.NOVEMBER, 15, 10, 0) // 11 月 15 日 10:00
        val next = SchedulePlanner.nextDailyAt(23 * 60, now) // 目标 23:00
        assertEquals(at(2026, Calendar.NOVEMBER, 15, 23, 0), next)
    }

    @Test
    fun `今天已过排明天`() {
        val now = at(2026, Calendar.NOVEMBER, 15, 23, 30) // 已过 23:00
        val next = SchedulePlanner.nextDailyAt(23 * 60, now)
        assertEquals(at(2026, Calendar.NOVEMBER, 16, 23, 0), next)
    }

    @Test
    fun `恰好等于目标时刻排明天（防双触发）`() {
        val now = at(2026, Calendar.NOVEMBER, 15, 8, 0) // 恰好 08:00
        val next = SchedulePlanner.nextDailyAt(8 * 60, now)
        assertEquals(at(2026, Calendar.NOVEMBER, 16, 8, 0), next)
    }

    @Test
    fun `午夜边界跨月正确`() {
        // 11 月 30 日 23:59 之后 → 12 月 1 日 00:00
        val now = at(2026, Calendar.NOVEMBER, 30, 23, 59) + 30_000 // 23:59:30
        val next = SchedulePlanner.nextDailyAt(0, now)
        assertEquals(at(2026, Calendar.DECEMBER, 1, 0, 0), next)
    }

    // ── scheduleLabel ─────────────────────────────────────────────────

    @Test
    fun `调度标签双模式人话`() {
        assertEquals("每 30 分钟", SchedulePlanner.scheduleLabel(BlueprintSchedule(intervalMinutes = 30)))
        assertEquals("每天 08:00", SchedulePlanner.scheduleLabel(BlueprintSchedule(dailyAtMinute = 480)))
    }
}
