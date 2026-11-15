package com.kyant.backdrop.backdrops

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.GlassRecordingGuard
import com.kyant.backdrop.InverseLayerScope

private val DefaultOnDraw: ContentDrawScope.() -> Unit = { drawContent() }

@Composable
fun rememberLayerBackdrop(
    graphicsLayer: GraphicsLayer = rememberGraphicsLayer(),
    onDraw: ContentDrawScope.() -> Unit = DefaultOnDraw
): LayerBackdrop {
    return remember(graphicsLayer, onDraw) {
        LayerBackdrop(graphicsLayer, onDraw)
    }
}

@Stable
class LayerBackdrop internal constructor(
    val graphicsLayer: GraphicsLayer,
    internal val onDraw: ContentDrawScope.() -> Unit
) : Backdrop {

    override val isCoordinatesDependent: Boolean = true

    internal var layerCoordinates: LayoutCoordinates? by mutableStateOf(null)

    /**
     * 自嵌套防线（本仓补丁 v1.1，2026-11-08）：
     *
     * 该 backdrop 的采样源（LayerBackdropNode）正在把自身内容录制进
     * graphicsLayer 期间为 true。此时若同一 backdrop 的消费方也在被录制
     * 的子树里执行 drawBackdrop —— 即玻璃件位于自身采样源内部 —— 照常
     * drawLayer(graphicsLayer) 会把**上一帧的录制**嵌进**本帧的录制**，
     * 显示列表（RenderNode 树）深度每帧 +1 且无界增长；RenderThread 递归
     * prepareTree 最终原生栈溢出：SIGSEGV (SEGV_ACCERR)，backtrace 呈
     * prepareListAndChildren ↔ prepareTreeImpl 数百帧交替（参考 bugreport
     * PLE110 2026-11-08 启动秒闪退三连崩，512 帧）。
     *
     * 守卫语义：自录制期间跳过采样绘制 —— 玻璃件视觉退为本层 tint /
     * 霜面 / 内容（视觉上可接受），树深恒定有界。正常绘制阶段（live
     * pass）不受影响，真实折射保留。
     */
    internal var isRecordingSelf = false

    /**
     * 成环断路器（本仓补丁 v3）：本采样源的层内容可传递地嵌入了哪些
     * 采样源（含经玻璃层中转的）。由 [GlassRecordingGuard.registerSample]
     * 在采样执行时登记 —— 一旦「采样 X 时 X 的传递源含本源」判定成立，
     * 即为跨帧无界增长的环，采样被就地拒绝。见 GlassRecordingGuard 文档。
     */
    internal val transitiveSources = HashSet<LayerBackdrop>()

    private var inverseLayerScope: InverseLayerScope? = null

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val coordinates = coordinates ?: return
        val layerCoordinates = layerCoordinates ?: return
        if (isRecordingSelf) return // 防自嵌套：见属性注释（v1.1 快路径）
        // 成环断路器（v3）：跨 backdrop 链式成环在此就地拒绝 ——
        // 帧内自嵌套（推广形态）与跨帧增长两类全覆盖。
        // （扩展函数内的 this 是 DrawScope 接收者 —— 限定 @LayerBackdrop）
        if (!GlassRecordingGuard.canSample(this@LayerBackdrop)) return
        GlassRecordingGuard.registerSample(this@LayerBackdrop)
        withTransform({
            if (layerBlock != null) {
                with(obtainInverseLayerScope()) { inverseTransform(density, layerBlock) }
            }
            val offset =
                try {
                    layerCoordinates.localPositionOf(coordinates)
                } catch (_: Exception) {
                    // TODO: outer transformations lead to wrong position calculation
                    coordinates.positionInWindow() - layerCoordinates.positionInWindow()
                }
            translate(-offset.x, -offset.y)
        }) {
            drawLayer(graphicsLayer)
        }
    }

    private fun obtainInverseLayerScope(): InverseLayerScope {
        return inverseLayerScope?.apply { reset() }
            ?: InverseLayerScope().also { inverseLayerScope = it }
    }
}
