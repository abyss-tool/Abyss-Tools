package com.abyss.tools.feature.syspower

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abyss.tools.core.designsystem.component.ChipTone
import com.abyss.tools.core.designsystem.component.StatusChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 隐私与管控（syspower 第四域）：权限审计 / 敏感操作记录 / Doze 白名单 /
 * 流量策略。四个面板全部复用 AuthorizationPanel 的共享脚手架（SysRunner /
 * SectionCard / AppRow / loadInstalledApps / permLabel / opLabel）——
 * 诚实纪律同全页：读不到 = 「未知 / 读取失败」，绝不用假数据占位。
 *
 * 为什么面板内读查询不走 runner.exclusive：同屏多面板的 LaunchedEffect
 * 会同时抢 exclusive 旗（busy 非排队，后来者直接被跳过，数据永远不加载），
 * 改用与 AppAuthorizationSheet 回读相同的裸 query —— 网关对每条命令起
 * 独立进程，并发读安全；写动作仍全部经 busy 单旗守卫。
 */

// ── 共用小件 ───────────────────────────────────────────────────────────────

/** 已装应用清单的一次性全量加载（IO 线程；各选择器面板共用）。 */
@Composable
private fun rememberLoadedApps(): List<InstalledAppInfo>? {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledAppInfo>?>(null) } // null = 加载中
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
    }
    return apps
}

/** 应用搜索 + 列表（授权总管同款交互：搜索框 + 有界滚动清单）。 */
@Composable
private fun AppSearchList(
    apps: List<InstalledAppInfo>?,
    onAppSelected: (InstalledAppInfo) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    OutlinedTextField(
        value = search,
        onValueChange = { search = it },
        label = { Text("搜索应用名 / 包名") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.size(8.dp))
    when (val list = apps) {
        null -> Text(
            text = "应用清单加载中…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> {
            val keyword = search.trim().lowercase()
            val filtered = remember(list, keyword) {
                if (keyword.isEmpty()) {
                    list
                } else {
                    list.filter {
                        it.label.lowercase().contains(keyword) ||
                            it.packageName.lowercase().contains(keyword)
                    }
                }
            }
            if (filtered.isEmpty()) {
                Text(
                    text = "无匹配应用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        AppRow(app = app, onClick = { onAppSelected(app) })
                    }
                }
            }
        }
    }
}

// ── 1) 权限审计（dumpsys package 危险运行时权限盘点 + 回收）──────────────

/**
 * 权限审计面板：自持应用搜索 + 清单，点开应用进 BottomSheet 审计 ——
 * dumpsys package 原始输出的危险运行时权限真值盘点，逐项授予 / 回收，
 * 或一键顺序回收全部已授予（与授权总管同一撤销链 / 错误条）。
 */
@Composable
internal fun PermissionAuditPanel(
    runner: SysRunner,
    onJournalChanged: () -> Unit,
) {
    var auditApp by remember { mutableStateOf<InstalledAppInfo?>(null) }

    SectionCard {
        Text(
            text = "权限审计",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "按应用盘点危险运行时权限的真实授予态（dumpsys package 系统真值），" +
                "可逐项授予 / 回收，或一键回收全部（动作进会话日志，可撤销）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(10.dp))
        AppSearchList(
            apps = rememberLoadedApps(),
            onAppSelected = { auditApp = it },
        )
    }

    auditApp?.let { app ->
        PermissionAuditSheet(
            app = app,
            runner = runner,
            onDismiss = { auditApp = null },
            onJournalChanged = onJournalChanged,
        )
    }
}

/**
 * 单应用权限审计面板（BottomSheet）：requested ∩ 危险白名单的行清单 +
 * 授予态徽章；逐项授予 / 回收（undo = 反向）；「回收全部已授予」为顺序
 * 批动作（任一失败即中断并如实报错，已成功条目照常可逐条撤销）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionAuditSheet(
    app: InstalledAppInfo,
    runner: SysRunner,
    onDismiss: () -> Unit,
    onJournalChanged: () -> Unit,
) {
    val sheetScope = rememberCoroutineScope()

    // 三态：!loaded = 读取中；loadFailed = dumpsys 未取得；其余按解析结果渲染
    var loaded by remember(app.packageName) { mutableStateOf(false) }
    var loadFailed by remember(app.packageName) { mutableStateOf(false) }
    var requested by remember(app.packageName) { mutableStateOf<List<String>?>(null) }
    var runtime by remember(app.packageName) { mutableStateOf<List<RuntimePermissionState>?>(null) }

    suspend fun reload() {
        val output = runner.query(PermissionAuditCommands.dumpPackage(app.packageName))
        if (output == null) {
            loadFailed = true
        } else {
            loadFailed = false
            requested = PermissionAuditCommands.parseRequestedPermissions(output)
            runtime = PermissionAuditCommands.parseRuntimePermissions(output)
        }
        loaded = true
    }

    LaunchedEffect(app.packageName) {
        loaded = false
        loadFailed = false
        requested = null
        runtime = null
        reload()
    }

    // 行驱动：requested ∩ 危险白名单；requested 段缺失时回退 runtime 键
    // （仍是「该应用真实持有的危险运行时权限」），双缺失 → 解析失败态
    val driver = requested ?: runtime?.map { it.perm } ?: emptyList()
    val rows: List<String> = PermissionAuditCommands.filterDangerous(driver).orEmpty()
    val grantedMap: Map<String, Boolean>? = runtime?.associate { it.perm to it.granted }
    val grantedCount = rows.count { grantedMap?.get(it) == true }
    val parseFailed = requested == null && runtime == null

    fun applyPerm(perm: String, grant: Boolean) {
        val verb = if (grant) "授予" else "回收"
        val command = if (grant) {
            RuntimePermCommands.grant(app.packageName, perm)
        } else {
            RuntimePermCommands.revoke(app.packageName, perm)
        }
        val undoCommand = if (grant) {
            RuntimePermCommands.revoke(app.packageName, perm)
        } else {
            RuntimePermCommands.grant(app.packageName, perm)
        }
        runner.execute(
            sheetScope,
            SysAction("$verb ${app.label} ${permLabel(perm)}", command),
            SysAction("${if (grant) "回收" else "授予"} ${app.label} ${permLabel(perm)}", undoCommand),
        ) { ok ->
            if (ok) onJournalChanged()
            reload()
        }
    }

    fun revokeAllGranted() {
        val targets = rows.filter { grantedMap?.get(it) == true }
        if (targets.isEmpty() || runner.busy) return
        runner.executeAll(
            sheetScope,
            targets.map { perm ->
                SysAction(
                    "回收 ${app.label} ${permLabel(perm)}",
                    RuntimePermCommands.revoke(app.packageName, perm),
                ) to SysAction(
                    "授予 ${app.label} ${permLabel(perm)}",
                    RuntimePermCommands.grant(app.packageName, perm),
                )
            },
        ) { ok ->
            if (ok) onJournalChanged()
            reload()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "权限审计 · ${app.label}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = "数据来自 dumpsys package 原始输出（runtime permissions 段的系统真值），" +
                    "只列白名单内的危险权限（读不到就显示「未知」，不猜）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(12.dp))
            when {
                !loaded -> Text(
                    text = "权限清单读取中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                loadFailed -> Column {
                    Text(
                        text = "读取失败：未取得 dumpsys package 输出（错误详情见页面顶部错误条）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { sheetScope.launch { reload() } }) { Text("重试") }
                }
                parseFailed -> Text(
                    text = "未能从 dumpsys 输出解析出权限段（格式不识别）—— 不猜测，请反馈机型。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                rows.isEmpty() -> Text(
                    text = "该应用未声明白名单内的危险运行时权限（无可审计项）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    Text(
                        text = "白名单危险权限 ${rows.size} 项 · 已授予 $grantedCount 项",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.size(6.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        contentPadding = PaddingValues(bottom = 8.dp),
                    ) {
                        items(rows) { perm ->
                            AuditPermRow(
                                perm = perm,
                                granted = grantedMap?.get(perm),
                                busy = runner.busy,
                                onGrant = { applyPerm(perm, true) },
                                onRevoke = { applyPerm(perm, false) },
                            )
                        }
                    }
                    Button(
                        onClick = { revokeAllGranted() },
                        enabled = !runner.busy && grantedCount > 0,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("回收全部已授予（$grantedCount 项）") }
                }
            }
            Spacer(Modifier.size(20.dp))
        }
    }
}

/** 审计行：人话标签 + 全名 + 授予态徽章 + 授予 / 回收（非法方向就地禁用）。 */
@Composable
private fun AuditPermRow(
    perm: String,
    granted: Boolean?,
    busy: Boolean,
    onGrant: () -> Unit,
    onRevoke: () -> Unit,
) {
    val (chipText, chipTone) = when (granted) {
        true -> "已授予" to ChipTone.OK
        false -> "未授予" to ChipTone.NEUTRAL
        null -> "未知" to ChipTone.WARN
    }
    ListItem(
        leadingContent = { StatusChip(text = chipText, tone = chipTone) },
        headlineContent = { Text(permLabel(perm)) },
        supportingContent = { Text(perm) },
        trailingContent = {
            Row {
                // pm grant 对已授予直接报错（revoke 对未授予同理）—— 只留合法方向
                TextButton(onClick = onGrant, enabled = !busy && granted == false) { Text("授予") }
                TextButton(onClick = onRevoke, enabled = !busy && granted == true) { Text("回收") }
            }
        },
    )
}

// ── 2) 敏感操作记录（appops query-op 记账回溯，只读）──────────────────────

/** 记账模式的人话标签（未知模式原样展示，不猜）。 */
private val QUERY_MODE_LABELS: Map<String, String> = mapOf(
    "allow" to "允许",
    "ignore" to "拒绝",
    "default" to "默认",
    "foreground" to "仅前台",
)

private fun queryModeLabel(mode: String): String = QUERY_MODE_LABELS[mode] ?: mode

/**
 * 敏感操作记录面板（只读）：appops query-op 按 op 反查「谁用过」——
 * 相机 / 麦克风 / 定位 / 剪贴板。非可逆动作（纯查询），不入会话日志；
 * 空结果 = 近期无应用使用，是合法答案而非错误。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SensitiveOpsPanel(runner: SysRunner) {
    val scope = rememberCoroutineScope()
    var selectedOp by remember { mutableStateOf(SensitiveOpsCommands.QUERY_OPS.first()) }
    var querying by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<List<OpUsage>?>(null) } // null = 尚未查询
    var queryFailed by remember { mutableStateOf(false) }

    fun runQuery() {
        if (querying) return
        scope.launch {
            querying = true
            try {
                val output = runner.query(SensitiveOpsCommands.queryOp(selectedOp))
                if (output == null) {
                    queryFailed = true
                    result = null
                } else {
                    queryFailed = false
                    result = SensitiveOpsCommands.parseQueryOp(output)
                }
            } finally {
                querying = false
            }
        }
    }

    SectionCard {
        Text(
            text = "敏感操作记录",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "数据来自系统 AppOps 记账（只读查询，无可撤销动作）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SensitiveOpsCommands.QUERY_OPS) { op ->
                FilterChip(
                    selected = op == selectedOp,
                    onClick = { selectedOp = op },
                    label = { Text(opLabel(op)) },
                    enabled = !querying,
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        Button(onClick = { runQuery() }, enabled = !querying) {
            Text(if (querying) "查询中…" else "查询")
        }
        Spacer(Modifier.size(8.dp))
        when {
            querying -> Text(
                text = "查询中…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            queryFailed -> Text(
                text = "读取失败：命令未成功执行（错误详情见页面顶部错误条）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> when (val list = result) {
                null -> Text(
                    text = "选择操作类型后点「查询」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> if (list.isEmpty()) {
                    Text(
                        text = "近期无应用使用记录（appops 无该 op 条目）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        contentPadding = PaddingValues(bottom = 8.dp),
                    ) {
                        items(list) { usage -> OpUsageRow(usage) }
                    }
                }
            }
        }
    }
}

/** 记账行：包名 + 当前模式徽章（人话；未知模式原样展示）。 */
@Composable
private fun OpUsageRow(usage: OpUsage) {
    ListItem(
        leadingContent = {
            Badge(
                containerColor = when (usage.mode) {
                    "allow" -> MaterialTheme.colorScheme.primaryContainer
                    "ignore" -> MaterialTheme.colorScheme.errorContainer
                    "default" -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.outlineVariant
                },
                contentColor = when (usage.mode) {
                    "allow" -> MaterialTheme.colorScheme.onPrimaryContainer
                    "ignore" -> MaterialTheme.colorScheme.onErrorContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) { Text(queryModeLabel(usage.mode)) }
        },
        headlineContent = { Text(usage.pkg) },
        supportingContent = { Text("op 模式: ${usage.mode}") },
    )
}

// ── 3) Doze 白名单（dumpsys deviceidle whitelist 读 / 增 / 删）───────────

/**
 * Doze 白名单面板：白名单内应用在 Doze 深睡期保留网络 / 同步豁免。
 * 增删走 journal（undo = 反向）；系统内置条目移除会被服务拒绝 —— 原样
 * 报错（页面错误条），不静默吞掉。
 */
@Composable
internal fun DeviceIdlePanel(
    runner: SysRunner,
    onJournalChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<String>?>(null) } // null = 未取得
    var loadFailed by remember { mutableStateOf(false) }
    var newPkg by remember { mutableStateOf("") }

    suspend fun reload() {
        val output = runner.query(DeviceIdleCommands.whitelist())
        if (output == null) {
            entries = null
            loadFailed = true
        } else {
            loadFailed = false
            entries = DeviceIdleCommands.parseWhitelist(output)
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun addEntry() {
        // 双保险门禁（按钮已按同正则禁用）：非包名形态就地拒执行
        if (!DeviceIdleCommands.looksLikePackageName(newPkg)) return
        val pkg = newPkg
        runner.execute(
            scope,
            SysAction("Doze 白名单添加 $pkg", DeviceIdleCommands.add(pkg)),
            SysAction("Doze 白名单移除 $pkg", DeviceIdleCommands.remove(pkg)),
        ) { ok ->
            if (ok) {
                onJournalChanged()
                newPkg = ""
            }
            reload()
        }
    }

    fun removeEntry(pkg: String) {
        runner.execute(
            scope,
            SysAction("Doze 白名单移除 $pkg", DeviceIdleCommands.remove(pkg)),
            SysAction("Doze 白名单恢复 $pkg", DeviceIdleCommands.add(pkg)),
        ) { ok ->
            if (ok) onJournalChanged()
            reload()
        }
    }

    SectionCard {
        Text(
            text = "Doze 白名单（deviceidle whitelist）",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "Doze 深睡期保留网络 / 同步豁免（系统内置条目由系统维护，移除会被拒绝）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(10.dp))
        OutlinedTextField(
            value = newPkg,
            onValueChange = {
                newPkg = it.filter { ch -> ch.isLetterOrDigit() || ch == '.' || ch == '_' }.take(64)
            },
            label = { Text("包名（如 com.foo.bar）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.size(6.dp))
        Button(
            onClick = { addEntry() },
            enabled = !runner.busy && DeviceIdleCommands.looksLikePackageName(newPkg),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("加入白名单") }
        Spacer(Modifier.size(10.dp))
        when {
            entries == null && !loadFailed -> Text(
                text = "白名单读取中…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            loadFailed -> Column {
                Text(
                    text = "读取失败：未取得 deviceidle whitelist 输出（错误详情见页面顶部错误条）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { scope.launch { reload() } }) { Text("重试") }
            }
            entries.orEmpty().isEmpty() -> Text(
                text = "白名单为空（读取成功，无任何条目）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp),
                contentPadding = PaddingValues(bottom = 4.dp),
            ) {
                items(entries.orEmpty()) { pkg ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 2.dp),
                    ) {
                        Text(
                            text = pkg,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { removeEntry(pkg) },
                            enabled = !runner.busy,
                        ) { Text("移除") }
                    }
                }
            }
        }
    }
}

// ── 4) 流量策略（cmd netpolicy：全局 restrict-background + 单应用 uid-policy）──

/** uid-policy 人话（0 = 默认；1 = 拒绝；4 = 显式允许；其余原样展示）。 */
private fun uidPolicyLabel(policy: Int): String = when (policy) {
    0 -> "默认（未设策略）"
    1 -> "拒绝后台流量"
    4 -> "允许后台流量（数据节省白名单）"
    else -> "未知策略值 $policy"
}

/**
 * 流量策略面板：全局流量节省开关（restrict-background）+ 单应用后台
 * 流量管制（uid-policy）。读失败诚实显示「未知 / 读取失败」，开关回退
 * 关位但不发写命令；uid 经 PackageManager 解析（失败如实报，不猜 0）。
 */
@Composable
internal fun NetPolicyPanel(
    runner: SysRunner,
    onJournalChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var restrictBg by remember { mutableStateOf<Boolean?>(null) }
    var restrictFailed by remember { mutableStateOf(false) }
    var restrictLoaded by remember { mutableStateOf(false) }

    var netApp by remember { mutableStateOf<InstalledAppInfo?>(null) }
    var uid by remember { mutableStateOf<Int?>(null) }
    var uidFailed by remember { mutableStateOf(false) }
    var policyValue by remember { mutableStateOf<Int?>(null) }
    var policyFailed by remember { mutableStateOf(false) }
    var policyLoaded by remember { mutableStateOf(false) }

    suspend fun reloadRestrict() {
        val output = runner.query(NetPolicyCommands.getRestrictBackground())
        if (output == null) {
            restrictFailed = true
        } else {
            restrictFailed = false
            restrictBg = NetPolicyCommands.parseRestrictBackground(output)
        }
        restrictLoaded = true
    }

    LaunchedEffect(Unit) { reloadRestrict() }

    // 选中应用 → uid（PackageManager，IO）→ 当前 uid-policy（shell 回读）
    LaunchedEffect(netApp?.packageName) {
        val app = netApp
        if (app == null) {
            uid = null
            uidFailed = false
            policyValue = null
            policyFailed = false
            policyLoaded = false
            return@LaunchedEffect
        }
        policyLoaded = false
        policyFailed = false
        policyValue = null
        uidFailed = false
        uid = null
        val resolved = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationInfo(app.packageName, 0).uid }.getOrNull()
        }
        uid = resolved
        if (resolved == null) {
            uidFailed = true
            policyLoaded = true
            return@LaunchedEffect
        }
        val output = runner.query(NetPolicyCommands.getUidPolicy(resolved))
        if (output == null) {
            policyFailed = true
        } else {
            policyFailed = false
            policyValue = NetPolicyCommands.parseUidPolicy(output)
        }
        policyLoaded = true
    }

    suspend fun reloadPolicy() {
        val targetUid = uid ?: return
        val output = runner.query(NetPolicyCommands.getUidPolicy(targetUid))
        if (output == null) {
            policyFailed = true
        } else {
            policyFailed = false
            policyValue = NetPolicyCommands.parseUidPolicy(output)
        }
        policyLoaded = true
    }

    fun applyRestrict(enable: Boolean) {
        runner.execute(
            scope,
            SysAction(
                if (enable) "全局流量节省开" else "全局流量节省关",
                NetPolicyCommands.setRestrictBackground(enable),
            ),
            SysAction(
                if (enable) "全局流量节省关" else "全局流量节省开",
                NetPolicyCommands.setRestrictBackground(!enable),
            ),
        ) { ok ->
            if (ok) onJournalChanged()
            reloadRestrict()
        }
    }

    fun applyUidPolicy(reject: Boolean) {
        val targetUid = uid ?: return
        val app = netApp
        val verb = if (reject) "拒绝后台流量" else "解除后台流量限制"
        val prev = policyValue
        // 撤销只还原 0/1 二值旧态；旧态是 4（显式允许）或未知 → 无撤销
        //（0/1 通道还原不回「显式允许」，诚实弃撤销而非写错值）
        val undo: SysAction? = when {
            !reject && prev == 1 ->
                SysAction("恢复拒绝后台流量", NetPolicyCommands.setUidPolicy(targetUid, true))
            reject && prev == 0 ->
                SysAction("解除后台流量限制", NetPolicyCommands.setUidPolicy(targetUid, false))
            else -> null
        }
        runner.execute(
            scope,
            SysAction("$verb · ${app?.label ?: "uid $targetUid"}", NetPolicyCommands.setUidPolicy(targetUid, reject)),
            undo,
        ) { ok ->
            if (ok) onJournalChanged()
            reloadPolicy()
        }
    }

    SectionCard {
        Text(
            text = "流量策略（cmd netpolicy）",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "数据节省与单应用后台流量管制（adb 官方通道，重启保持）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(4.dp))
        // 全局开关状态文案（先算再拼 —— when 结果作字符串拼接操作数可读性差）
        val restrictText = when {
            !restrictLoaded -> "读取中…"
            restrictFailed -> "读取失败（见顶部错误条）"
            restrictBg == true -> "已开启"
            restrictBg == false -> "已关闭"
            else -> "未知（输出无法解析）"
        }
        ListItem(
            headlineContent = { Text("全局流量节省（限制后台数据）") },
            supportingContent = {
                Text(text = "当前: $restrictText · 计量网络下全系统限制后台数据")
            },
            trailingContent = {
                // 读失败 / 未知时只回退关位展示，禁写 —— 不盲发命令
                Switch(
                    checked = restrictBg == true,
                    onCheckedChange = { applyRestrict(it) },
                    enabled = !runner.busy && restrictLoaded && !restrictFailed && restrictBg != null,
                )
            },
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Spacer(Modifier.size(4.dp))
        Text(
            text = "按应用策略（uid-policy）",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "先选应用（读取 uid 与当前策略后再管制）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        AppSearchList(
            apps = rememberLoadedApps(),
            onAppSelected = { netApp = it },
        )
        netApp?.let { app ->
            Spacer(Modifier.size(6.dp))
            Text(
                text = app.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(4.dp))
            if (uidFailed) {
                Text(
                    text = "UID 读取失败（无法获取应用 uid）—— 无法下发策略。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                // 应用策略状态文案（uid / 策略两段各自诚实三态）
                // 委托属性不可 smart cast —— 先落本地 val 再判空（when 分支
                // 里的 policyValue 会被编译器视为每次重读的 Int?）
                val currentPolicy = policyValue
                val policyText = when {
                    !policyLoaded -> "读取中…"
                    policyFailed -> "读取失败（见顶部错误条）"
                    currentPolicy == null -> "未知（输出无法解析）"
                    else -> uidPolicyLabel(currentPolicy)
                }
                Text(
                    text = "UID: ${uid ?: "读取中…"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "当前策略: $policyText",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.size(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 已知策略的重复方向就地禁用；未知 / 读取失败仍可发（系统如实回应）
                    FilledTonalButton(
                        onClick = { applyUidPolicy(true) },
                        enabled = !runner.busy && uid != null && policyValue != 1,
                    ) { Text("拒绝后台流量") }
                    FilledTonalButton(
                        onClick = { applyUidPolicy(false) },
                        enabled = !runner.busy && uid != null && policyValue != 0,
                    ) { Text("解除限制") }
                }
                Spacer(Modifier.size(4.dp))
                Text(
                    text = "拒绝 = POLICY_REJECT_METERED_BACKGROUND（计量网络后台数据一律拒绝），" +
                        "解除 = 清除该应用全部策略位回默认",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
