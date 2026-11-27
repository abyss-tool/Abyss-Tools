package com.abyss.tools.feature.shizukukit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abyss.tools.core.designsystem.component.ChipTone
import com.abyss.tools.core.designsystem.component.StatusChip
import com.abyss.tools.core.shell.ShellGateway
import com.abyss.tools.core.shell.ShellResult
import com.abyss.tools.core.shell.ShizukuStatus
import com.abyss.tools.core.shell.ShizukuVariant
import com.abyss.tools.core.shell.ShizukuVariants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 工具箱共享组件：页头 / Shizuku 门禁卡 / 通用卡容器 / 执行状态机与
 * 结果卡 / 终端输出块 / 复制按钮。视觉纪律与 disguise / appmgr 的卡片
 * 家族完全同构（色彩全部来自 AbyssTheme 与 ChipTone，无硬编码色）。
 */

/** 页头（返回 + 标题 + 通道徽章）。 */
@Composable
internal fun KitHeader(onBack: () -> Unit) {
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
            text = "Shizuku 工具箱",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        StatusChip(text = "免 Root · adb shell", tone = ChipTone.INFO)
    }
}

/**
 * Shizuku 门禁卡（v0.8.5 重做：可收起 + 变体感知）：
 *  - 未运行 → 「启动 Shizuku / 获取变体」展开引导；
 *  - 未授权 → 「去授权」展开引导；
 *  - 已授权 → **默认收起**成一行绿徽章（点可展开详情）—— 用户反馈
 *    「常驻大卡占屏幕」，就绪后不再吃头部空间；
 *  - 展开态列出 Shizuku / Shevery / Shizuku+ / Sui 四变体的安装情况，
 *    「启动」按钮自动打开已装变体（全没装则去下载页）。
 */
@Composable
internal fun ShizukuGateCard(
    shizukuStatus: ShizukuStatus,
    onRequestPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
) {
    val context = LocalContext.current
    val running = shizukuStatus as? ShizukuStatus.Running
    val ready = running?.authorized == true

    // 折叠语义：默认「就绪收起 / 未就绪展开」，用户点箭头翻转后覆盖；
    // 状态翻转（授权成功 / 掉线）时输入键变化 → 回到新状态的默认档。
    var userCollapsed by rememberSaveable(shizukuStatus) { mutableStateOf<Boolean?>(null) }
    val collapsed = userCollapsed ?: ready

    // 变体安装快照：随状态变化重算（从 Shizuku 回来授权时刷新）。
    val installedVariants = remember(shizukuStatus) {
        ShizukuVariants.installed(context)
    }

    val title: String
    val body: String
    val tone: ChipTone
    val badge: String?
    when {
        running == null -> {
            title = "Shizuku 未运行"
            body = "命令经 Shizuku 兼容服务以 adb shell（uid 2000）上下文执行 —— 免 Root。" +
                "请先启动 Shizuku（推荐「无线调试」激活），再回来继续。"
            tone = ChipTone.WARN
            badge = null
        }

        !running.authorized -> {
            title = "Shizuku 未授权"
            body = "Shizuku 兼容服务已在运行，但深渊工具还没有获得授权 —— 授权一次，" +
                "工具箱全功能开放。"
            tone = ChipTone.WARN
            badge = null
        }

        else -> {
            title = "Shizuku 已就绪"
            body = "appops / settings / dumpsys 均为官方通道，指令即时生效"
            tone = ChipTone.OK
            badge = "adb shell 通道就绪"
        }
    }

    val chevronRotation by animateFloatAsState(
        targetValue = if (collapsed) 0f else 180f,
        animationSpec = tween(220),
        label = "gate-chevron",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(tone.container.copy(alpha = 0.45f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // 折叠态主体：状态行 + 展开箭头（整行可点）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { userCollapsed = !collapsed },
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = tone.content,
                modifier = Modifier.weight(1f),
            )
            if (badge != null) {
                StatusChip(text = badge, tone = tone)
            }
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (collapsed) "展开详情" else "收起",
                modifier = Modifier.rotate(chevronRotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!collapsed) {
            Spacer(Modifier.size(4.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 变体安装情况（一行小徽章；Sui 无常规入口只提示接线状态）
            Spacer(Modifier.size(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "兼容变体：",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ShizukuVariant.entries.forEach { variant ->
                    val installed = variant in installedVariants
                    StatusChip(
                        text = if (installed) "${variant.displayName} ✓" else variant.displayName,
                        tone = if (installed) ChipTone.OK else ChipTone.NEUTRAL,
                    )
                }
            }

            Spacer(Modifier.size(10.dp))
            when {
                running == null -> {
                    OutlinedButton(onClick = onOpenShizuku) {
                        Text(
                            if (installedVariants.isEmpty()) "获取 Shizuku（下载页）"
                            else "启动 ${installedVariants.first().displayName}",
                        )
                    }
                }

                !running.authorized -> {
                    Button(onClick = onRequestPermission) { Text("去授权") }
                }
            }
        }
    }
}

/**
 * 可收起的说明卡（面板尾部的「什么是 appops」类长文案）：默认收起，
 * 点标题展开 —— 面板主体（输入 / 按钮）不再被大段说明文挤到屏幕外。
 */
@Composable
internal fun KitExpanderCard(
    title: String,
    body: String,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(220),
        label = "expander-chevron",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (expanded) "收起说明" else "展开说明",
                modifier = Modifier.rotate(chevronRotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Spacer(Modifier.size(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 通用卡容器（默认 surfaceVariant 半透明；面板区块骨架）。 */
@Composable
internal fun KitCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
        content = content,
    )
}

/** 卡内区块标题。 */
@Composable
internal fun KitCardTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

/** 输入校验 / 持久化的 WARN 提示行（null 不渲染）。 */
@Composable
internal fun KitErrorLine(message: String?) {
    if (message == null) return
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = ChipTone.WARN.content,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(ChipTone.WARN.container.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** 执行中指示行（running 状态机的外显）。 */
@Composable
internal fun KitBusyLine(busy: Boolean) {
    if (!busy) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )
        Text(
            text = "执行中…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 终端样式输出块：等宽字体 + 反色容器（黑底浅字）+ max-h 320dp 可滚动。
 * stdout 用 NEUTRAL（近黑终端色），stderr 用 ERROR（错误色系）。
 */
@Composable
internal fun TerminalBlock(
    text: String,
    tone: ChipTone = ChipTone.NEUTRAL,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
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

/** 复制按钮（ClipboardManager，含 1.6s「已复制」回显）。 */
@Composable
internal fun CopyOutputButton(text: String) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }
    OutlinedButton(
        onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("abyss-shizuku-kit", text))
            copied = true
        },
        enabled = text.isNotBlank(),
    ) {
        Text(if (copied) "已复制" else "复制输出")
    }
}

/** 白名单命令行卡（Dumpsys 面板）：标题 + 命令原文 + 说明，整卡可点。 */
@Composable
internal fun KitCommandCard(
    title: String,
    subtitle: String,
    detail: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            StatusChip(text = "执行", tone = ChipTone.INFO)
        }
        Spacer(Modifier.size(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 一次执行的完整记录（结果卡数据源；[toHistoryItem] 派生历史条目）。 */
internal data class ExecRecord(
    val type: CommandType,
    val label: String,
    val command: String,
    /** null = 通道层失败（Shizuku 未运行 / 未授权等），人话错误见 [error]。 */
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val truncated: Boolean,
    val elapsedMs: Long,
    val error: String?,
) {
    /** exitCode 兜底 -1（通道失败哨兵，历史徽章显示「通道失败」）。 */
    fun toHistoryItem(): CommandHistoryItem = CommandHistoryItem(
        type = type,
        command = command,
        exitCode = exitCode ?: -1,
        elapsedMs = elapsedMs,
        timestamp = System.currentTimeMillis(),
    )

    /** 复制按钮的完整文本（命令 + stdout + stderr）。 */
    fun fullOutput(): String = buildString {
        append("$ ").append(command)
        if (stdout.isNotBlank()) {
            append("\n\n").append(stdout)
        }
        if (stderr.isNotBlank()) {
            append("\n\n[stderr]\n").append(stderr.trim())
        }
    }
}

/**
 * 执行结果卡（三个功能面板共用）：
 * label + exitCode 徽章 + 耗时；命令原文（等宽）；stdout 终端块
 * （黑底浅字 / max-h 320 可滚）；stderr 错误色块；通道失败人话展示。
 */
@Composable
internal fun ExecResultCard(record: ExecRecord?) {
    if (record == null) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = record.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when {
                record.error != null -> StatusChip(text = "通道失败", tone = ChipTone.WARN)
                record.exitCode == 0 -> StatusChip(text = "exit 0", tone = ChipTone.OK)
                else -> StatusChip(text = "exit ${record.exitCode}", tone = ChipTone.ERROR)
            }
            StatusChip(text = "${record.elapsedMs} ms", tone = ChipTone.NEUTRAL)
        }
        Spacer(Modifier.size(6.dp))
        Text(
            text = record.command,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (record.error != null) {
            Spacer(Modifier.size(8.dp))
            Text(
                text = "执行失败：${record.error}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = ChipTone.ERROR.content,
            )
        }
        if (record.stdout.isNotBlank()) {
            Spacer(Modifier.size(8.dp))
            TerminalBlock(text = record.stdout)
        }
        if (record.stderr.isNotBlank()) {
            Spacer(Modifier.size(8.dp))
            TerminalBlock(text = record.stderr.trim(), tone = ChipTone.ERROR)
        }
        Spacer(Modifier.size(10.dp))
        CopyOutputButton(text = record.fullOutput())
    }
}

/**
 * 执行状态机（三个功能面板共用）：running → ShellResult → 结果卡 +
 * 历史落盘；通道失败（ShizukuUnavailableException 等）人话展示。
 *
 * onResult 在 busy 复位之后回调 —— 面板内可直接串联下一条命令
 * （如 appops 写入成功后自动回读系统真实状态）。
 */
internal class KitRunner(
    private val gateway: ShellGateway,
    private val scope: CoroutineScope,
    private val onFinished: (ExecRecord) -> Unit,
) {
    var busy by mutableStateOf(false)
        private set

    fun execute(
        type: CommandType,
        label: String,
        command: String,
        onResult: (ShellResult) -> Unit = {},
    ) {
        if (busy) return
        busy = true
        scope.launch {
            val startedAt = System.nanoTime()
            var followUp: (() -> Unit)? = null
            gateway.exec(command)
                .onSuccess { result ->
                    val elapsedMs = (System.nanoTime() - startedAt) / NANOS_PER_MILLI
                    val window = KitCommands.windowOutput(result.stdout)
                    onFinished(
                        ExecRecord(
                            type = type,
                            label = label,
                            command = command,
                            exitCode = result.exitCode,
                            stdout = window.text,
                            stderr = result.stderr,
                            truncated = window.truncated,
                            elapsedMs = elapsedMs,
                            error = null,
                        ),
                    )
                    followUp = { onResult(result) }
                }
                .onFailure { e ->
                    val elapsedMs = (System.nanoTime() - startedAt) / NANOS_PER_MILLI
                    onFinished(
                        ExecRecord(
                            type = type,
                            label = label,
                            command = command,
                            exitCode = null,
                            stdout = "",
                            stderr = "",
                            truncated = false,
                            elapsedMs = elapsedMs,
                            error = e.message ?: e.javaClass.simpleName,
                        ),
                    )
                }
            busy = false
            followUp?.invoke()
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
