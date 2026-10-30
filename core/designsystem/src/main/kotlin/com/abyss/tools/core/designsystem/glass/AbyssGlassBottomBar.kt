package com.abyss.tools.core.designsystem.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop

/**
 * 主界面底部导航 —— 液态玻璃 Dark 栏（深渊工具的门面件）。
 *
 * 结构：AbyssGlassSurface（真实采样内容 blur/lens）内嵌一行导航项；
 * 选中项以「果冻药丸」呈现（primary 色容器 + 图标文字提亮 + 160ms
 * 形变动画）。内容从栏下滚过时产生真实折射 —— 这就是「液态」的含义。
 */
data class AbyssNavItem(
    val icon: ImageVector,
    val label: String,
    val badge: String? = null,
)

/** 玻璃底栏高度（含内边距）—— 各页面内容 bottom contentPadding 的基准。 */
val AbyssBottomBarHeight = 76.dp

@Composable
fun AbyssGlassBottomBar(
    backdrop: Backdrop?,
    items: List<AbyssNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    AbyssGlassSurface(
        backdrop = backdrop,
        shape = RoundedCornerShape(32.dp),
        style = AbyssGlassStyle(
            blurRadius = 26.dp,
            lensHeight = 2.5.dp,
            lensAmount = 12.dp,
            tint = Color0D9,
        ),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                GlassNavItem(item = item, selected = selected, modifier = Modifier.weight(1f)) {
                    onSelect(index)
                }
            }
        }
    }
}

@Composable
private fun GlassNavItem(
    item: AbyssNavItem,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val pillAlpha by animateFloatAsState(
        targetValue = if (selected) 0.18f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "glass_nav_pill",
    )
    val interaction = remember { MutableInteractionSource() }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = pillAlpha))
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) { onClick() }
            .padding(vertical = 8.dp),
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
                NavIcon(item, contentColor)
            }
        } else {
            NavIcon(item, contentColor)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
        )
    }
}

@Composable
private fun NavIcon(item: AbyssNavItem, tint: androidx.compose.ui.graphics.Color) {
    Box(contentAlignment = Alignment.Center) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** 底栏专用霜面（略透的深渊黑，突出折射层次）。 */
private val Color0D9 = androidx.compose.ui.graphics.Color(0xD90A0E13)
