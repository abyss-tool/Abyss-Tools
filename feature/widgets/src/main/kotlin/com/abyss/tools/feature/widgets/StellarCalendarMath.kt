package com.abyss.tools.feature.widgets

import java.util.Calendar

/**
 * 星轨日历纯数学层（仅依赖 java.util.Calendar —— 纯 JVM 可单测）。
 *
 * 「时刻 → 轨道几何」的映射全部收敛在这里：年积日 / 年长度（闰年自
 * 适应）/ 行星轨道角（年进度线性映射到一整圈，0° 锚定轨道右端起点）/
 * 月份索引（十二月刻度哪枚该点亮）/ 年进度文案。绘制层
 * （[StellarCalendarArt]）只消费这些数值。
 */
object StellarCalendarMath {

    /** 年积日（1 基：1 月 1 日 = 1）。 */
    fun dayOfYear(timeMs: Long): Int = calendarOf(timeMs).get(Calendar.DAY_OF_YEAR)

    /** 年长度：平年 365 / 闰年 366（getActualMaximum 按当年自适应 —— 不手写闰年规则）。 */
    fun daysInYear(timeMs: Long): Int = daysInYearOf(calendarOf(timeMs))

    /**
     * 行星轨道角（度）：年进度线性映射一整圈（dayOfYear / daysInYear
     * × 360）。0° 在轨道右端 —— 一年结束恰走满 360° 回到起点。
     */
    fun orbitAngleDeg(timeMs: Long): Float {
        val cal = calendarOf(timeMs)
        val day = cal.get(Calendar.DAY_OF_YEAR)
        return day / daysInYearOf(cal).toFloat() * 360f
    }

    /** 月份索引 0..11（十二月刻度的点亮判定）。 */
    fun monthIndex(timeMs: Long): Int = calendarOf(timeMs).get(Calendar.MONTH).coerceIn(0, 11)

    /**
     * 年进度 0..1：天粒度 + 当日时间分数（预览按帧平滑推进；桌面组件
     * 整分步进 —— 两者共用同一函数）。
     */
    fun yearProgress(timeMs: Long): Float {
        val cal = calendarOf(timeMs)
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
        val secOfDay = cal.get(Calendar.HOUR_OF_DAY) * 3600 +
            cal.get(Calendar.MINUTE) * 60 +
            cal.get(Calendar.SECOND)
        val frac = (dayOfYear - 1 + secOfDay / 86_400f) / daysInYearOf(cal)
        return frac.coerceIn(0f, 1f)
    }

    private fun calendarOf(timeMs: Long): Calendar = Calendar.getInstance().apply {
        timeInMillis = timeMs
    }

    private fun daysInYearOf(cal: Calendar): Int = cal.getActualMaximum(Calendar.DAY_OF_YEAR)
}
