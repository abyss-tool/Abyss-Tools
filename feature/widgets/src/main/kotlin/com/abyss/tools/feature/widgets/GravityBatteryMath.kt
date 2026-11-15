package com.abyss.tools.feature.widgets

import kotlin.math.sin

/** 电量档位：≥50 正常 / 20..49 警告 / <20 危急 —— 环带热色分档的语义层。 */
enum class GravityLevelTier { OK, WARN, CRITICAL }

/**
 * 引力电池纯数学层（零 Android 依赖 —— 可单测）。
 *
 * 「电量百分比 → 视觉参数」的映射全部收敛在这里：环带扫过分数、档位
 * 边界、档位热色（低电向琥珀 / 红逐通道偏移 —— 用户从色相就能读出电
 * 量档位，不必细看数字）、充电喷流的亮度相位。绘制层
 * （[GravityBatteryArt]）只消费这些数值，自身无可测逻辑。
 *
 * 喷流相位以「分钟级周期」折算：桌面组件整分步进可见缓慢呼吸，工坊
 * 预览按帧平滑呼吸 —— 同一函数两端共用（与吸积盘旋转角同款纪律）。
 */
object GravityBatteryMath {

    /** 警告档（20..49）暖色偏移锚点：琥珀。 */
    const val AMBER = 0xFFFFD9A0.toInt()

    /** 危急档（<20）暖色偏移锚点：红。 */
    const val CRITICAL_RED = 0xFFFF8A80.toInt()

    /** 喷流呼吸周期：20 分钟（「慢正弦」—— 分钟粒度下的可感知脉动）。 */
    private const val JET_PERIOD_MS = 20L * 60_000L

    private val TWO_PI = (2.0 * Math.PI).toFloat()

    /** 电量 → 环带扫过分数（0..1）；越界就地钳制 —— 永不画出反转或过盈的环。 */
    fun fraction(levelPct: Int): Float = levelPct.coerceIn(0, 100) / 100f

    /** 档位：≥50 OK / 20..49 WARN / <20 CRITICAL。 */
    fun levelTier(levelPct: Int): GravityLevelTier = when {
        levelPct >= 50 -> GravityLevelTier.OK
        levelPct >= 20 -> GravityLevelTier.WARN
        else -> GravityLevelTier.CRITICAL
    }

    /**
     * 档位热色：OK 原样返回主题色；WARN 向琥珀、CRITICAL 向红逐通道线
     * 性混合（纯函数，确定性输出）。
     *
     * 只偏移热色 —— 冷色保持主题：环带渐变的「冷端」是家族观感的锚，
     * 不随档位漂移（三档共用同一条冷端，视觉上才是同一个吸积环）。
     */
    fun tierHotColor(levelPct: Int, themeHot: Int): Int = when (levelTier(levelPct)) {
        GravityLevelTier.OK -> themeHot
        GravityLevelTier.WARN -> blend(themeHot, AMBER, 0.60f)
        GravityLevelTier.CRITICAL -> blend(themeHot, CRITICAL_RED, 0.78f)
    }

    /** 充电喷流亮度：0.35..0.9 慢正弦（相位由分钟级周期折算；边界 coerce 兜住浮点舍入）。 */
    fun jetAlpha(timeMs: Long): Float {
        val phase = (timeMs % JET_PERIOD_MS) / JET_PERIOD_MS.toFloat()
        val wave = sin(phase * TWO_PI)
        return (0.625f + 0.275f * wave).coerceIn(0.35f, 0.9f)
    }

    /** 状态行文案：已充满 / 充电中 / 放电中。 */
    fun statusLabel(charging: Boolean, full: Boolean): String = when {
        full -> "已充满"
        charging -> "充电中"
        else -> "放电中"
    }

    /** ARGB 逐通道线性混合（t 钳 0..1；alpha 一并混合 —— 两个锚点都不透明时保持不透明）。 */
    private fun blend(from: Int, to: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val a = (from ushr shift) and 0xFF
            val b = (to ushr shift) and 0xFF
            return (a + (b - a) * k).toInt().coerceIn(0, 255)
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
