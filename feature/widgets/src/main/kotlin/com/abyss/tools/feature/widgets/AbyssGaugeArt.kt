package com.abyss.tools.feature.widgets

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.sin

/**
 * 引力仪读数快照（provider 无权限读取 → 绘制层 / 工坊预览消费）。
 *
 * ramFraction / storageFraction = null 表示对应读数不可用（totalMem/
 * totalBytes 不健康或系统服务异常）—— 对应仪表只画暗轨、文字行诚实
 * 标注「读取失败」，绝不编造占用（fail-closed 纪律）。
 */
data class AbyssGaugeSnapshot(
    val ramFraction: Float?,
    val storageFraction: Float?,
)

/**
 * 深渊引力仪绘制引擎 —— 单一实现（SignalStatusArt 纪律）：RemoteViews
 * 位图与组件工坊的 Compose 预览共用本对象，所见即所得。
 *
 * 视觉结构（分层顺序 = 前后遮挡关系）：
 *  1. 深空底渐变 + 确定性星场（[WidgetSpace] 共享基座）
 *  2. 外环仪表：内存占用（暗轨 + 扫过弧 + 引力尘埃粒子）
 *  3. 内环仪表：存储占用（同构，半径小一档）
 *  4. 迷你黑洞核心（三组件里最小 —— 双环是主角）
 *  5. 底部两行等宽字体读数：「内存 NN%」「存储 NN%」—— 文字绘制进
 *     位图，RemoteViews 布局零文本节点
 *
 * 两环都是正圆（不压扁）：这是「仪表」语义 —— 与电池的吸积环、日历
 * 的椭圆轨道在几何语言上刻意区分。
 */
object AbyssGaugeArt {

    /** 弧起点：-90°（12 点方向，与电池环一致 —— 家族同款读数方向）。 */
    private const val SWEEP_START_DEG = -90f

    /** 渲染完整位图（provider 消费；density 已折算进像素尺寸）。 */
    fun render(
        widthPx: Int,
        heightPx: Int,
        timeMs: Long,
        theme: BlackHoleTheme,
        snapshot: AbyssGaugeSnapshot,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(
            widthPx.coerceAtLeast(1),
            heightPx.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        renderInto(Canvas(bitmap), widthPx, heightPx, timeMs, theme, snapshot)
        return bitmap
    }

    /** 绘制进既有画布（Compose 预览经 drawIntoCanvas 复用同一实现）。 */
    fun renderInto(
        canvas: Canvas,
        widthPx: Int,
        heightPx: Int,
        timeMs: Long,
        theme: BlackHoleTheme,
        snapshot: AbyssGaugeSnapshot,
    ) {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val cx = w * 0.5f
        val cy = h * 0.40f
        val horizon = (minOf(w, h) * 0.13f).coerceAtLeast(12f)
        val outerR = horizon * 2.45f
        val innerR = horizon * 1.80f

        WidgetSpace.drawSpace(canvas, w, h, timeMs)

        drawGauge(canvas, cx, cy, outerR, horizon, theme, snapshot.ramFraction, particleCount = 4)
        drawGauge(canvas, cx, cy, innerR, horizon, theme, snapshot.storageFraction, particleCount = 3)

        WidgetSpace.drawMiniHole(canvas, cx, cy, horizon, theme)

        drawGaugeText(canvas, w, h, cy, outerR, horizon, snapshot)
    }

    // ── 2/3. 单条环仪表：暗轨 → 扫过弧 → 引力尘埃 ───────────────────

    private fun drawGauge(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        horizon: Float,
        theme: BlackHoleTheme,
        fraction: Float?,
        particleCount: Int,
    ) {
        val stroke = horizon * 0.24f
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = WidgetSpace.withAlpha(theme.diskCool, 0.30f)
        }
        canvas.drawCircle(cx, cy, radius, track)

        if (fraction == null) return // 读取失败：只留暗轨（文字行给失败说明）

        val sweep = AbyssGaugeMath.gaugeSweep(fraction) * 360f
        if (sweep >= 1f) {
            val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                strokeCap = Paint.Cap.ROUND
                color = WidgetSpace.withAlpha(theme.diskHot, 0.92f)
            }
            canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, SWEEP_START_DEG, sweep, false, arc)
        }

        // 引力尘埃：光子色小点，均匀散布在扫过的弧段上
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WidgetSpace.withAlpha(theme.photon, 0.85f)
        }
        AbyssGaugeMath.particleAngles(fraction, particleCount).forEach { a ->
            val rad = Math.toRadians((SWEEP_START_DEG + a).toDouble())
            canvas.drawCircle(
                cx + radius * cos(rad).toFloat(),
                cy + radius * sin(rad).toFloat(),
                horizon * 0.085f,
                dotPaint,
            )
        }
    }

    // ── 5. 底部读数（等宽字体 —— 数字读数的仪表气质）─────────────────

    private fun drawGaugeText(
        canvas: Canvas,
        w: Int,
        h: Int,
        cy: Float,
        outerR: Float,
        horizon: Float,
        snapshot: AbyssGaugeSnapshot,
    ) {
        val ramText = snapshot.ramFraction?.let { "内存 ${AbyssGaugeMath.pctText(it)}" } ?: "内存 读取失败"
        val storageText = snapshot.storageFraction?.let { "存储 ${AbyssGaugeMath.pctText(it)}" } ?: "存储 读取失败"

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFC9D7E4.toInt()
            typeface = Typeface.MONOSPACE
            textSize = (horizon * 0.34f).coerceAtLeast(11f)
            letterSpacing = 0.08f
        }

        val base1 = (cy + outerR + horizon * 0.75f + paint.textSize).coerceAtMost(h * 0.86f)
        canvas.drawText(ramText, (w - paint.measureText(ramText)) / 2f, base1, paint)
        val base2 = (base1 + paint.textSize * 1.35f).coerceAtMost(h - 4f)
        canvas.drawText(storageText, (w - paint.measureText(storageText)) / 2f, base2, paint)
    }
}
