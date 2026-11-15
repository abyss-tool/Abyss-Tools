package com.abyss.tools.feature.appmgr

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SearchBar
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
import kotlinx.coroutines.launch

/** 列表排序模式。 */
internal enum class AppSortMode(val label: String) {
    NAME("名称"),
    SIZE("大小"),
    INSTALL_TIME("安装时间"),
}

/** 列表过滤模式。 */
internal enum class AppFilterMode(val label: String) {
    ALL("全部"),
    USER("用户应用"),
    SYSTEM("系统应用"),
}

/** 应用管理器 SharedPreferences 文件名（搜索关键词历史落盘用）。 */
private const val PREFS_APPMGR = "abyss_appmgr"

/** SearchBar 建议区展示的历史关键词条数（存储 6 条，多存 1 条轮换余量）。 */
private const val SEARCH_SUGGESTION_LIMIT = 5

/** SearchBar 建议区展示的匹配应用行数 —— 更多结果收起搜索后在主列表看。 */
private const val SEARCH_MATCH_LIMIT = 6

/**
 * 应用管理器 —— 列表页（全量应用目录：搜索 / 排序 / 过滤 / 冻结概览）。
 *
 * 数据纪律：
 *  - 应用列表走 UiState 三态（Loading / Error 可重试 / Data），禁止假数据；
 *  - 冻结集合经 Shizuku 读 `pm list packages -d`，未授权 / 读取失败 →
 *    null（未知），绝不显示猜测值 —— 徽章只在确认为真时点亮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppManagerScreen(
    provider: AppInfoProvider,
    gateway: ShellGateway,
    shizukuStatus: ShizukuStatus,
    onRequestShizukuPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onBack: () -> Unit,
    onOpenApp: (AbyssAppInfo) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var appsState by remember { mutableStateOf<UiState<List<AbyssAppInfo>>>(UiState.Loading) }
    var query by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(AppSortMode.NAME) }
    var filterMode by remember { mutableStateOf(AppFilterMode.ALL) }

    // ── 搜索视图（M3 SearchBar 展开态）────────────────────────────
    // 关键词历史只服务 SearchBar 的建议区；主列表过滤仍由同一个 query
    // 状态驱动（输入即过滤，收起搜索后列表保持过滤结果 —— 与旧
    // OutlinedTextField 时代行为完全一致）。
    val context = LocalContext.current
    val searchHistory = remember {
        AppSearchHistory(context.getSharedPreferences(PREFS_APPMGR, Context.MODE_PRIVATE))
    }
    var recentKeywords by remember { mutableStateOf(searchHistory.all()) }
    var searchActive by remember { mutableStateOf(false) }

    // 搜索展开时先吃一次系统返回：收起搜索视图而不是直接退出列表页
    //（Google 搜索同款层级；旧输入框时代不存在此坑）。
    BackHandler(enabled = searchActive) { searchActive = false }

    /** null = 未知（Shizuku 未授权 / 读取失败）—— 已冻结徽章仅确认为真时显示。 */
    var frozenPackages by remember { mutableStateOf<Set<String>?>(null) }
    var frozenReadFailed by remember { mutableStateOf(false) }

    val shizukuAuthorized = (shizukuStatus as? ShizukuStatus.Running)?.authorized == true

    fun loadApps() {
        appsState = UiState.Loading
        scope.launch {
            appsState = runCatching { provider.installedApps() }.fold(
                onSuccess = { UiState.Data(it) },
                onFailure = { UiState.Error(it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    fun refreshFrozen() {
        if (!shizukuAuthorized) return
        frozenReadFailed = false
        scope.launch {
            gateway.exec(AppManagerCommands.listDisabled())
                .onSuccess { result ->
                    if (result.isSuccess) {
                        frozenPackages = AppManagerCommands.parseDisabledPackages(result.stdout)
                        frozenReadFailed = false
                    } else {
                        frozenReadFailed = true
                    }
                }
                .onFailure {
                    frozenReadFailed = true
                }
        }
    }

    // 进入页面即拉应用列表（内存缓存命中则瞬间返回）。
    LaunchedEffect(Unit) { loadApps() }

    // Shizuku 授权态变化时刷新冻结集合；未授权 → 清空（绝显示猜测值）。
    LaunchedEffect(shizukuAuthorized) {
        if (shizukuAuthorized) {
            refreshFrozen()
        } else {
            frozenPackages = null
            frozenReadFailed = false
        }
    }

    val allApps: List<AbyssAppInfo>? = when (val state = appsState) {
        is UiState.Data -> state.value
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        AppManagerHeader(
            title = "应用管理器",
            onBack = onBack,
            trailing = {
                allApps?.let { apps ->
                    StatusChip(text = "${apps.size} 个应用", tone = ChipTone.NEUTRAL)
                }
            },
        )

        when (val state = appsState) {
            is UiState.Loading -> LoadingState(
                message = "正在读取应用列表…",
                modifier = Modifier.weight(1f),
            )

            is UiState.Error -> ErrorState(
                message = "读取应用列表失败：${state.message}",
                modifier = Modifier.weight(1f),
                onRetry = { loadApps() },
            )

            is UiState.Data -> {
                val apps = state.value
                val keyword = query.trim()
                val visibleApps = apps
                    .filter { app ->
                        when (filterMode) {
                            AppFilterMode.ALL -> true
                            AppFilterMode.USER -> !app.isSystem
                            AppFilterMode.SYSTEM -> app.isSystem
                        }
                    }
                    .filter { app ->
                        keyword.isEmpty() ||
                            app.label.contains(keyword, ignoreCase = true) ||
                            app.packageName.contains(keyword, ignoreCase = true)
                    }
                    .sortedWith(
                        when (sortMode) {
                            AppSortMode.NAME ->
                                compareBy({ it.label.lowercase() }, { it.packageName })

                            AppSortMode.SIZE ->
                                compareByDescending { it.apkSize }

                            AppSortMode.INSTALL_TIME ->
                                compareByDescending { it.installTime }
                        },
                    )

                Column(modifier = Modifier.weight(1f)) {
                    // ── 搜索 + 排序 / 过滤（固定区，不随列表滚动）──────────
                    Column(
                        modifier = Modifier.padding(
                            start = 20.dp,
                            end = 20.dp,
                            top = 4.dp,
                        ),
                    ) {
                        // M3 SearchBar（经典重载，点按展开）：未激活 =
                        // 56dp 搜索条；激活 = 原地展开搜索视图（历史建议 /
                        // 应用匹配行）。heightIn(max)：经典 SearchBar 激活
                        // 时会占满父级给出的全部高度 —— 不设上限会把下方
                        // 过滤 chip 行与冻结卡挤成 0 高；限高后固定区其余
                        // 部分始终可见，建议区超高时内部滚动。
                        SearchBar(
                            query = query,
                            onQueryChange = { query = it },
                            onSearch = { submitted ->
                                // 提交即记入关键词历史（非空去重，上限 6）
                                searchHistory.record(submitted)
                                recentKeywords = searchHistory.all()
                                searchActive = false
                            },
                            active = searchActive,
                            onActiveChange = { searchActive = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp),
                            placeholder = { Text("搜索应用名或包名") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = null,
                                )
                            },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Clear,
                                            contentDescription = "清空搜索",
                                        )
                                    }
                                }
                            },
                        ) {
                            SearchSuggestions(
                                apps = apps,
                                query = query,
                                recentKeywords = recentKeywords,
                                frozenPackages = frozenPackages,
                                onPickKeyword = { query = it },
                                onOpenApp = { app ->
                                    // 点匹配行直达详情；顺带收起搜索视图
                                    searchActive = false
                                    onOpenApp(app)
                                },
                            )
                        }
                        Spacer(Modifier.size(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppFilterMode.entries.forEach { mode ->
                                AppFilterChip(
                                    text = mode.label,
                                    selected = filterMode == mode,
                                    onClick = { filterMode = mode },
                                )
                            }
                        }
                        Spacer(Modifier.size(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppSortMode.entries.forEach { mode ->
                                AppFilterChip(
                                    text = mode.label,
                                    selected = sortMode == mode,
                                    onClick = { sortMode = mode },
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = "${visibleApps.size} / ${apps.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.size(12.dp))

                        // ── 冻结概览 / Shizuku 门禁（合一卡片）─────────────
                        when {
                            !shizukuAuthorized -> ShizukuGateCard(
                                shizukuStatus = shizukuStatus,
                                onRequestPermission = onRequestShizukuPermission,
                                onOpenShizuku = onOpenShizuku,
                            )

                            frozenReadFailed -> AppCard {
                                Text(
                                    text = "冻结状态读取失败",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.size(4.dp))
                                Text(
                                    text = "「pm list packages -d」未执行成功，冻结徽章暂不可用，" +
                                        "其余功能不受影响。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.size(10.dp))
                                OutlinedButton(onClick = { refreshFrozen() }) { Text("重试") }
                            }

                            frozenPackages == null -> AppCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.size(10.dp))
                                    Text(
                                        text = "正在读取冻结状态…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            else -> {
                                val frozenCount = frozenPackages.orEmpty().size
                                AppCard {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (frozenCount == 0) {
                                                    "当前没有已冻结的应用"
                                                } else {
                                                    "已冻结 $frozenCount 个应用"
                                                },
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Spacer(Modifier.size(4.dp))
                                            Text(
                                                text = if (frozenCount == 0) {
                                                    "冻结 = 免 Root 停用应用且保留数据。" +
                                                        "进入任意应用详情页即可冻结。"
                                                } else {
                                                    "冻结的应用已停用（数据保留）。进入详情页可解冻恢复。"
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (frozenCount > 0) {
                                            StatusChip(
                                                text = "$frozenCount",
                                                tone = ChipTone.WARN,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ── 应用列表 ─────────────────────────────────────────
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(
                            start = 20.dp,
                            end = 20.dp,
                            top = 4.dp,
                            bottom = 32.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (visibleApps.isEmpty()) {
                            item(key = "empty") {
                                EmptyState(
                                    title = if (keyword.isEmpty() && filterMode == AppFilterMode.ALL) {
                                        "没有可用应用"
                                    } else {
                                        "没有匹配的应用"
                                    },
                                    message = "换个关键词，或调整上方的过滤条件（支持应用名 / 包名，中英文均可）。",
                                )
                            }
                        } else {
                            items(visibleApps, key = { it.packageName }) { app ->
                                AppRowCard(
                                    app = app,
                                    frozen = frozenPackages?.contains(app.packageName) == true,
                                    onClick = { onOpenApp(app) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * SearchBar 展开态内容：空关键词 → 最近搜索历史（最多 5 条）；有
 * 关键词 → 实时匹配应用行（最多 6 条，复用主列表行视觉）。匹配只在
 * 全量应用上做（不叠加过滤 chip 条件）—— 搜索的本意就是跨过滤找应用。
 */
@Composable
private fun SearchSuggestions(
    apps: List<AbyssAppInfo>,
    query: String,
    recentKeywords: List<String>,
    frozenPackages: Set<String>?,
    onPickKeyword: (String) -> Unit,
    onOpenApp: (AbyssAppInfo) -> Unit,
) {
    val keyword = query.trim()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (keyword.isEmpty()) {
            if (recentKeywords.isEmpty()) {
                SearchHintText(text = "输入应用名或包名，最近搜索会出现在这里")
            } else {
                recentKeywords.take(SEARCH_SUGGESTION_LIMIT).forEach { word ->
                    RecentKeywordRow(keyword = word, onClick = { onPickKeyword(word) })
                }
            }
        } else {
            val matches = apps
                .filter {
                    it.label.contains(keyword, ignoreCase = true) ||
                        it.packageName.contains(keyword, ignoreCase = true)
                }
                .take(SEARCH_MATCH_LIMIT)
            if (matches.isEmpty()) {
                SearchHintText(text = "没有匹配的应用")
            } else {
                matches.forEach { app ->
                    AppRowCard(
                        app = app,
                        frozen = frozenPackages?.contains(app.packageName) == true,
                        onClick = { onOpenApp(app) },
                    )
                }
            }
        }
    }
}

/** 最近搜索行：点选 → 填入搜索框并实时出匹配（Search 前缀镜射输入态）。 */
@Composable
private fun RecentKeywordRow(keyword: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = keyword,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 建议区空态提示（人话，不装腔）。 */
@Composable
private fun SearchHintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
    )
}

/** 应用行卡（镜像 designsystem ListRowCard 的视觉，图标位换为真实应用图标）。 */
@Composable
private fun AppRowCard(
    app: AbyssAppInfo,
    frozen: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon = app.icon, modifier = Modifier.size(46.dp))
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = "${app.packageName} · v${app.versionName ?: app.versionCode} · " +
                    formatBytes(context, app.apkSize),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (app.isSystem || frozen) {
            Spacer(Modifier.size(8.dp))
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (app.isSystem) {
                    StatusChip(text = "系统应用", tone = ChipTone.NEUTRAL)
                }
                if (frozen) {
                    StatusChip(text = "已冻结", tone = ChipTone.WARN)
                }
            }
        }
    }
}
