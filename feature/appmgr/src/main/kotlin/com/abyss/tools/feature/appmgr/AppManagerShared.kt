package com.abyss.tools.feature.appmgr

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.abyss.tools.core.designsystem.component.ChipTone
import com.abyss.tools.core.shell.ShizukuStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用管理器共享组件：页头 / Shizuku 门禁卡 / 通用卡容器 / 自绘 chip /
 * 应用图标渲染 / 体积与时间格式化。
 *
 * （navhub 的 ToolScreen 页头是 internal，跨模块不可复用 —— 按 DetailBar
 * 模式自写，视觉与 disguise 的 SpoofHeader 完全同构。）
 */

/** 页头（返回键 + 标题 + 可选尾随徽章）。 */
@Composable
internal fun AppManagerHeader(
    title: String,
    onBack: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/**
 * Shizuku 门禁卡：未运行 / 未授权时置顶展示（图标 + 人话 + 修复动作）。
 * 已授权则什么都不渲染（不打扰）。冻结 / 隐藏 / 强停等 Shizuku 依赖
 * 动作在调用方同步禁用。
 */
@Composable
internal fun ShizukuGateCard(
    shizukuStatus: ShizukuStatus,
    onRequestPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
) {
    val running = shizukuStatus as? ShizukuStatus.Running
    if (running?.authorized == true) return
    AppCard(tone = ChipTone.WARN) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                tint = ChipTone.WARN.content,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = if (running == null) "Shizuku 未运行" else "Shizuku 未授权",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = ChipTone.WARN.content,
            )
        }
        Spacer(Modifier.size(4.dp))
        Text(
            text = if (running == null) {
                "冻结 / 隐藏 / 强制停止经 Shizuku 以 adb shell 上下文执行（免 Root）。" +
                    "请先启动 Shizuku（推荐「无线调试」激活），再回来重试。" +
                    "浏览、搜索与提取安装包不受影响。"
            } else {
                "Shizuku 已在运行，但深渊工具还没有获得授权 —— 授权一次，" +
                    "冻结 / 隐藏 / 强制停止即可使用。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(10.dp))
        if (running == null) {
            OutlinedButton(onClick = onOpenShizuku) { Text("启动 Shizuku") }
        } else {
            Button(onClick = onRequestPermission) { Text("去授权") }
        }
    }
}

/**
 * 通用卡容器（视觉同 disguise 的卡片家族：默认 surfaceVariant 半透明，
 * 传 [tone] 时用徽章色系做引导横幅）。
 */
@Composable
internal fun AppCard(
    modifier: Modifier = Modifier,
    tone: ChipTone? = null,
    content: @Composable () -> Unit,
) {
    val containerColor = if (tone != null) {
        tone.container.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(containerColor)
            .padding(16.dp),
    ) {
        content()
    }
}

/** 可选中筛选 chip（自绘：零实验性 API 依赖，disguise 同款纪律）。 */
@Composable
internal fun AppFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/**
 * 应用图标渲染：loadIcon 的 Drawable → 位图（自适应图标安全缩放）。
 * 转换失败 / 无图标 → 占位方块（primaryContainer + Info），
 * 绝不因图标问题丢行。
 */
@Composable
internal fun AppIcon(icon: Drawable?, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val bitmap = remember(icon, density) {
        if (icon == null) {
            null
        } else {
            runCatching {
                val px = with(density) { 48.dp.toPx() }.toInt().coerceAtLeast(1)
                icon.toBitmap(px, px).asImageBitmap()
            }.getOrNull()
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier,
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 体积人话格式化（系统本地化，如 "35.4 MB"）。 */
internal fun formatBytes(context: Context, bytes: Long): String =
    Formatter.formatFileSize(context, bytes)

/** 时间戳人话格式化（列表 / 详情共用；未知值 ≤0 → "未知"）。 */
internal fun formatTimestamp(millis: Long): String =
    if (millis <= 0) {
        "未知"
    } else {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
    }
