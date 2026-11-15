package com.abyss.tools.core.designsystem.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.asState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 液滴运动状态 —— 「滑动时边缘渐变光」的单一驱动源。
 *
 * 设计要点（全部来自实机复刻教训，改动前务必读）：
 *
 * 1. **一个连续索引驱动所有视觉**：visualIndex 是浮点而非整数。液滴位移、
 *    图标对齐、拉伸、流光中心、按压体积全部由它派生，避免各层动画不同拍。
 *    参考 PrismalAGSL / LiquidGlassBar 的 DampedDragAnimation 复盘。
 * 2. **状态只在 draw / graphicsLayer 阶段读取**：本类对外只暴露 State<T>，
 *    组件绝不把 .value 读进 Composable 函数体（否则 60~120fps 重组）。
 *    官方 Compose 性能指南明确要求 lambda-based modifier 做阶段延迟。
 * 3. **静止不跑任何循环动画**：常驻底栏做无限缓动高光会让背景每帧重录，
 *    Convx 实测约 45fps 全屏重采样、耗电 ~400mA，最终把高光角度冻结在中点。
 *    本类只在拖动 / 切 tab / 显式滚动相位更新时变值，静止时零 invalidate。
 * 4. **速度驱动拉伸**：velocity 归一化到 [-1,1]，stretch = |velocity| 决定
 *    液滴横向拉长与光带被「甩」到后方的距离，松手 spring 回弹。
 */
@Stable
class AbyssLiquidMotion internal constructor(
    initialIndex: Float,
    private val itemCount: Int,
    private val scope: CoroutineScope,
) {
    private val indexAnim = Animatable(initialIndex)
    private val pressAnim = Animatable(0f)
    private val stretchAnim = Animatable(0f)
    private val velocityAnim = Animatable(0f)

    /** 连续选中位置（0 .. itemCount-1），draw 阶段读取。 */
    val visualIndex: State<Float> = indexAnim.asState()

    /** 按压体积 0..1，拖动 / 按下时膨胀。 */
    val press: State<Float> = pressAnim.asState()

    /** 横向拉伸 0..1，由速度驱动，松手回弹。 */
    val stretch: State<Float> = stretchAnim.asState()

    /** 归一化拖动速度 -1..1，决定流光偏向哪一侧。 */
    val velocity: State<Float> = velocityAnim.asState()

    /** 列表滚动相位（可选接线），量化后再传入，避免每像素 invalidate。 */
    private val _scrollPhase = mutableFloatStateOf(0f)
    val scrollPhase: State<Float> get() = _scrollPhase

    /** 拖动中标志：阻止外部 selectedIndex 回灌打断手势。 */
    private val _isDragging = mutableStateOf(false)
    val isDragging: State<Boolean> get() = _isDragging

    /** 拖动事件串行化：pointerInput 每帧回调都 launch 会并发 snapTo 同一 Animatable。 */
    private var dragJob: kotlinx.coroutines.Job? = null

    private val maxIndex: Float get() = (itemCount - 1).coerceAtLeast(0).toFloat()

    /** 外部 selectedIndex 变化时平滑滑过去（单一事实源，不在手势里改路由）。 */
    fun select(index: Int) {
        scope.launch {
            indexAnim.animateTo(
                index.coerceIn(0, itemCount - 1).toFloat(),
                spring(dampingRatio = 0.78f, stiffness = 420f),
            )
        }
    }

    fun onPress() {
        scope.launch { pressAnim.animateTo(1f, tween(90)) }
    }

    fun beginDrag() {
        _isDragging.value = true
        dragJob?.cancel()
        dragJob = null
        onPress()
    }

    /**
     * 拖动增量（单位：tab 个数）与速度（px/s）。
     * 用 snapTo 而非 animateTo：手势期间液滴必须黏手，不能有弹簧滞后。
     */
    fun onDrag(deltaIndex: Float, velocityPxPerSec: Float, tabWidthPx: Float) {
        val target = (indexAnim.value + deltaIndex).coerceIn(0f, maxIndex)
        val normalized = (velocityPxPerSec / (tabWidthPx * 6f)).coerceIn(-1f, 1f)
        dragJob?.cancel()
        dragJob = scope.launch {
            indexAnim.snapTo(target)
            velocityAnim.snapTo(normalized)
            stretchAnim.snapTo((abs(normalized) * 0.9f + 0.12f).coerceIn(0f, 1f))
        }
    }

    /** 松手：吸附到最近 tab，拉伸/速度/按压三项并行回弹，最后提交路由。 */
    fun endDrag(onSettle: (Int) -> Unit) {
        _isDragging.value = false
        dragJob?.cancel()
        dragJob = null
        val target = indexAnim.value.roundToInt().coerceIn(0, itemCount - 1)
        scope.launch {
            launch { stretchAnim.animateTo(0f, spring(0.70f, 300f)) }
            launch { velocityAnim.animateTo(0f, spring(0.70f, 260f)) }
            launch { pressAnim.animateTo(0f, tween(180)) }
        }
        // 吸附走 select 单一入口：外部 selectedIndex 随后变化时 LaunchedEffect
        // 会以同值再调一次 select，不会两条弹簧抢同一个 Animatable。
        select(target)
        onSettle(target)
    }

    fun cancelDrag() {
        _isDragging.value = false
        dragJob?.cancel()
        dragJob = null
        scope.launch {
            launch { stretchAnim.animateTo(0f, spring(0.70f, 300f)) }
            launch { velocityAnim.animateTo(0f, spring(0.70f, 260f)) }
            launch { pressAnim.animateTo(0f, tween(180)) }
        }
        select(indexAnim.value.roundToInt().coerceIn(0, itemCount - 1))
    }

    /**
     * 可选：列表滚动相位。调用方应把滚动量量化（例如每 24dp 一档 / 3° 一档）
     * 后再传入，别把 firstVisibleItemScrollOffset 原样灌进来。
     */
    fun updateScrollPhase(phase: Float) {
        _scrollPhase.floatValue = phase.coerceIn(-1f, 1f)
    }
}

@Composable
fun rememberAbyssLiquidMotion(
    selectedIndex: Int,
    itemCount: Int,
): AbyssLiquidMotion {
    val scope = rememberCoroutineScope()
    // selectedIndex 只作初始动画起点：key 里绝不能带它，否则每次切 tab 都
    // 重建整个 motion（弹簧从头开始，拖动状态被清空）。
    val motion = remember(itemCount, scope) {
        AbyssLiquidMotion(selectedIndex.toFloat(), itemCount, scope)
    }
    val currentSelected by rememberUpdatedState(selectedIndex)

    // 外部状态 → 内部动画；拖动中不回灌（手势优先），松手后由 select 收敛。
    LaunchedEffect(motion.isDragging.value, currentSelected) {
        if (!motion.isDragging.value) motion.select(currentSelected)
    }
    return motion
}
