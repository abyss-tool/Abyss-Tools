package com.abyss.tools.feature.blueprint

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.abyss.tools.core.designsystem.component.StatusChip
import com.abyss.tools.core.shell.ShizukuStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 任务蓝图共享组件：页头 / Shizuku 门禁卡 / 通用卡容器 / 终端块 /
 * 步骤行与结果行 / 时间与耗时格式化。视觉纪律与 disguise / appmgr /
 * shizukukit 的卡片家族完全同构（色彩全部来自 AbyssTheme 与 ChipTone，
 * 无硬编码色）。
 */

/** 页头（返回键 + 标题 + 可选尾随徽章）。 */
@Composable
internal fun BlueprintHeader(
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
            text = "任务蓝图",
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
 * Shizuku 门禁卡：未就绪时置顶展示（已授权则不渲染，不打扰）。
 * 与其它工具不同：本页 TOAST / DELAY 蓝图不依赖 Shizuku，可独立运行 ——
 * 文案如实说明边界。
 */
@Composable
internal fun ShizukuGateCard(
    shizukuStatus: ShizukuStatus,
    onRequestPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
) {
    val running = shizukuStatus as? ShizukuStatus.Running
    BlueprintCard(tone = ChipTone.WARN) {
        Text(
            text = if (running == null) "Shizuku 未运行" else "Shizuku 未授权",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = ChipTone.WARN.content,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = if (running == null) {
                "SHELL 步骤经 Shizuku 以 adb shell 上下文执行（免 Root）。请先启动 Shizuku" +
                    "（推荐「无线调试」激活），再回来运行命令类蓝图。" +
                    "纯 TOAST / DELAY 蓝图不受影响，可直接运行。"
            } else {
                "Shizuku 已在运行，但深渊工具还没有获得授权 —— 授权一次，" +
                    "命令类蓝图（SHELL 步骤）即可执行。纯 TOAST / DELAY 蓝图不受影响。"
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

/** 通用卡容器（默认 surfaceVariant 半透明；传 tone 时用徽章色系做引导横幅）。 */
@Composable
internal fun BlueprintCard(
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

/** WARN 语气提示行（null 不渲染）：存档错误 / 校验错误 / 坏文件警告共用。 */
@Composable
internal fun BlueprintWarnLine(message: String?) {
    if (message == null) return
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = ChipTone.WARN.content,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ChipTone.WARN.container.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** 终端样式输出块：等宽字体 + 反色容器（黑底浅字）+ max-h 320dp 可滚动。 */
@Composable
internal fun TerminalBlock(
    text: String,
    tone: ChipTone = ChipTone.NEUTRAL,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tone.container)
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = tone.content,
        )
    }
}

// ── 步骤渲染 ─────────────────────────────────────────────────────────────

/** 步骤类型徽章文案。 */
internal fun stepTypeLabel(type: StepType): String = when (type) {
    StepType.SHELL -> "命令"
    StepType.TOAST -> "提示"
    StepType.DELAY -> "等待"
}

/** 步骤类型徽章语气：命令(蓝) / 提示(绿) / 等待(灰)。 */
internal fun stepTypeTone(type: StepType): ChipTone = when (type) {
    StepType.SHELL -> ChipTone.INFO
    StepType.TOAST -> ChipTone.OK
    StepType.DELAY -> ChipTone.NEUTRAL
}

/** 蓝图是否依赖 Shizuku 通道（含任一 SHELL 步骤即依赖）。 */
internal fun needsShellChannel(manifest: BlueprintManifest): Boolean =
    manifest.steps.any { it.type == StepType.SHELL }

/**
 * 静态步骤预览：第 N 步 + 类型徽章 + content（等宽，单行截断）。
 */
@Composable
internal fun StepPreviewList(steps: List<BlueprintStep>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { index, step ->
            StepLineRow(step = step) {
                Text(
                    text = "第 ${index + 1} 步",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 执行中的实时步骤区（onStepStart / onStepDone 驱动）：
 *  - 已完成 → [StepResultRow]（✓/✗ 徽章 + 耗时 + 输出摘要，点开全文）；
 *  - 执行中 → 小转圈 + 命令行；
 *  - 未轮到 → 「排队中」；链被中断 → 「未执行」（如实呈现，不虚构状态）。
 */
@Composable
internal fun LiveStepsSection(
    steps: List<BlueprintStep>,
    results: List<StepResult>,
    currentIndex: Int?,
    running: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { index, step ->
            val done = results.firstOrNull { it.index == index }
            when {
                done != null -> StepResultRow(done)

                running && index == currentIndex -> StepLineRow(step = step) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                }

                running -> StepLineRow(step = step) {
                    StatusChip(text = "排队中", tone = ChipTone.NEUTRAL)
                }

                else -> StepLineRow(step = step) {
                    StatusChip(text = "未执行", tone = ChipTone.NEUTRAL)
                }
            }
        }
    }
}

/** 步骤行骨架：leading 槽位（序号 / 状态）+ 类型徽章 + content 等宽截断。 */
@Composable
private fun StepLineRow(
    step: BlueprintStep,
    leading: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading()
        StatusChip(text = stepTypeLabel(step.type), tone = stepTypeTone(step.type))
        Text(
            text = step.content,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 单步结果行：第 N 步 + 类型徽章 + ✓/✗ 徽章 + 耗时；输出摘要
 * （maxLines 2），点击展开全文终端块（stdout 黑底 / stderr 错误色 /
 * 失败原因人话行）。
 */
@Composable
internal fun StepResultRow(result: StepResult) {
    var expanded by remember(result) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "第 ${result.index + 1} 步",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatusChip(text = stepTypeLabel(result.type), tone = stepTypeTone(result.type))
            if (result.ok) {
                StatusChip(text = "成功", tone = ChipTone.OK)
            } else {
                StatusChip(text = "失败", tone = ChipTone.ERROR)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "${result.elapsedMs} ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val stdout = result.stdout
        val stderr = result.stderr
        val summary = stdout.ifBlank { stderr.orEmpty().ifBlank { result.message.orEmpty() } }
        if (summary.isNotBlank() || !result.ok) {
            Spacer(Modifier.size(6.dp))
            if (expanded) {
                if (stdout.isNotBlank()) TerminalBlock(stdout)
                if (!stderr.isNullOrBlank()) {
                    if (stdout.isNotBlank()) Spacer(Modifier.size(6.dp))
                    TerminalBlock(stderr.trim(), tone = ChipTone.ERROR)
                }
                if (!result.ok && result.message != null) {
                    if (stdout.isNotBlank() || !stderr.isNullOrBlank()) Spacer(Modifier.size(6.dp))
                    Text(
                        text = "原因：${result.message}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = ChipTone.ERROR.content,
                    )
                }
            } else if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ── 格式化 ───────────────────────────────────────────────────────────────

private val runTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

/** 执行历史时间戳人话格式化（≤0 → 未知）。 */
internal fun formatRunTime(millis: Long): String =
    if (millis <= 0) "未知" else runTimeFormat.format(Date(millis))

/** 总耗时人话格式化（≥1s 用秒，保留一位小数）。 */
internal fun formatTotalMs(ms: Long): String =
    if (ms >= 1_000) String.format(Locale.getDefault(), "%.1f s", ms / 1000.0) else "$ms ms"
