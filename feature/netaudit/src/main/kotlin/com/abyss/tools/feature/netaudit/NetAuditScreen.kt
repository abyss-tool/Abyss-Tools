package com.abyss.tools.feature.netaudit

// 嵌套类型直引（解析器的数据类住在 object NetStatsParser 内 —— 保持
// 单一命名空间，消费方经 import 拿到与测试同构的形态）。
import com.abyss.tools.feature.netaudit.NetStatsParser.NetStatsSnapshot
import com.abyss.tools.feature.netaudit.NetStatsParser.UidTraffic

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 采集命令（只读 dump，无拼接输入面 —— 白名单即命令本身）。 */
private const val DUMPSYS_NETSTATS = "dumpsys netstats"

/** 榜单过滤口径（FilterChip 三选一）。 */
private enum class UidScope(val label: String) {
    ALL("全部"),
    SYSTEM("仅系统"),
    USER("仅应用"),
}

/**
 * uid → 应用信息的解析产物（label / 代表包名 / 是否系统应用）。
 * [unknown] 是解析完成前的诚实占位（"uid=N" —— 不猜应用名）。
 */
private data class UidAppInfo(
    val label: String,
    val packageName: String?,
    val isSystem: Boolean,
) {
    companion object {
        fun unknown(uid: Int): UidAppInfo =
            UidAppInfo(label = "uid=$uid", packageName = null, isSystem = true)
    }
}

/**
 * 已知共享 uid 的固定人话标签：这些 uid 的 packagesForUid 会返回上百个系统包，
 * 任取首个包的 label 反而误导（「设置」不代表 uid 1000 的全部流量），按
 * AOSP uid 分配表直接固定命名；列表行如实展示 uid，用户可自行核对。
 */
private val KNOWN_UIDS: Map<Int, String> = mapOf(
    0 to "root",
    1000 to "系统 (android)",
    1001 to "系统 (phone)",
    1013 to "系统 (radio)",
    1021 to "系统 (media)",
)

/** 主线程专用（Compose 组合期调用，SimpleDateFormat 非线程安全故不共享到 IO）。 */
private val collectedAtFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatCollectedAt(millis: Long): String = collectedAtFormat.format(Date(millis))

/**
 * uid → 应用解析（IO 线程逐 uid 查 PackageManager）。坏读取全部 runCatching
 * 兜底（共享 uid / 无包名 / label 抛异常 → 退回 "uid=N"，绝不因一个 uid 拖垮
 * 整页）。共享 uid 取首个包做代表（顺序由 PM 决定 —— 尽力而为口径的一部分）。
 */
@Suppress("DEPRECATION") // getApplicationInfo(pkg, flags)：Int 形态全版本可用（Flags 形态 API 33+）
private fun resolveUidAppInfo(pm: PackageManager, uid: Int): UidAppInfo {
    KNOWN_UIDS[uid]?.let { known ->
        return UidAppInfo(label = known, packageName = null, isSystem = true)
    }
    val pkg = runCatching { pm.getPackagesForUid(uid) }.getOrNull()?.firstOrNull()
        ?: return UidAppInfo.unknown(uid)
    val appInfo = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
        ?: return UidAppInfo(label = "uid=$uid", packageName = pkg, isSystem = true)
    return UidAppInfo(
        label = runCatching { appInfo.loadLabel(pm).toString() }.getOrDefault(pkg),
        packageName = pkg,
        isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
    )
}

/**
 * 流量审计页 —— `dumpsys netstats` 的应用维度流量画像（Shizuku 免 Root）。
 *
 * 能力与诚实边界（页面文案同步声明，不藏在代码里）：
 *  - 数据源 = 系统网络统计服务的 dump（只读），数字为系统统计口径的
 *    尽力而为聚合：不同 ROM 格式差异、可能含重复周期桶 → 上限估计；
 *  - 输出超单流上限（1MB）被通道 StreamGuards 截断时，如实横幅提示
 *    数据不完整，绝不假装完整；
 *  - VpnService 级深度包审计 / 域名画像不在本页（属网络守护插件，路线图）。
 *
 * 数据纪律：UiState 三态（Loading / Error 可重试 / Data），解析在纯 Kotlin
 * 的 [NetStatsParser]（可单测）；uid → 应用映射在 IO 线程解析，解析完成前
 * 行内展示 "uid=N" 占位（真实 uid，非假数据）。
 */
@Composable
fun NetAuditScreen(
    shizukuStatus: ShizukuStatus,
    gateway: ShellGateway,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pm = remember { context.packageManager }

    var stats by remember { mutableStateOf<UiState<NetStatsSnapshot>>(UiState.Loading) }
    var truncated by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var collectedAt by remember { mutableStateOf<Long?>(null) }
    var query by remember { mutableStateOf("") }
    var scopeFilter by remember { mutableStateOf(UidScope.ALL) }
    var uidInfos by remember { mutableStateOf<Map<Int, UidAppInfo>>(emptyMap()) }

    /**
     * 唯一采集动作：dump → 尽力而为解析 → UiState.Data。首次进入即 Loading；
     * 手动刷新期间保留旧快照（真实数据，不是假数据），double-tap 由
     * [refreshing] 门禁挡住。truncated 由通道如实上报，页面如实横幅。
     */
    fun refresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            gateway.exec(DUMPSYS_NETSTATS).fold(
                onSuccess = { result ->
                    truncated = result.truncated
                    collectedAt = System.currentTimeMillis()
                    stats = UiState.Data(NetStatsParser.parse(result.stdout))
                },
                onFailure = { e ->
                    truncated = false
                    stats = UiState.Error(e.message ?: "读取失败")
                },
            )
            refreshing = false
        }
    }

    // 进入页面即采集（初始加载）。
    LaunchedEffect(Unit) { refresh() }

    // 当前快照（Data 时非空；作 uid 解析的依赖键 —— 内容不变则不重查 PM）。
    val snapshot: NetStatsSnapshot? = when (val state = stats) {
        is UiState.Data -> state.value
        else -> null
    }

    // uid → 应用解析：IO 线程逐 uid 查 PackageManager（packagesForUid 的
    // binder 调用不阻塞主线程；appmgr 的 Provider 同款纪律）。
    LaunchedEffect(snapshot) {
        val value = snapshot ?: return@LaunchedEffect
        uidInfos = withContext(Dispatchers.IO) {
            value.perUid.associate { it.uid to resolveUidAppInfo(pm, it.uid) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        NetAuditHeader(title = "流量审计", onBack = onBack)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── 1. 能力与边界（诚实声明常驻，不随加载状态消失）─────────
            item(key = "intro") { NetAuditIntroCard() }

            // ── 2. Shizuku 门禁（inline WARN chip + 人话指引）────────
            item(key = "shizuku-gate") {
                ShizukuInlineWarn(shizukuStatus = shizukuStatus)
            }

            when (val state = stats) {
                UiState.Loading -> item(key = "loading") {
                    LoadingState(message = "正在读取 dumpsys netstats…")
                }

                is UiState.Error -> item(key = "error") {
                    ErrorState(
                        message = "读取流量统计失败：${state.message}",
                        onRetry = { refresh() },
                    )
                }

                is UiState.Data -> {
                    val snap = state.value
                    val keyword = query.trim()
                    val visibleRows = snap.perUid.filter { entry ->
                        val info = uidInfos[entry.uid] ?: UidAppInfo.unknown(entry.uid)
                        val passScope = when (scopeFilter) {
                            UidScope.ALL -> true
                            UidScope.SYSTEM -> info.isSystem
                            UidScope.USER -> !info.isSystem
                        }
                        val passKeyword = keyword.isEmpty() ||
                            info.label.contains(keyword, ignoreCase = true) ||
                            info.packageName?.contains(keyword, ignoreCase = true) == true
                        passScope && passKeyword
                    }
                    // 条形图分母：本榜（过滤后）最大应用的总流量。
                    val maxTotal = visibleRows.maxOfOrNull { it.total } ?: 0L

                    // ── 4. 总览：双卡 + 采集时间 + 刷新 ─────────────────
                    item(key = "summary") {
                        SummaryCard(
                            snapshot = snap,
                            collectedAt = collectedAt,
                            refreshing = refreshing,
                            onRefresh = { refresh() },
                        )
                    }

                    // ── 8a. 截断横幅（通道如实上报，页面如实提示）───────
                    if (truncated) {
                        item(key = "truncated") { TruncatedBanner() }
                    }

                    // ── 5. 搜索（按应用名 / 包名过滤）─────────────────
                    item(key = "search") {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("按应用名过滤") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = null,
                                )
                            },
                            singleLine = true,
                        )
                    }

                    // ── 7. 过滤 chips（全部 / 仅系统 / 仅应用）─────────
                    item(key = "scope-chips") {
                        UidScopeChips(
                            selected = scopeFilter,
                            onSelect = { scopeFilter = it },
                        )
                    }

                    // ── 榜单（Top-talkers）────────────────────────────
                    item(key = "list-header") {
                        Column {
                            HorizontalDivider()
                            SectionHeader(
                                title = "应用流量画像",
                                subtitle = "按总流量降序 · 条形宽度正比于本榜最大值（主色 = 下载，辅色 = 上传）",
                                trailing = {
                                    StatusChip(
                                        text = "${visibleRows.size} / ${snap.perUid.size}",
                                        tone = ChipTone.NEUTRAL,
                                    )
                                },
                            )
                        }
                    }

                    if (visibleRows.isEmpty()) {
                        item(key = "empty") {
                            EmptyState(
                                title = "没有匹配的流量记录",
                                message = "换个关键词或调整过滤条件；本口径下没有可归因流量的应用不会出现在榜单。",
                            )
                        }
                    } else {
                        items(visibleRows, key = { it.uid }) { entry ->
                            val info = uidInfos[entry.uid] ?: UidAppInfo.unknown(entry.uid)
                            Column(modifier = Modifier.fillMaxWidth()) {
                                UidTrafficRow(entry = entry, info = info)
                                Spacer(Modifier.size(6.dp))
                                TrafficBar(
                                    rxBytes = entry.rxBytes,
                                    txBytes = entry.txBytes,
                                    maxTotal = maxTotal,
                                )
                            }
                        }
                    }

                    // ── 8b. 覆盖率行（诚实信号：解析吃到了几行）───────
                    item(key = "coverage") {
                        Column {
                            HorizontalDivider()
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = "已解析 ${snap.parsedLines} 行",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── 页头（navhub DetailHeader 的模块内拷贝 —— 那边 internal 跨模块不可见，
//    appmgr 同款处理）─────────────────────────────────────────────────────

@Composable
private fun NetAuditHeader(title: String, onBack: () -> Unit) {
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
        )
    }
}

// ── 说明卡家族（视觉同 disguise / appmgr 的卡片：surfaceVariant 半透明）──

@Composable
private fun NetAuditIntroCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
    ) {
        Text(
            text = "能力与边界",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "读取系统 netstats（dumpsys，Shizuku 免 Root）做应用维度流量画像 —— " +
                "数字为系统统计口径的尽力而为聚合（不同 ROM 格式差异，可能含重复周期桶，" +
                "视为上限估计）。VpnService 级深度包审计与域名画像属网络守护插件（路线图）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Shizuku 门禁（inline WARN chip + 人话指引）：本页签名不含授权回调 ——
 * 授权动作统一收敛在仪表盘 / 系统控制中心，这里只做诚实指引；已授权则
 * 什么都不渲染（不打扰）。
 */
@Composable
private fun ShizukuInlineWarn(shizukuStatus: ShizukuStatus) {
    val running = shizukuStatus as? ShizukuStatus.Running
    if (running?.authorized == true) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(ChipTone.WARN.container.copy(alpha = 0.45f))
            .padding(16.dp),
    ) {
        StatusChip(
            text = if (running == null) "Shizuku 未运行" else "Shizuku 未授权",
            tone = ChipTone.WARN,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = "本页数据经 Shizuku 以 adb shell 上下文执行 dumpsys 读取（免 Root）。" +
                "授权入口在仪表盘/系统控制中心 —— 授权后回本页点刷新即可重新采集。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 总览卡：双列统计卡 + 采集时间 + 刷新按钮（刷新期间按钮内转小菊花）。 */
@Composable
private fun SummaryCard(
    snapshot: NetStatsSnapshot,
    collectedAt: Long?,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "流量总览",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "采集时间：" + (collectedAt?.let { formatCollectedAt(it) } ?: "尚未采集"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRefresh, enabled = !refreshing) {
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "刷新",
                    )
                }
            }
        }
        Spacer(Modifier.size(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TrafficStatCard(
                label = "总下载",
                bytes = snapshot.totalRx,
                modifier = Modifier.weight(1f),
            )
            TrafficStatCard(
                label = "总上传",
                bytes = snapshot.totalTx,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 单列统计卡（数值用 Bytes.format 人话化，主色强调 —— 不硬编码色彩）。 */
@Composable
private fun TrafficStatCard(
    label: String,
    bytes: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = NetStatsParser.Bytes.format(bytes),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** 截断横幅：chip 文案即通道上报的事实，附一行后果说明（不假装完整）。 */
@Composable
private fun TruncatedBanner() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(ChipTone.WARN.container.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        StatusChip(
            text = "输出超 1MB 被截断（数据不完整）",
            tone = ChipTone.WARN,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "单流上限 1MB（通道 StreamGuards）：截断点之后的统计桶未计入，" +
                "榜单可能缺少尾部应用 —— 数字按上限估计口径解读。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 过滤 chips（FilterChip 三选一）。 */
@OptIn(ExperimentalMaterial3Api::class) // FilterChip：material3 实验标记按需 OptIn
@Composable
private fun UidScopeChips(
    selected: UidScope,
    onSelect: (UidScope) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        UidScope.entries.forEach { scope ->
            FilterChip(
                selected = selected == scope,
                onClick = { onSelect(scope) },
                label = { Text(scope.label) },
            )
        }
    }
}

/**
 * 榜单行：ListItem（M3）—— 图标位 = 真实应用图标位图，副标题 = 包名 + uid，
 * 尾部 = Badge 徽章（↓下载 ↑上传，人话字节）。图标加载 runCatching 兜底：
 * 失败退首字占位方块，绝不因图标问题丢行（appmgr AppIcon 同款纪律）。
 */
@OptIn(ExperimentalMaterial3Api::class) // ListItem / Badge：material3 实验标记按需 OptIn
@Composable
private fun UidTrafficRow(
    entry: NetStatsParser.UidTraffic,
    info: UidAppInfo,
) {
    ListItem(
        headlineContent = {
            Text(
                text = info.label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = (info.packageName ?: "无归属包名") + " · uid=${entry.uid}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            UidLeadingVisual(info = info)
        },
        trailingContent = {
            Badge(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Text(
                    text = "↓${NetStatsParser.Bytes.format(entry.rxBytes)} " +
                        "↑${NetStatsParser.Bytes.format(entry.txBytes)}",
                    style = MaterialTheme.typography.labelMedium,
                    softWrap = false,
                )
            }
        },
    )
}

/**
 * 图标位：包名作 remember 键（uid → 包映射确定后包名即稳定键）；
 * 已知共享 uid（root / android 系统等）无包名 → 首字占位方块。
 * 图标位图 36dp（与 appmgr 行卡同量级）。
 */
@Composable
private fun UidLeadingVisual(info: UidAppInfo, modifier: Modifier = Modifier) {
    val pm = LocalContext.current.packageManager
    val iconBitmap = remember(info.packageName) {
        runCatching {
            info.packageName?.let { pkg -> pm.getApplicationIcon(pkg).toBitmap().asImageBitmap() }
        }.getOrNull()
    }
    if (iconBitmap != null) {
        Image(
            bitmap = iconBitmap,
            contentDescription = null,
            modifier = modifier.size(36.dp),
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .size(36.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            Text(
                text = info.label.take(1).ifEmpty { "?" },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * 行下流量条：双段 Box —— 下载段（primary）+ 上传段（辅色 tertiary），
 * 宽度 = 各自流量 / 本榜最大应用总流量（两段之和恒 ≤ 全宽）。
 * 高 4dp、圆角；零流量段不渲染（避免 fillMaxWidth(0f) 的零尺寸语义歧义）。
 */
@Composable
private fun TrafficBar(
    rxBytes: Long,
    txBytes: Long,
    maxTotal: Long,
) {
    val max = maxTotal.coerceAtLeast(1L)
    val rxFraction = (rxBytes.toFloat() / max).coerceIn(0f, 1f)
    val txFraction = (txBytes.toFloat() / max).coerceIn(0f, 1f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(MaterialTheme.shapes.extraSmall),
    ) {
        if (rxFraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(rxFraction)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        if (txFraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(txFraction)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.tertiary),
            )
        }
    }
}
