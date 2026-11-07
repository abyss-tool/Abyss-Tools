package com.abyss.tools.feature.shizukukit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abyss.tools.core.designsystem.component.ChipTone
import com.abyss.tools.core.designsystem.component.EmptyState
import com.abyss.tools.core.designsystem.component.StatusChip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史面板：filesDir/shizuku-kit/history.json 的可视层（最近 50 条，
 * 最新在前）。类型徽章 + 命令文本（等宽）+ exitCode 徽章 + 耗时；
 * 点击回填对应面板（不自动执行）；「清空历史」需确认。
 */
@Composable
internal fun HistoryPanel(
    records: List<CommandHistoryItem>,
    error: String?,
    onItemTap: (CommandHistoryItem) -> Unit,
    onClear: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "执行历史",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "最近 ${records.size} 条（上限 ${HistoryStore.MAX_ITEMS}），点击回填对应面板",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = { confirmClear = true },
                    enabled = records.isNotEmpty(),
                ) {
                    Text(text = "清空历史", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (error != null) {
            item { KitErrorLine(error) }
        }

        if (records.isEmpty()) {
            item {
                EmptyState(
                    title = "暂无历史",
                    message = "AppOps / 系统设置 / Dumpsys 执行过的每条命令都会记录在这里",
                )
            }
        } else {
            items(records) { item ->
                HistoryRow(item = item, onClick = { onItemTap(item) })
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空历史") },
            text = { Text("将删除全部 ${records.size} 条执行记录（不可恢复）。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClear()
                    },
                ) {
                    Text(text = "清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

/** 历史行：类型徽章 + 命令（等宽，最多 2 行）+ exitCode 徽章；次行时间 + 耗时。 */
@Composable
private fun HistoryRow(item: CommandHistoryItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusChip(text = item.type.label, tone = typeTone(item.type))
            Text(
                text = item.command,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when {
                item.exitCode == 0 -> StatusChip(text = "exit 0", tone = ChipTone.OK)
                item.exitCode < 0 -> StatusChip(text = "通道失败", tone = ChipTone.ERROR)
                else -> StatusChip(text = "exit ${item.exitCode}", tone = ChipTone.ERROR)
            }
        }
        Spacer(Modifier.size(4.dp))
        Text(
            text = formatHistoryTime(item.timestamp) + " · 耗时 ${item.elapsedMs} ms",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 类型徽章语气：AppOps(青) / 设置(蓝) / Dumpsys(灰)。 */
private fun typeTone(type: CommandType): ChipTone = when (type) {
    CommandType.APPOPS -> ChipTone.INFO
    CommandType.SETTINGS -> ChipTone.OK
    CommandType.DUMPSYS -> ChipTone.NEUTRAL
}

private val historyTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

private fun formatHistoryTime(millis: Long): String =
    if (millis <= 0) "未知" else historyTimeFormat.format(Date(millis))
