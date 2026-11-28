package com.abyss.tools.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 可交互状态原语（FEAT-038 / FEAT-039，v0.10.2）。
 *
 * UI-001 纪律不动 [StatusChip]（8dp 圆点、40+ 调用点）；这里新增**独立**组件：
 *  - [InteractiveStatusChip]：8dp 圆点 + 短标签 + ≥ 24dp 触控区，点击直达授权页；
 *  - [HelpIconButton]：小问号；
 *  - [LockBadge]：锁定卡的「锁 + 需 XX」短文案。
 * 全部颜色从 [ChipTone] 派生，业务页不手写 ARGB。
 */

/**
 * 能力明细片：圆点（同 StatusChip 语义）+ 短标签（≤ 6 字）。
 * [status] 只进 contentDescription（「标签 · 状态」），视觉仍以圆点色表态。
 */
@Composable
fun InteractiveStatusChip(
    label: String,
    status: String,
    tone: ChipTone,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val description = "$label · $status"
    Row(
        modifier = modifier
            .sizeIn(minWidth = 24.dp, minHeight = 24.dp)
            .clip(MaterialTheme.shapes.small)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = description, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .semantics { contentDescription = description }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(tone.content),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 小问号：普通用户档位旁常驻，点击弹权限说明。 */
@Composable
fun HelpIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = "权限说明",
) {
    IconButton(onClick = onClick, modifier = modifier.size(28.dp)) {
        Icon(
            imageVector = Icons.Rounded.HelpOutline,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * 锁定徽标：锁形 + 「需 Shizuku、无障碍」短文案（[lockedLabel] 由 core:common 生成）。
 * [a11y] 为完整朗读文案（含最低档位）。
 */
@Composable
fun LockBadge(
    lockedText: String,
    a11y: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.Lock,
) {
    Row(
        modifier = modifier.semantics { contentDescription = a11y },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = ChipTone.WARN.content,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = lockedText,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = ChipTone.WARN.content,
        )
    }
}

/** 锁定卡整体降透明度（视觉弱化，不改变语义）。 */
fun Modifier.lockedAlpha(locked: Boolean): Modifier = if (locked) alpha(0.62f) else this
