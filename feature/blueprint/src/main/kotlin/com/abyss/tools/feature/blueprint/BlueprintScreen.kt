package com.abyss.tools.feature.blueprint

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abyss.tools.core.common.UiState
import com.abyss.tools.core.designsystem.component.ChipTone
import com.abyss.tools.core.designsystem.component.EmptyState
import com.abyss.tools.core.designsystem.component.ErrorState
import com.abyss.tools.core.designsystem.component.LoadingState
import com.abyss.tools.core.designsystem.component.StatusChip
import com.abyss.tools.core.shell.ShellGateway
import com.abyss.tools.core.shell.ShizukuStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 分区导航：「任务蓝图」与「自动化编排」两个工具入口共用本页（initialTab 区分落地）。 */
enum class BlueprintTab(val title: String) {
    LIBRARY("蓝图"),
    AUTOMATION("调度"),
    HISTORY("历史"),
}

/**
 * 任务蓝图 / 自动化编排 —— JSON 任务编排主页（单页 + Tab 分区）。
 *
 *  - 蓝图：内置 + 用户蓝图目录（点击展开运行区，onStepDone 实时渲染
 *    ✓/✗ 徽章 + 耗时 + 输出摘要，点开全文终端块）；用户蓝图可编辑/删除，
 *    内置可「复制为用户版」；新建走整屏编辑页（JSON + 实时校验 + 调度开关）；
 *  - 调度：自动化编排管理（AlarmManager 精确闹钟清单 + 取消调度）；
 *  - 历史：runs.json 最近 20 条执行记录（手动 + 定时一视同仁）。
 *
 * 通道语义：SHELL 步骤经 [gateway]（Shizuku adb shell 上下文）执行 ——
 * Shizuku 不可用时 SHELL 蓝图运行按钮门禁；TOAST / DELAY 蓝图引擎层
 * 无 Shizuku 依赖，可独立运行。
 */
@Composable
fun BlueprintScreen(
    gateway: ShellGateway,
    shizukuStatus: ShizukuStatus,
    onRequestShizukuPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onBack: () -> Unit,
    initialTab: BlueprintTab = BlueprintTab.LIBRARY,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { BlueprintStore.create(context) }

    var tab by remember { mutableStateOf(initialTab) }
    var library by remember { mutableStateOf<UiState<LibrarySnapshot>>(UiState.Loading) }
    var runs by remember { mutableStateOf<List<BlueprintRun>>(emptyList()) }
    var runsError by remember { mutableStateOf<String?>(null) }
    var storeError by remember { mutableStateOf<String?>(null) }
    var expandedId by remember { mutableStateOf<String?>(null) }

    // 编辑器（null = 关闭；initial = 新建 / 编辑预填）
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BlueprintManifest?>(null) }

    // 运行态（单飞：同时只允许一条蓝图在跑）
    var runningId by remember { mutableStateOf<String?>(null) }
    var liveResults by remember { mutableStateOf<List<StepResult>>(emptyList()) }
    var liveCurrent by remember { mutableStateOf<Int?>(null) }
    var resultsOwnerId by remember { mutableStateOf<String?>(null) }

    val shizukuAuthorized = (shizukuStatus as? ShizukuStatus.Running)?.authorized == true

    fun refresh() {
        library = UiState.Loading
        storeError = null
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) { store.loadAll() }
            library = UiState.Data(snapshot)
            withContext(Dispatchers.IO) { store.loadRuns() }
                .onSuccess {
                    runs = it
                    runsError = null
                }
                .onFailure { runsError = "执行历史读取失败：${it.message}" }
        }
    }

    fun runBlueprint(manifest: BlueprintManifest) {
        if (runningId != null) return
        runningId = manifest.id
        resultsOwnerId = manifest.id
        liveResults = emptyList()
        liveCurrent = null
        scope.launch {
            val run = BlueprintEngine.execute(
                manifest = manifest,
                gateway = gateway,
                onStepStart = { liveCurrent = it },
                onStepDone = { step ->
                    liveResults = liveResults + step
                    if (step.type == StepType.TOAST) {
                        Toast.makeText(context, step.message.orEmpty(), Toast.LENGTH_SHORT).show()
                    }
                },
            )
            runningId = null
            liveCurrent = null
            withContext(Dispatchers.IO) { store.appendRun(run) }
                .onSuccess { runs = it }
                .onFailure { storeError = "执行历史写入失败：${it.message}（本次会话内结果仍可查看）" }
        }
    }

    fun deleteManifest(manifest: BlueprintManifest) {
        scope.launch {
            withContext(Dispatchers.IO) { store.deleteUser(manifest.id) }
                .onSuccess {
                    ScheduleAlarmReceiver.cancel(context, manifest.id)
                    if (expandedId == manifest.id) expandedId = null
                    if (resultsOwnerId == manifest.id) {
                        liveResults = emptyList()
                        resultsOwnerId = null
                    }
                    refresh()
                }
                .onFailure { storeError = "删除失败：${it.message}" }
        }
    }

    fun cancelSchedule(manifest: BlueprintManifest) {
        scope.launch {
            // 用户版：把 schedule=null 落盘（否则重启 / 再保存会复活调度）
            val isUser = (library as? UiState.Data)?.value?.userIds?.contains(manifest.id) == true
            if (isUser) {
                withContext(Dispatchers.IO) { store.saveUser(manifest.copy(schedule = null)) }
                    .onFailure {
                        storeError = "调度已撤下，但蓝图落盘失败（重启后可能恢复）：${it.message}"
                    }
            }
            ScheduleAlarmReceiver.cancel(context, manifest.id)
            refresh()
        }
    }

    // 进入页面读库；保存 / 删除 / 取消调度后刷新
    LaunchedEffect(Unit) { refresh() }

    // 编辑页打开时，系统返回键先关编辑页而不是退出本页
    BackHandler(enabled = editorOpen) { editorOpen = false }

    if (editorOpen) {
        BlueprintEditor(
            initial = editing,
            onDismiss = { editorOpen = false },
            onSaved = { refresh() },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        BlueprintHeader(onBack = onBack) {
            StatusChip(text = "JSON 编排", tone = ChipTone.INFO)
        }

        // Shizuku 门禁（已授权不渲染；TOAST / DELAY 蓝图不受影响的边界如实说明）
        if (!shizukuAuthorized) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp),
            ) {
                ShizukuGateCard(
                    shizukuStatus = shizukuStatus,
                    onRequestPermission = onRequestShizukuPermission,
                    onOpenShizuku = onOpenShizuku,
                )
            }
        }

        TabRow(selectedTabIndex = BlueprintTab.entries.indexOf(tab)) {
            BlueprintTab.entries.forEach { current ->
                Tab(
                    selected = current == tab,
                    onClick = { tab = current },
                    text = { Text(current.title) },
                )
            }
        }

        val nameById = (library as? UiState.Data)
            ?.value?.blueprints?.associate { it.id to it.name }
            .orEmpty()

        Box(modifier = Modifier.weight(1f)) {
            when (tab) {
                BlueprintTab.LIBRARY -> LibraryTab(
                    library = library,
                    storeError = storeError,
                    expandedId = expandedId,
                    runningId = runningId,
                    liveResults = liveResults,
                    liveCurrent = liveCurrent,
                    resultsOwnerId = resultsOwnerId,
                    shizukuAuthorized = shizukuAuthorized,
                    onRefresh = { refresh() },
                    onToggleExpand = { id ->
                        if (expandedId == id) {
                            expandedId = null
                        } else {
                            expandedId = id
                            if (resultsOwnerId != null && resultsOwnerId != id) {
                                liveResults = emptyList()
                                liveCurrent = null
                                resultsOwnerId = null
                            }
                        }
                    },
                    onRun = { runBlueprint(it) },
                    onEdit = { manifest ->
                        editing = manifest
                        editorOpen = true
                    },
                    onDelete = { deleteManifest(it) },
                    onCreate = {
                        editing = null
                        editorOpen = true
                    },
                )

                BlueprintTab.AUTOMATION -> AutomationTab(
                    library = library,
                    shizukuAuthorized = shizukuAuthorized,
                    onCreate = {
                        editing = null
                        editorOpen = true
                    },
                    onCancelSchedule = { cancelSchedule(it) },
                )

                BlueprintTab.HISTORY -> HistoryTab(
                    runs = runs,
                    runsError = runsError,
                    nameById = nameById,
                )
            }
        }
    }
}

// ── 蓝图库 Tab ───────────────────────────────────────────────────────────

@Composable
private fun LibraryTab(
    library: UiState<LibrarySnapshot>,
    storeError: String?,
    expandedId: String?,
    runningId: String?,
    liveResults: List<StepResult>,
    liveCurrent: Int?,
    resultsOwnerId: String?,
    shizukuAuthorized: Boolean,
    onRefresh: () -> Unit,
    onToggleExpand: (String) -> Unit,
    onRun: (BlueprintManifest) -> Unit,
    onEdit: (BlueprintManifest) -> Unit,
    onDelete: (BlueprintManifest) -> Unit,
    onCreate: () -> Unit,
) {
    when (val state = library) {
        is UiState.Loading -> LoadingState(
            message = "正在读取蓝图库…",
            modifier = Modifier.fillMaxSize(),
        )

        is UiState.Error -> ErrorState(
            message = "蓝图库读取失败：${state.message}",
            modifier = Modifier.fillMaxSize(),
            onRetry = onRefresh,
        )

        is UiState.Data -> {
            val snapshot = state.value
            val anyRunning = runningId != null
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "library-header") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "蓝图库",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "内置 ${snapshot.blueprints.size - snapshot.userIds.size} 个 · " +
                                    "用户 ${snapshot.userIds.size} 个 · 点击行展开运行",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(onClick = onCreate) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(4.dp))
                            Text("新建蓝图")
                        }
                    }
                }

                if (storeError != null) {
                    item(key = "store-error") { BlueprintWarnLine(storeError) }
                }
                snapshot.warnings.forEach { warning ->
                    item(key = "warn-$warning") { BlueprintWarnLine(warning) }
                }

                items(snapshot.blueprints, key = { it.id }) { manifest ->
                    BlueprintLibraryRow(
                        manifest = manifest,
                        isUser = manifest.id in snapshot.userIds,
                        expanded = expandedId == manifest.id,
                        isRunning = runningId == manifest.id,
                        anyRunning = anyRunning,
                        liveResults = if (resultsOwnerId == manifest.id) liveResults else emptyList(),
                        liveCurrent = liveCurrent,
                        shizukuAuthorized = shizukuAuthorized,
                        onToggleExpand = { onToggleExpand(manifest.id) },
                        onRun = { onRun(manifest) },
                        onEdit = { onEdit(manifest) },
                        onDelete = { onDelete(manifest) },
                    )
                }
            }
        }
    }
}

/**
 * 蓝图行卡：名称 + 步骤数徽章 + 内置/用户徽章 + 调度徽章（每 N 分钟）；
 * 展开后为运行区（步骤预览 → 实时结果）+ 动作区（编辑 / 删除 / 复制为用户版）。
 */
@Composable
private fun BlueprintLibraryRow(
    manifest: BlueprintManifest,
    isUser: Boolean,
    expanded: Boolean,
    isRunning: Boolean,
    anyRunning: Boolean,
    liveResults: List<StepResult>,
    liveCurrent: Int?,
    shizukuAuthorized: Boolean,
    onToggleExpand: () -> Unit,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember(manifest.id) { mutableStateOf(false) }
    val gateBlocked = needsShellChannel(manifest) && !shizukuAuthorized

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                .clickable(onClick = onToggleExpand)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = manifest.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusChip(text = "${manifest.steps.size} 步", tone = ChipTone.NEUTRAL)
                    StatusChip(
                        text = if (isUser) "用户" else "内置",
                        tone = if (isUser) ChipTone.INFO else ChipTone.NEUTRAL,
                    )
                    manifest.schedule?.let {
                        StatusChip(text = "每 ${it.intervalMinutes} 分钟", tone = ChipTone.WARN)
                    }
                }
                if (manifest.description.isNotBlank()) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = manifest.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) 4 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = if (expanded) {
                    Icons.Rounded.KeyboardArrowUp
                } else {
                    Icons.Rounded.KeyboardArrowDown
                },
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (expanded) {
            Spacer(Modifier.size(8.dp))
            BlueprintCard {
                if (isRunning || liveResults.isNotEmpty()) {
                    LiveStepsSection(
                        steps = manifest.steps,
                        results = liveResults,
                        currentIndex = liveCurrent,
                        running = isRunning,
                    )
                } else {
                    StepPreviewList(manifest.steps)
                }

                Spacer(Modifier.size(12.dp))
                if (isRunning) {
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
                } else {
                    Button(onClick = onRun, enabled = !anyRunning && !gateBlocked) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(4.dp))
                        Text("运行蓝图")
                    }
                    if (gateBlocked) {
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = "此蓝图含 SHELL 步骤，需要先就绪 Shizuku 通道（顶部卡片可引导）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = ChipTone.WARN.content,
                        )
                    }
                }

                Spacer(Modifier.size(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isUser) {
                        OutlinedButton(onClick = onEdit, enabled = !isRunning) { Text("编辑") }
                        if (confirmDelete) {
                            Button(onClick = onDelete, enabled = !isRunning) { Text("确认删除") }
                            OutlinedButton(
                                onClick = { confirmDelete = false },
                                enabled = !isRunning,
                            ) {
                                Text("取消")
                            }
                        } else {
                            OutlinedButton(
                                onClick = { confirmDelete = true },
                                enabled = !isRunning,
                            ) {
                                Text(
                                    text = "删除",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    } else {
                        OutlinedButton(onClick = onEdit, enabled = !isRunning) {
                            Text("复制为用户版")
                        }
                    }
                }
            }
        }
    }
}

// ── 自动化调度 Tab ───────────────────────────────────────────────────────

@Composable
private fun AutomationTab(
    library: UiState<LibrarySnapshot>,
    shizukuAuthorized: Boolean,
    onCreate: () -> Unit,
    onCancelSchedule: (BlueprintManifest) -> Unit,
) {
    when (val state = library) {
        is UiState.Loading -> LoadingState(
            message = "正在读取调度清单…",
            modifier = Modifier.fillMaxSize(),
        )

        is UiState.Error -> EmptyState(
            title = "调度清单暂不可用",
            message = "蓝图库读取失败，稍后重进本页即可恢复。",
        )

        is UiState.Data -> {
            val scheduled = state.value.blueprints.filter { it.schedule != null }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "automation-header") {
                    Column {
                        Text(
                            text = "自动化调度",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "AlarmManager 精确闹钟 · 每 N 分钟自动执行 · 重启自动恢复",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item(key = "automation-explainer") {
                    BlueprintCard {
                        Text(
                            text = "调度原理",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            text = "保存开启调度的蓝图时排入精确闹钟（setExactAndAllowWhileIdle，" +
                                "省电模式下也会触发）；到期在后台执行，执行结果与手动运行一样写入" +
                                "「历史」；设备重启后由开机接收器自动重排，调度不丢。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!shizukuAuthorized) {
                            Spacer(Modifier.size(6.dp))
                            Text(
                                text = "注意：Shizuku 当前未就绪 —— 到期执行 SHELL 步骤会失败，历史里会如实记录。",
                                style = MaterialTheme.typography.bodySmall,
                                color = ChipTone.WARN.content,
                            )
                        }
                    }
                }

                if (scheduled.isEmpty()) {
                    item(key = "automation-empty") {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            EmptyState(
                                title = "暂无调度中的蓝图",
                                message = "在蓝图编辑页打开「定时调度」开关即可；内置蓝图需先" +
                                    "「复制为用户版」再开启（间隔最小 " +
                                    "${BlueprintParser.MIN_INTERVAL_MINUTES} 分钟）。",
                            )
                            OutlinedButton(onClick = onCreate) { Text("新建蓝图") }
                        }
                    }
                } else {
                    items(scheduled, key = { it.id }) { manifest ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = manifest.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.size(4.dp))
                                Text(
                                    text = "id ${manifest.id} · ${manifest.steps.size} 步",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            StatusChip(
                                text = "每 ${manifest.schedule?.intervalMinutes ?: 0} 分钟",
                                tone = ChipTone.WARN,
                            )
                            Spacer(Modifier.size(10.dp))
                            OutlinedButton(onClick = { onCancelSchedule(manifest) }) {
                                Text("取消调度")
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── 执行历史 Tab ─────────────────────────────────────────────────────────

@Composable
private fun HistoryTab(
    runs: List<BlueprintRun>,
    runsError: String?,
    nameById: Map<String, String>,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "history-header") {
            Column {
                Text(
                    text = "执行历史",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "最近 ${BlueprintStore.MAX_RUNS} 条 · 手动运行与定时调度一视同仁",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (runsError != null) {
            item(key = "runs-error") { BlueprintWarnLine(runsError) }
        }

        if (runs.isEmpty() && runsError == null) {
            item(key = "runs-empty") {
                EmptyState(
                    title = "暂无执行记录",
                    message = "手动运行或定时调度的蓝图，每次执行（含每个步骤的结果）都会记录在这里。",
                )
            }
        } else {
            items(runs) { run ->
                RunRow(run = run, nameById = nameById)
            }
        }
    }
}

/** 单次执行行：时间 + 蓝图名 + 成功徽章 + 总耗时；点击展开步骤结果。 */
@Composable
private fun RunRow(
    run: BlueprintRun,
    nameById: Map<String, String>,
) {
    var expanded by remember(run) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable { expanded = !expanded }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = nameById[run.blueprintId] ?: run.blueprintId,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    text = formatRunTime(run.startedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (run.success) {
                StatusChip(text = "成功", tone = ChipTone.OK)
            } else {
                StatusChip(text = "失败", tone = ChipTone.ERROR)
            }
            StatusChip(text = formatTotalMs(run.totalMs), tone = ChipTone.NEUTRAL)
        }

        if (expanded) {
            Spacer(Modifier.size(10.dp))
            if (run.results.isEmpty()) {
                Text(
                    text = "本条记录没有任何步骤结果。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    run.results.forEach { result -> StepResultRow(result) }
                }
            }
        }
    }
}
