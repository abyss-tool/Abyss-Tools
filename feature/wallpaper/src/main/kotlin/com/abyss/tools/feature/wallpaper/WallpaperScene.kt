package com.abyss.tools.feature.wallpaper

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 场景几何与双渲染层的共享内核。
 *
 * 数据只在这里，渲染分两套后端：
 *  - WallpaperService（Engine）用 android.graphics —— 见 [drawFrame]，
 *    服务进程内无 Compose 运行时，且必须做 Paint 池零分配；
 *  - 预览页用 Compose Canvas —— 见 WallpaperScreen 里的 drawAbyssPreview。
 * 两套共享本文件的 [WallpaperTheme] / [buildScene] / [orbCenter] 与全部
 * 几何常量，保证「桌面看到的」与「预览看到的」逐参数一致。
 *
 * [buildScene] / [orbCenter] 是纯 Kotlin（不碰 android.*），可 JVM 单测。
 */

/** 单个柔光光斑。 */
data class Orb(
    /** 初始相位（0–2π，随机）。 */
    val phase: Float,
    /** 光斑半径（px，同场景内一致）。 */
    val radius: Float,
    /** 主题色板索引。 */
    val colorIndex: Int,
    /** 角速度（rad/s，叠在 t 上 → 完整漂移周期约 9–21 秒）。 */
    val speed: Float,
    /** 锚点 x（px）。 */
    val cx0: Float,
    /** 锚点 y（px）。 */
    val cy0: Float,
)

// ── 双渲染层共享常量（数值即规格）────────────────────────────────────────────

/** 光斑基准半径 = min(w, h) × 本系数 × 主题 glowRadiusFactor。 */
internal const val ORB_RADIUS_BASE_FACTOR = 0.28f

/** 横向漂移幅度（× 屏宽）。 */
internal const val ORB_DRIFT_X_FACTOR = 0.06f

/** 纵向漂移幅度（× 屏高）。 */
internal const val ORB_DRIFT_Y_FACTOR = 0.045f

/** 主页视差：cx += xOffset × 屏宽 × 本系数。 */
internal const val PARALLAX_FACTOR = 0.08f

/** 光斑角速度区间（rad/s）。 */
internal const val ORB_SPEED_MIN = 0.30f
internal const val ORB_SPEED_MAX = 0.70f

/** 光斑锚点分布：屏宽 20%–80%。 */
internal const val ORB_X_START_FACTOR = 0.20f
internal const val ORB_X_SPAN_FACTOR = 0.60f

/** 光斑锚点分布：屏高 15%–70%。 */
internal const val ORB_Y_START_FACTOR = 0.15f
internal const val ORB_Y_SPAN_FACTOR = 0.55f

// ── android.graphics 渲染常量（Compose 预览端同参数值）─────────────────────

/** 内层亮核 alpha。 */
internal const val ORB_INNER_ALPHA = 220

/** 外层柔晕 alpha。 */
internal const val ORB_OUTER_ALPHA = 140

/** 外层柔晕半径 = 光斑半径 × 本系数。 */
internal const val ORB_OUTER_RADIUS_FACTOR = 1.5f

/**
 * 确定性建场：同 (theme, w, h, seed) 永远得到同一场景（java.util.Random，
 * 不碰 android.*）。光斑数 = 主题色板长度；半径 = min(w,h) × 0.28 × 主题系数；
 * 锚点分布在屏宽 20%–80% / 屏高 15%–70%；相位随机 0–2π。
 */
fun buildScene(theme: WallpaperTheme, w: Int, h: Int, randomSeed: Long = 42L): List<Orb> {
    val random = Random(randomSeed)
    val radius = min(w, h) * ORB_RADIUS_BASE_FACTOR * theme.glowRadiusFactor
    return List(theme.colors.size) { i ->
        Orb(
            phase = random.nextFloat() * (2.0 * PI).toFloat(),
            radius = radius,
            colorIndex = i,
            speed = ORB_SPEED_MIN + random.nextFloat() * (ORB_SPEED_MAX - ORB_SPEED_MIN),
            cx0 = w * (ORB_X_START_FACTOR + random.nextFloat() * ORB_X_SPAN_FACTOR),
            cy0 = h * (ORB_Y_START_FACTOR + random.nextFloat() * ORB_Y_SPAN_FACTOR),
        )
    }
}

/**
 * 光斑中心随时间缓慢漂移（正弦轨迹，无累积误差）。
 *
 * @param t 漂移时间（秒，已含主题节奏与用户速度折算）
 * @param parallax 主页 xOffset（0–1），叠加横向视差偏移，默认 0（预览端用不到）
 */
fun orbCenter(orb: Orb, t: Float, w: Int, h: Int, parallax: Float = 0f): Pair<Float, Float> {
    val cx = orb.cx0 +
        sin(t * orb.speed + orb.phase) * w * ORB_DRIFT_X_FACTOR +
        parallax * w * PARALLAX_FACTOR
    val cy = orb.cy0 + cos(t * orb.speed * 0.8f + orb.phase * 1.7f) * h * ORB_DRIFT_Y_FACTOR
    return cx to cy
}

/**
 * android.graphics 渲染池（WallpaperService 专用）。
 *
 * 每帧零分配纪律：
 *  - [backgroundPaint] / [layerPaints] 常驻复用（Paint 池），仅当主题或半径
 *    变化时重建一次；
 *  - RadialGradient 锚定在原点（0,0），光斑移动靠 canvas.translate 实现 ——
 *    场景不变时 shader 与 Paint 都不重建，drawFrame 内没有任何对象分配。
 *
 * 单线程约定：只在引擎的主线程 Handler 回调里调用（预览页走 Compose，
 * 不经过本对象）。
 */
private object OrbPainter {

    private var cacheKey: String? = null

    /** 底色画笔（纯色无 shader）。 */
    private val backgroundPaint = Paint()

    /** 光斑双层画笔池：[colorIndex × 2 + layer]，layer 0 = 内核 / 1 = 外晕。 */
    private var layerPaints: List<Paint> = emptyList()

    fun ensure(theme: WallpaperTheme, radius: Float) {
        val key = "${theme.id}:$radius"
        if (key == cacheKey) return
        cacheKey = key
        backgroundPaint.color = theme.base.toInt()
        layerPaints = theme.colors.flatMap { argbLong ->
            val argb = argbLong.toInt()
            val fadeOut = argb and 0x00FFFFFF // 同色相透明端，避免渐变收尾发暗
            listOf(
                layer(argb, fadeOut, radius, ORB_INNER_ALPHA),
                layer(argb, fadeOut, radius * ORB_OUTER_RADIUS_FACTOR, ORB_OUTER_ALPHA),
            )
        }
    }

    private fun layer(color: Int, fadeOut: Int, radius: Float, alpha: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.alpha = alpha
            shader = RadialGradient(
                /* centerX = */ 0f,
                /* centerY = */ 0f,
                /* radius = */ radius,
                /* colors = */ intArrayOf(color, fadeOut),
                /* stops = */ null,
                /* tileMode = */ Shader.TileMode.CLAMP,
            )
        }

    fun drawBackground(canvas: Canvas, w: Int, h: Int) {
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), backgroundPaint)
    }

    fun drawOrb(canvas: Canvas, cx: Float, cy: Float, radius: Float, colorIndex: Int) {
        if (layerPaints.isEmpty()) return
        val base = (colorIndex * 2).coerceIn(0, (layerPaints.size / 2 - 1) * 2)
        canvas.save()
        canvas.translate(cx, cy) // 渐变锚定原点，平移画布代替重建 shader
        canvas.drawCircle(0f, 0f, radius * ORB_OUTER_RADIUS_FACTOR, layerPaints[base + 1])
        canvas.drawCircle(0f, 0f, radius, layerPaints[base])
        canvas.restore()
    }
}

/**
 * 绘制一帧（android.graphics 后端）：底色平铺 + 每光斑双层柔光
 * （内核 alpha 220 / 外晕 alpha 140 叠加，液态玻璃质感）。
 *
 * @param t 漂移时间（秒，已含主题节奏与用户速度折算）
 * @param parallax 主页 xOffset（0–1），叠加到光斑横向位置（× w × 0.08）
 */
fun drawFrame(
    canvas: Canvas,
    theme: WallpaperTheme,
    orbs: List<Orb>,
    t: Float,
    w: Int,
    h: Int,
    parallax: Float = 0f,
) {
    if (w <= 0 || h <= 0 || orbs.isEmpty()) return
    val radius = orbs.first().radius // buildScene 保证同场景半径一致
    OrbPainter.ensure(theme, radius)
    OrbPainter.drawBackground(canvas, w, h)
    for (orb in orbs) {
        val (cx, cy) = orbCenter(orb, t, w, h, parallax)
        OrbPainter.drawOrb(canvas, cx, cy, orb.radius, orb.colorIndex)
    }
}
