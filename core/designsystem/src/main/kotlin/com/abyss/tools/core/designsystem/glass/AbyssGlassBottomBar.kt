package com.abyss.tools.core.designsystem.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 主界面底部导航 —— 悬浮椭圆胶囊玻璃栏 + 会滑动的液滴小椭圆选中指示器。
 *
 * v0.9.6：移除 BarTint/BarStyle/DropletStyle 文件级硬编码常量，改为
 * 在 Composable 体内从 [LocalAbyssGlassPalette] 读取当前明暗档色板 ——
 * 修 v0.9.5 白天模式硬编码近黑 0xD90A0E13 的 Bug。整栏 blur 从 26dp 收敛
 * 到 14dp，浅色模式白膜已足够塑形，过度模糊会让底色透不出来发灰。
 */
data class AbyssNavItem(
    val icon: ImageVector,
    val label: String,
    val badge: String? = null,
)

/** 玻璃底栏基准高度。 */
val AbyssBottomBarHeight = 76.dp

private val BarBodyHeight = 72.dp
private val DropletHeight = 56.dp
private val BarHorizontalPadding = 12.dp
private val SlotSpacing = 4.dp

@Composable
private fun rememberBarStyle(): AbyssGlassStyle {
    // 整栏玻璃：blur 14dp（v0.9.6 从26dp收敛，浅色白膜不需要重模糊）
    return AbyssGlassStyle(
        blurRadius = 14.dp,
        lensHeight = 2.5.dp,
        lensAmount = 12.dp,
        chromaticAberration = true,
        // solidTint = null → 走 palette 默认 135° 对角渐变
    )
}

@Composable
private fun rememberDropletStyle(): AbyssGlassStyle {
    val palette = LocalAbyssGlassPalette.current
    return AbyssGlassStyle(
        blurRadius = 4.dp,
        lensHeight = 1.5.dp,
        lensAmount = 7.dp,
        chromaticAberration = false,
        solidTint = palette.dropletTint,
        edgeAlpha = 1f,
    )
}

@Composable
fun AbyssGlassBottomBar(
    backdrop: Backdrop?,
    items: List<AbyssNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = rememberAbyssLiquidMotion(selectedIndex, items.size)
    val iconsBackdrop = rememberLayerBackdrop()
    val lensBackdrop: Backdrop? = if (backdrop != null) iconsBackdrop else null
    val currentOnSelect by rememberUpdatedState(onSelect)
    val density = LocalDensity.current
    val palette = LocalAbyssGlassPalette.current
    val barStyle = rememberBarStyle()
    val dropletStyle = rememberDropletStyle()

    var barWidthPx by remember { mutableIntStateOf(0) }
    val count = items.size.coerceAtLeast(1)
    val padPx = with(density) { BarHorizontalPadding.toPx() }
    val spacingPx = with(density) { SlotSpacing.toPx() }
    val slotWidthPx = if (barWidthPx > 0) {
        ((barWidthPx - 2f * padPx - spacingPx * (count - 1)) / count).coerceAtLeast(1f)
    } else {
        0f
    }
    val stridePx = slotWidthPx + spacingPx
    val slotWidthDp = with(density) { slotWidthPx.toDp() }
    val barWidthDp = with(density) { barWidthPx.toDp() }
    val dropletX: () -> Float = { padPx + motion.visualIndex.value * stridePx }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(BarBodyHeight)
            .onGloballyPositioned { barWidthPx = it.size.width }
            .pointerInput(items.size, motion, stridePx) {
                var lastUptime = 0L
                detectHorizontalDragGestures(
                    onDragStart = { lastUptime = 0L; motion.beginDrag() },
                    onDragEnd = { motion.endDrag { currentOnSelect(it) } },
                    onDragCancel = { motion.cancelDrag() },
                ) { change, dragAmount ->
                    change.consume()
                    val now = change.uptimeMillis
                    val dtMs = if (lastUptime == 0L) 16L else (now - lastUptime).coerceAtLeast(1L)
                    lastUptime = now
                    val velocityPxPerSec = dragAmount / (dtMs / 1000f)
                    if (stridePx > 0f) {
                        motion.onDrag(dragAmount / stridePx, velocityPxPerSec, stridePx)
                    }
                }
            },
    ) {
        // ① 整栏玻璃
        AbyssGlassSurface(
            backdrop = backdrop,
            shape = CircleShape,
            style = barStyle,
            modifier = Modifier.matchParentSize(),
        ) {}

        // ② 图标副本源（供液滴透镜采样）
        Row(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = 0f }
                .then(if (backdrop != null) Modifier.layerBackdrop(iconsBackdrop) else Modifier)
                .padding(horizontal = BarHorizontalPadding, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(SlotSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                NavGlyph(
                    item = item,
                    tint = if (index == selectedIndex) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                    showLabel = false,
                )
            }
        }

        // ③ 可见 Base 层
        Row(
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = BarHorizontalPadding, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(SlotSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                val interaction = remember { MutableInteractionSource() }
                val tint = if (index == selectedIndex) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                NavGlyph(
                    item = item,
                    tint = tint,
                    showLabel = true,
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer {
                            val d = abs(motion.visualIndex.value - index)
                            alpha = ((d - 0.5f) / 0.25f).coerceIn(0f, 1f)
                        }
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                        ) { currentOnSelect(index) },
                )
            }
        }

        // ④ 液滴小椭圆
        if (barWidthPx > 0) {
            Box(Modifier.matchParentSize()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset { IntOffset(dropletX().roundToInt(), 0) }
                        .width(slotWidthDp)
                        .height(DropletHeight)
                        .graphicsLayer {
                            val stretch = motion.stretch.value
                            val bulb = 1f + motion.press.value * 0.06f
                            scaleX = (1f + stretch * 0.34f) * bulb
                            scaleY = (1f - stretch * 0.10f) * bulb
                        }
                        .clip(CircleShape),
                ) {
                    // 4a 玻璃透镜
                    AbyssGlassSurface(
                        backdrop = lensBackdrop,
                        shape = CircleShape,
                        style = dropletStyle,
                        m3Container = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.matchParentSize(),
                    ) {}

                    // 4b 选中色薄膜（alpha 按明暗档分：浅色 0.20 / 深色 0.14）
                    Box(
                        Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                alpha = palette.dropletIndicatorAlpha + motion.press.value * 0.08f
                            }
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )

                    // 4c 放大的图标副本
                    Row(
                        modifier = Modifier
                            .width(barWidthDp)
                            .fillMaxHeight()
                            .graphicsLayer {
                                translationX = -dropletX()
                            }
                            .padding(horizontal = BarHorizontalPadding),
                        horizontalArrangement = Arrangement.spacedBy(SlotSpacing),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        items.forEach { item ->
                            NavGlyph(
                                item = item,
                                tint = MaterialTheme.colorScheme.primary,
                                showLabel = false,
                                iconScale = 1.18f,
                                modifier = Modifier.width(slotWidthDp),
                            )
                        }
                    }

                    // 4d 边缘渐变光
                    Box(
                        Modifier
                            .matchParentSize()
                            .abyssLiquidRim(motion),
                    )
                }
            }
        }
    }
}

@Composable
private fun NavGlyph(
    item: AbyssNavItem,
    tint: Color,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    iconScale: Float = 1f,
) {
    Column(
        modifier = modifier.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (item.badge != null) {
            BadgedBox(
                badge = {
                    Text(
                        text = item.badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onError,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.error)
                            .padding(horizontal = 4.dp),
                    )
                },
            ) {
                GlyphIcon(item, tint, iconScale)
            }
        } else {
            GlyphIcon(item, tint, iconScale)
        }
        if (showLabel) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelMedium,
                color = tint,
            )
        }
    }
}

@Composable
private fun GlyphIcon(item: AbyssNavItem, tint: Color, scale: Float) {
    Icon(
        imageVector = item.icon,
        contentDescription = item.label,
        tint = tint,
        modifier = Modifier
            .size(24.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    )
}
