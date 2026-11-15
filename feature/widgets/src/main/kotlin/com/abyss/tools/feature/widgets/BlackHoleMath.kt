package com.abyss.tools.feature.widgets

/**
 * 黑洞时钟的纯数学层（零 Android 依赖 —— 可单测）。
 *
 * 设计意图：把「时间 → 视觉参数」的映射全部收敛在这里 —— 吸积盘旋转
 * 角、星点分布（确定性伪随机：同一 seed 永远同一片星空，位图重绘不抖
 * 动）、闪烁相位、整分边界。绘制层（BlackHoleArt）只消费这些数值，
 * 自身无可测逻辑。
 */
object BlackHoleMath {

    /** 吸积盘旋转角（度）：每小时一整圈 —— 每分钟 6°，肉眼可感知的
     *  「动态」节奏；与分钟重绘链的粒度天然对齐。整数路径运算（Float 在
     *  亿级毫秒时间戳上精度不足 —— 测试可复现的舍入漂移）。 */
    fun diskRotationDeg(timeMs: Long): Float =
        ((timeMs / 60_000L) * 6L % 360L).toFloat()

    /** 下一个整分边界（AlarmManager 自续期链的落点）。 */
    fun nextMinuteBoundary(nowMs: Long): Long = (nowMs / 60_000 + 1) * 60_000

    /**
     * 确定性星点坐标（归一化 0..1）—— LCG 伪随机（数值同 Java
     * Random 的乘子常数，但显式持有 seed，避免平台实现差异）。
     * 重绘一千次也是同一片星空（时间流逝只驱动闪烁相位）。
     */
    fun starField(count: Int, seed: Long = 0x5DEECE66DL): List<Pair<Float, Float>> {
        require(count >= 0) { "星点数不能为负：$count" }
        var state = seed
        val out = ArrayList<Pair<Float, Float>>(count)
        repeat(count) {
            state = state * 0x5DEECE66DL + 0xBL and 0xFFFFFFFFFFFFL
            val x = (state ushr 24) / 16_777_216f
            state = state * 0x5DEECE66DL + 0xBL and 0xFFFFFFFFFFFFL
            val y = (state ushr 24) / 16_777_216f
            out += x to y
        }
        return out
    }

    /** 星点亮度：0.30..1.0 的正弦闪烁（相位由星点序号错开，频率各异）。 */
    fun twinkleAlpha(starIndex: Int, timeMs: Long): Float {
        val phase = starIndex * 1.618f
        val speed = 0.0009f + (starIndex % 5) * 0.00035f
        val wave = kotlin.math.sin(timeMs * speed + phase)
        return 0.30f + 0.70f * (wave + 1f) / 2f
    }

    /** 星点半径档位（1.2f..2.6f dp 级，按序号取模三档 —— 大小星错落）。 */
    fun starRadius(starIndex: Int): Float = when (starIndex % 3) {
        0 -> 1.2f
        1 -> 1.8f
        else -> 2.6f
    }
}
