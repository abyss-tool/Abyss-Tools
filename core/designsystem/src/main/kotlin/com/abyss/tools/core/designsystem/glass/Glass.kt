package com.abyss.tools.core.designsystem.glass

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
 *    135°对角渐变 tint（浅色白膜/深色黑膜）→ 三段式边缘 rim →
 *    InnerShadow 厚度定界 + 冷灰外投影。
 *  - M3 卡面档（无源 或 API < 31）：标准 Material 3 卡面
 *    （surfaceContainerHigh 色阶 + 形状阶梯 + 标准阴影），零 GPU 管线。
 *
 * 明暗双档（v0.9.6）：色板由 Theme 注入 [LocalAbyssGlassPalette]，不再
 * 文件级常量硬编码深色值，修 v0.9.5 白天模式底部导航栏近黑 Bug。
 *
 * 采样收敛（v0.8.0）：真实液态玻璃当前仅底栏一处（闸门在 AbyssRoot），
 * 页内玻璃件一律 M3 卡面档。
 *
 * 自嵌套防线（v0.7.2）：vendored 库内建 isRecordingSelf 重入守卫。
 * 成环断路器（v0.7.4）：GlassRecordingGuard 判环拒绝跨源循环。
 * 嵌套深度预算（v0.7.4）：超 MAX_GLASS_NESTING_DEPTH 内层自动降级。
 */

/** 玻璃强度（0.4f 弱 ~ 1.6f 强）：设置页滑杆实时驱动 blur/lens 参数。 */
val LocalGlassIntensity = compositionLocalOf { 1f }

/**
 * 玻璃嵌套深度（v0.7.4）：玻璃件组合树内的层级，根为 0。
 * 供 [AbyssGlassSurface] 计算深度预算 —— 超深内层自动 Frosted。
 */
val LocalGlassNestingDepth = compositionLocalOf { 0 }

/** 玻璃最大嵌套深度（第 0 层 = 顶层玻璃件，可再嵌 2 层）。 */
const val MAX_GLASS_NESTING_DEPTH = 2

/**
 * lens（AGSL RuntimeShader 折射/色散）开关（v0.9.6 精简）。
 * 默认 true；runCatching 单项降级兜底，外断路器已移除。
 * 保留 CompositionLocal 以供未来设置页扩展。
 */
val LocalGlassLensEnabled = compositionLocalOf { true }

@Composable
fun rememberAbyssBackdrop(): LayerBackdrop = rememberLayerBackdrop()

fun Modifier.abyssBackdropSource(backdrop: LayerBackdrop): Modifier =
    layerBackdrop(backdrop)

/**
 * 玻璃样式参数集（真实采样档的单一调节面 —— 模糊 / 折射 / 受光）。
 * v0.9.6：移除硬编码 `tint`/`edgeColor`/`innerShadowColor`/`elevation`
 * 默认值，改为从 [AbyssGlassPalette] 读；留 blur/lens 等强度参数。
 */
data class AbyssGlassStyle(
    val blurRadius: Dp = 14.dp,
    val lensHeight: Dp = 2.dp,
    val lensAmount: Dp = 10.dp,
    val chromaticAberration: Boolean = true,
    val edgeAlpha: Float = 0.9f,
    /** 覆写 tint（null 则用 palette 默认渐变；液滴等小胶囊传单色） */
    val solidTint: Color? = null,
)

/**
 * 统一玻璃容器。用法：
 * ```
 * val backdrop = rememberAbyssBackdrop()
 * Box(Modifier.fillMaxSize()) {
 *     Content(Modifier.abyssBackdropSource(backdrop))
 *     AbyssGlassSurface(backdrop, Modifier.align(Alignment.BottomCenter)) { ... }
 * }
 * ```
 */
@Composable
fun AbyssGlassSurface(
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    style: AbyssGlassStyle = AbyssGlassStyle(),
    /** M3 卡面档容器色覆写（缺省 surfaceContainerHigh） */
    m3Container: Color? = null,
    content: @Composable () -> Unit,
) {
    val intensity = LocalGlassIntensity.current
    val nestingDepth = LocalGlassNestingDepth.current
    val lensEnabled = LocalGlassLensEnabled.current
    val palette = LocalAbyssGlassPalette.current
    val canRender = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val canSampleLive = canRender && nestingDepth < MAX_GLASS_NESTING_DEPTH
    val hasLiveSource = backdrop != null && canSampleLive

    // ── M3 卡面档
    if (!hasLiveSource) {
        Surface(
            modifier = modifier,
            shape = shape,
            color = m3Container ?: MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 1.dp,
        ) {
            CompositionLocalProvider(LocalGlassNestingDepth provides nestingDepth + 1) {
                content()
            }
        }
        return
    }

    val effectiveBackdrop: Backdrop = backdrop

    val shapeBlock = remember(shape) { { shape } }

    val effectsBlock: BackdropEffectScope.() -> Unit = remember(style, intensity, lensEnabled) {
        {
            // OEM RenderEffect/AGSL 差异防御：单项失败降级为无该效果，
            // 组合树与进程存活优先（runCatching fail-open 到下一个效果）。
            runCatching { vibrancy() }
            runCatching { blur((style.blurRadius * intensity).toPx()) }
            if (style.lensHeight > 0.dp && lensEnabled) {
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

    val highlightBlock = remember(style, palette) {
        {
            Highlight(
                width = 0.8.dp,
                blurRadius = 1.2.dp,
                alpha = style.edgeAlpha,
                style = HighlightStyle.Plain(
                    color = palette.rimColor,
                    blendMode = palette.highlightBlendMode,
                ),
            )
        }
    }

    val innerShadowBlock = remember(palette) {
        {
            InnerShadow(
                radius = 6.dp,
                offset = DpOffset(0.dp, 3.dp),
                color = palette.innerShadowColor,
                alpha = 1f,
            )
        }
    }

    val surfaceBlock: DrawScope.() -> Unit = remember(style, palette) {
        {
            val tint = style.solidTint
            if (tint != null) {
                // 液滴等小胶囊：单色填充
                drawRect(tint)
            } else {
                // 整栏：135°对角渐变填充（iOS 26 液态玻璃白膜/黑膜）
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(palette.barTintStart, palette.barTintEnd),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    ),
                )
                // 顶部 1px 三段式 rim：左暗-中亮-右暗（模拟顶部受光）
                val rimHeightPx = 1.dp.toPx()
                val rimWidth = size.width
                if (rimWidth > 0f) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                palette.rimColor.copy(alpha = 0f),
                                palette.rimColor,
                                palette.rimColor.copy(alpha = 0f),
                            ),
                            startX = rimWidth * 0.1f,
                            endX = rimWidth * 0.9f,
                        ),
                        size = Size(rimWidth, rimHeightPx),
                    )
                }
            }
        }
    }

    Box(
        modifier = modifier
            .shadow(
                elevation = 12.dp,
                shape = shape,
                clip = false,
                ambientColor = palette.ambientShadowColor,
                spotColor = palette.spotShadowColor,
            )
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
        CompositionLocalProvider(LocalGlassNestingDepth provides nestingDepth + 1) {
            content()
        }
    }
}
