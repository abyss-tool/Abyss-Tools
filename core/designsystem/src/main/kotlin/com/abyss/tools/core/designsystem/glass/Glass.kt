package com.abyss.tools.core.designsystem.glass

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow

/**
 * ═══════════════════════════════════════════════════════════════════
 *  液态玻璃系统（Kyant0/AndroidLiquidGlass vendored 底座）
 * ═══════════════════════════════════════════════════════════════════
 *
 * 业务 UI 禁止直接散落玻璃 API —— 一律经由本包。底层库替换时只改
 * glass 包，业务组件零感知（对齐 Android-Guru-Agent 的 GlassSurface 纪律）。
 *
 * 真实能力分层（诚实声明，禁止冒充）：
 *  - Backdrop 档（backdrop != null 且 API 31+）：真实采样源内容 →
 *    GPU RenderEffect blur →（API 33+）AGSL lens 折射/色散 → 霜面
 *    tint → 边缘受光 Highlight → InnerShadow 厚度定界。
 *  - Frosted 档（无源 或 API < 31）：霜面渐变 + 边缘光，零 GPU 管线。
 *
 * 采样源由页面提供：`val backdrop = rememberAbyssBackdrop()` 挂在内容
 * 根上（Modifier.abyssBackdropSource(backdrop)），玻璃件悬浮其上 ——
 * 内容从玻璃件下方滚过时即产生真实的液态折射。
 *
 * 自嵌套防线（v0.7.2）：玻璃件位于自身采样源内部（如仪表盘更新卡 /
 * 插件详情 Hero）是合法布局 —— vendored 库已内建 isRecordingSelf 重入
 * 守卫，源录制期间同一 backdrop 的采样绘制被跳过，显示列表深度恒定
 * 有界（修复 v0.7.1 及之前启动秒闪退：RenderThread prepareTree 栈溢出）。
 * 业务层无需为"玻璃件在源内"做任何特殊处理。
 *
 * 所有 drawBackdrop 的 lambda 一律记忆化（ModifierNodeElement 对 lambda
 * 按身份比较，重组期新建 lambda 会引发 draw 失效风暴 —— 参考仓
 * diag-20261011 教训）。
 */

/** 玻璃强度（0.4f 弱 ~ 1.6f 强）：设置页滑杆实时驱动 blur/lens 参数。 */
val LocalGlassIntensity = compositionLocalOf { 1f }

@Composable
fun rememberAbyssBackdrop(): LayerBackdrop = rememberLayerBackdrop()

fun Modifier.abyssBackdropSource(backdrop: LayerBackdrop): Modifier =
    layerBackdrop(backdrop)

/** 玻璃样式参数集（霜面 / 模糊 / 折射 / 受光的单一调节面）。 */
data class AbyssGlassStyle(
    val blurRadius: Dp = 22.dp,
    val lensHeight: Dp = 2.dp,
    val lensAmount: Dp = 10.dp,
    val chromaticAberration: Boolean = true,
    val tint: Color = Color(0xD90A0E13),
    val frostLift: Color = Color(0xFF1B2530),
    val frostBase: Color = Color(0xFF10161E),
    val edgeColor: Color = Color.White.copy(alpha = 0.42f),
    val edgeAlpha: Float = 0.9f,
    val innerShadowColor: Color = Color.Black.copy(alpha = 0.35f),
    val elevation: Dp = 10.dp,
)

/**
 * 统一玻璃容器。用法：
 * ```
 * val backdrop = rememberAbyssBackdrop()
 * Box(Modifier.fillMaxSize()) {
 *     Content(Modifier.abyssBackdropSource(backdrop))   // 采样源
 *     AbyssGlassSurface(backdrop = backdrop, modifier = Modifier.align(Alignment.BottomCenter)) { ... }
 * }
 * ```
 * `backdrop == null` 时自动落 Frosted 档（静态页 / 低端机降级）。
 */
@Composable
fun AbyssGlassSurface(
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    style: AbyssGlassStyle = AbyssGlassStyle(),
    content: @Composable () -> Unit,
) {
    val intensity = LocalGlassIntensity.current
    val canRender = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val effectiveBackdrop: Backdrop = if (backdrop != null && canRender) backdrop else emptyBackdrop()
    val hasLiveSource = backdrop != null && canRender

    // ── lambda 记忆化（键 = 捕获的全部值）────────────────────────────
    val shapeBlock = remember(shape) { { shape } }

    val effectsBlock: BackdropEffectScope.() -> Unit = remember(style, intensity, hasLiveSource) {
        {
            if (hasLiveSource) {
                // OEM RenderEffect/AGSL 差异防御：单项失败降级为无该效果，
                // 组合树与进程存活优先（参考仓 v1.4.9 同款纪律）。
                runCatching { vibrancy() }
                runCatching { blur((style.blurRadius * intensity).toPx()) }
                if (style.lensHeight > 0.dp) {
                    runCatching {
                        lens(
                            refractionHeight = (style.lensHeight * intensity).toPx(),
                            refractionAmount = (style.lensAmount * intensity).toPx(),
                            chromaticAberration = style.chromaticAberration,
                        )
                    }
                }
            }
        }
    }

    val highlightBlock = remember(style, hasLiveSource) {
        {
            Highlight(
                width = 0.8.dp,
                blurRadius = 1.2.dp,
                alpha = if (hasLiveSource) style.edgeAlpha else style.edgeAlpha * 0.7f,
                style = HighlightStyle.Plain(
                    color = style.edgeColor,
                    blendMode = BlendMode.Plus,
                ),
            )
        }
    }

    val innerShadowBlock = remember(style) {
        {
            InnerShadow(
                radius = 6.dp,
                offset = DpOffset(0.dp, 3.dp),
                color = style.innerShadowColor,
                alpha = 0.6f,
            )
        }
    }

    val frostBrush = remember(style) {
        Brush.verticalGradient(
            colors = listOf(style.frostLift, style.frostBase),
            startY = 0f,
            endY = Float.POSITIVE_INFINITY,
        )
    }

    val surfaceBlock: DrawScope.() -> Unit = remember(hasLiveSource, style, frostBrush) {
        {
            if (hasLiveSource) {
                drawRect(style.tint)
            } else {
                drawRect(frostBrush)
            }
        }
    }

    Box(
        modifier = modifier
            // 深度：外阴影在最外层，不被裁剪
            .shadow(
                elevation = style.elevation,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.5f),
                spotColor = Color.Black.copy(alpha = 0.5f),
            )
            // RenderNode 级圆角裁剪：杜绝 blur 角部外溢
            .graphicsLayer {
                this.shape = shape
                this.clip = true
            }
            .drawBackdrop(
                backdrop = effectiveBackdrop,
                shape = shapeBlock,
                effects = effectsBlock,
                highlight = highlightBlock,
                shadow = { null },
                innerShadow = innerShadowBlock,
                onDrawSurface = surfaceBlock,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
