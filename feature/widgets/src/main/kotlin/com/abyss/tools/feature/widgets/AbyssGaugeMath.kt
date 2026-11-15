package com.abyss.tools.feature.widgets

import kotlin.math.roundToInt

/**
 * 深渊引力仪纯数学层（零 Android 依赖 —— 可单测）。
 *
 * 「占用分数 → 环仪表几何」的映射全部收敛在这里：扫过分数钳制（负值
 * / 超界永不画出反转或过盈的环）、引力尘埃的粒子角（沿扫过弧段均匀
 * 散布 —— 近零占用不画粒子，空环更诚实）、百分比文本。绘制层
 * （[AbyssGaugeArt]）只消费这些数值。
 */
object AbyssGaugeMath {

    /** 粒子最小可见弧长（度）：低于它视为「没有扫过」，不撒粒子。 */
    private const val MIN_PARTICLE_SWEEP_DEG = 3f

    /** 仪表扫过分数（0..1，就地钳制 —— 越界输入绝不画出反转或过盈的环）。 */
    fun gaugeSweep(fraction: Float): Float = fraction.coerceIn(0f, 1f)

    /**
     * 引力尘埃粒子角（度，相对弧起点 0°）：在扫过弧段内等距散布 ——
     * step = sweep/(count+1)，粒子不压弧的首尾两端（起手弧不冒点）。
     * 近零扫过或非正粒子数 → 空表（诚实空环）。
     */
    fun particleAngles(fraction: Float, count: Int): List<Float> {
        if (count <= 0) return emptyList()
        val sweepDeg = gaugeSweep(fraction) * 360f
        if (sweepDeg < MIN_PARTICLE_SWEEP_DEG) return emptyList()
        val step = sweepDeg / (count + 1)
        return List(count) { i -> step * (i + 1) }
    }

    /** 百分比文本（钳制后四舍五入）："62%" / "0%" / "100%"。 */
    fun pctText(fraction: Float): String = "${(gaugeSweep(fraction) * 100f).roundToInt()}%"
}
