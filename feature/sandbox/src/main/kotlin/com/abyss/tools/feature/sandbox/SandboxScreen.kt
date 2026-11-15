package com.abyss.tools.feature.sandbox

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.abyss.tools.core.common.UiState
import com.abyss.tools.core.designsystem.component.ChipTone
import com.abyss.tools.core.designsystem.component.EmptyState
import com.abyss.tools.core.designsystem.component.ErrorState
import com.abyss.tools.core.designsystem.component.LoadingState
import com.abyss.tools.core.designsystem.component.SectionHeader
import com.abyss.tools.core.designsystem.component.StatusChip
import com.abyss.tools.core.shell.ShellGateway
import com.abyss.tools.core.shell.ShizukuStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── 模块内常量 / 数据载体（Screen 专用；命令与解析在 SandboxCommands）──────────

/** 创建空间名称长度上界（人话长度：系统用户切换器里的显示宽度）。 */
private const val MAX_NAME_LENGTH = 24

/** 会话动作日志只保留最近 50 条（内存态，退出页面即清空，绝不落盘假装持久）。 */
private const val MAX_LOG_ENTRIES = 50

/**
 * 系统侧用户名集合（个别 ROM 的预置用户形态）。id 判定为主、名称为辅 ——
 * 两道都过才算「可移除的隔离空间」。
 */
private val SYSTEM_USER_NAMES = setOf("Owner", "owner")

/**
 * 双开候选应用（第三方，FLAG_SYSTEM == 0）。
 *
 * 注意：[icon] 是 Drawable（引用语义），**不要**用本类的 equals 比较两个
 * 实例 —— 一律以 [packageName] 为唯一键（appmgr 的 AbyssAppInfo 同款纪律）。
 */
private data class SandboxApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
)

/** 动作反馈（成功 / 失败 + ≤ 2 行系统原文摘要 —— 诚实展示，不吞错）。 */
private data class ActionFeedback(
    val message: String,
    val isError: Boolean = false,
    val detail: String? = null,
)

/** 会话动作日志条目（time = 追加时刻的 HH:mm:ss）。 */
private data class SessionLogEntry(
    val time: String,
    val action: String,
    val ok: Boolean,
)

/**
 * 系统侧用户判定。为什么 id < 10：Android 全用户 id 从 10 起（pm create-user
 * 首个新用户即 10），0..9 是系统保留段 —— 0 就是 Owner。名称命中已知系统名
 * 同样视为系统侧（个别 ROM 预置用户的兜底）。
 *
 * 系统侧行只给「拉起 / 切换」，**永不给移除入口**：拆 Owner = 拆掉整台机的
 * 数据主体（/data/user/0 就是全部主用户数据）。UI 不渲染入口 + 命令层
 * removeUser 的 require 兜底，双层防御。
 */
private fun SandboxCommands.AndroidUser.isSystemSideUser(): Boolean =
    id < 10 || name in SYSTEM_USER_NAMES

/**
 * 系统原文摘录（错误 chip 里最多 2 行）：空行不挤占名额、超长行截断到
 * 160 字符。原文为空 → null（不编造「无详情」）。
 */
private fun excerptLines(
    raw: String,
    maxLines: Int = 2,
    maxCharsPerLine: Int = 160,
): String? {
    val lines = raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .take(maxLines)
        .map { it.take(maxCharsPerLine) }
        .toList()
    return if (lines.isEmpty()) null else lines.joinToString(separator = "\n")
}

/** 日志时间戳（HH:mm:ss —— 会话内足够，不需要日期）。 */
private fun formatLogTime(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))

/**
 * 读取本机第三方应用（双开候选清单）。本地 PackageManager 读取 —— 不依赖
 * Shizuku（未授权也能浏览应用）。
 *
 * 坏包纪律（fail-closed，appmgr 的 PackageManagerProvider 同款）：每个包的
 * 转换都在 runCatching 内 —— loadLabel / loadIcon 这两个官方文档明示可能抛
 * 异常的读取点单点兜底（label 回退包名、icon 置 null 交首字占位），结构性坏
 * 包整包跳过，单个包坏绝不拖垮整区。
 */
private suspend fun loadThirdPartyApps(context: Context): List<SandboxApp> =
    withContext(Dispatchers.IO) {
        val pm = context.packageManager
        // 为什么是 GET_META_DATA 且容忍 deprecation：getInstalledApplications 全族
        // 在 API 33 起标 deprecated，但 minSdk 26 → 33 之间它是唯一通道；GET_META_DATA
        // 维持经典语义（spec 钉死）。targetSdk 28（本仓钉死）不受 Android 11+ 包
        // 可见性过滤影响 —— appmgr 同款结论。
        @Suppress("DEPRECATION")
        val installed: List<ApplicationInfo> =
            pm.getInstalledApplications(PackageManager.GET_META_DATA).orEmpty()
        installed.asSequence()
            // 只列第三方（双开对象）：FLAG_SYSTEM == 0 才是用户自装应用
            .filter { info -> (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .mapNotNull { info ->
                runCatching {
                    SandboxApp(
                        packageName = info.packageName,
                        label = info.loadLabel(pm).toString(),
                        icon = info.loadIcon(pm),
                    )
                }.getOrNull()
            }
            .filter { it.packageName.isNotBlank() }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
            .toList()
    }

// ── 入口 ─────────────────────────────────────────────────────────────

/**
 * 隔离空间 —— Android 原生多用户引擎（pm create-user / pm install --user）。
 *
 * 能力与诚实边界（页内同步展示）：
 *  - 创建独立用户空间即得「第二套系统」，应用注册进空间即双开 —— 数据天然
 *    隔离（各自的 /data/user/<id>）；经 Shizuku 免 Root 执行；
 *  - 部分 ROM（厂商阉割）禁用多用户 → createUser 直接失败，stderr 原文展示；
 *  - VirtualApp 级免安装运行与 Hook 层属沙箱插件（路线图），本页不伪装具备。
 *
 * 状态纪律：单一 busy 旗（Shizuku 单命令通道串行）；用户列表走 UiState 三态；
 * 未知值（当前用户 / 空名）显示「未知」或留空标记，绝不显示猜测值。
 */
@Composable
fun SandboxScreen(
    shizukuStatus: ShizukuStatus,
    gateway: ShellGateway,
    onRequestShizukuPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shizukuAuthorized = (shizukuStatus as? ShizukuStatus.Running)?.authorized == true

    var usersState by remember {
        mutableStateOf<UiState<List<SandboxCommands.AndroidUser>>>(UiState.Loading)
    }
    /** am get-current-user 是否读失败（真话提示：「当前」标记本回合不显示，不猜）。 */
    var currentReadFailed by remember { mutableStateOf(false) }
    var appsState by remember { mutableStateOf<UiState<List<SandboxApp>>>(UiState.Loading) }
    var appQuery by remember { mutableStateOf("") }
    var newUserName by remember { mutableStateOf("深渊空间") }
    var targetUserId by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<SandboxCommands.AndroidUser?>(null) }
    var sessionLog by remember { mutableStateOf<List<SessionLogEntry>>(emptyList()) }
    var usersFeedback by remember { mutableStateOf<ActionFeedback?>(null) }
    var createFeedback by remember { mutableStateOf<ActionFeedback?>(null) }
    var twinFeedback by remember { mutableStateOf<ActionFeedback?>(null) }

    /** 追加会话动作日志（头插，最新在前；只裁剪不改写历史 —— 成败原样保留）。 */
    fun appendLog(action: String, ok: Boolean) {
        val entry = SessionLogEntry(
            time = formatLogTime(System.currentTimeMillis()),
            action = action,
            ok = ok,
        )
        sessionLog = (listOf(entry) + sessionLog).take(MAX_LOG_ENTRIES)
    }

    /**
     * 刷新用户列表（读侧两步顺序执行）：先 am get-current-user（isCurrent 的
     * 唯一真源），再 pm list users。取不到当前 id → UNKNOWN 哨兵（不标任何
     * 「当前」，currentReadFailed 置真如实提示）—— 绝不猜 0。
     */
    fun refresh() {
        currentReadFailed = false
        usersState = UiState.Loading
        scope.launch {
            var currentId = SandboxCommands.UNKNOWN_USER_ID
            gateway.exec(SandboxCommands.getCurrentUser())
                .onSuccess { result ->
                    if (result.isSuccess) {
                        val parsed = SandboxCommands.parseCurrentUserId(result.stdout)
                        if (parsed != null) currentId = parsed else currentReadFailed = true
                    } else {
                        currentReadFailed = true
                    }
                }
                .onFailure { currentReadFailed = true }
            // 当前 id 读不到也要继续列用户（诚实降级：列表照真，仅「当前」标记缺失）
            gateway.exec(SandboxCommands.listUsers())
                .onSuccess { result ->
                    usersState = if (result.isSuccess) {
                        UiState.Data(SandboxCommands.parseUsers(result.stdout, currentId))
                    } else {
                        val failText = result.stderr.ifBlank { result.stdout }.trim()
                        UiState.Error(
                            failText.ifBlank { "pm list users 失败（退出码 ${result.exitCode}）" },
                        )
                    }
                }
                .onFailure { e ->
                    usersState = UiState.Error(e.message ?: e.javaClass.simpleName)
                }
        }
    }

    /**
     * 单命令执行（单一 busy 旗 —— 全部写动作互斥，Shizuku 通道一次一条）。
     * onDone 收到（ok, 原文）：成功取 stdout（create 需从中解析新 id）、失败取
     * stderr（空则 stdout）；原文不在此裁剪 —— 展示层 excerptLines 再摘 ≤ 2 行。
     */
    fun runCommand(command: String, actionLabel: String, onDone: (ok: Boolean, output: String) -> Unit) {
        if (busy || !shizukuAuthorized) return
        busy = true
        scope.launch {
            var ok = false
            var output = ""
            gateway.exec(command)
                .onSuccess { result ->
                    ok = result.isSuccess
                    output = if (ok) result.stdout else result.stderr.ifBlank { result.stdout }
                }
                .onFailure { e ->
                    ok = false
                    output = e.message ?: e.javaClass.simpleName
                }
            busy = false
            appendLog(actionLabel, ok)
            onDone(ok, output)
        }
    }

    /** 应用候选清单（本地读取，不依赖 Shizuku）。 */
    fun loadApps() {
        appsState = UiState.Loading
        scope.launch {
            appsState = runCatching { loadThirdPartyApps(context) }.fold(
                onSuccess = { UiState.Data(it) },
                onFailure = { UiState.Error(it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    // 进入页面即拉应用清单（本地）；Shizuku 授权态变化时读 / 重读用户列表。
    LaunchedEffect(Unit) { loadApps() }
    LaunchedEffect(shizukuAuthorized) {
        if (shizukuAuthorized) refresh() else usersState = UiState.Loading
    }

    val users: List<SandboxCommands.AndroidUser> = when (val state = usersState) {
        is UiState.Data -> state.value
        else -> emptyList()
    }
    // 双开目标 = 非当前 且 非系统侧（绝不向 Owner / 系统保留段注册应用）
    val eligibleTargets = users.filter { !it.isCurrent && !it.isSystemSideUser() }
    val eligibleIds = eligibleTargets.map { it.id }
    LaunchedEffect(eligibleIds) {
        // 选中目标失效（空间被移除 / 列表刷新）→ 回落首个可用；无可用 → 清空
        if (targetUserId == null || targetUserId !in eligibleIds) {
            targetUserId = eligibleIds.firstOrNull()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        SandboxHeader(title = "隔离空间", busy = busy, onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Shizuku 门禁（未运行 / 未授权时置顶；已授权不渲染，不打扰）──
            item {
                ShizukuGateCard(
                    shizukuStatus = shizukuStatus,
                    onRequestPermission = onRequestShizukuPermission,
                    onOpenShizuku = onOpenShizuku,
                )
            }

            // ── 诚实说明卡 ─────────────────────────────────────────────
            item { IntroCard() }

            // ── 用户空间区 ─────────────────────────────────────────────
            item {
                SectionHeader(
                    title = "用户空间",
                    subtitle = "Android 原生多用户 · 各空间数据天然隔离（/data/user/<id>）",
                    trailing = {
                        IconButton(onClick = { if (!busy) refresh() }, enabled = !busy) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "刷新用户列表",
                            )
                        }
                    },
                )
            }
            when (val state = usersState) {
                is UiState.Loading -> {
                    if (shizukuAuthorized) {
                        item { LoadingState(message = "正在读取用户空间…") }
                    } else {
                        item { NotAuthorizedHintCard() }
                    }
                }

                is UiState.Error -> item {
                    ErrorState(
                        message = "读取用户空间失败：${state.message}",
                        onRetry = { refresh() },
                    )
                }

                is UiState.Data -> {
                    if (state.value.isEmpty()) {
                        item {
                            EmptyState(
                                title = "用户列表为空",
                                message = "pm list users 输出不含 UserInfo 行（ROM 输出形态异常）—— 可点右上角刷新重试。",
                            )
                        }
                    } else {
                        items(state.value, key = { it.id }) { user ->
                            UserRow(
                                user = user,
                                busy = busy,
                                authorized = shizukuAuthorized,
                                onStart = {
                                    runCommand(
                                        SandboxCommands.startUser(user.id),
                                        "拉起空间 ${user.id}",
                                    ) { ok, output ->
                                        usersFeedback = if (ok) {
                                            ActionFeedback(
                                                "已请求拉起「${user.name.ifBlank { "未知" }}」(id=${user.id}) —— 异步不锁前台，稍后自动刷新",
                                            )
                                        } else {
                                            ActionFeedback(
                                                "拉起失败（系统原文见下）",
                                                isError = true,
                                                detail = excerptLines(output),
                                            )
                                        }
                                        if (ok) {
                                            scope.launch {
                                                delay(1200)
                                                refresh()
                                            }
                                        }
                                    }
                                },
                                onSwitch = {
                                    runCommand(
                                        SandboxCommands.switchUser(user.id),
                                        "切换到空间 ${user.id}",
                                    ) { ok, output ->
                                        usersFeedback = if (ok) {
                                            ActionFeedback(
                                                "已请求切换到「${user.name.ifBlank { "未知" }}」—— 系统将进入该用户的解锁界面，本应用随之退到后台",
                                            )
                                        } else {
                                            ActionFeedback(
                                                "切换失败（系统原文见下）",
                                                isError = true,
                                                detail = excerptLines(output),
                                            )
                                        }
                                    }
                                },
                                onStop = {
                                    runCommand(
                                        SandboxCommands.stopUser(user.id),
                                        "停止空间 ${user.id}",
                                    ) { ok, output ->
                                        usersFeedback = if (ok) {
                                            ActionFeedback(
                                                "已停止「${user.name.ifBlank { "未知" }}」(id=${user.id}) —— -w 等待完成，状态即真值",
                                            )
                                        } else {
                                            ActionFeedback(
                                                "停止失败（系统原文见下）",
                                                isError = true,
                                                detail = excerptLines(output),
                                            )
                                        }
                                        if (ok) {
                                            scope.launch { refresh() }
                                        }
                                    }
                                },
                                onRequestRemove = { removeTarget = user },
                            )
                        }
                        if (currentReadFailed) {
                            item {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    StatusChip(
                                        text = "「当前」标记不可用（am get-current-user 未成功）—— 不猜",
                                        tone = ChipTone.WARN,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            usersFeedback?.let { feedback ->
                item { FeedbackContent(feedback = feedback) }
            }

            // ── 创建空间 ─────────────────────────────────────────────
            item {
                CreateSpaceCard(
                    name = newUserName,
                    onNameChange = { value ->
                        if (value.length <= MAX_NAME_LENGTH) newUserName = value
                    },
                    busy = busy,
                    authorized = shizukuAuthorized,
                    onCreate = {
                        val name = newUserName.trim()
                        runCommand(
                            SandboxCommands.createUser(name),
                            "创建空间「$name」",
                        ) { ok, output ->
                            createFeedback = if (ok) {
                                val newId = SandboxCommands.parseCreatedUserId(output)
                                if (newId != null) {
                                    ActionFeedback("已创建空间「$name」· id=$newId")
                                } else {
                                    // 成功但解析不出 id：不猜 —— 如实说输出形态异常并附原文
                                    ActionFeedback(
                                        "创建命令已执行，但未从输出解析到新空间 id（输出形态异常）",
                                        detail = excerptLines(output),
                                    )
                                }
                            } else {
                                ActionFeedback(
                                    "创建失败（部分 ROM 禁用多用户 —— 系统原文见下）",
                                    isError = true,
                                    detail = excerptLines(output),
                                )
                            }
                            if (ok) refresh()
                        }
                    },
                    feedback = createFeedback,
                )
            }

            // ── 应用双开区 ─────────────────────────────────────────────
            item {
                SectionHeader(
                    title = "应用双开",
                    subtitle = "注册本机已装应用进隔离空间 —— 第二份独立数据",
                )
            }
            item {
                TwinAppCard(
                    eligibleTargets = eligibleTargets,
                    targetUserId = targetUserId,
                    appsState = appsState,
                    query = appQuery,
                    onQueryChange = { appQuery = it },
                    onTargetSelect = { targetUserId = it },
                    onReloadApps = { loadApps() },
                    busy = busy,
                    authorized = shizukuAuthorized,
                    onInstall = { app ->
                        val target = targetUserId
                        if (target != null) {
                            runCommand(
                                SandboxCommands.installExisting(app.packageName, target),
                                "双开 ${app.packageName} → 空间 $target",
                            ) { ok, output ->
                                twinFeedback = if (ok) {
                                    ActionFeedback("已注册到空间 — 在该用户桌面可见")
                                } else {
                                    ActionFeedback(
                                        "注册失败（部分 ROM 的 pm 子命令形态不同 —— 系统原文见下）",
                                        isError = true,
                                        detail = excerptLines(output),
                                    )
                                }
                            }
                        }
                    },
                    onUninstall = { app ->
                        val target = targetUserId
                        if (target != null) {
                            runCommand(
                                SandboxCommands.uninstallForUser(app.packageName, target),
                                "从空间移除 ${app.packageName}",
                            ) { ok, output ->
                                twinFeedback = if (ok) {
                                    ActionFeedback(
                                        "已从空间 $target 移除「${app.label}」—— 该空间内的副本与数据一并清除，主用户不受影响",
                                    )
                                } else {
                                    ActionFeedback(
                                        "移除失败（系统原文见下）",
                                        isError = true,
                                        detail = excerptLines(output),
                                    )
                                }
                            }
                        }
                    },
                    feedback = twinFeedback,
                )
            }

            // ── 会话动作日志 ──────────────────────────────────────────
            item {
                SectionHeader(
                    title = "本次会话动作日志",
                    subtitle = "仅内存留存 · 退出页面即清空（不落盘不夸大持久性）",
                )
            }
            item { SessionLogCard(entries = sessionLog) }
        }
    }

    // ── 移除用户确认弹窗（仅隔离空间可达；Owner / 系统侧入口不渲染）────────
    removeTarget?.let { user ->
        RemoveUserDialog(
            user = user,
            busy = busy,
            onDismiss = { removeTarget = null },
            onConfirm = {
                removeTarget = null
                runCommand(
                    SandboxCommands.removeUser(user.id),
                    "移除空间 ${user.id}",
                ) { ok, output ->
                    usersFeedback = if (ok) {
                        ActionFeedback(
                            "已移除「${user.name.ifBlank { "未知" }}」(id=${user.id}) —— 空间数据目录一并删除",
                        )
                    } else {
                        ActionFeedback(
                            "移除失败（系统原文见下）",
                            isError = true,
                            detail = excerptLines(output),
                        )
                    }
                    if (ok) refresh()
                }
            },
        )
    }
}

// ── 私有组件 ─────────────────────────────────────────────────────────

/** 页头（返回 + 标题 + 状态徽章；busy 时徽章如实换「执行中…」）。 */
@Composable
private fun SandboxHeader(title: String, busy: Boolean, onBack: () -> Unit) {
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
        if (busy) {
            StatusChip(text = "执行中…", tone = ChipTone.NEUTRAL)
        } else {
            StatusChip(text = "主壳内置 · Shizuku 免 Root", tone = ChipTone.OK)
        }
    }
}

/** 通用卡容器（视觉同 disguise / appmgr 的卡片家族：surfaceVariant 半透明）。 */
@Composable
private fun SandboxCard(
    modifier: Modifier = Modifier,
    tone: ChipTone? = null,
    content: @Composable ColumnScope.() -> Unit,
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
        content = content,
    )
}

/**
 * Shizuku 门禁卡（镜像 BatterySpoofScreen 的 ShizukuGuardBanner）：
 * 未运行 → TextButton 打开 Shizuku；已运行未授权 → Button 授权 + TextButton
 * 打开 Shizuku。已授权不渲染（不打扰）。
 */
@Composable
private fun ShizukuGateCard(
    shizukuStatus: ShizukuStatus,
    onRequestPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
) {
    val running = shizukuStatus as? ShizukuStatus.Running
    if (running?.authorized == true) return
    SandboxCard(tone = ChipTone.WARN) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                tint = ChipTone.WARN.content,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = if (running == null) "Shizuku 未运行" else "Shizuku 未授权",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = ChipTone.WARN.content,
            )
        }
        Spacer(Modifier.size(4.dp))
        Text(
            text = if (running == null) {
                "隔离空间引擎经 Shizuku 以 adb shell 上下文执行（免 Root）。" +
                    "请先启动 Shizuku（推荐「无线调试」激活），再回来继续。" +
                    "应用浏览（本地读取）不受影响。"
            } else {
                "Shizuku 已在运行，但深渊工具还没有获得授权 —— 授权一次，" +
                    "多用户引擎全功能开放。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running != null) {
                Button(onClick = onRequestPermission) { Text("去授权") }
            }
            TextButton(onClick = onOpenShizuku) { Text("打开 Shizuku") }
        }
    }
}

/** 未授权时的用户区占位说明（不转圈 —— 没在加载，是没授权）。 */
@Composable
private fun NotAuthorizedHintCard() {
    SandboxCard {
        Text(
            text = "Shizuku 授权后自动读取用户空间（顶部卡片可直达授权）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 诚实说明卡（引擎是什么 · 边界在哪 —— 文案与产品口径一字不差）。 */
@Composable
private fun IntroCard() {
    SandboxCard {
        Text(
            text = "这是什么",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "基于 Android 原生多用户的隔离引擎（Shizuku 免 Root）：创建独立用户空间即得" +
                "「第二套系统」，应用注册进空间即双开 —— 数据天然隔离（各自的 /data/user/<id>）。" +
                "诚实边界：部分 ROM（厂商阉割）禁用多用户，createUser 会直接失败并如实展示错误；" +
                "VirtualApp 级免安装运行与 Hook 层属沙箱插件（路线图）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 用户行：headline = 名字、supporting = id + 角色、trailing = 运行态 Badge +
 * 「当前」StatusChip +（非当前用户的）动作区。
 *
 * 动作可见性（诚实边界）：
 *  - 当前用户：无动作（不能对自己启动 / 切换 / 停止）；
 *  - 非当前系统侧用户（id < 10 或已知系统名）：只给 启动 / 切换 —— **永不给
 *    移除**（拆 Owner = 拆掉整台机的数据主体；AOSP 的 pm 会拒绝，但 UI 与
 *    命令层双层防御，绝不依赖系统兜底）；
 *  - 非当前隔离空间：启动 / 切换 / 更多菜单（停止 · 移除用户，移除走确认弹窗）。
 */
@OptIn(ExperimentalMaterial3Api::class) // ListItem / Badge：material3 实验标记按需 OptIn
@Composable
private fun UserRow(
    user: SandboxCommands.AndroidUser,
    busy: Boolean,
    authorized: Boolean,
    onStart: () -> Unit,
    onSwitch: () -> Unit,
    onStop: () -> Unit,
    onRequestRemove: () -> Unit,
) {
    val systemSide = user.isSystemSideUser()
    var menuExpanded by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = {
            Text(
                text = user.name.ifBlank { "未知" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = "id=${user.id} · " + if (systemSide) "系统用户" else "隔离空间",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 运行态 Badge：running = 主题容器高亮、stopped = 中性灰
                    // （颜色只取 MaterialTheme.colorScheme，不手写色彩）
                    Badge(
                        containerColor = if (user.running) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (user.running) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    ) {
                        Text(
                            text = if (user.running) "运行中" else "已停止",
                            style = MaterialTheme.typography.labelMedium,
                            softWrap = false,
                        )
                    }
                    if (user.isCurrent) {
                        StatusChip(text = "当前", tone = ChipTone.OK)
                    }
                }
                if (!user.isCurrent && authorized) {
                    Spacer(Modifier.size(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        IconButton(onClick = onStart, enabled = !busy) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = "拉起空间",
                            )
                        }
                        TextButton(onClick = onSwitch, enabled = !busy) { Text("切换") }
                        if (!systemSide) {
                            Box {
                                IconButton(
                                    onClick = { menuExpanded = true },
                                    enabled = !busy,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.MoreVert,
                                        contentDescription = "更多操作",
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("停止") },
                                        onClick = {
                                            menuExpanded = false
                                            onStop()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("移除用户") },
                                        onClick = {
                                            menuExpanded = false
                                            onRequestRemove()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

/** 创建空间卡：名称输入（默认「深渊空间」）+ 创建按钮 + 结果反馈。 */
@Composable
private fun CreateSpaceCard(
    name: String,
    onNameChange: (String) -> Unit,
    busy: Boolean,
    authorized: Boolean,
    onCreate: () -> Unit,
    feedback: ActionFeedback?,
) {
    SandboxCard {
        Text(
            text = "创建隔离空间",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "创建即得一套独立「第二系统」（新用户 + 独立数据目录），稍后在「应用双开」区把应用注册进去。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(10.dp))
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("空间名称") },
            singleLine = true,
            supportingText = { Text("支持中文 · 最多 $MAX_NAME_LENGTH 字") },
        )
        Spacer(Modifier.size(10.dp))
        FilledTonalButton(
            onClick = onCreate,
            enabled = !busy && authorized && name.isNotBlank(),
        ) {
            Text("创建隔离空间")
        }
        feedback?.let { fb ->
            Spacer(Modifier.size(8.dp))
            FeedbackContent(feedback = fb)
        }
    }
}

/**
 * 应用双开区：目标空间 FilterChip 行（非当前 · 非系统侧）→ 应用搜索 →
 * 第三方应用列表（LazyColumn 限高 320dp，列表套列表必须有界）→ 逐行
 * 「双开」/「从空间移除」。
 */
@OptIn(ExperimentalMaterial3Api::class) // FilterChip / ListItem：material3 实验标记按需 OptIn
@Composable
private fun TwinAppCard(
    eligibleTargets: List<SandboxCommands.AndroidUser>,
    targetUserId: Int?,
    appsState: UiState<List<SandboxApp>>,
    query: String,
    onQueryChange: (String) -> Unit,
    onTargetSelect: (Int) -> Unit,
    onReloadApps: () -> Unit,
    busy: Boolean,
    authorized: Boolean,
    onInstall: (SandboxApp) -> Unit,
    onUninstall: (SandboxApp) -> Unit,
    feedback: ActionFeedback?,
) {
    val actionsEnabled = targetUserId != null && !busy && authorized
    SandboxCard {
        Text(
            text = "目标空间",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "选择接收双开应用的隔离空间（非当前、非系统侧；应用清单为本地读取）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        if (eligibleTargets.isEmpty()) {
            Text(
                text = "暂无可注册的隔离空间 —— 先在上方创建一个隔离空间。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(eligibleTargets, key = { it.id }) { user ->
                    FilterChip(
                        selected = targetUserId == user.id,
                        onClick = { onTargetSelect(user.id) },
                        label = {
                            Text(text = "${user.name.ifBlank { "未知" }} · ${user.id}")
                        },
                    )
                }
            }
        }
        Spacer(Modifier.size(10.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("搜索应用") },
            placeholder = { Text("应用名或包名") },
            singleLine = true,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                )
            },
        )
        Spacer(Modifier.size(8.dp))
        when (val state = appsState) {
            is UiState.Loading -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = "正在读取第三方应用…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is UiState.Error -> {
                Text(
                    text = "读取应用列表失败：${state.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.size(6.dp))
                TextButton(onClick = onReloadApps) { Text("重试") }
            }

            is UiState.Data -> {
                val keyword = query.trim()
                val visible = state.value.filter { app ->
                    keyword.isEmpty() ||
                        app.label.contains(keyword, ignoreCase = true) ||
                        app.packageName.contains(keyword, ignoreCase = true)
                }
                if (visible.isEmpty()) {
                    EmptyState(
                        title = "没有匹配的第三方应用",
                        message = "换个关键词（应用名 / 包名均可，中英文不限）。",
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                    ) {
                        items(visible, key = { it.packageName }) { app ->
                            AppRow(
                                app = app,
                                enabled = actionsEnabled,
                                onInstall = { onInstall(app) },
                                onUninstall = { onUninstall(app) },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.size(6.dp))
        Text(
            text = "「双开」= 注册本机已装应用进目标空间（免重装 APK、免传输）；系统侧应用不在候选内。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        feedback?.let { fb ->
            Spacer(Modifier.size(8.dp))
            FeedbackContent(
                feedback = fb,
                successNote = if (fb.isError) {
                    null
                } else {
                    "应用首次在空间内启动需初始化数据目录。"
                },
            )
        }
    }
}

/**
 * 应用行（ListItem）：图标位 = 真实应用图标位图（remember(包名) + runCatching，
 * netaudit / appmgr 同款纪律），失败退首字占位方块 —— 绝不因图标问题丢行；
 * trailing = 「双开」TextButton + more_vert 菜单（从空间移除）。
 */
@OptIn(ExperimentalMaterial3Api::class) // ListItem：material3 实验标记按需 OptIn
@Composable
private fun AppRow(
    app: SandboxApp,
    enabled: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    ListItem(
        leadingContent = { AppLeadingVisual(app = app, modifier = Modifier.size(36.dp)) },
        headlineContent = {
            Text(
                text = app.label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onInstall, enabled = enabled) { Text("双开") }
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        enabled = enabled,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "更多操作",
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("从空间移除") },
                            onClick = {
                                menuExpanded = false
                                onUninstall()
                            },
                        )
                    }
                }
            }
        },
    )
}

/** 图标位：应用图标 → 位图；加载失败 / 无图标 → 首字占位方块。 */
@Composable
private fun AppLeadingVisual(app: SandboxApp, modifier: Modifier = Modifier) {
    val iconBitmap = remember(app.packageName) {
        runCatching { app.icon?.toBitmap()?.asImageBitmap() }.getOrNull()
    }
    if (iconBitmap != null) {
        Image(
            bitmap = iconBitmap,
            contentDescription = null,
            modifier = modifier,
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            Text(
                text = app.label.take(1).ifEmpty { "?" },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** 动作反馈条（成功 / 失败徽章 + 消息 + ≤ 2 行系统原文；视觉同 ExecFeedback 家族）。 */
@Composable
private fun FeedbackContent(feedback: ActionFeedback, successNote: String? = null) {
    val tone = if (feedback.isError) ChipTone.ERROR else ChipTone.OK
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(tone.container.copy(alpha = 0.45f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusChip(text = if (feedback.isError) "失败" else "成功", tone = tone)
            Text(
                text = feedback.message,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = tone.content,
                modifier = Modifier.weight(1f),
            )
        }
        feedback.detail?.let { detail ->
            Spacer(Modifier.size(2.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!feedback.isError && successNote != null) {
            Spacer(Modifier.size(2.dp))
            Text(
                text = successNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 会话动作日志卡（labelSmall 逐行；空 = 如实「暂无动作」）。 */
@Composable
private fun SessionLogCard(entries: List<SessionLogEntry>) {
    SandboxCard {
        if (entries.isEmpty()) {
            Text(
                text = "暂无动作 —— 创建 / 拉起 / 切换 / 双开等动作会如实记录在这里（含成败）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            entries.forEach { entry ->
                Text(
                    text = "${entry.time} · ${entry.action} · ${if (entry.ok) "成功" else "失败"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (entry.ok) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

/** 移除用户确认弹窗（数据目录一并删除、不可恢复 —— 说清后果再确认）。 */
@Composable
private fun RemoveUserDialog(
    user: SandboxCommands.AndroidUser,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
            )
        },
        title = { Text(text = "移除空间「${user.name.ifBlank { "未知" }}」？") },
        text = {
            Text(
                text = "该隔离空间的全部数据（/data/user/${user.id}）将被删除且不可恢复；" +
                    "当前用户与其它空间不受影响。",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) { Text("移除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
