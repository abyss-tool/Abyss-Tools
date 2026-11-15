package com.abyss.tools.feature.widgets

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 黑洞主题（组件工坊选择 → SharedPreferences → provider 重绘读取）。
 *
 * 三个档位共用同一绘制算法，只换吸积盘 / 光子环色相 —— 主题切换后
 * 下一分钟（或手动「立即刷新」）即全局生效。
 */
enum class BlackHoleTheme(val label: String, val diskHot: Int, val diskCool: Int, val photon: Int) {
    ABYSS("深渊青", 0xFF7EC8FF.toInt(), 0xFF24557E.toInt(), 0xFFE4F4FF.toInt()),
    DARK_MATTER("暗物质紫", 0xFFB9A6FF.toInt(), 0xFF4A377F.toInt(), 0xFFEFE7FF.toInt()),
    GOLDFLARE("辉光金", 0xFFFFD9A0.toInt(), 0xFF9A6A2C.toInt(), 0xFFFFF2D9.toInt());

    companion object {
        fun fromOrdinalOrDefault(ordinal: Int): BlackHoleTheme =
            entries.getOrNull(ordinal) ?: ABYSS
    }
}

/**
 * 黑洞时钟绘制引擎 —— 单一实现（SignalStatusArt 纪律）：
 * RemoteViews 位图与组件工坊的 Compose 预览共用本对象，所见即所得。
 *
 * 视觉结构（分层顺序 = 前后遮挡关系）：
 *  1. 深空底渐变 + 确定性星场（闪烁）
 *  2. 吸积盘「后半」（黑洞背后的椭圆弧，被事件视界截断）
 *  3. 事件视界（纯黑圆）+ 光子环（细亮环 + 柔光晕）
 *  4. 吸积盘「前半」（黑洞面前的椭圆弧）
 *  5. 时间（HH:mm）与日期 —— 「绘制的黑洞动态时间」：文字绘制进位图，
 *     RemoteViews 布局零文本节点。
 *
 * 「动态」的实现：几何固定（椭圆盘面 / 前后弧的切分线钉死在屏幕坐
 * 标 —— 转的是气流而不是整个盘面），亮度相位随时间旋转（SweepGradient
 * 经 localMatrix 旋转，每小时一整圈）—— 这是物理正确的观感：盘面倾角
 * 不变，炽热气流绕行。
 */
object BlackHoleArt {

    /** 渲染完整位图（provider 消费；density 已折算进像素尺寸）。 */
    fun render(
        widthPx: Int,
        heightPx: Int,
        timeMs: Long,
        theme: BlackHoleTheme,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(
            widthPx.coerceAtLeast(1),
            heightPx.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        renderInto(Canvas(bitmap), widthPx, heightPx, timeMs, theme)
        return bitmap
    }

    /** 绘制进既有画布（Compose 预览经 drawIntoCanvas 复用同一实现）。 */
    fun renderInto(
        canvas: Canvas,
        widthPx: Int,
        heightPx: Int,
        timeMs: Long,
        theme: BlackHoleTheme,
    ) {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val cx = w * 0.5f
        val cy = h * 0.42f
        val horizon = (minOf(w, h) * 0.21f).coerceAtLeast(18f)
        val rotation = BlackHoleMath.diskRotationDeg(timeMs)

        drawSpace(canvas, w, h, timeMs)
        drawDiskHalf(canvas, cx, cy, horizon, rotation, theme, backHalf = true)
        drawHorizon(canvas, cx, cy, horizon, theme)
        drawDiskHalf(canvas, cx, cy, horizon, rotation, theme, backHalf = false)
        drawClock(canvas, w, h, cy, horizon, timeMs)
    }

    // ── 1. 深空底 + 星场 ──────────────────────────────────────────────

    private fun drawSpace(canvas: Canvas, w: Int, h: Int, timeMs: Long) {
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                0xFF05070B.toInt(), 0xFF0A0F16.toInt(),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

        val starCount = (w * h / 5200).coerceIn(24, 90)
        val stars = BlackHoleMath.starField(starCount)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        stars.forEachIndexed { index, (nx, ny) ->
            paint.color = Color.WHITE
            paint.alpha = (BlackHoleMath.twinkleAlpha(index, timeMs) * 255f).toInt().coerceIn(0, 255)
            canvas.drawCircle(nx * w, ny * h, BlackHoleMath.starRadius(index), paint)
        }
    }

    // ── 2/4. 吸积盘（后半被视界截断 / 前半覆盖视界下缘）───────────────

    private fun drawDiskHalf(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        horizon: Float,
        rotation: Float,
        theme: BlackHoleTheme,
        backHalf: Boolean,
    ) {
        // 椭圆压扁：盘面倾角观感（0.30 ≈ 接近侧视）。只 scale 不 rotate ——
        // 前后弧的切分线（水平轴）固定在屏幕坐标。
        val squash = 0.30f
        val startAngle = if (backHalf) 180f else 0f
        // 三层环带：内亮外暗 + 各自环宽，合成「盘」的厚度感
        val rings = listOf(
            Triple(1.42f, 0.20f, 0.95f),
            Triple(1.80f, 0.24f, 0.62f),
            Triple(2.18f, 0.30f, 0.34f),
        )
        // 亮度相位旋转（气流绕行）：经 shader localMatrix —— 几何不动，
        // SweepGradient 的色相前沿随时间沿椭圆弧行进（每小时一圈）。
        val phaseMatrix = Matrix().apply { setRotate(rotation, cx, cy) }
        val saveCount = canvas.save()
        canvas.scale(1f, squash, cx, cy)
        rings.forEach { (radiusScale, widthScale, ringAlpha) ->
            val r = horizon * radiusScale
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = horizon * widthScale
                shader = SweepGradient(
                    cx, cy,
                    intArrayOf(theme.diskHot, theme.diskCool, theme.diskCool, theme.diskHot),
                    floatArrayOf(0f, 0.25f, 0.72f, 1f),
                ).apply { setLocalMatrix(phaseMatrix) }
                alpha = (ringAlpha * (if (backHalf) 0.85f else 1f) * 255f).toInt().coerceIn(0, 255)
            }
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, startAngle, 180f, false, paint)
        }
        canvas.restoreToCount(saveCount)
    }

    // ── 3. 事件视界 + 光子环 ──────────────────────────────────────────

    private fun drawHorizon(canvas: Canvas, cx: Float, cy: Float, horizon: Float, theme: BlackHoleTheme) {
        // 光晕：吸积盘内缘炽光对视界边缘的漫射观感
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, horizon * 1.6f,
                theme.diskHot, 0x00000000.toInt(),
                Shader.TileMode.CLAMP,
            )
            alpha = 70
        }
        canvas.drawCircle(cx, cy, horizon * 1.6f, glow)

        // 光子环：细亮双描边（外柔内锐）
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = horizon * 0.10f
            color = theme.photon
            alpha = 120
        }
        canvas.drawCircle(cx, cy, horizon * 1.14f, halo)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = horizon * 0.05f
            color = theme.photon
            alpha = 235
        }
        canvas.drawCircle(cx, cy, horizon * 1.10f, ring)

        // 事件视界：纯黑 —— 连背景星光一并吞噬
        canvas.drawCircle(cx, cy, horizon, Paint().apply { color = 0xFF000000.toInt() })
    }

    // ── 5. 时间文字（绘制进位图）─────────────────────────────────────

    private fun drawClock(canvas: Canvas, w: Int, h: Int, cy: Float, horizon: Float, timeMs: Long) {
        val timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timeMs))
        val dateText = SimpleDateFormat("M月d日 EEEE", Locale.CHINESE).format(Date(timeMs))

        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF2F8FF.toInt()
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = (horizon * 1.05f).coerceAtLeast(22f)
            letterSpacing = 0.06f
        }
        val timeWidth = timePaint.measureText(timeText)
        val baseline = (cy + horizon * 1.9f + timePaint.textSize * 0.9f).coerceAtMost(h * 0.92f)
        canvas.drawText(timeText, (w - timeWidth) / 2f, baseline, timePaint)

        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9FB2C4.toInt()
            typeface = Typeface.SANS_SERIF
            textSize = (horizon * 0.34f).coerceAtLeast(11f)
            letterSpacing = 0.1f
        }
        val dateWidth = datePaint.measureText(dateText)
        canvas.drawText(dateText, (w - dateWidth) / 2f, baseline + horizon * 0.55f, datePaint)
    }
}
