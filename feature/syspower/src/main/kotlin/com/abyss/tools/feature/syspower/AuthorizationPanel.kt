package com.abyss.tools.feature.syspower

/**
 * 授权总管（syspower 的拆分文件，spec 的「internal composables」位）：
 * 模块共享脚手架（SysRunner 执行器 / SectionCard 卡容器 / 页头 /
 * Shizuku 门禁卡 / inline 错误条 / 应用清单加载与行组件）+ 授权总管本体
 * （应用选择器与 AppOps / 运行时权限的 BottomSheet 授权面板）。
 * 脚手架同时供 PrivacyPanels.kt（隐私与管控四面板）复用 —— 共享件
 * internal，行为两处一致。
 */
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.abyss.tools.core.shell.ShellGateway
import com.abyss.tools.core.shell.ShizukuStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 共享执行器：busy 单旗守卫顺序执行（页面全部写动作共用，防并发写互踩
 * Shizuku 会话）；失败就地置 [error]（inline 错误条消费 —— 诚实可见，
 * 不走 toast 吞掉）；成功动作写入 [journal]（撤销链）。
 */
internal class SysRunner(
    private val gateway: ShellGateway,
    internal val journal: SessionJournal,
) {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    /** 顺序执行一条写动作（成功即入日志）；onDone 在协程内回调（可挂起回读）。 */
    fun execute(
        scope: CoroutineScope,
        action: SysAction,
        undo: SysAction? = null,
        onDone: suspend (Boolean) -> Unit = {},
    ) {
        if (busy) return
        error = null
        busy = true
        scope.launch {
            try {
                val ok = runCommand(action.command)
                if (ok) journal.record(action, undo)
                onDone(ok)
            } finally {
                busy = false
            }
        }
    }

    /** 撤销通道：执行逆动作但不入日志 —— 撤销动作再入日志会无限乒乓。 */
    fun executeUnrecorded(
        scope: CoroutineScope,
        action: SysAction,
        onDone: suspend (Boolean) -> Unit = {},
    ) {
        if (busy) return
        error = null
        busy = true
        scope.launch {
            try {
                onDone(runCommand(action.command))
            } finally {
                busy = false
            }
        }
    }

    /**
     * 批量顺序执行（「回收全部已授予」这类成组动作）：busy 单旗全程持有，
     * 逐条走 runCommand、成功逐条入日志；首条失败即中断后续（已成功条目
     * 照常入日志可逐条撤销，失败详情经 error 如实回报 —— 绝不把半程失败
     * 伪装成整体成功）。为什么不用循环调 execute：它是异步返回的，连发
     * 会被 busy 守卫丢弃，只剩第一条真执行。
     */
    fun executeAll(
        scope: CoroutineScope,
        actions: List<Pair<SysAction, SysAction?>>,
        onDone: suspend (Boolean) -> Unit = {},
    ) {
        if (busy || actions.isEmpty()) return
        error = null
        busy = true
        scope.launch {
            try {
                var allOk = true
                for ((action, undo) in actions) {
                    if (!runCommand(action.command)) {
                        allOk = false
                        break
                    }
                    journal.record(action, undo)
                }
                onDone(allOk)
            } finally {
                busy = false
            }
        }
    }

    /** 只读查询：成功返回 stdout（trim），失败置 error 返回 null —— 绝不猜。 */
    suspend fun query(command: String): String? =
        gateway.exec(command).fold(
            onSuccess = { r -> if (r.isSuccess) r.stdout.trim() else null },
            onFailure = { e ->
                error = e.message ?: e.javaClass.simpleName
                null
            },
        )

    /** 进页面全量回读（busy 全程持有，与写动作互斥）。 */
    suspend fun exclusive(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        try {
            block()
        } finally {
            busy = false
        }
    }

    private suspend fun runCommand(command: String): Boolean =
        gateway.exec(command).fold(
            onSuccess = { r ->
                if (r.isSuccess) {
                    true
                } else {
                    error = "系统拒绝（退出码 ${r.exitCode}）：" +
                        r.stderr.ifBlank { r.stdout }.trim().take(200)
                    false
                }
            },
            onFailure = { e ->
                error = e.message ?: e.javaClass.simpleName
                false
            },
        )
}

/** 通用卡容器（视觉同 disguise 卡片家族：surfaceVariant 半透明）。 */
@Composable
internal fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
    ) {
        content()
    }
}

/** 已安装应用条目（选择器数据；图标按 packageName 惰性转换）。 */
internal data class InstalledAppInfo(
    val packageName: String,
    val label: String,
)

/** 页头（返回 + 标题；视觉与 disguise 的 SpoofHeader 同构）。 */
@Composable
internal fun SysControlHeader(onBack: () -> Unit) {
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
            text = "系统控制中心",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Shizuku 门禁卡：未运行 / 未授权时置顶展示（errorContainer 引导 + 直达
 * 两个修复动作）。已授权则不渲染（不打扰）。页面分区照常展示 —— 动作会
 * 以 ShizukuUnavailableException 失败并经 inline 错误条如实回报。
 */
@Composable
internal fun ShizukuGateCard(
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
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f))
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
            text = "系统控制中心的全部动作经 Shizuku 以 adb shell 上下文执行（免 Root）。" +
                if (running == null) {
                    "请先启动 Shizuku（推荐「无线调试」激活），装好后回来继续。"
                } else {
                    "授权一次，全功能开放。"
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
        )
        Spacer(Modifier.size(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRequestPermission) { Text("授权") }
            TextButton(onClick = onOpenShizuku) { Text("打开 Shizuku") }
        }
    }
}

/** inline 错误条（唯一失败出口：异常 message / 系统原始输出摘要）。 */
@Composable
internal fun InlineErrorChip(message: String?) {
    if (message == null) return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** 敏感 op 的人话标签（授权面板 / 敏感操作记录面板共用，展示序 = INTERESTING_OPS 顺序）。 */
private val OP_LABELS: Map<String, String> = mapOf(
    "CAMERA" to "相机",
    "MICROPHONE" to "麦克风",
    "FINE_LOCATION" to "精确定位",
    "COARSE_LOCATION" to "粗略定位",
    "SYSTEM_ALERT_WINDOW" to "悬浮窗",
    "RUN_IN_BACKGROUND" to "后台运行",
    "WAKE_LOCK" to "唤醒锁",
    "START_FOREGROUND" to "前台服务",
    "PLAY_AUDIO" to "播放音频",
    "VIBRATE" to "振动",
    "READ_CLIPBOARD" to "读取剪贴板",
    "POST_NOTIFICATION" to "通知",
)

internal fun opLabel(op: String): String = OP_LABELS[op] ?: op

/** 常见危险权限人话（授权面板 / 权限审计面板共用；未命中回退尾段，原文始终完整展示在 supporting）。 */
internal fun permLabel(perm: String): String = when (perm) {
    "android.permission.CAMERA" -> "相机"
    "android.permission.RECORD_AUDIO" -> "麦克风"
    "android.permission.ACCESS_FINE_LOCATION" -> "精确定位"
    "android.permission.ACCESS_COARSE_LOCATION" -> "粗略定位"
    "android.permission.ACCESS_BACKGROUND_LOCATION" -> "后台定位"
    "android.permission.READ_CONTACTS" -> "读取联系人"
    "android.permission.WRITE_CONTACTS" -> "写入联系人"
    "android.permission.READ_CALENDAR" -> "读取日历"
    "android.permission.WRITE_CALENDAR" -> "写入日历"
    "android.permission.READ_CALL_LOG" -> "读取通话记录"
    "android.permission.READ_PHONE_STATE" -> "读取手机状态"
    "android.permission.CALL_PHONE" -> "拨打电话"
    "android.permission.READ_SMS" -> "读取短信"
    "android.permission.SEND_SMS" -> "发送短信"
    "android.permission.RECEIVE_SMS" -> "接收短信"
    "android.permission.READ_EXTERNAL_STORAGE" -> "读取外部存储"
    "android.permission.WRITE_EXTERNAL_STORAGE" -> "写入外部存储"
    "android.permission.BODY_SENSORS" -> "身体传感器"
    "android.permission.ACTIVITY_RECOGNITION" -> "活动识别"
    else -> perm.substringAfterLast('.')
}

/**
 * 授权总管入口卡：系统授权的深度利用 —— 对任意应用的敏感 AppOps 与
 * 运行时权限做 allow / ignore / default 直改（adb 官方通道，免 Root）。
 * 已装应用清单 + 搜索；点击应用进授权面板（BottomSheet）。
 */
@Composable
internal fun AuthorizationPanel(
    runner: SysRunner,
    onAppSelected: (InstalledAppInfo) -> Unit,
) {
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<InstalledAppInfo>?>(null) } // null = 加载中

    // 清单一次全量拉取（IO 线程；targetSdk 28 无包可见性过滤，全量可达）
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
    }

    SectionCard {
        Text(
            text = "授权总管",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "系统授权的深度利用：对任意应用的敏感 AppOps 与运行时权限做 " +
                "allow / ignore / default 直改（adb 官方通道，免 Root），先选择目标应用",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(10.dp))
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
                            .heightIn(max = 360.dp),
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
}

/** 应用清单行：圆形图标（转换失败/无图标 → 首字母占位）+ 名称 + 包名（授权总管 / 隐私与管控各选择器共用）。 */
@Composable
internal fun AppRow(app: InstalledAppInfo, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppLeadingIcon(packageName = app.packageName, label = app.label)
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(text = app.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 应用图标：loadIcon 的 Drawable → 位图（remember 按 packageName 缓存，
 * 只做一次廉价转换）；失败 / 无图标 → primaryContainer 首字母圆 ——
 * 绝不因图标问题丢行。
 */
@Composable
private fun AppLeadingIcon(packageName: String, label: String) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val bitmap = remember(packageName, density) {
        runCatching {
            val px = with(density) { 36.dp.toPx() }.toInt().coerceAtLeast(1)
            val icon: Drawable = context.packageManager.getApplicationIcon(packageName)
            icon.toBitmap(px, px).asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape),
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            Text(
                text = label.firstOrNull()?.toString() ?: "?",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * 单应用授权面板（ModalBottomSheet，两节）：
 *  a) 敏感操作（AppOps）—— 12 项 INTERESTING_OPS 逐项查询 + MoreVert
 *     三态直改（改完自动回读系统真值）；
 *  b) 危险运行时权限 —— pm grant / revoke，非法动作就地禁用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppAuthorizationSheet(
    app: InstalledAppInfo,
    runner: SysRunner,
    onDismiss: () -> Unit,
    onJournalChanged: () -> Unit,
) {
    val context = LocalContext.current
    val sheetScope = rememberCoroutineScope()

    var opModes by remember(app.packageName) { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var permStates by remember(app.packageName) { mutableStateOf<Map<String, Boolean?>>(emptyMap()) }
    var dangerousPerms by remember(app.packageName) { mutableStateOf<List<String>?>(null) }

    // 打开即顺序查询：12 项 op + 权限清单（IO）后逐项 check —— 坏一项显示「未知」
    LaunchedEffect(app.packageName) {
        opModes = emptyMap()
        permStates = emptyMap()
        for (op in AppOpsCommands.INTERESTING_OPS) {
            val output = runner.query(AppOpsCommands.query(app.packageName, op))
            opModes = opModes + (op to output?.let { AppOpsCommands.parseMode(it) })
        }
        val perms = withContext(Dispatchers.IO) {
            requestedDangerousPermissions(context.packageManager, app.packageName)
        }
        dangerousPerms = perms
        for (perm in perms) {
            val output = runner.query(RuntimePermCommands.check(app.packageName, perm))
            permStates = permStates + (perm to output?.let { RuntimePermCommands.parseGranted(it) })
        }
    }

    fun applyOp(op: String, mode: AppOpsMode) {
        // 逆动作：改回查询到的旧模式；旧态是 foreground / 未知等不可直设值 → 无撤销（诚实）
        val undoMode = AppOpsMode.entries.firstOrNull { it.cli == opModes[op] }
        runner.execute(
            sheetScope,
            SysAction("${opLabel(op)}改${mode.label}", AppOpsCommands.state(app.packageName, op, mode)),
            undoMode?.let {
                SysAction("${opLabel(op)}改${it.label}", AppOpsCommands.state(app.packageName, op, it))
            },
        ) { ok ->
            if (ok) {
                onJournalChanged()
                val output = runner.query(AppOpsCommands.query(app.packageName, op))
                opModes = opModes + (op to output?.let { AppOpsCommands.parseMode(it) })
            }
        }
    }

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
            SysAction("$verb ${permLabel(perm)}", command),
            SysAction("${if (grant) "回收" else "授予"} ${permLabel(perm)}", undoCommand),
        ) { ok ->
            if (ok) {
                onJournalChanged()
                val output = runner.query(RuntimePermCommands.check(app.packageName, perm))
                permStates = permStates + (perm to output?.let { RuntimePermCommands.parseGranted(it) })
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(12.dp))
            Text(
                text = "敏感操作（AppOps）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "allow = 显式放行，ignore = 显式拒绝，" +
                    "default = 归还系统默认（改动即时生效，重启不丢失）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(6.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                items(AppOpsCommands.INTERESTING_OPS) { op ->
                    AppOpsRow(
                        op = op,
                        mode = opModes[op],
                        busy = runner.busy,
                        onApply = { mode -> applyOp(op, mode) },
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            HorizontalDivider()
            Spacer(Modifier.size(12.dp))
            Text(
                text = "危险运行时权限（pm grant / revoke）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "只列 manifest 声明的危险权限（授予 / 回收即写即生效）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(6.dp))
            when (val perms = dangerousPerms) {
                null -> Text(
                    text = "危险权限清单读取中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    if (perms.isEmpty()) {
                        Text(
                            text = "该应用未声明危险权限",
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
                            items(perms) { perm ->
                                PermRow(
                                    perm = perm,
                                    state = permStates[perm],
                                    busy = runner.busy,
                                    onGrant = { applyPerm(perm, true) },
                                    onRevoke = { applyPerm(perm, false) },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.size(20.dp))
        }
    }
}

/** 单项 AppOps 行：状态徽章 + 人话标签 + MoreVert 三态下拉直改。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppOpsRow(
    op: String,
    mode: String?,
    busy: Boolean,
    onApply: (AppOpsMode) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    ListItem(
        leadingContent = {
            Badge(
                containerColor = when (mode) {
                    "allow" -> MaterialTheme.colorScheme.primaryContainer
                    "ignore" -> MaterialTheme.colorScheme.errorContainer
                    "default" -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.outlineVariant
                },
                contentColor = when (mode) {
                    "allow" -> MaterialTheme.colorScheme.onPrimaryContainer
                    "ignore" -> MaterialTheme.colorScheme.onErrorContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) {
                Text(mode ?: "未知")
            }
        },
        headlineContent = { Text(opLabel(op)) },
        supportingContent = { Text("当前: ${mode ?: "未知"}") },
        trailingContent = {
            Box {
                IconButton(onClick = { menuExpanded = true }, enabled = !busy) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "选择模式",
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    AppOpsMode.entries.forEach { m ->
                        DropdownMenuItem(
                            text = { Text("${m.label}（${m.cli}）") },
                            onClick = {
                                menuExpanded = false
                                onApply(m)
                            },
                        )
                    }
                }
            }
        },
    )
}

/** 单项危险权限行：状态 + 授予 / 回收（非法动作就地禁用而非丢给系统报错）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermRow(
    perm: String,
    state: Boolean?,
    busy: Boolean,
    onGrant: () -> Unit,
    onRevoke: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(permLabel(perm)) },
        supportingContent = {
            Text("$perm · 当前: " + when (state) {
                true -> "已授予"
                false -> "未授予"
                null -> "未知"
            })
        },
        trailingContent = {
            Row {
                // pm grant 对已授予权限直接报错（pm revoke 对未授予同理）——
                // 非法动作就地禁用：只留合法方向，不把注定失败的命令丢给系统
                TextButton(onClick = onGrant, enabled = !busy && state == false) { Text("授予") }
                TextButton(onClick = onRevoke, enabled = !busy && state == true) { Text("回收") }
            }
        },
    )
}

/** 已装应用清单（一次全量；单包 label 读取失败回退包名，不丢行；授权总管 / 隐私与管控各面板共用）。 */
internal fun loadInstalledApps(context: Context): List<InstalledAppInfo> {
    val pm = context.packageManager
    return runCatching {
        pm.getInstalledApplications(0).map { app ->
            InstalledAppInfo(
                packageName = app.packageName,
                label = runCatching { app.loadLabel(pm).toString() }.getOrDefault(app.packageName),
            )
        }.sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
    }.getOrDefault(emptyList())
}

/**
 * manifest 声明的危险权限清单：requestedPermissions 逐条过
 * getPermissionInfo().protectionLevel == PROTECTION_DANGEROUS 门禁 ——
 * 未知权限（NameNotFoundException）按非危险处理（fail-closed）。
 */
private fun requestedDangerousPermissions(pm: PackageManager, packageName: String): List<String> {
    val packageInfo = runCatching {
        pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
    }.getOrNull() ?: return emptyList()
    val requested = packageInfo.requestedPermissions ?: return emptyList()
    return requested.filter { perm ->
        runCatching {
            pm.getPermissionInfo(perm, 0).protectionLevel == PermissionInfo.PROTECTION_DANGEROUS
        }.getOrDefault(false)
    }.sorted()
}
