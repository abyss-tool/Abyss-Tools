package com.abyss.tools.feature.disguise

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abyss.tools.core.designsystem.component.ChipTone
import com.abyss.tools.core.designsystem.component.InfoRow
import com.abyss.tools.core.designsystem.component.StatusChip
import com.abyss.tools.core.shell.ShizukuStatus

/**
 * 伪装四件套共享组件：页头 / Shizuku 引导横幅 / 命令反馈条 / 说明卡 /
 * 可选档案 chip。视觉纪律与 ToolScreen 的卡片家族完全同构。
 */

/** 页头（返回 + 标题 + 生效层级徽章）。 */
@Composable
internal fun SpoofHeader(
    title: String,
    badge: String = "主壳内置 · Shizuku 免 Root",
    onBack: () -> Unit,
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
            modifier = Modifier.weight(1f),
        )
        StatusChip(text = badge, tone = ChipTone.OK)
    }
}

/**
 * Shizuku 引导横幅：未运行 / 未授权时置顶展示，直达两个修复动作。
 * 已授权则什么都不显示（不打扰）。
 */
@Composable
internal fun ShizukuGuardBanner(
    shizukuStatus: ShizukuStatus,
    onRequestPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
) {
    val running = shizukuStatus as? ShizukuStatus.Running
    if (running?.authorized == true) return
    InfoCard(
        title = if (running == null) "Shizuku 未运行" else "Shizuku 未授权",
        body = if (running == null) {
            "伪装功能经 Shizuku 以 adb shell 上下文执行（免 Root）。" +
                "请先启动 Shizuku（无线调试激活），装好后回来继续。"
        } else {
            "Shizuku 已在运行，但深渊工具还没有获得授权 —— 授权一次，全功能开放。"
        },
        tone = ChipTone.WARN,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running == null) {
                OutlinedButton(onClick = onOpenShizuku) { Text("打开 Shizuku") }
            } else {
                Button(onClick = onRequestPermission) { Text("立即授权") }
            }
        }
    }
}

/** 信息卡（说明 / 能力边界 / 引导）。 */
@Composable
internal fun InfoCard(
    title: String,
    body: String,
    tone: ChipTone = ChipTone.NEUTRAL,
    actions: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(tone.container.copy(alpha = 0.45f))
            .padding(16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = tone.content,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actions != null) {
            Spacer(Modifier.size(10.dp))
            actions()
        }
    }
}

/** 命令执行反馈条（成功绿 / 失败红，带系统原始输出摘要）。 */
@Composable
internal fun ExecFeedback(
    message: String?,
    isError: Boolean,
    detail: String? = null,
) {
    if (message == null) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isError) {
                    ChipTone.ERROR.container.copy(alpha = 0.45f)
                } else {
                    ChipTone.OK.container.copy(alpha = 0.45f)
                },
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (isError) ChipTone.ERROR.content else ChipTone.OK.content,
        )
        if (!detail.isNullOrBlank()) {
            Spacer(Modifier.size(2.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 可选中档案 chip（自绘：零实验性 API 依赖）。 */
@Composable
internal fun SelectableChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
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
        modifier = modifier
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

/** 键值卡（真机信息 / 状态读数通用容器）。 */
@Composable
internal fun KeyValueCard(title: String, rows: List<Pair<String, String>>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(6.dp))
        rows.forEach { (k, v) -> InfoRow(label = k, value = v) }
    }
}
