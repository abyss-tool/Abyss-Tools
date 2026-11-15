package com.abyss.tools.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 通用组件库：章节头 / 状态徽章 / 三态占位（Loading/Error/Empty）/
 * 信息行。全部从 AbyssTheme 派生颜色，业务页不手写色彩。
 */

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * 语气化状态徽章语义：INFO / OK / NEUTRAL / WARN / ERROR。
 *
 * 颜色不再内联（v0.8.2）：[StatusChip] 按 M3 色角色（container 系列）
 * 取色 —— 动态取色（Material You）开启时徽章自动跟随壁纸色系。
 * 遗留硬编码取色仅 BlueprintShared 等处直接读 container/content 在用，
 * 随各 feature 迁移逐步退役。
 */
enum class ChipTone { INFO, OK, NEUTRAL, WARN, ERROR }

/** ChipTone → M3 container 色角色映射（单一事实源，业务页禁止散落）。 */
private fun ChipTone.containerRole(scheme: ColorScheme) = when (this) {
    ChipTone.INFO -> scheme.secondaryContainer to scheme.onSecondaryContainer
    ChipTone.OK -> scheme.primaryContainer to scheme.onPrimaryContainer
    ChipTone.NEUTRAL -> scheme.surfaceVariant to scheme.onSurfaceVariant
    ChipTone.WARN -> scheme.tertiaryContainer to scheme.onTertiaryContainer
    ChipTone.ERROR -> scheme.errorContainer to scheme.onErrorContainer
}

@Composable
fun StatusChip(text: String, tone: ChipTone = ChipTone.NEUTRAL, modifier: Modifier = Modifier) {
    val (container, content) = tone.containerRole(MaterialTheme.colorScheme)
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun LoadingState(message: String = "加载中…", modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
        Spacer(Modifier.size(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "⚠",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onRetry != null) {
            Spacer(Modifier.size(12.dp))
            Button(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.size(4.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 键值信息行（插件详情 / 设置页通用）。 */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.34f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/**
 * 图标 + 标题 + 摘要 + 徽章 的行卡（导航页工具行 / 插件行通用骨架）。
 *
 * v0.8.2 换装 Google 现成组件：M3 [ElevatedCard]（surfaceContainerLow
 * 色阶 + 形状阶梯 large + 标准阴影与涟漪）—— 果冻缩放让位给 M3 标准
 * 按压语言；pressScale 仍供伪装选项件等自持 interactionSource 场景。
 */
@Composable
fun ListRowCard(
    icon: ImageVector,
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    badge: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val row: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(22.dp),
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.size(2.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            badge?.invoke()
        }
    }
    if (onClick != null) {
        ElevatedCard(onClick = onClick, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
            row()
        }
    } else {
        ElevatedCard(shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
            row()
        }
    }
}
