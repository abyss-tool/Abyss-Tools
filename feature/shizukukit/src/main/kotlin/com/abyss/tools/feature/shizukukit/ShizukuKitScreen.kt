package com.abyss.tools.feature.shizukukit

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.abyss.tools.core.shell.ShellGateway
import com.abyss.tools.core.shell.ShizukuStatus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 分区导航（三个功能面板 + 历史记录）。 */
internal enum class KitTab(val title: String) {
    APPOPS("AppOps"),
    SETTINGS("系统设置"),
    DUMPSYS("Dumpsys"),
    HISTORY("历史"),
}

/**
 * Shizuku 工具箱 —— 免 Root 提权指令面板（单页 + Tab 分区）。
 *
 * 三大区块均为系统官方通道的可视化封装（fail-closed：非法输入就地拒绝、
 * dumpsys 白名单放行、settings 写入二次确认）：
 *  - AppOps：权限精细管控（allow / deny / default）；
 *  - 系统设置：settings get / put（global / system / secure 三表）；
 *  - Dumpsys：白名单只读诊断；
 *  - 历史：filesDir/shizuku-kit/history.json 持久化（最近 50 条，点击回填）。
 *
 * 通道语义与伪装四件套一致：命令经 [gateway]（Shizuku adb shell 上下文）
 * 执行；状态门禁 [shizukuStatus] 置顶引导。
 */
@Composable
fun ShizukuKitScreen(
    gateway: ShellGateway,
    shizukuStatus: ShizukuStatus,
    onRequestShizukuPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── 执行状态机 + 历史持久化（filesDir/shizuku-kit/history.json）──────
    val historyFile = remember {
        CommandHistoryFile(File(File(context.filesDir, "shizuku-kit"), "history.json"))
    }
    var historyItems by remember { mutableStateOf<List<CommandHistoryItem>>(emptyList()) }
    var historyError by remember { mutableStateOf<String?>(null) }
    var lastRecord by remember { mutableStateOf<ExecRecord?>(null) }

    // load on launch（读档失败 fail-closed：人话错误 + 保留文件，不猜不补）
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { historyFile.load() }
            .onSuccess { historyItems = it }
            .onFailure { historyError = "历史加载失败：${it.message}" }
    }

    fun persistHistory(record: ExecRecord) {
        // save on each command：落盘失败不打断工具主流程，人话提示 + 会话内仍可用
        scope.launch {
            withContext(Dispatchers.IO) { historyFile.push(record.toHistoryItem()) }
                .onSuccess {
                    historyItems = it
                    historyError = null
                }
                .onFailure {
                    historyError = "历史写入失败：${it.message}（本次会话内历史仍可用）"
                    historyItems = HistoryStore.push(historyItems, record.toHistoryItem())
                }
        }
    }

    val runner = remember {
        KitRunner(gateway = gateway, scope = scope) { record ->
            lastRecord = record
            persistHistory(record)
        }
    }

    // ── 面板输入态（提升到页面层：历史回填跨 Tab 存活）────────────────────
    var tab by remember { mutableStateOf(KitTab.APPOPS) }
    var appOpsPkg by remember { mutableStateOf("") }
    var appOpsOp by remember { mutableStateOf("") }
    var settingsNamespace by remember { mutableStateOf(SettingsCommands.Namespace.GLOBAL) }
    var settingsKey by remember { mutableStateOf("") }
    var settingsValue by remember { mutableStateOf("") }

    // 已装应用清单（AppOps 选择器一次性加载，Dispatchers.IO）
    var appEntries by remember { mutableStateOf<List<AppEntry>?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            appEntries = runCatching { loadInstalledApps(context.packageManager) }
                .getOrDefault(emptyList())
        }
    }

    fun clearHistory() {
        scope.launch {
            withContext(Dispatchers.IO) { historyFile.clear() }
                .onSuccess {
                    historyItems = emptyList()
                    historyError = null
                }
                .onFailure { historyError = "历史清空失败：${it.message}" }
        }
    }

    /** 历史回放：反解命令并预填对应面板（不自动执行 —— 用户确认后再点）。 */
    fun replay(item: CommandHistoryItem) {
        when (item.type) {
            CommandType.APPOPS -> {
                val target = AppOpsCommands.parseTarget(item.command) ?: return
                appOpsPkg = target.pkg
                appOpsOp = target.op
                tab = KitTab.APPOPS
            }

            CommandType.SETTINGS -> {
                val target = SettingsCommands.parseTarget(item.command) ?: return
                settingsNamespace = target.namespace
                settingsKey = target.key
                target.value?.let { settingsValue = it }
                tab = KitTab.SETTINGS
            }

            CommandType.DUMPSYS -> tab = KitTab.DUMPSYS
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        KitHeader(onBack = onBack)

        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            ShizukuGateCard(
                shizukuStatus = shizukuStatus,
                onRequestPermission = onRequestShizukuPermission,
                onOpenShizuku = onOpenShizuku,
            )
        }

        TabRow(selectedTabIndex = KitTab.entries.indexOf(tab)) {
            KitTab.entries.forEach { current ->
                Tab(
                    selected = current == tab,
                    onClick = { tab = current },
                    text = { Text(current.title) },
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (tab) {
                KitTab.APPOPS -> AppOpsPanel(
                    runner = runner,
                    record = lastRecord,
                    pkg = appOpsPkg,
                    onPkgChange = { appOpsPkg = it },
                    op = appOpsOp,
                    onOpChange = { appOpsOp = it },
                    appEntries = appEntries,
                )

                KitTab.SETTINGS -> SettingsPanel(
                    runner = runner,
                    record = lastRecord,
                    namespace = settingsNamespace,
                    onNamespaceChange = { settingsNamespace = it },
                    key = settingsKey,
                    onKeyChange = { settingsKey = it },
                    value = settingsValue,
                    onValueChange = { settingsValue = it },
                )

                KitTab.DUMPSYS -> DumpsysPanel(
                    runner = runner,
                    record = lastRecord,
                )

                KitTab.HISTORY -> HistoryPanel(
                    records = historyItems,
                    error = historyError,
                    onItemTap = ::replay,
                    onClear = ::clearHistory,
                )
            }
        }
    }
}

/** 已装应用清单（BottomSheet 选择器数据，Dispatchers.IO 一次性加载）。 */
@Suppress("DEPRECATION") // getInstalledApplications(Int) 全版本可用；Flags 形态 API 33+ 才存在
private fun loadInstalledApps(pm: PackageManager): List<AppEntry> =
    pm.getInstalledApplications(0)
        .map { AppEntry(label = it.loadLabel(pm).toString(), packageName = it.packageName) }
        .sortedBy { it.label.lowercase() }
