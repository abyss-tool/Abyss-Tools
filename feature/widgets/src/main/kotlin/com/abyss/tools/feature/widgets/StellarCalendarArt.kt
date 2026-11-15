package com.abyss.tools.feature.widgets

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 星轨日历绘制引擎 —— 单一实现（SignalStatusArt 纪律）：RemoteViews
 * 位图与组件工坊的 Compose 预览共用本对象，所见即所得。
 *
 * 视觉结构（分层顺序 = 前后遮挡关系）：
 *  1. 深空底渐变 + 确定性星场（[WidgetSpace] 共享基座）
 *  2. 椭圆轨道环（squash 0.30 —— 与黑洞时钟吸积盘同款倾角观感）
 *  3. 行星尾迹弧（行星身后 16° 的渐隐光弧）
 *  4. 十二枚月刻度点（当前月更亮更大）
 *  5. 黑洞「恒星」核心（事件视界 + 光子环）
 *  6. 行星本体（亮点 + 光晕，位于年进度折算的轨道角上）
 *  7. 大号日期数字 +「M月 EEEE」星期行 +「年内进度 NN%」—— 文字绘
 *     制进位图，RemoteViews 布局零文本节点
 */
object StellarCalendarArt {

    /** 渲染完整位图（provider 消费；density 已折算进像素尺寸）。 */
    fun render(widthPx: Int, heightPx: Int, timeMs: Long, theme: BlackHoleTheme): Bitmap {
        val bitmap = Bitmap.createBitmap(
            widthPx.coerceAtLeast(1),
            heightPx.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        renderInto(Canvas(bitmap), widthPx, heightPx, timeMs, theme)
        return bitmap
    }

    /** 绘制进既有画布（Compose 预览经 drawIntoCanvas 复用同一实现）。 */
    fun renderInto(canvas: Canvas, widthPx: Int, heightPx: Int, timeMs: Long, theme: BlackHoleTheme) {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val cx = w * 0.5f
        val cy = h * 0.40f
        val horizon = (minOf(w, h) * 0.15f).coerceAtLeast(13f)
        val orbitR = horizon * 2.6f
        // 椭圆压扁：与黑洞时钟吸积盘同款倾角（0.30）—— 家族观感一致
        val squash = 0.30f

        WidgetSpace.drawSpace(canvas, w, h, timeMs)

        // ── 2. 轨道环：scale 后画整圆 —— 椭圆由此而来（与吸积盘同技法）──
        val orbitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = horizon * 0.07f
            color = WidgetSpace.withAlpha(theme.diskCool, 0.55f)
        }
        val saveOrbit = canvas.save()
        canvas.scale(1f, squash, cx, cy)
        canvas.drawCircle(cx, cy, orbitR, orbitPaint)
        canvas.restoreToCount(saveOrbit)

        // ── 3. 行星尾迹：行星身后 16° 渐隐光弧（画在刻度之下）──────────
        val angle = StellarCalendarMath.orbitAngleDeg(timeMs)
        val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = horizon * 0.12f
            strokeCap = Paint.Cap.ROUND
            color = WidgetSpace.withAlpha(theme.photon, 0.40f)
        }
        val saveTrail = canvas.save()
        canvas.scale(1f, squash, cx, cy)
        canvas.drawArc(cx - orbitR, cy - orbitR, cx + orbitR, cy + orbitR, angle - 16f, 16f, false, trailPaint)
        canvas.restoreToCount(saveTrail)

        // ── 4. 十二月刻度：月 i 锚定 i*30°（一月锚在轨道右端起点 ——
        //    行星跨过一枚刻度即跨过一个月初；当前月刻度加亮加大）────────
        val month = StellarCalendarMath.monthIndex(timeMs)
        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 12) {
            val rad = Math.toRadians((i * 30).toDouble())
            val tx = cx + orbitR * Math.cos(rad).toFloat()
            val ty = cy + orbitR * squash * Math.sin(rad).toFloat()
            val current = i == month
            tickPaint.color = if (current) {
                WidgetSpace.withAlpha(theme.photon, 0.92f)
            } else {
                WidgetSpace.withAlpha(0xFF9FB2C4.toInt(), 0.40f)
            }
            canvas.drawCircle(tx, ty, if (current) horizon * 0.14f else horizon * 0.08f, tickPaint)
        }

        // ── 5. 黑洞「恒星」核心 ─────────────────────────────────────
        WidgetSpace.drawMiniHole(canvas, cx, cy, horizon, theme)

        // ── 6. 行星本体：坐标在椭圆上直接折算（不经 scale —— 圆点不变形）──
        val rad = Math.toRadians(angle.toDouble())
        val px = cx + orbitR * Math.cos(rad).toFloat()
        val py = cy + orbitR * squash * Math.sin(rad).toFloat()
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                px, py, horizon * 0.45f,
                WidgetSpace.withAlpha(theme.photon, 0.55f),
                WidgetSpace.withAlpha(theme.photon, 0f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(px, py, horizon * 0.45f, glow)
        canvas.drawCircle(
            px, py, horizon * 0.15f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() },
        )

        drawCalendarText(canvas, w, h, horizon, timeMs)
    }

    // ── 7. 日历文字（底部自下而上三行：日期 / 星期 / 年内进度）────────

    private fun drawCalendarText(canvas: Canvas, w: Int, h: Int, horizon: Float, timeMs: Long) {
        val dayText = SimpleDateFormat("d", Locale.getDefault()).format(Date(timeMs))
        val weekText = SimpleDateFormat("M月 EEEE", Locale.CHINESE).format(Date(timeMs))
        val progressText = "年内进度 ${(StellarCalendarMath.yearProgress(timeMs) * 100f).roundToInt()}%"

        val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF2F8FF.toInt()
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = (horizon * 1.0f).coerceAtLeast(20f)
            letterSpacing = 0.06f
        }
        val midPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFC9D7E4.toInt()
            typeface = Typeface.SANS_SERIF
            textSize = (horizon * 0.36f).coerceAtLeast(11f)
            letterSpacing = 0.1f
        }
        val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8FA1B3.toInt()
            typeface = Typeface.SANS_SERIF
            textSize = (horizon * 0.30f).coerceAtLeast(10f)
            letterSpacing = 0.1f
        }

        // 自下而上排布：进度贴底、星期居中、日期在上 —— 小组件永不裁底
        val baseSmall = h * 0.95f
        val baseMid = baseSmall - smallPaint.textSize * 1.55f
        val baseBig = (baseMid - midPaint.textSize * 1.9f).coerceAtLeast(h * 0.55f)

        canvas.drawText(dayText, (w - bigPaint.measureText(dayText)) / 2f, baseBig, bigPaint)
        canvas.drawText(weekText, (w - midPaint.measureText(weekText)) / 2f, baseMid, midPaint)
        canvas.drawText(progressText, (w - smallPaint.measureText(progressText)) / 2f, baseSmall, smallPaint)
    }
}
