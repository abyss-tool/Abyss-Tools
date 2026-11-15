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
import kotlin.math.roundToInt

/**
 * 主界面底部导航 —— 悬浮椭圆胶囊玻璃栏 + 会滑动的液滴小椭圆选中指示器。
 *
 * ┌─ 四层结构（三层采样 + 可见层，对齐 iOS 26 / PrismalAGSL 复刻）──────┐
 * │ ① 整栏玻璃：消费主 backdrop（整页内容）→ blur + lens + 色散          │
 * │ ② 图标副本源：layerBackdrop(iconsBackdrop)，源内【无任何玻璃消费方】  │
 * │ ③ 可见 Base 层：点击在这里，液滴盖住的 tab 用 draw 阶段 alpha 隐藏    │
 * │ ④ 液滴透镜：消费 iconsBackdrop，折射/放大其下方图标 + 边缘渐变流光   │
 * └────────────────────────────────────────────────────────────────┘
 *
 * 与宿主装配（AbyssRoot.kt:239-431）的对应关系：内容层 Box 是主 backdrop
 * 采样源，底栏 Box 是它的**兄弟节点**（不在源内）→ 整栏玻璃能真实折射，
 * 且液滴采样 iconsBackdrop 时不会落进主 backdrop 的录制栈，不触发
 * GlassRecordingGuard 的跨源成环拒绝。
 *
 * 为什么液滴不塞进 ① 的 content{}：vendored 库 DrawBackdropModifier.kt:332
 * 的 isRecordingSelf 分支会把「位于自身采样源内的玻璃件」整体跳过绘制，
 * 液滴会只剩 tint、拿不到折射。四层必须是兄弟节点。
 *
 * 「滑动时边缘渐变光」= 液滴位移跟随连续 visualIndex，边缘 AGSL 光带中心
 * 由速度/拉伸反向偏移（abyssLiquidRim），静止时冻结、零 invalidate。
 *
 * 几何：本文件自行 fillMaxWidth（宿主 AbyssRoot.kt:427 的调用点没有写
 * fillMaxWidth，旧实现靠 content Row 的 fillMaxWidth 撑宽；本实现各层用
 * matchParentSize，必须由本文件自己撑宽，否则父 Box 宽度不确定）。
 */
data class AbyssNavItem(
    val icon: ImageVector,
    val label: String,
    val badge: String? = null,
)

/** 玻璃底栏基准高度（页面 bottomClearance 的基准，保持 76dp 不动）。 */
val AbyssBottomBarHeight = 76.dp

private val BarBodyHeight = 72.dp
private val DropletHeight = 56.dp
private val BarHorizontalPadding = 12.dp
private val SlotSpacing = 4.dp
private val BarTint = Color(0xD90A0E13)

private val BarStyle = AbyssGlassStyle(
    blurRadius = 26.dp,
    lensHeight = 2.5.dp,
    lensAmount = 12.dp,
    chromaticAberration = true,   // 色散挂整栏：宽矮长直边能铺成连续彩虹带
    tint = BarTint,
    elevation = 12.dp,
)

private val DropletStyle = AbyssGlassStyle(
    blurRadius = 4.dp,            // 低模糊保住图标高频对比
    lensHeight = 1.5.dp,
    lensAmount = 7.dp,
    chromaticAberration = false,  // 小胶囊上强色散会出现「异常彩虹纹」
    tint = Color(0x260A0E13),
    edgeAlpha = 1f,
    elevation = 0.dp,
)

@Composable
fun AbyssGlassBottomBar(
    backdrop: Backdrop?,
    items: List<AbyssNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = rememberAbyssLiquidMotion(selectedIndex, items.size)
    // 第二采样源：只录图标，供液滴透镜消费
    val iconsBackdrop = rememberLayerBackdrop()
    val lensBackdrop: Backdrop? = if (backdrop != null) iconsBackdrop else null
    val currentOnSelect by rememberUpdatedState(onSelect)
    val density = LocalDensity.current

    var barWidthPx by remember { mutableIntStateOf(0) }
    val count = items.size.coerceAtLeast(1)
    val padPx = with(density) { BarHorizontalPadding.toPx() }
    val spacingPx = with(density) { SlotSpacing.toPx() }
    // 精确的 slot 几何：与 ②③ 两层 Row 的 padding + spacedBy 逐像素对齐，
    // 否则液滴会偏离图标中心（旧版用 barWidth/count 是错的）。
    val slotWidthPx = if (barWidthPx > 0) {
        ((barWidthPx - 2f * padPx - spacingPx * (count - 1)) / count).coerceAtLeast(1f)
    } else {
        0f
    }
    val stridePx = slotWidthPx + spacingPx
    val slotWidthDp = with(density) { slotWidthPx.toDp() }
    val barWidthDp = with(density) { barWidthPx.toDp() }
    // 液滴窗口左边缘（相对底栏左边缘）
    val dropletX: () -> Float = { padPx + motion.visualIndex.value * stridePx }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(BarBodyHeight)
            .onGloballyPositioned { barWidthPx = it.size.width }
            // 只认横向拖动：纵向手势留给子 clickable / 页面滚动，避免吞掉点击
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
        // ① 整栏玻璃（兄弟节点，不与液滴构成自嵌套）
        AbyssGlassSurface(
            backdrop = backdrop,
            shape = CircleShape,
            style = BarStyle,
            modifier = Modifier.matchParentSize(),
        ) {}

        // ② HiddenSampling 图标副本源（PrismalAGSL 三层结构）：只向液滴
        // 透镜供样、屏上不可见 —— alpha=0 必须挂在 layerBackdrop **外层**
        // （先 alpha 后挂源）：录制进 iconsBackdrop 的内容保持全 alpha，
        // 屏上合成不可见。若反序，录进 backdrop 的也是空层。本层不隐藏的
        // 后果：无标签版与 ③ 带标签版图标垂直中心差 ~9px，全栏图标重影。
        // 安全模式下不挂采样修饰符（零 backdrop 录制）。
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

        // ③ 可见 Base 层：点击在这里；液滴盖住的 tab 用 draw 阶段 alpha 隐藏，零重组
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
                            // 选中项交给液滴层显示，避免重影；读 State 在 draw 阶段
                            alpha = if (motion.visualIndex.value.roundToInt() == index) 0f else 1f
                        }
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                        ) { currentOnSelect(index) },
                )
            }
        }

        // ④ 液滴小椭圆：位移 / 拉伸 / 流光全部由 motion 在 layout/draw 阶段驱动
        if (barWidthPx > 0) {
            Box(Modifier.matchParentSize()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        // offset{} lambda 版：layout 阶段读状态，不触发重组
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
                    // 4a 玻璃透镜：折射放大其下方图标（消费 ② 的独立采样源）
                    AbyssGlassSurface(
                        backdrop = lensBackdrop,
                        shape = CircleShape,
                        style = DropletStyle,
                        modifier = Modifier.matchParentSize(),
                    ) {}

                    // 4b 选中色薄膜（弱，保证浅色背景下可读；alpha 在 draw 阶段读）
                    Box(
                        Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                alpha = 0.14f + motion.press.value * 0.08f
                            }
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )

                    // 4c 放大的图标副本：整栏图标对齐到当前 slot，纯 draw 阶段无重组。
                    // 第 i 个 slot 在窗口坐标系的 x = (i - visualIndex) * stride，
                    // i = round(visualIndex) 时恰好落在 [0, slotWidth] 内。
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
                            // 只有当前 slot 的图标会落进液滴窗口，颜色恒为选中色；
                            // 绝不在这里读 visualIndex（组合阶段读 = 每帧重组）。
                            NavGlyph(
                                item = item,
                                tint = MaterialTheme.colorScheme.primary,
                                showLabel = false,
                                iconScale = 1.18f,
                                modifier = Modifier.width(slotWidthDp),
                            )
                        }
                    }

                    // 4d 边缘渐变光（滑动时流动、速度越快甩得越远）
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
                            .clip(RoundedCornerShape(8.dp))
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
