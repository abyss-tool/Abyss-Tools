package com.abyss.tools.feature.terminal

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.rememberPlainTooltipPositionProvider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abyss.tools.core.designsystem.component.ChipTone
import com.abyss.tools.core.designsystem.component.StatusChip
import com.abyss.tools.core.shell.ShellResult
import com.abyss.tools.core.shell.ShellGateway
import com.abyss.tools.core.shell.ShizukuStatus
import kotlinx.coroutines.launch

/** 快捷命令预设（点填不执行 —— 用户看清了再点发送，防误触发系统级写操作）。 */
private val QUICK_COMMANDS = listOf(
    "dumpsys battery",
    "pm list packages -3",
    "df -h /data",
    "getprop ro.product.model",
    "wm size",
    "top -n 1 | head -30",
    "settings get global device_name",
)

/** 控制台字号三档（小 / 标准 / 大），档位索引持久化在 SharedPreferences。 */
private val FONT_SIZES = listOf(12.sp, 14.sp, 16.sp)
private val FONT_LABELS = listOf("小", "标准", "大")

/** 输入框下方常驻的历史条数（全量历史走右上角 BottomSheet）。 */
private const val RECENT_COUNT = 6

private const val PREFS_NAME = "abyss_terminal"
private const val KEY_FONT_SIZE = "font_size_index"
private const val DEFAULT_FONT_INDEX = 1

/** exit 行前缀（非零退出码按失败渲染 —— 见 [isNonZeroExitLine]）。 */
private const val EXIT_PREFIX = "exit:"

/** 单流截断的人话提示（ShellGateway 管道纪律的 UI 侧如实上报）。 */
private const val TRUNCATED_NOTE = "输出超限已截断（单流 1MB 上限）"

/**
 * 内置终端 —— Shizuku adb shell 上下文的单命令面板（v0.8.0 从插件内置化）。
 *
 * 诚实能力边界（置顶横幅原样告知）：单命令顺序执行、非交互式 PTY ——
 * 没有会话状态（cd / export 只影响当次子进程），交互式命令（top 持续态、
 * vi）不可用；与路线图上的本地终端引擎（Termux 血统）互补而非替代。
 *
 * 通道纪律与伪装四件套 / Shizuku 工具箱同源：命令经 [gateway]（uid 2000
 * adb shell 上下文）执行，stdout/stderr/退出码如实分色上屏；Shizuku
 * 未授权时输入禁用 + 置顶引导卡（不静默假装可用）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    shizukuStatus: ShizukuStatus,
    gateway: ShellGateway,
    onRequestShizukuPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── 状态持有（ConsoleBuffer / CommandHistory 均为普通对象，
    //    Compose 侧只镜像快照进 mutableStateOf 驱动重组）──────────────
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val buffer = remember { ConsoleBuffer() }
    val history = remember { CommandHistory(PrefsHistoryStore(prefs)) }

    var consoleLines by remember { mutableStateOf(buffer.lines()) }
    var historyLines by remember { mutableStateOf(history.all()) }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var historySheetVisible by remember { mutableStateOf(false) }
    var fontSizeIndex by remember {
        mutableStateOf(
            prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_INDEX).coerceIn(0, FONT_SIZES.lastIndex),
        )
    }

    val runningStatus = shizukuStatus as? ShizukuStatus.Running
    val authorized = runningStatus?.authorized == true

    /** 批量上屏：大输出按行 append 完只刷一次 Compose 状态（一行一刷会在 dumpsys 级输出上把重组打爆）。 */
    fun appendBatch(newLines: List<ConsoleLine>) {
        newLines.forEach(buffer::append)
        consoleLines = buffer.lines()
    }

    /** 结果如实分色：stdout→输出色 / stderr→错误色 / 截断→系统提示 / 末行 exit 码。 */
    fun renderResult(result: ShellResult) {
        val batch = buildList {
            result.stdout.lineSequence().forEach { add(ConsoleLine(it, LineKind.OUTPUT)) }
            result.stderr.lineSequence().forEach { add(ConsoleLine(it, LineKind.ERROR)) }
            if (result.truncated) add(ConsoleLine(TRUNCATED_NOTE, LineKind.SYSTEM))
            add(ConsoleLine("$EXIT_PREFIX ${result.exitCode}", LineKind.SYSTEM))
        }
        appendBatch(batch)
    }

    /** 执行一条命令：先回显输入（含历史记录），再等通道结果；运行中 / 未授权不重复触发。 */
    fun execute(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty() || running || !authorized) return
        appendBatch(listOf(ConsoleLine(cmd, LineKind.INPUT)))
        history.record(cmd)
        historyLines = history.all()
        input = ""
        running = true
        scope.launch {
            gateway.exec(cmd)
                .onSuccess { result -> renderResult(result) }
                .onFailure { e ->
                    appendBatch(
                        listOf(ConsoleLine(e.message ?: e.javaClass.simpleName, LineKind.ERROR)),
                    )
                }
            running = false
        }
    }

    /** 点填（快捷命令 / 历史 / Sheet 共用）：只进输入框，绝不自动执行。 */
    fun fill(command: String) {
        input = command
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TerminalHeader(
            shizukuStatus = shizukuStatus,
            onHistory = { historySheetVisible = true },
            onBack = onBack,
        )

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            CapabilityBanner()
            if (!authorized) {
                Spacer(Modifier.size(10.dp))
                ShizukuGateCard(
                    shizukuStatus = shizukuStatus,
                    onRequestPermission = onRequestShizukuPermission,
                    onOpenShizuku = onOpenShizuku,
                )
            }
            Spacer(Modifier.size(10.dp))
            FontSizeRow(
                selectedIndex = fontSizeIndex,
                onSelect = { index ->
                    fontSizeIndex = index
                    prefs.edit().putInt(KEY_FONT_SIZE, index).apply()
                },
            )
        }

        ConsoleArea(
            lines = consoleLines,
            fontSize = FONT_SIZES[fontSizeIndex],
            onClear = {
                buffer.clear()
                consoleLines = buffer.lines()
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            QuickCommandsRow(onPick = { fill(it) })
            Spacer(Modifier.size(10.dp))
            InputRow(
                value = input,
                onValueChange = { input = it },
                running = running,
                enabled = authorized,
                onSend = { execute(input) },
            )
            if (historyLines.isNotEmpty()) {
                Spacer(Modifier.size(10.dp))
                RecentHistoryRow(
                    commands = historyLines.take(RECENT_COUNT),
                    onPick = { fill(it) },
                )
            }
        }
    }

    if (historySheetVisible) {
        HistorySheet(
            commands = historyLines,
            onPick = { command ->
                fill(command)
                historySheetVisible = false
            },
            onRemove = { command ->
                // 单条删除（chip 尾部 ×）：只删这条，其余历史顺序不动
                history.remove(command)
                historyLines = history.all()
            },
            onClear = {
                history.clear()
                historyLines = emptyList()
            },
            onDismiss = { historySheetVisible = false },
        )
    }
}

/**
 * 页头：返回 + 标题 + Shizuku 状态徽章 + 历史入口（DetailHeader 同构）。
 * 图标按钮补长按 Tooltip（Plain）：contentDescription 只服务读屏用户，
 * 长按提示才覆盖视力正常用户（Google Apps 同款双层可达性）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalHeader(
    shizukuStatus: ShizukuStatus,
    onHistory: () -> Unit,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TooltipBox(
            positionProvider = rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("返回") } },
            state = rememberTooltipState(),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                )
            }
        }
        Text(
            text = "内置终端",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        StatusChip(text = shizukuChipText(shizukuStatus), tone = shizukuChipTone(shizukuStatus))
        TooltipBox(
            positionProvider = rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("历史记录") } },
            state = rememberTooltipState(),
        ) {
            IconButton(onClick = onHistory) {
                Icon(
                    imageVector = Icons.Rounded.List,
                    contentDescription = "命令历史",
                )
            }
        }
    }
}

private fun shizukuChipText(status: ShizukuStatus): String = when (status) {
    is ShizukuStatus.Running -> if (status.authorized) "Shizuku 已授权" else "Shizuku 未授权"
    ShizukuStatus.NotRunning -> "Shizuku 未运行"
}

private fun shizukuChipTone(status: ShizukuStatus): ChipTone = when (status) {
    is ShizukuStatus.Running -> if (status.authorized) ChipTone.OK else ChipTone.WARN
    ShizukuStatus.NotRunning -> ChipTone.ERROR
}

/** 诚实能力横幅（surfaceVariant 卡）：说不做什么比说能做什么更重要。 */
@Composable
private fun CapabilityBanner() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
    ) {
        Text(
            text = "能力边界",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "adb shell 上下文（Shizuku 免 Root 提权）· 单命令顺序执行 · 非交互式 PTY —— " +
                "与本地终端引擎（Termux 血统，路线图）互补",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Shizuku 门禁卡（未运行 / 未授权时置顶；errorContainer 语气）。 */
@Composable
private fun ShizukuGateCard(
    shizukuStatus: ShizukuStatus,
    onRequestPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
) {
    val running = shizukuStatus as? ShizukuStatus.Running
    if (running?.authorized == true) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f))
            .padding(16.dp),
    ) {
        Text(
            text = if (running == null) "Shizuku 未运行" else "Shizuku 未授权",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = if (running == null) {
                "终端命令经 Shizuku 以 adb shell 上下文执行（免 Root）。" +
                    "请先启动 Shizuku（无线调试或连接电脑激活），再回来继续。"
            } else {
                "Shizuku 已在运行，但深渊工具还没有获得授权 —— 授权一次，终端即可执行命令。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.size(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRequestPermission) { Text("授权 Shizuku") }
            TextButton(onClick = onOpenShizuku) { Text("打开 Shizuku 应用") }
        }
    }
}

/**
 * 控制台区：有界滚动窗口 + 清屏 FAB（锚在控制台右下角 —— 锚全屏右下会压住
 * 输入行，控制台内锚定最顺手）。自动滚动按「行数变化」触发：滚到当前窗口
 * 末行即可（缓冲裁剪后窗口恒定，索引不跳变）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsoleArea(
    lines: List<ConsoleLine>,
    fontSize: TextUnit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        if (lines.isEmpty()) {
            Text(
                text = "输出为空：输入一条命令，或点快捷命令填入输入框",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) {
                items(lines) { line ->
                    ConsoleLineText(line = line, fontSize = fontSize)
                }
            }
        }
        ExtendedFloatingActionButton(
            text = { Text("清屏") },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = null, // 按钮自带文字，图标无需重复语义
                )
            },
            onClick = onClear,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
        )
    }
}

/** 单行渲染：等宽字体；INPUT 前缀 "$ "；SYSTEM 斜体；非零 exit 行按错误色。 */
@Composable
private fun ConsoleLineText(line: ConsoleLine, fontSize: TextUnit) {
    Text(
        text = if (line.kind == LineKind.INPUT) "$ " + line.text else line.text,
        color = when (line.kind) {
            LineKind.INPUT -> MaterialTheme.colorScheme.primary
            LineKind.OUTPUT -> MaterialTheme.colorScheme.onSurface
            LineKind.ERROR -> MaterialTheme.colorScheme.error
            LineKind.SYSTEM ->
                if (isNonZeroExitLine(line)) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
        },
        fontStyle = if (line.kind == LineKind.SYSTEM) FontStyle.Italic else null,
        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 非零退出码 = 命令失败：exit 行从系统提示色升级为错误色（人话：红即失败）。 */
private fun isNonZeroExitLine(line: ConsoleLine): Boolean {
    if (line.kind != LineKind.SYSTEM) return false
    if (!line.text.startsWith(EXIT_PREFIX)) return false
    val code = line.text.removePrefix(EXIT_PREFIX).trim().toIntOrNull() ?: return false
    return code != 0
}

/** 输入行：等宽输入框（IME 发送键直达）+ 发送按钮；运行中禁用并转小菊花。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputRow(
    value: String,
    onValueChange: (String) -> Unit,
    running: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            enabled = enabled && !running,
            singleLine = true,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace),
            placeholder = { Text("输入命令（sh 单条执行）") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
        )
        FilledIconButton(
            onClick = onSend,
            enabled = enabled && !running,
        ) {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Send,
                    contentDescription = "执行",
                )
            }
        }
    }
}

/** 快捷命令横滚条（FilterChip 点填 —— 只填不发）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickCommandsRow(onPick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QUICK_COMMANDS.forEach { command ->
            FilterChip(
                selected = false,
                onClick = { onPick(command) },
                label = { Text(command) },
            )
        }
    }
}

/** 最近命令常驻条（AssistChip 点填；全量历史走右上角 Sheet）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentHistoryRow(commands: List<String>, onPick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        commands.forEach { command ->
            AssistChip(
                onClick = { onPick(command) },
                label = { Text(command) },
            )
        }
    }
}

/** 字号三档（SegmentedButton 单选；档位索引落 SharedPreferences）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FontSizeRow(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "字号",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
            FONT_LABELS.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = selectedIndex == index,
                    onClick = { onSelect(index) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = FONT_LABELS.size,
                    ),
                ) {
                    Text(label)
                }
            }
        }
    }
}

/**
 * 全量历史 Sheet：每条命令一枚 InputChip，FlowRow 自动换行（长命令在
 * 芯内单行省略）—— chip 点击回填输入框（不自动执行），尾部 × 单条
 * 删除；「清空历史」沉底（destructive 动作与 chip 区分离，防误触）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun HistorySheet(
    commands: List<String>,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = "命令历史",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "最近 ${commands.size} 条 · 点击回填（不自动执行）· 尾部 × 删除单条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            if (commands.isEmpty()) {
                Text(
                    text = "暂无历史：执行过的命令会记录在这里",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            } else {
                // 上限 50 条（CommandHistory cap）非懒加载也可承受 ——
                // chip 数量级下 FlowRow 一次成型比 LazyColumn 简单可靠。
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        commands.forEach { command ->
                            HistoryChip(
                                command = command,
                                onPick = { onPick(command) },
                                onRemove = { onRemove(command) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onClear,
                    enabled = commands.isNotEmpty(),
                ) {
                    Text(text = "清空历史", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.size(16.dp))
        }
    }
}

/**
 * 单条历史 chip：整枚点击回填；尾部 × 只删这一条（M3 InputChip 可删除
 * 语义）。× 用嵌套 clickable 拦截 —— 命中测试先分发给最内层，否则整枚
 * chip 的回填会连带触发。
 */
@Composable
private fun HistoryChip(
    command: String,
    onPick: () -> Unit,
    onRemove: () -> Unit,
) {
    InputChip(
        selected = false,
        onClick = onPick,
        label = {
            Text(
                text = command,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingIcon = {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "删除这条历史",
                    modifier = Modifier.size(16.dp),
                )
            }
        },
    )
}
