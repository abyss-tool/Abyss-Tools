package com.kyant.backdrop.backdrops

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import com.kyant.backdrop.recordLayer

fun Modifier.layerBackdrop(backdrop: LayerBackdrop): Modifier =
    this then LayerBackdropElement(backdrop)

private class LayerBackdropElement(
    val backdrop: LayerBackdrop
) : ModifierNodeElement<LayerBackdropNode>() {

    override fun create(): LayerBackdropNode {
        return LayerBackdropNode(backdrop)
    }

    override fun update(node: LayerBackdropNode) {
        node.backdrop = backdrop
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "layerBackdrop"
        properties["backdrop"] = backdrop
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LayerBackdropElement) return false

        if (backdrop != other.backdrop) return false

        return true
    }

    override fun hashCode(): Int {
        return backdrop.hashCode()
    }
}

private class LayerBackdropNode(
    var backdrop: LayerBackdrop
) : DrawModifierNode, GlobalPositionAwareModifierNode, Modifier.Node() {

    override val shouldAutoInvalidate: Boolean = false

    override fun ContentDrawScope.draw() {
        drawContent()
        // 自嵌套防线（本仓补丁 v1.1）：录制自身内容期间置位 isRecordingSelf，
        // 同一 backdrop 的消费方（drawBackdrop）在该录制里跳过采样绘制，
        // 杜绝"录制内容嵌套上一帧录制"的每帧 +1 无界增长（RenderThread
        // prepareTree 栈溢出 SIGSEGV）。save/restore 而非 true/false：
        // 防御"多个采样源共用同一 backdrop"时内层 finally 提前复位。
        val wasRecording = backdrop.isRecordingSelf
        backdrop.isRecordingSelf = true
        try {
            recordLayer(this@LayerBackdropNode, backdrop.graphicsLayer) { backdrop.onDraw(this@draw) }
        } finally {
            backdrop.isRecordingSelf = wasRecording
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            backdrop.layerCoordinates = coordinates
        }
    }

    override fun onDetach() {
        backdrop.layerCoordinates = null
    }
}
