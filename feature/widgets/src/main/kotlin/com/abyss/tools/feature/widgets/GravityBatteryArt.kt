package com.abyss.tools.feature.widgets

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface

/**
 * 电池状态快照（provider 粘性广播读取 → 绘制层 / 工坊预览消费）。
 *
 * levelPct = null 表示读取失败（sticky 广播缺失 / level 或 scale 不健
 * 康）—— 绘制层渲染诚实占位，绝不编造电量（fail-closed 纪律）。
 */
data class GravityBatterySnapshot(
    val levelPct: Int?,
    val charging: Boolean,
    val full: Boolean,
)

/**
 * 引力电池绘制引擎 —— 单一实现（SignalStatusArt 纪律）：RemoteViews
 * 位图与组件工坊的 Compose 预览共用本对象，所见即所得。
 *
 * 视觉结构（分层顺序 = 前后遮挡关系）：
 *  1. 深空底渐变 + 确定性星场（[WidgetSpace] 共享基座）
 *  2. 充电时：上下两道极向喷流（线性渐隐；亮度分钟级慢正弦脉动）
 *  3. 吸积环：整圈暗轨（diskCool 低透明度）+ 电量扫过弧（-90° 即 12
 *     点方向起步，热色按档位偏移：≥50 主题色 / 20..49 琥珀 / <20 红）
 *  4. 迷你黑洞（事件视界 + 光子环，比时钟略小 —— 环带才是主角）
 *  5. 大号「NN%」+ 状态行（充电中 / 已充满 / 放电中）—— 文字绘制进
 *     位图，RemoteViews 布局零文本节点
 */
object GravityBatteryArt {

    /** 渲染完整位图（provider 消费；density 已折算进像素尺寸）。 */
    fun render(
        widthPx: Int,
        heightPx: Int,
        timeMs: Long,
        theme: BlackHoleTheme,
        snapshot: GravityBatterySnapshot,
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
        snapshot: GravityBatterySnapshot,
    ) {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val cx = w * 0.5f
        val cy = h * 0.40f
        val horizon = (minOf(w, h) * 0.16f).coerceAtLeast(14f)
        val ringR = horizon * 1.85f
        val stroke = horizon * 0.34f

        WidgetSpace.drawSpace(canvas, w, h, timeMs)
        drawTrack(canvas, cx, cy, ringR, stroke, theme)

        val level = snapshot.levelPct
        if (level != null) {
            val hot = GravityBatteryMath.tierHotColor(level, theme.diskHot)
            // 喷流画在环带之下 —— 等离子体从盘面背后喷出（遮挡关系即物理观感）
            if (snapshot.charging) {
                drawJets(canvas, cx, cy, horizon, timeMs, hot)
            }
            drawSweep(canvas, cx, cy, ringR, stroke, level, hot, theme)
            WidgetSpace.drawMiniHole(canvas, cx, cy, horizon, theme)
            drawLevelText(canvas, w, h, cy, ringR, horizon, level, snapshot)
        } else {
            // 读取失败：只有星场 + 暗轨 + 黑洞 + 诚实占位文案（宁空勿假）
            WidgetSpace.drawMiniHole(canvas, cx, cy, horizon, theme)
            drawFailText(canvas, w, h, cy, ringR, horizon)
        }
    }

    // ── 3a. 整圈暗轨（电量的「轨道」，与扫过弧同半径同环宽）──────────

    private fun drawTrack(canvas: Canvas, cx: Float, cy: Float, ringR: Float, stroke: Float, theme: BlackHoleTheme) {
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = WidgetSpace.withAlpha(theme.diskCool, 0.28f)
        }
        canvas.drawCircle(cx, cy, ringR, track)
    }

    // ── 3b. 电量扫过弧（-90° 起步，热色 → 冷色渐变）───────────────────

    private fun drawSweep(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        ringR: Float,
        stroke: Float,
        levelPct: Int,
        hot: Int,
        theme: BlackHoleTheme,
    ) {
        val sweep = GravityBatteryMath.fraction(levelPct) * 360f
        if (sweep < 1f) return
        // 渐变热端锚定在 12 点（弧起点）：SweepGradient 的位置 0.75 恰对
        // 应 -90° 方向 —— 免 Matrix 旋转，色相前沿与弧起点天然对齐。
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            shader = SweepGradient(
                cx, cy,
                intArrayOf(theme.diskCool, hot, theme.diskCool),
                floatArrayOf(0f, 0.75f, 1f),
            )
        }
        canvas.drawArc(cx - ringR, cy - ringR, cx + ringR, cy + ringR, -90f, sweep, false, arc)
    }

    // ── 2. 极向喷流（充电态专属）─────────────────────────────────────

    private fun drawJets(canvas: Canvas, cx: Float, cy: Float, horizon: Float, timeMs: Long, hot: Int) {
        val alpha = GravityBatteryMath.jetAlpha(timeMs)
        val halfW = horizon * 0.28f
        val inner = horizon * 1.30f
        val outer = horizon * 2.9f

        // 上行喷流：光子环外缘起亮 → 顶端渐隐
        val up = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, cy - inner, 0f, cy - outer,
                WidgetSpace.withAlpha(hot, alpha), WidgetSpace.withAlpha(hot, 0f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(cx - halfW, cy - outer, cx + halfW, cy - inner, up)

        // 下行喷流：镜像
        val down = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, cy + inner, 0f, cy + outer,
                WidgetSpace.withAlpha(hot, alpha), WidgetSpace.withAlpha(hot, 0f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(cx - halfW, cy + inner, cx + halfW, cy + outer, down)
    }

    // ── 5. 电量文字（绘制进位图）─────────────────────────────────────

    private fun drawLevelText(
        canvas: Canvas,
        w: Int,
        h: Int,
        cy: Float,
        ringR: Float,
        horizon: Float,
        levelPct: Int,
        snapshot: GravityBatterySnapshot,
    ) {
        val bigText = "$levelPct%"
        val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF2F8FF.toInt()
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = (horizon * 1.05f).coerceAtLeast(22f)
            letterSpacing = 0.06f
        }
        val bigWidth = bigPaint.measureText(bigText)
        // 环带下缘留出间距后落基线；小组件上贴边时整体上提（coerce）—— 永不裁出画面
        val baseBig = (cy + ringR + horizon * 0.55f + bigPaint.textSize).coerceAtMost(h * 0.90f)
        canvas.drawText(bigText, (w - bigWidth) / 2f, baseBig, bigPaint)

        val label = GravityBatteryMath.statusLabel(snapshot.charging, snapshot.full)
        val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9FB2C4.toInt()
            typeface = Typeface.SANS_SERIF
            textSize = (horizon * 0.34f).coerceAtLeast(11f)
            letterSpacing = 0.1f
        }
        val smallWidth = smallPaint.measureText(label)
        canvas.drawText(label, (w - smallWidth) / 2f, (baseBig + horizon * 0.55f).coerceAtMost(h - 4f), smallPaint)
    }

    /** 读取失败的诚实占位（与电量文案同版式 —— 只是内容换成失败说明）。 */
    private fun drawFailText(canvas: Canvas, w: Int, h: Int, cy: Float, ringR: Float, horizon: Float) {
        val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF2F8FF.toInt()
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = (horizon * 0.72f).coerceAtLeast(18f)
            letterSpacing = 0.06f
        }
        val bigWidth = bigPaint.measureText("读取失败")
        val baseBig = (cy + ringR + horizon * 0.55f + bigPaint.textSize).coerceAtMost(h * 0.90f)
        canvas.drawText("读取失败", (w - bigWidth) / 2f, baseBig, bigPaint)

        val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9FB2C4.toInt()
            typeface = Typeface.SANS_SERIF
            textSize = (horizon * 0.34f).coerceAtLeast(11f)
            letterSpacing = 0.1f
        }
        val smallText = "电池状态暂不可读"
        val smallWidth = smallPaint.measureText(smallText)
        canvas.drawText(smallText, (w - smallWidth) / 2f, (baseBig + horizon * 0.55f).coerceAtMost(h - 4f), smallPaint)
    }
}
