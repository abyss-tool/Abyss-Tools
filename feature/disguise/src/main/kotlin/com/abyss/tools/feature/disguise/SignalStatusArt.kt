package com.abyss.tools.feature.disguise

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.max

/**
 * 信号显示伪装 —— 纯配置与绘制几何（单一实现，两处消费）：
 *  - [SignalOverlayView]（覆盖层真身，状态栏顶部窗口）
 *  - 信号伪装页的 Compose 画布预览（所见即覆盖层所得）
 *
 * 纯 Kotlin 部分（枚举 / 数据类 / clamp）零 Android 依赖可单测；
 * 绘制函数只依赖 android.graphics.Canvas（feature 模块内合法）。
 */

/** 伪装网络 assertion 类型（label 即状态栏渲染文本，持久化用 name）。 */
enum class FakeNetworkType(val label: String) {
    FIVE_G_A("5G-A"),
    FIVE_G("5G"),
    FOUR_G("4G"),
    LTE("LTE"),
    THREE_G("3G"),
}

/** 图标配色：AUTO 跟随系统深色模式（深底白图标 / 浅底黑图标）。 */
enum class SignalIconTheme { AUTO, ON_DARK, ON_LIGHT }

/** 覆盖层渲染规格（全部字段可持久化恢复）。 */
data class SignalSpoofSpec(
    val networkType: FakeNetworkType = FakeNetworkType.FIVE_G,
    /** 蜂窝信号格（1..4，AOSP 标准四格制，未满格画 32% 透明空槽）。 */
    val bars: Int = 4,
    /** 是否绘制 Wi-Fi 图标（无卡 / 关 Wi-Fi 时也能「显示已连接」）。 */
    val wifi: Boolean = false,
    /** Wi-Fi 强度弧（1..4）。 */
    val wifiBars: Int = 4,
    /** 图标区右缘距屏幕右缘的距离（dp）—— 让位给真实电池图标。 */
    val rightMarginDp: Int = 68,
    /** 遮挡条：盖住真实信号图标（如「无 SIM」角标）的实心底条。 */
    val coverEnabled: Boolean = false,
    val coverWidthDp: Int = 96,
    val iconTheme: SignalIconTheme = SignalIconTheme.AUTO,
) {
    fun clamped(): SignalSpoofSpec = copy(
        bars = bars.coerceIn(1, TOTAL_BARS),
        wifiBars = wifiBars.coerceIn(1, TOTAL_ARCS),
        rightMarginDp = rightMarginDp.coerceIn(RIGHT_MARGIN_MIN_DP, RIGHT_MARGIN_MAX_DP),
        coverWidthDp = coverWidthDp.coerceIn(COVER_WIDTH_MIN_DP, COVER_WIDTH_MAX_DP),
    )

    /** 通知 / 状态卡的文案（"5G-A · 满格 · Wi-Fi"）。 */
    val summary: String
        get() = buildString {
            append(networkType.label)
            append(" · 信号 ")
            append(bars)
            append("/")
            append(TOTAL_BARS)
            if (wifi) {
                append(" · Wi-Fi ")
                append(wifiBars)
                append("/")
                append(TOTAL_ARCS)
            }
        }

    companion object {
        const val TOTAL_BARS = 4
        const val TOTAL_ARCS = 4
        const val RIGHT_MARGIN_MIN_DP = 16
        const val RIGHT_MARGIN_MAX_DP = 200
        const val COVER_WIDTH_MIN_DP = 48
        const val COVER_WIDTH_MAX_DP = 240

        /** 持久化恢复（坏值一律回默认，绝不猜）。 */
        fun fromPrefs(
            networkTypeName: String?,
            bars: Int,
            wifi: Boolean,
            wifiBars: Int,
            rightMarginDp: Int,
            coverEnabled: Boolean,
            coverWidthDp: Int,
            iconThemeName: String?,
        ): SignalSpoofSpec = SignalSpoofSpec(
            networkType = runCatching { FakeNetworkType.valueOf(networkTypeName ?: "") }
                .getOrDefault(FakeNetworkType.FIVE_G),
            bars = bars,
            wifi = wifi,
            wifiBars = wifiBars,
            rightMarginDp = rightMarginDp,
            coverEnabled = coverEnabled,
            coverWidthDp = coverWidthDp,
            iconTheme = runCatching { SignalIconTheme.valueOf(iconThemeName ?: "") }
                .getOrDefault(SignalIconTheme.AUTO),
        ).clamped()
    }
}

/**
 * 状态栏图标绘制（信号格 + 网络制式文本 + Wi-Fi 扇形）。
 *
 * 布局（自右向左）：[右缘留白 rightMarginDp，让位真实电池图标]
 *   → Wi-Fi 扇形（可选） → 间隔 → 四格信号条 → 间隔 → 制式文本（5G-A…）。
 * 遮挡条（可选）从图标左缘再向左铺 coverWidthDp 的实心底色，
 * 用于盖住真实信号位（如无 SIM 角标）。
 */
object SignalStatusArt {

    // 与系统状态栏图标一致的半透明实色（非纯白/纯黑，视觉更接近原生）。
    // Kotlin 十六进制字面量超 Int 范围时推断 Long（无 Java 式回绕）→ toInt() 收敛。
    private val LIGHT_ICON: Int = 0xE6FFFFFF.toInt()
    private val DARK_ICON: Int = 0xE61B1B21.toInt()
    private const val EMPTY_SLOT_ALPHA = 82 // 32%

    fun iconColor(spec: SignalSpoofSpec, nightMode: Boolean): Int =
        when (spec.iconTheme) {
            SignalIconTheme.AUTO -> if (nightMode) LIGHT_ICON else DARK_ICON
            SignalIconTheme.ON_DARK -> LIGHT_ICON
            SignalIconTheme.ON_LIGHT -> DARK_ICON
        }

    fun coverColor(spec: SignalSpoofSpec, nightMode: Boolean): Int =
        if (iconColor(spec, nightMode) == LIGHT_ICON) Color.BLACK else Color.WHITE

    /**
     * 在 [width]×[height] 画布上绘制整套伪装图标。
     * [paint] 由调用方持有复用（View.onDraw / Compose 预览各自的生命周期）。
     */
    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        rawSpec: SignalSpoofSpec,
        nightMode: Boolean,
        density: Float,
        paint: Paint,
    ) {
        val spec = rawSpec.clamped()
        val icon = iconColor(spec, nightMode)

        val rightPad = spec.rightMarginDp * density
        val gap = max(2.5f * density, height * 0.09f)
        val barW = max(2.5f * density, height * 0.13f)
        val barGap = max(1.2f * density, height * 0.05f)
        val textPaint = paint.apply {
            color = icon
            style = Paint.Style.FILL
            textSize = height * 0.42f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val textW = textPaint.measureText(spec.networkType.label)

        val wifiOuterR = height * 0.44f
        val wifiW = if (spec.wifi) wifiOuterR * 2f else 0f
        val barsW = SignalSpoofSpec.TOTAL_BARS * barW +
            (SignalSpoofSpec.TOTAL_BARS - 1) * barGap
        val contentW = textW + gap + barsW + (if (spec.wifi) gap + wifiW else 0f)

        val rightEdge = width - rightPad

        // ── 遮挡条（先画，垫在图标底下）────────────────────────────────
        if (spec.coverEnabled) {
            val coverW = max(spec.coverWidthDp * density, contentW + 10f * density)
            paint.style = Paint.Style.FILL
            paint.color = coverColor(spec, nightMode)
            canvas.drawRect(rightEdge - coverW, 0f, rightEdge, height, paint)
            paint.color = icon
        }

        // ── 自右向左依次落位 ──────────────────────────────────────────
        var cursor = rightEdge

        if (spec.wifi) {
            drawWifiFan(canvas, paint, cursor, height, spec.wifiBars, wifiOuterR, icon, density)
            cursor -= wifiW
        }
        cursor -= gap
        drawBars(canvas, paint, cursor, height, spec.bars, barW, barGap, icon)
        cursor -= barsW + gap

        // 制式文本（垂直几何居中）
        val fm = textPaint.fontMetrics
        val baseline = height / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(spec.networkType.label, cursor - textW, baseline, textPaint)
    }

    /** 四格信号条（右端对齐 [rightX]，未满格画透明空槽）。 */
    private fun drawBars(
        canvas: Canvas,
        paint: Paint,
        rightX: Float,
        height: Float,
        level: Int,
        barW: Float,
        barGap: Float,
        icon: Int,
    ) {
        val total = SignalSpoofSpec.TOTAL_BARS
        val bottom = height * 0.74f
        val minH = height * 0.30f
        val maxH = height * 0.72f
        val step = (maxH - minH) / (total - 1)
        paint.style = Paint.Style.FILL
        for (i in 0 until total) {
            // 自右向左：最右为最高格（与系统信号条方向一致）
            val indexFromRight = total - 1 - i
            val x = rightX - barW - i * (barW + barGap)
            val h = maxH - step * indexFromRight
            val filled = (total - indexFromRight) <= level
            paint.color = icon
            paint.alpha = if (filled) Color.alpha(icon) else EMPTY_SLOT_ALPHA
            canvas.drawRoundRect(
                x,
                bottom - h,
                x + barW,
                bottom,
                barW / 2f,
                barW / 2f,
                paint,
            )
        }
        // 恢复图标原生 alpha（后续文本 / 扇形绘制继承同一 paint）
        paint.alpha = Color.alpha(icon)
    }

    /** Wi-Fi 扇形（圆心在图标块底部中点，四弧自弱到强，未满弧画空槽）。 */
    private fun drawWifiFan(
        canvas: Canvas,
        paint: Paint,
        rightX: Float,
        height: Float,
        level: Int,
        outerR: Float,
        icon: Int,
        density: Float,
    ) {
        val cx = rightX - outerR
        val cy = height * 0.76f
        val stroke = max(2f * density, height * 0.10f)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = stroke
        val rect = android.graphics.RectF()
        for (i in 0 until SignalSpoofSpec.TOTAL_ARCS) {
            val radius = outerR * (i + 1) / SignalSpoofSpec.TOTAL_ARCS
            rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
            val filled = (i + 1) <= level
            paint.color = icon
            paint.alpha = if (filled) Color.alpha(icon) else EMPTY_SLOT_ALPHA
            canvas.drawArc(rect, -135f, 90f, false, paint)
        }
        paint.alpha = 255
        // 底部信号点
        paint.style = Paint.Style.FILL
        paint.color = icon
        canvas.drawCircle(cx, cy, max(1.6f * density, height * 0.055f), paint)
    }
}
