package com.abyss.tools.core.designsystem.glass

import android.os.Build
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.abyss.tools.core.common.GlassProbe
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
 * 成环断路器（v0.7.4，vendored 库补丁 v3）：跨 backdrop 链式成环
 * （玻璃采样导出源、导出源又回灌采样源 —— A→B→C→A）导致显示列表
 * 深度每帧无界增长，RenderThread prepareTree 数分钟后原生栈溢出
 * （bugreport PLE110 2026-11-15，存活 472s）。守卫在采样咽喉统一
 * 判环（帧内录制栈 + 每源可传递采样源集合），成环采样就地拒绝。
 *
 * 嵌套深度预算（v0.7.4）：玻璃件套玻璃件，每层消耗 2~3 个
 * GraphicsLayer 渲染节点 —— 深度超过 [MAX_GLASS_NESTING_DEPTH] 的
 * 内层自动降级 Frosted，渲染树深度上限为常数、与业务布局无关。
 *
 * 所有 drawBackdrop 的 lambda 一律记忆化（ModifierNodeElement 对 lambda
 * 按身份比较，重组期新建 lambda 会引发 draw 失效风暴 —— 参考仓
 * diag-20261011 教训）。
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
 * lens（AGSL RuntimeShader 折射/色散）是否可用（v0.7.8 分级降级）：
 * RenderSafeMode.GlassMode.NO_LENS 档由 MainActivity 置 false —— 全部
 * 玻璃件跳过 lens，保留 vibrancy + blur。AGSL 是各 OEM GPU 驱动最脆弱
 * 的一环，断路器第一次异常终止即停它止血（详见 RenderSafeMode 文档）。
 */
val LocalGlassLensEnabled = compositionLocalOf { true }

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
    val nestingDepth = LocalGlassNestingDepth.current
    val lensEnabled = LocalGlassLensEnabled.current
    val canRender = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // 深度预算（v0.7.4）：玻璃套玻璃超限的内层降级 Frosted ——
    // 渲染树深度恒定有界，不受业务嵌套布局影响。
    val canSampleLive = canRender && nestingDepth < MAX_GLASS_NESTING_DEPTH
    val effectiveBackdrop: Backdrop = if (backdrop != null && canSampleLive) backdrop else emptyBackdrop()
    val hasLiveSource = backdrop != null && canSampleLive

    // ── lambda 记忆化（键 = 捕获的全部值）────────────────────────────
    val shapeBlock = remember(shape) { { shape } }

    val effectsBlock: BackdropEffectScope.() -> Unit = remember(style, intensity, hasLiveSource, lensEnabled) {
        {
            if (hasLiveSource) {
                // OEM RenderEffect/AGSL 差异防御：单项失败降级为无该效果，
                // 组合树与进程存活优先（参考仓 v1.4.9 同款纪律）。
                // 管线探针（v0.7.8）：每个效果挂载成功即落里程碑 ——
                // RenderThread 原生崩溃拿不到 Java 堆栈，探针尾部 =
                // 死亡时管线推进到哪一步（见 GlassProbe 文档）。
                runCatching { vibrancy() }
                    .onSuccess { GlassProbe.mark(GlassProbe.STAGE_VIBRANCY) }
                runCatching { blur((style.blurRadius * intensity).toPx()) }
                    .onSuccess { GlassProbe.mark(GlassProbe.STAGE_BLUR) }
                if (style.lensHeight > 0.dp) {
                    if (!lensEnabled) {
                        // 分级降级档（NO_LENS）：AGSL 全跳，仅落探针
                        GlassProbe.mark(GlassProbe.STAGE_LENS_SKIPPED)
                    } else {
                        runCatching {
                            lens(
                                refractionHeight = (style.lensHeight * intensity).toPx(),
                                refractionAmount = (style.lensAmount * intensity).toPx(),
                                chromaticAberration = style.chromaticAberration,
                            )
                        }.onSuccess { GlassProbe.mark(GlassProbe.STAGE_LENS) }
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
                // 管线探针：首个真实采样帧绘制完成（同阶段连续去重，
                // 逐帧不刷屏）—— 探针尾部若停在 lens-applied 与此之间，
                // 即 GPU 首帧执行期死亡（驱动嫌疑，见 GlassProbe 文档）
                GlassProbe.mark(GlassProbe.STAGE_FIRST_FRAME)
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
        // 子树嵌套深度 +1：玻璃件内的玻璃件按预算判定是否继续真实采样
        CompositionLocalProvider(LocalGlassNestingDepth provides nestingDepth + 1) {
            content()
        }
    }
}
