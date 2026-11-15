package com.abyss.tools.feature.widgets

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader

/**
 * 引力家族共享绘制基座（v0.9.0「引力场」）。
 *
 * 深空渐变底 + 确定性星场 + 迷你黑洞核心（事件视界 + 光子环）—— 引力
 * 电池 / 星轨日历 / 深渊引力仪三个新组件与黑洞时钟共用同一片星空：
 * 星点数学直接复用 [BlackHoleMath]（同一 seed 永远同一片星空，位图整
 * 分重绘绝不抖动），黑洞核心常量与 BlackHoleArt 逐行对齐 —— 家族观
 * 感一致。
 *
 * 为什么复制而不改 [BlackHoleArt]：黑洞时钟已上真机验收，公共 API
 * （render / renderInto）牵连 RemoteViews 与工坊预览两处消费方；引力
 * 家族自带基座让时钟零回归风险（单一实现纪律以「同源数学 + 对齐常量」
 * 保障，而不是靠重构现有引擎）。
 */
internal object WidgetSpace {

    /** 深空渐变底 + 确定性星场（星点数学与黑洞时钟完全同源）。 */
    fun drawSpace(canvas: Canvas, w: Int, h: Int, timeMs: Long) {
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

    /**
     * 迷你黑洞核心：光晕 → 光子环（外柔内锐双描边）→ 纯黑事件视界。
     * 半径由调用方给（各组件的黑洞大小不同：电池 > 日历 > 引力仪）。
     */
    fun drawMiniHole(canvas: Canvas, cx: Float, cy: Float, horizon: Float, theme: BlackHoleTheme) {
        // 光晕：吸积内缘炽光对视界边缘的漫射观感
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, horizon * 1.6f,
                theme.diskHot, 0x00000000.toInt(),
                Shader.TileMode.CLAMP,
            )
            alpha = 70
        }
        canvas.drawCircle(cx, cy, horizon * 1.6f, glow)

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

    /** 改写 ARGB 透明通道（0..1）—— alpha 直接烘进颜色本体，规避 Paint.alpha 与 shader 调制的平台差异。 */
    fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }
}
