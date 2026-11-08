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

    private var inverseLayerScope: InverseLayerScope? = null

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val coordinates = coordinates ?: return
        val layerCoordinates = layerCoordinates ?: return
        if (isRecordingSelf) return // 防自嵌套：见属性注释
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
