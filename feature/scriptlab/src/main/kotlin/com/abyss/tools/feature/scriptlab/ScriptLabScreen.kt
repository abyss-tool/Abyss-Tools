package com.abyss.tools.feature.scriptlab

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abyss.tools.core.designsystem.component.ChipTone
import com.abyss.tools.core.designsystem.component.EmptyState
import com.abyss.tools.core.designsystem.component.SectionHeader
import com.abyss.tools.core.designsystem.component.StatusChip
import com.abyss.tools.core.shell.ShellGateway
import com.abyss.tools.core.shell.ShellResult
import com.abyss.tools.core.shell.ShizukuStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 脚本实验室 —— v0.8.0「Python 插件内置化」的第一阶：
 * 编辑 + 保存 + 经 Shizuku（adb shell 上下文）执行 Shell 脚本；
 * Python 只做运行时能力探测（设备自带 CPython 才可用，无则诚实标注
 * 不可用 —— 完整 Python 引擎（Chaquopy）随主壳后续内置化路线推进）。
 *
 * 诚实边界（置顶卡原样告知）：
 *  - 脚本体不落临时文件：app 私有目录（/data/data/pkg）对 shell 用户
 *    （uid 2000）不可读，「写文件再执行」注定失败 —— 脚本体经
 *    [buildRunCommand] 的 printf 单引号管道送进解释器（stdin 路线）；
 *  - 无中断：单命令网关（ShellGateway）不支持取消，超时 30s 自动返回；
 *  - 脚本库 = filesDir/scripts（应用私有，卸载即清空，绝无外部共享）。
 *
 * 通道纪律同伪装四件套 / 内置终端：命令经 [gateway] 执行，stdout / stderr /
 * 退出码如实分色上屏；Shizuku 未授权时置顶引导卡（下方仍可浏览、可编辑、
 * 可保存，运行被禁用 —— 不静默假装可用）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptLabScreen(
    shizukuStatus: ShizukuStatus,
    gateway: ShellGateway,
    onRequestShizukuPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val store = remember { ScriptStore(File(context.filesDir, SCRIPTS_DIR_NAME)) }
    val stampFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val rowTimeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    var scripts by remember { mutableStateOf(store.list()) }
    var lang by remember { mutableStateOf(ScriptLang.SHELL) }
    var scriptName by remember { mutableStateOf("") }
    var scriptBody by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var runReport by remember { mutableStateOf<RunReport?>(null) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var saveFailed by remember { mutableStateOf(false) }

    // Python 运行时探测（只读）：版本 / 探测中 / Shizuku 通道未就绪 三态
    var pythonRuntime by remember { mutableStateOf<String?>(null) }
    var probing by remember { mutableStateOf(false) }
    var probeBlocked by remember { mutableStateOf(false) }

    // 覆盖式动作的确认挂起位（编辑器有内容 → 先问再做，绝不静默覆盖）
    var pendingTemplate by remember { mutableStateOf<ScriptTemplate?>(null) }
    var pendingLoad by remember { mutableStateOf<SavedScript?>(null) }

    val runningStatus = shizukuStatus as? ShizukuStatus.Running
    val authorized = runningStatus?.authorized == true
    val nameValid = safeFileName(scriptName) != null
    val nameInvalid = scriptName.isNotEmpty() && !nameValid

    fun timeNow(): String = stampFormat.format(Date())

    /** Python 运行时探测：stdout（空则 stderr 合看）首条版本命中；通道失败 → WARN 态。 */
    fun probePython() {
        if (probing) return
        probing = true
        probeBlocked = false
        scope.launch {
            gateway.exec(RunDetectors.PROBE_COMMAND)
                .onSuccess { result ->
                    pythonRuntime = RunDetectors.parseVersion(result.stdout.ifBlank { result.stderr })
                }
                .onFailure { probeBlocked = true }
            probing = false
        }
    }

    /** 结果如实分色：stdout→输出色 / stderr→错误色 / 截断→系统提示 / 末行 exit 码。 */
    fun renderShellResult(result: ShellResult): List<OutputLine> = buildList {
        result.stdout.lineSequence().forEach { add(OutputLine(it, LineKind.STDOUT)) }
        result.stderr.lineSequence().forEach { add(OutputLine(it, LineKind.STDERR)) }
        if (result.truncated) add(OutputLine(TRUNCATED_NOTE, LineKind.SYSTEM))
        if (result.timedOut) add(OutputLine(TIMEOUT_NOTE, LineKind.SYSTEM))
        if (result.streamAbandoned) add(OutputLine(STREAM_ABANDONED_NOTE, LineKind.SYSTEM))
        add(OutputLine("$EXIT_PREFIX ${result.exitCode}", LineKind.SYSTEM))
    }

    /** 一次 exec 的中间形态（通道失败 → ok=false 且 exitCode=null —— 人话上抛）。 */
    fun renderAttempt(result: Result<ShellResult>): RenderedResult = result.fold(
        onSuccess = { r ->
            RenderedResult(ok = r.isSuccess, exitCode = r.exitCode, lines = renderShellResult(r))
        },
        onFailure = { e ->
            val text = e.message ?: e.javaClass.simpleName
            RenderedResult(ok = false, exitCode = null, lines = listOf(OutputLine(text, LineKind.STDERR)))
        },
    )

    /**
     * 执行一段脚本体（source 标注来源 —— 「编辑器」或脚本文件名，输出卡如实显示）。
     * Python 失败时改试 python（老设备常只有 python 入口），两次输出都如实上屏。
     */
    fun startRun(source: String, runLang: ScriptLang, body: String) {
        if (busy || !authorized || body.isBlank()) return
        val command = try {
            buildRunCommand(runLang, body)
        } catch (e: IllegalArgumentException) {
            // 16KB / NUL 门禁：就地给人话错误，绝不上通道半截执行
            runReport = RunReport(
                source = source,
                stamp = timeNow(),
                lines = listOf(OutputLine("无法运行：${e.message}", LineKind.STDERR)),
            )
            return
        }
        busy = true
        runReport = null
        scope.launch {
            val first = renderAttempt(gateway.exec(command))
            var lines = first.lines
            if (runLang == ScriptLang.PYTHON && !first.ok) {
                val retryNote = OutputLine(
                    text = "python3 未成功（${first.exitCode?.let { "exit $it" } ?: "通道失败"}）—— 改试 python：",
                    kind = LineKind.SYSTEM,
                )
                val second = renderAttempt(gateway.exec(buildPythonFallbackCommand(body)))
                lines = first.lines + retryNote + second.lines
            }
            runReport = RunReport(source = source, stamp = timeNow(), lines = lines)
            busy = false
        }
    }

    /** 保存当前编辑器内容（名字过门禁 + 体非空；Result 如实上屏）。 */
    fun saveCurrent() {
        if (busy || !nameValid || scriptBody.isBlank()) return
        busy = true
        val snapshotName = scriptName
        val snapshotLang = lang
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                store.save(SavedScript(snapshotName, snapshotLang, scriptBody, modifiedAt = 0L))
                    .map { store.list() }
            }
            outcome
                .onSuccess { fresh ->
                    scripts = fresh
                    saveMessage = "已保存 $snapshotName.${snapshotLang.ext}（应用私有目录）"
                    saveFailed = false
                }
                .onFailure { e ->
                    saveMessage = "保存失败：${e.message ?: e.javaClass.simpleName}"
                    saveFailed = true
                }
            busy = false
        }
    }

    /** 删除脚本（结果如实上屏 —— 删除失败也绝不静默当作成功）。 */
    fun deleteScript(script: SavedScript) {
        if (busy) return
        busy = true
        scope.launch {
            val (deleted, fresh) = withContext(Dispatchers.IO) {
                store.delete(script.name, script.lang) to store.list()
            }
            scripts = fresh
            if (deleted) {
                saveMessage = "已删除 ${script.name}.${script.lang.ext}"
                saveFailed = false
            } else {
                saveMessage = "删除失败（文件已不存在或不可删）"
                saveFailed = true
            }
            busy = false
        }
    }

    fun applyTemplate(template: ScriptTemplate) {
        lang = template.lang
        scriptBody = template.body
        saveMessage = null
    }

    fun applyLoad(script: SavedScript) {
        scriptName = script.name
        lang = script.lang
        scriptBody = script.body
        saveMessage = null
    }

    fun copyOutput() {
        runReport?.let { clipboard.setText(AnnotatedString(it.raw)) }
    }

    /** 进入页面即探测（Shizuku 未就绪 → WARN 态引导；授权后点「重新探测」）。 */
    LaunchedEffect(Unit) { probePython() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        ScriptLabHeader(shizukuStatus = shizukuStatus, onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // UI-001：能力边界说明卡移除

            // ── Shizuku 门禁（未授权置顶；下方仍可浏览 / 编辑 / 保存）─────
            if (!authorized) {
                item {
                    ShizukuGateCard(
                        shizukuStatus = shizukuStatus,
                        onRequestPermission = onRequestShizukuPermission,
                        onOpenShizuku = onOpenShizuku,
                    )
                }
            }

            // ── Python 运行时探测（只读，诚实三态）────────────────────────
            item {
                PythonRuntimeCard(
                    runtime = pythonRuntime,
                    probing = probing,
                    probeBlocked = probeBlocked,
                    canProbe = authorized && !probing && !busy,
                    onProbe = { probePython() },
                )
            }

            // ── 语言选择 ───────────────────────────────────────────────
            item {
                LanguageCard(
                    selected = lang,
                    pythonAvailable = pythonRuntime != null,
                    onSelect = { lang = it },
                )
            }

            // ── 编辑器 ─────────────────────────────────────────────────
            item {
                EditorCard(
                    lang = lang,
                    name = scriptName,
                    nameInvalid = nameInvalid,
                    body = scriptBody,
                    onNameChange = { scriptName = it },
                    onBodyChange = { scriptBody = it },
                    canSave = !busy && nameValid && scriptBody.isNotBlank(),
                    onSave = { saveCurrent() },
                    onPickTemplate = { template ->
                        if (scriptBody.isNotEmpty()) pendingTemplate = template else applyTemplate(template)
                    },
                    saveMessage = saveMessage,
                    saveFailed = saveFailed,
                )
            }

            // ── 运行 ───────────────────────────────────────────────────
            item {
                RunCard(
                    canRun = authorized && !busy && scriptBody.isNotBlank(),
                    running = busy,
                    onRun = { startRun(source = "编辑器", runLang = lang, body = scriptBody) },
                )
            }

            // ── 输出 ───────────────────────────────────────────────────
            item {
                OutputCard(
                    report = runReport,
                    running = busy,
                    onCopy = { copyOutput() },
                )
            }

            // ── 脚本库（rows 落在外层 LazyColumn —— 不嵌套懒布局）────────
            item {
                SectionHeader(
                    title = "脚本库",
                )
            }
            if (scripts.isEmpty()) {
                item {
                    EmptyState(
                        title = "暂无已保存脚本",
                        message = "写好脚本点「保存脚本」（文件落在应用私有目录）",
                    )
                }
            } else {
                items(scripts, key = { "${it.name}.${it.lang.ext}" }) { script ->
                    ScriptRow(
                        script = script,
                        timeFormat = rowTimeFormat,
                        canRun = authorized && !busy,
                        canDelete = !busy,
                        onLoad = {
                            if (scriptBody.isNotEmpty()) pendingLoad = script else applyLoad(script)
                        },
                        onDelete = { deleteScript(script) },
                        onDirectRun = {
                            startRun(
                                source = "${script.name}.${script.lang.ext}",
                                runLang = script.lang,
                                body = script.body,
                            )
                        },
                    )
                }
            }
        }
    }

    // ── 确认对话框（覆盖即破坏编辑器现有内容 —— 先问再做）──────────────
    pendingTemplate?.let { template ->
        AlertDialog(
            onDismissRequest = { pendingTemplate = null },
            title = { Text("覆盖当前脚本体？") },
            text = {
                Text(
                    text = "编辑器已有内容 —— 载入模板会把脚本体替换为「${template.label}」" +
                        "并把语言切到 ${template.lang.title}。",
                )
            },
            confirmButton = {
                TextButton(onClick = { applyTemplate(template); pendingTemplate = null }) { Text("覆盖载入") }
            },
            dismissButton = {
                TextButton(onClick = { pendingTemplate = null }) { Text("取消") }
            },
        )
    }
    pendingLoad?.let { script ->
        AlertDialog(
            onDismissRequest = { pendingLoad = null },
            title = { Text("载入「${script.name}」？") },
            text = { Text("编辑器当前有未保存内容 —— 载入会替换脚本名、语言与脚本体。") },
            confirmButton = {
                TextButton(onClick = { applyLoad(script); pendingLoad = null }) { Text("载入") }
            },
            dismissButton = {
                TextButton(onClick = { pendingLoad = null }) { Text("取消") }
            },
        )
    }
}

// ── 数据形态（本页私有：输出行 / 运行报告 / 模板）──────────────────────────

/** 快速模板（点填不执行 —— 用户看清了再点运行，防误触发系统级写操作）。 */
private data class ScriptTemplate(
    val label: String,
    val lang: ScriptLang,
    val body: String,
)

private val TEMPLATES = listOf(
    ScriptTemplate(
        label = "Hello Shell",
        lang = ScriptLang.SHELL,
        body = "echo hello from abyss tools\nuname -a\ndate",
    ),
    ScriptTemplate(
        label = "电池快照",
        lang = ScriptLang.SHELL,
        body = "dumpsys battery | head -20",
    ),
    ScriptTemplate(
        label = "存储盘点",
        lang = ScriptLang.SHELL,
        body = "df -h | head -12",
    ),
    ScriptTemplate(
        label = "Python Hello",
        lang = ScriptLang.PYTHON,
        body = "import sys\nprint(f\"hello from python\")\nprint(sys.version)",
    ),
)

/** 输出行类型（stdout / stderr / 系统提示行：exit 码、截断说明、回退标注）。 */
private enum class LineKind { STDOUT, STDERR, SYSTEM }

/** 输出的一行（渲染层按 kind 派生颜色，数据零渲染语义）。 */
private data class OutputLine(val text: String, val kind: LineKind)

/** 一次运行的完整报告（source = 「编辑器」或脚本文件名；raw 供复制 —— 全量）。 */
private data class RunReport(
    val source: String,
    val stamp: String,
    val lines: List<OutputLine>,
) {
    val raw: String get() = lines.joinToString("\n") { it.text }
}

/** exec 结果的中间形态（通道失败 → exitCode=null，人话标注而非猜测）。 */
private data class RenderedResult(
    val ok: Boolean,
    val exitCode: Int?,
    val lines: List<OutputLine>,
)

/** 输出区展示窗口（最新 N 行 —— 大输出不把重组打爆；完整内容可复制，窗口只是渲染防线）。 */
private const val DISPLAY_LINE_CAP = 400

/** 单流截断的人话提示（ShellGateway 管道纪律的 UI 侧如实上报）。 */
private const val TRUNCATED_NOTE = "输出超限已截断（单流 1MB 上限）"
private const val TIMEOUT_NOTE = "命令超时被强杀（30s 硬超时）—— 退出码不可信"
private const val STREAM_ABANDONED_NOTE = "输出流未收尾（管道被继承），结果不完整"

/** exit 行前缀（非零退出码按失败色渲染 —— 见 [isNonZeroExitLine]）。 */
private const val EXIT_PREFIX = "exit:"

// ── 子组件 ───────────────────────────────────────────────────────────────

/** 页头：返回 + 标题 + Shizuku 状态徽章（DetailHeader 同构，徽章复用终端页语义）。 */
@Composable
private fun ScriptLabHeader(shizukuStatus: ShizukuStatus, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Text(
            text = "脚本实验室",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        StatusChip(text = shizukuChipText(shizukuStatus), tone = shizukuChipTone(shizukuStatus))
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

/** 模块统一卡片容器（surfaceVariant 半透明 —— 伪装 / 终端页同款视觉家族）。 */
@Composable
private fun LabCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (subtitle != null) {
            Spacer(Modifier.size(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(8.dp))
        content()
    }
}

/** Shizuku 门禁卡（未运行 / 未授权时置顶；下方内容仍可浏览与保存，运行被禁用）。 */
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
            .clip(MaterialTheme.shapes.large)
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
                "脚本执行经 Shizuku 以 adb shell 上下文完成（免 Root）。" +
                    "请先启动 Shizuku（无线调试或连接电脑激活），再回来继续 —— 下面仍可编辑与保存脚本。"
            } else {
                "Shizuku 已在运行，但深渊工具还没获得授权 —— 授权一次，脚本即可执行。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.size(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRequestPermission) { Text("授权") }
            TextButton(onClick = onOpenShizuku) { Text("打开 Shizuku") }
        }
    }
}

/** Python 运行时探测卡：只读探测，诚实三态（可用 / 未检测到 / 通道未就绪）。 */
@Composable
private fun PythonRuntimeCard(
    runtime: String?,
    probing: Boolean,
    probeBlocked: Boolean,
    canProbe: Boolean,
    onProbe: () -> Unit,
) {
    LabCard(title = "Python 运行时探测") {
        when {
            probing -> StatusChip(text = "探测中…", tone = ChipTone.NEUTRAL)
            probeBlocked -> StatusChip(text = "探测需要 Shizuku（当前未就绪）", tone = ChipTone.WARN)
            runtime != null -> StatusChip(text = "Python $runtime 可用", tone = ChipTone.OK)
            else -> StatusChip(text = "未检测到系统 Python", tone = ChipTone.NEUTRAL)
        }
        Spacer(Modifier.size(8.dp))
        Text(
            text = "探测命令：${RunDetectors.PROBE_COMMAND}（经 Shizuku 执行，只读探测）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(10.dp))
        OutlinedButton(onClick = onProbe, enabled = canProbe) { Text("重新探测") }
    }
}

/** 语言选择（SegmentedButton 单选；选 Python 且无运行时 → 诚实告警横幅）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageCard(
    selected: ScriptLang,
    pythonAvailable: Boolean,
    onSelect: (ScriptLang) -> Unit,
) {
    LabCard(title = "语言") {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ScriptLang.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = ScriptLang.entries.size,
                    ),
                ) {
                    Text(option.title)
                }
            }
        }
        if (selected == ScriptLang.PYTHON && !pythonAvailable) {
            Spacer(Modifier.size(6.dp))
            WarnLine(text = "设备无系统 Python：运行将失败，可仍用于编辑保存")
        }
    }
}

/** 语气化告警横幅（WARN 色系 —— ShizukuKit 同款 token，非手写色值）。 */
@Composable
private fun WarnLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = ChipTone.WARN.content,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(ChipTone.WARN.container.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** 编辑器卡：名字（门禁实时校验）+ 脚本体（等宽多行）+ 快速模板 + 保存。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorCard(
    lang: ScriptLang,
    name: String,
    nameInvalid: Boolean,
    body: String,
    onNameChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    canSave: Boolean,
    onSave: () -> Unit,
    onPickTemplate: (ScriptTemplate) -> Unit,
    saveMessage: String?,
    saveFailed: Boolean,
) {
    LabCard(title = "脚本编辑器", subtitle = "当前语言 ${lang.title}，保存为 <脚本名>.${lang.ext}") {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = nameInvalid,
            label = { Text("脚本名") },
            supportingText = {
                Text(
                    text = if (nameInvalid) {
                        "非法名：仅字母/数字/./_/-，不能以点开头，长度 1..40"
                    } else {
                        "仅字母/数字/./_/-，不能以点开头，长度 1..40"
                    },
                )
            },
        )
        Spacer(Modifier.size(8.dp))
        TextField(
            value = body,
            onValueChange = onBodyChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = 10,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            label = { Text("脚本体") },
            placeholder = { Text("Shell: 逐行真实执行\nPython: 需设备自带运行时") },
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = "快速模板（点填不执行，看清了再点运行）",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TEMPLATES.forEach { template ->
                AssistChip(
                    onClick = { onPickTemplate(template) },
                    label = { Text(template.label) },
                )
            }
        }
        Spacer(Modifier.size(10.dp))
        FilledTonalButton(onClick = onSave, enabled = canSave) {
            Text("保存脚本")
        }
        if (saveMessage != null) {
            Spacer(Modifier.size(6.dp))
            SaveFeedbackLine(message = saveMessage, failed = saveFailed)
        }
    }
}

/** 保存 / 删除结果反馈条（成功 OK 色系 / 失败 ERROR 色系 —— 诚实可见）。 */
@Composable
private fun SaveFeedbackLine(message: String, failed: Boolean) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        color = if (failed) ChipTone.ERROR.content else ChipTone.OK.content,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(
                (if (failed) ChipTone.ERROR.container else ChipTone.OK.container).copy(alpha = 0.45f),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** 运行卡：printf 管道路线的诚实说明 + 运行入口（未授权 / 执行中禁用）。 */
@Composable
private fun RunCard(
    canRun: Boolean,
    running: Boolean,
    onRun: () -> Unit,
) {
    LabCard(title = "运行", subtitle = "经 Shizuku（adb shell 上下文）执行当前编辑器内容") {
        FilledTonalButton(
            onClick = onRun,
            enabled = canRun,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null)
            Spacer(Modifier.size(6.dp))
            Text(if (running) "执行中…" else "运行脚本")
        }
        Spacer(Modifier.size(8.dp))
        // UI-001：执行通道说明段删除（16KB / 30s / 1MB 限额见 FEAT-014 规格）
    }
}

/** 输出卡：stdout/stderr/exit 行如实分色上屏 + 复制 + 运行时间戳。 */
@Composable
private fun OutputCard(
    report: RunReport?,
    running: Boolean,
    onCopy: () -> Unit,
) {
    LabCard(title = "执行输出") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = report?.let { "${it.source} · ${it.stamp}" } ?: "尚无运行",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCopy, enabled = report != null) { Text("复制输出") }
        }
        Spacer(Modifier.size(4.dp))
        if (running) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = "执行中（单命令网关暂不支持中断，超时 30s 自动返回）…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (report == null) {
            Text(
                text = "尚未执行（编辑脚本后点「运行脚本」或脚本库菜单「直接运行」）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            OutputLines(report)
        }
    }
}

/** 输出行窗口（最新 [DISPLAY_LINE_CAP] 行；完整输出可复制 —— 窗口只是渲染防线）。 */
@Composable
private fun OutputLines(report: RunReport) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (report.lines.size > DISPLAY_LINE_CAP) {
            Text(
                text = "…（仅展示末 $DISPLAY_LINE_CAP 行，完整输出可复制）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        report.lines.takeLast(DISPLAY_LINE_CAP).forEach { line ->
            OutputLineText(line = line)
        }
    }
}

/** 单行渲染：等宽字体；stderr 与非零 exit 行按错误色（红即失败）。 */
@Composable
private fun OutputLineText(line: OutputLine) {
    Text(
        text = line.text,
        color = when (line.kind) {
            LineKind.STDOUT -> MaterialTheme.colorScheme.onSurface
            LineKind.STDERR -> MaterialTheme.colorScheme.error
            LineKind.SYSTEM ->
                if (isNonZeroExitLine(line)) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
        },
        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 非零退出码 = 命令失败：exit 行从系统提示色升级为错误色（人话：红即失败）。 */
private fun isNonZeroExitLine(line: OutputLine): Boolean {
    if (line.kind != LineKind.SYSTEM) return false
    if (!line.text.startsWith(EXIT_PREFIX)) return false
    val code = line.text.removePrefix(EXIT_PREFIX).trim().toIntOrNull() ?: return false
    return code != 0
}

/** 脚本库行：名字 + 语言/修改时间 + 操作菜单（载入 / 删除 / 直接运行）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScriptRow(
    script: SavedScript,
    timeFormat: SimpleDateFormat,
    canRun: Boolean,
    canDelete: Boolean,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
    onDirectRun: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
        headlineContent = {
            Text(
                text = script.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = "${script.lang.title}（.${script.lang.ext}）· 修改 ${timeFormat.format(Date(script.modifiedAt))}",
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(imageVector = Icons.Rounded.MoreVert, contentDescription = "脚本操作")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("载入编辑器") },
                        onClick = {
                            menuExpanded = false
                            onLoad()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("直接运行") },
                        onClick = {
                            menuExpanded = false
                            onDirectRun()
                        },
                        enabled = canRun,
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                        enabled = canDelete,
                    )
                }
            }
        },
    )
}
