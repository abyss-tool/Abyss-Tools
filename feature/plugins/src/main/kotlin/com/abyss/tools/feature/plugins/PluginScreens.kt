package com.abyss.tools.feature.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abyss.tools.core.designsystem.component.entranceStagger
import com.abyss.tools.core.designsystem.component.pressScale
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import com.abyss.tools.core.common.AbyssChannels
import com.abyss.tools.core.common.MIRROR_AUTO
import com.abyss.tools.core.common.UiState
import com.abyss.tools.core.common.toUiState
import com.abyss.tools.core.designsystem.component.ChipTone
import com.abyss.tools.core.designsystem.component.EmptyState
import com.abyss.tools.core.designsystem.component.ErrorState
import com.abyss.tools.core.designsystem.component.InfoRow
import com.abyss.tools.core.designsystem.component.LoadingState
import com.abyss.tools.core.designsystem.component.SectionHeader
import com.abyss.tools.core.designsystem.component.StatusChip
import com.abyss.tools.core.designsystem.glass.AbyssGlassSurface
import com.abyss.tools.core.plugin.PermissionAudit
import com.abyss.tools.core.plugin.PermissionGroup
import com.abyss.tools.core.plugin.PluginChannelClient
import com.abyss.tools.core.plugin.PluginIndex
import com.abyss.tools.core.plugin.PluginIndexEntry
import com.abyss.tools.core.plugin.PluginInstaller
import com.abyss.tools.core.plugin.PluginPatch
import com.abyss.tools.core.plugin.PluginPatchChannel
import com.abyss.tools.core.plugin.PluginPermission
import com.abyss.tools.core.plugin.PluginRepository
import com.abyss.tools.core.update.AgentReleaseManager
import com.abyss.tools.core.update.AgentReleaseManifest
import com.abyss.tools.core.update.DownloadInfo
import com.abyss.tools.core.update.MirrorRouter
import com.abyss.tools.core.update.PatchReport
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * N-EXP-006：安装前权限审阅的目标形态（数据层/APK层）。
 *
 * 放在文件顶层而非 Composable 内部，因为 Kotlin 不允许在局部作用域
 * 内声明 enum class（"Modifier 'enum' is not applicable to 'local class'"）。
 */
private enum class ReviewTarget { DATA, APK }

/**
 * 插件中心 —— 插件索引（Abyss-Plugins / plugins.json）的浏览页。
 *
 * v0.1.1 起「我的插件」分区落地：本机已装清单（installer.installed()）
 * 置顶展示；插件点击进入具体插件详情（APK / 数据双形态安装 AP 框架 +
 * 补丁通道应用闭环）。
 *
 * v0.7.0 起「下载镜像」分区落地：国内反代 + 延迟实测 + 手动锁定 /
 * 自动择优（见 [MirrorSection]）。
 *
 * @param stateVersion 插件本机状态版本号（安装 / 卸载 / 补丁应用后自增，
 *   驱动「我的插件」与安装徽章重读磁盘）。
 * @param onPluginsChanged 插件本机状态变化回调（AbyssRoot 持有并自增版本号）。
 * @param mirrorRouter 下载镜像路由器（探测缓存与下载管线共享）。
 * @param mirrorPreference 当前镜像偏好（"auto" 或镜像 id）。
 * @param onMirrorPreference 镜像偏好写回（持久化到设置存储）。
 * @param onBack 返回回调；顶层 tab 态传 null —— 不渲染返回箭头、不提供
 *   返回语义（FEAT-034 FR-006）；详情页态传 pop 闭包，外观与 v0.9.10 一致。
 * @param title 页头标题；顶层 tab 态传「插件」与 tab 文案一致，详情页缺省
 *   「插件中心」保持零回归。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginHubScreen(
    repository: PluginRepository,
    channelClient: PluginChannelClient,
    installer: PluginInstaller,
    onBack: (() -> Unit)?,
    onOpenPlugin: (String) -> Unit,
    stateVersion: Int = 0,
    onPluginsChanged: () -> Unit = {},
    mirrorRouter: MirrorRouter? = null,
    mirrorPreference: String = MIRROR_AUTO,
    onMirrorPreference: (String) -> Unit = {},
    title: String = "插件中心",
    /** 深渊agent 直达下载（FEAT-037）：null = 未装配（服务未初始化时隐藏置顶卡）。 */
    agentRelease: AgentReleaseManager? = null,
) {
    var indexState by remember { mutableStateOf<UiState<PluginIndex>>(UiState.Loading) }
    var retryKey by remember { mutableStateOf(0) }
    var categoryFilter by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 下拉刷新进度位：与 retryKey 同一条索引重取管线共用 —— 数据态刷新
    // 时列表原地保留（不闪全屏 Loading），进度交给 PullToRefreshBox 指示器；
    // 首载 / 错误重试仍走全屏 Loading（原行为不变）。
    var pullRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(retryKey) {
        if (indexState is UiState.Data) pullRefreshing = true else indexState = UiState.Loading
        indexState = repository.fetchIndex().toUiState()
        pullRefreshing = false
    }

    // 本机已装插件（stateVersion 变化时重读磁盘）
    val installedPlugins = remember(stateVersion) { installer.installed() }

    // ── 一键检查更新（v0.3.0）：遍历已装插件的补丁通道，
    //    找出 from 命中本机版本的可应用补丁 ────────────────────────
    var updatesState by remember { mutableStateOf<UiState<Map<String, PluginPatch>>>(UiState.Loading) }
    var updatesChecked by remember { mutableStateOf(false) }
    val checkUpdates: (PluginIndex) -> Unit = { index ->
        updatesChecked = true
        updatesState = UiState.Loading
        scope.launch {
            updatesState = withContext(Dispatchers.IO) {
                runCatching {
                    val installedIds = installedPlugins.map { it.id }.toSet()
                    index.plugins
                        .filter { it.id in installedIds }
                        .map { entry ->
                            // 每插件一通道，并发拉取；单通道失败不影响其余
                            async {
                                val url = entry.patchChannel
                                    ?: AbyssChannels.pluginPatchChannel(entry.id)
                                channelClient.fetchPatchChannel(url).getOrNull()
                                    ?.let { channel ->
                                        installer.updatablePatch(entry.id, channel)
                                            ?.let { patch -> entry.id to patch }
                                    }
                            }
                        }
                        .awaitAll()
                        .mapNotNull { it }
                        .toMap()
                }.toUiState()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 顶层 tab 态（FEAT-034 FR-006）：无返回语义 —— 不渲染返回箭头；
            // 占位 Spacer 让标题与详情页态的文本起点视觉对齐（页内容 20dp）。
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            } else {
                Spacer(Modifier.size(12.dp))
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "索引源：abyss-tool/Abyss-Plugins · plugins.json",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when (val state = indexState) {
            is UiState.Loading -> LoadingState("正在拉取插件索引…", Modifier.fillMaxSize())

            is UiState.Error -> ErrorState(
                message = "插件索引拉取失败：${state.message}",
                modifier = Modifier.fillMaxSize(),
                onRetry = { retryKey++ },
            )

            is UiState.Data -> {
                val entries = state.value.plugins
                val categories = entries.map { it.category }.distinct()
                // 下拉刷新（M3 PullToRefreshBox）：onRefresh 与错误态「重试」
                // 按钮共用同一条 retryKey 索引重取管线 —— 不另起网络逻辑；
                // 数据态触发时上方 LaunchedEffect 已保证列表原地保留，
                // 仅顶部指示器转圈。页头保留在刷新区之外（不随下拉位移）。
                PullToRefreshBox(
                    isRefreshing = pullRefreshing,
                    onRefresh = { retryKey++ },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 20.dp,
                            end = 20.dp,
                            bottom = 32.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // ── 深渊agent（FEAT-037）：置顶直达 —— 姊妹项目
                        //    Ultra-Guru/Android-Guru-Agent 独立 APK，多镜像
                        //    下载 + sha256 强校验 + 系统安装器。置顶于镜像区
                        //    之前（用户指定「插件页面置顶」）。────────────────
                        if (agentRelease != null) {
                            item(key = "agent-pinned") {
                                Box(Modifier.fillMaxWidth().entranceStagger(0)) {
                                    AgentPinnedCard(manager = agentRelease)
                                }
                            }
                        }

                        // ── 下载镜像（v0.7.0：国内反代 + 延迟实测）──────────
                        // 错峰入场（v0.7.6）：包裹 Box 挂 entranceStagger，区块零改动。
                        if (mirrorRouter != null) {
                            item {
                                Box(Modifier.fillMaxWidth().entranceStagger(0)) {
                                    MirrorSection(
                                        mirrorRouter = mirrorRouter,
                                        preference = mirrorPreference,
                                        onPreference = onMirrorPreference,
                                    )
                                }
                            }
                        }

                        // ── 我的插件（本机已装）───────────────────────────────
                        if (installedPlugins.isNotEmpty()) {
                            item {
                                Box(Modifier.fillMaxWidth().entranceStagger(1)) {
                                    SectionHeader(
                                        title = "我的插件",
                                        trailing = {
                                            androidx.compose.material3.TextButton(
                                                onClick = { checkUpdates(state.value) },
                                            ) { Text(if (updatesChecked) "重新检查" else "检查更新") }
                                        },
                                    )
                                }
                            }
                            // 检查结果摘要（人话：N 个可更新 / 全部最新 / 检查失败可重试）
                            if (updatesChecked) {
                                item {
                                    when (val u = updatesState) {
                                        is UiState.Loading -> Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(14.dp),
                                                strokeWidth = 2.dp,
                                            )
                                            Spacer(Modifier.size(8.dp))
                                            Text(
                                                "正在核对补丁通道…",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }

                                        is UiState.Error -> Text(
                                            text = "检查更新失败：${u.message}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )

                                        is UiState.Data ->
                                            if (u.value.isEmpty()) {
                                                Text(
                                                    "已装插件均为通道可应用链最新版本",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            } else {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    StatusChip("${u.value.size} 个可更新", ChipTone.WARN)
                                                    Spacer(Modifier.size(8.dp))
                                                    Text(
                                                        "进入对应插件详情页下载并应用补丁。",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                    }
                                }
                            }
                            val updatableIds = when (val u = updatesState) {
                                is UiState.Data -> u.value.keys
                                else -> emptySet()
                            }
                            items(
                                count = installedPlugins.size,
                                key = { "mine-$stateVersion-${installedPlugins[it].id}" },
                            ) { index ->
                                val manifest = installedPlugins[index]
                                Box(Modifier.fillMaxWidth().entranceStagger(2 + index)) {
                                    InstalledPluginRow(
                                        name = manifest.name,
                                        id = manifest.id,
                                        localVersion = manifest.version,
                                        updatable = manifest.id in updatableIds,
                                        onClick = { onOpenPlugin(manifest.id) },
                                    )
                                }
                            }
                            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
                            item { Box(Modifier.size(4.dp)) }
                        }

                        item {
                            Box(Modifier.fillMaxWidth().entranceStagger(3)) {
                                SectionHeader(title = "插件生态")
                            }
                        }
                        item {
                            Box(Modifier.fillMaxWidth().entranceStagger(4)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilterChip(text = "全部 ${entries.size}", selected = categoryFilter == null) {
                                        categoryFilter = null
                                    }
                                    categories.forEach { cat ->
                                        FilterChip(
                                            text = categoryLabel(cat),
                                            selected = categoryFilter == cat,
                                        ) { categoryFilter = cat }
                                    }
                                }
                            }
                        }
                        val visible = entries.filter { categoryFilter == null || it.category == categoryFilter }
                        items(
                            count = visible.size,
                            key = { "hub-$stateVersion-${visible[it].id}" },
                        ) { index ->
                            val entry = visible[index]
                            Box(Modifier.fillMaxWidth().entranceStagger(5 + index)) {
                                PluginCard(
                                    entry = entry,
                                    installed = installer.isInstalled(entry.id),
                                    onClick = { onOpenPlugin(entry.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledPluginRow(
    name: String,
    id: String,
    localVersion: String,
    updatable: Boolean = false,
    onClick: () -> Unit,
) {
    // 点击接线修复（v0.7.6）：onClick 此前从未接线 —— 已装插件行点不进
    // 详情页（补丁更新入口）。接线方式与 PluginCard 一致。
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = id,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (updatable) {
            StatusChip("可更新", ChipTone.WARN)
            Spacer(Modifier.size(6.dp))
        }
        StatusChip("本地 v$localVersion", ChipTone.OK)
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun PluginCard(entry: PluginIndexEntry, installed: Boolean, onClick: () -> Unit) {
    // 点击接线修复（v0.7.6）：onClick 此前从未接线 —— 插件中心生态卡片
    // 整页点不进详情页（与导航页入口卡同族 bug）。interactionSource 自持
    // 同步喂给 clickable + pressScale，果冻按压反馈（与底栏同款纪律）。
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = entry.icon ?: "🧩", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.size(6.dp))
                StatusChip("v${entry.version}", ChipTone.NEUTRAL)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (installed) StatusChip("已安装", ChipTone.OK) else StatusChip("未安装", ChipTone.WARN)
            Text(
                text = categoryLabel(entry.category),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 插件详情 —— 具体插件的安装 AP 流水线（APK / 数据双形态）与补丁更新通道。
 */
@Composable
fun PluginDetailScreen(
    pluginId: String,
    repository: PluginRepository,
    channelClient: PluginChannelClient,
    installer: PluginInstaller,
    backdrop: Backdrop?,
    onBack: () -> Unit,
    onPluginsChanged: () -> Unit = {},
) {
    var indexState by remember { mutableStateOf<UiState<PluginIndex>>(UiState.Loading) }
    var retryKey by remember { mutableStateOf(0) }
    LaunchedEffect(retryKey) {
        indexState = UiState.Loading
        indexState = repository.fetchIndex().toUiState()
    }

    when (val state = indexState) {
        is UiState.Loading -> Column(Modifier.fillMaxSize()) {
            DetailBar(title = "插件", onBack = onBack)
            LoadingState("正在拉取插件索引…", Modifier.fillMaxSize())
        }

        is UiState.Error -> Column(Modifier.fillMaxSize()) {
            DetailBar(title = "插件", onBack = onBack)
            ErrorState("索引拉取失败：${state.message}", Modifier.fillMaxSize()) { retryKey++ }
        }

        is UiState.Data -> {
            val entry = state.value.plugins.firstOrNull { it.id == pluginId }
            if (entry == null) {
                Column(Modifier.fillMaxSize()) {
                    DetailBar(title = pluginId, onBack = onBack)
                    // 索引中已下架 / 未登记的插件：本机若已装仍可管理
                    val local = remember { installer.installedManifest(pluginId) }
                    if (local != null) {
                        EmptyState(
                            title = "索引中不存在：$pluginId",
                            message = "该插件已从插件中心下架，但本机仍保留安装登记（卸载请见设置页清理）。",
                        )
                    } else {
                        ErrorState("索引中不存在插件：$pluginId", Modifier.fillMaxSize())
                    }
                }
            } else {
                PluginDetailContent(
                    entry = entry,
                    channelClient = channelClient,
                    installer = installer,
                    backdrop = backdrop,
                    onBack = onBack,
                    onPluginsChanged = onPluginsChanged,
                )
            }
        }
    }
}

@Composable
private fun DetailBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** APK 安装流水线阶段（下载 → 校验 → 提交系统安装器 → 版本账本同步）。 */
private enum class InstallPhase {
    IDLE, DOWNLOADING, VERIFYING, INSTALLING, REGISTERING, DONE, FAILED,
}

/** 数据插件安装阶段（manifest 校验 → 原子落盘）。 */
private enum class DataInstallPhase { IDLE, WORKING, DONE, FAILED }

/** 插件补丁应用阶段（下载 → 校验 → 应用 → 回滚保护）。 */
private enum class PatchPhase { IDLE, DOWNLOADING, VERIFYING, APPLYING, DONE, FAILED }

@Composable
private fun PluginDetailContent(
    entry: PluginIndexEntry,
    channelClient: PluginChannelClient,
    installer: PluginInstaller,
    backdrop: Backdrop?,
    onBack: () -> Unit,
    onPluginsChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // ── 本机安装态（安装 / 卸载 / 补丁应用后重读）──────────────────────
    var localManifest by remember { mutableStateOf(installer.installedManifest(entry.id)) }
    val refreshLocal = {
        localManifest = installer.installedManifest(entry.id)
        onPluginsChanged()
    }

    // ── 补丁通道 ─────────────────────────────────────────────────────
    var channelState by remember {
        mutableStateOf<UiState<PluginPatchChannel>>(UiState.Loading)
    }
    val channelUrl = entry.patchChannel
        ?: AbyssChannels.pluginPatchChannel(entry.id)
    LaunchedEffect(entry.id) {
        channelState = channelClient.fetchPatchChannel(channelUrl).toUiState()
    }

    // ── APK 安装流水线状态机 ─────────────────────────────────────────
    var phase by remember { mutableStateOf(InstallPhase.IDLE) }
    var progress by remember { mutableFloatStateOf(-1f) }
    var failure by remember { mutableStateOf<String?>(null) }

    val startInstall: () -> Unit = {
        if (phase != InstallPhase.DOWNLOADING && phase != InstallPhase.VERIFYING &&
            phase != InstallPhase.INSTALLING && phase != InstallPhase.REGISTERING
        ) {
            phase = InstallPhase.DOWNLOADING
            failure = null
            scope.launch {
                // v0.3.0：一条龙安装（下载 → 校验 → 系统安装器 → 版本账本同步）
                installer.installApkPlugin(
                    entry,
                    onProgress = { read, total ->
                        progress = if (total > 0) read.toFloat() / total else -1f
                    },
                    onPhase = { p ->
                        phase = when (p) {
                            PluginInstaller.ApkPipelinePhase.DOWNLOADING -> InstallPhase.DOWNLOADING
                            PluginInstaller.ApkPipelinePhase.VERIFYING -> InstallPhase.VERIFYING
                            PluginInstaller.ApkPipelinePhase.INSTALLING -> InstallPhase.INSTALLING
                            PluginInstaller.ApkPipelinePhase.REGISTERING -> InstallPhase.REGISTERING
                        }
                    },
                ).onSuccess {
                    phase = InstallPhase.DONE
                    refreshLocal()
                }.onFailure {
                    phase = InstallPhase.FAILED
                    failure = it.message ?: "安装失败"
                }
            }
        }
    }

    // ── 数据插件安装状态机（manifest 校验 + 原子落盘）──────────────────
    var dataPhase by remember { mutableStateOf(DataInstallPhase.IDLE) }
    var dataFailure by remember { mutableStateOf<String?>(null) }
    val startDataInstall: () -> Unit = {
        if (dataPhase != DataInstallPhase.WORKING) {
            dataPhase = DataInstallPhase.WORKING
            dataFailure = null
            scope.launch {
                val result = withContext(Dispatchers.IO) { installer.installDataPlugin(entry) }
                result
                    .onSuccess {
                        dataPhase = DataInstallPhase.DONE
                        refreshLocal()
                    }
                    .onFailure {
                        dataPhase = DataInstallPhase.FAILED
                        dataFailure = it.message ?: "安装失败"
                    }
            }
        }
    }

    // ── 卸载 ─────────────────────────────────────────────────────────
    var uninstallConfirm by remember { mutableStateOf(false) }
    val doUninstall: () -> Unit = {
        uninstallConfirm = false
        scope.launch {
            withContext(Dispatchers.IO) { installer.uninstall(entry.id) }
            refreshLocal()
        }
    }

    // ── 补丁应用状态机（下载 → 校验 → 应用 → 结果报告）─────────────────
    var patchPhase by remember { mutableStateOf(PatchPhase.IDLE) }
    var patchProgress by remember { mutableFloatStateOf(-1f) }
    var patchFailure by remember { mutableStateOf<String?>(null) }
    var patchReport by remember { mutableStateOf<PatchReport?>(null) }

    // ── 安装前权限审阅（N-EXP-006 FR-004：未审阅不允许一键安装）───────
    // null = 对话框关闭；DATA/APK = 针对数据层/APK层安装进行审阅。
    var reviewFor by remember { mutableStateOf<ReviewTarget?>(null) }
    val onReviewConfirm: () -> Unit = {
        when (reviewFor) {
            ReviewTarget.DATA -> startDataInstall()
            ReviewTarget.APK -> startInstall()
            null -> Unit
        }
        reviewFor = null
    }
    val requestReview: (ReviewTarget) -> Unit = { target -> reviewFor = target }

    val applyPatch: (PluginPatch) -> Unit = { patch ->
        if (patchPhase != PatchPhase.DOWNLOADING && patchPhase != PatchPhase.VERIFYING &&
            patchPhase != PatchPhase.APPLYING
        ) {
            patchPhase = PatchPhase.DOWNLOADING
            patchFailure = null
            patchReport = null
            scope.launch {
                installer.downloadPatch(entry.id, patch) { read, total ->
                    patchProgress = if (total > 0) read.toFloat() / total else -1f
                }.onSuccess { file ->
                    patchPhase = PatchPhase.VERIFYING
                    if (!installer.verifyPatch(patch, file)) {
                        patchPhase = PatchPhase.FAILED
                        patchFailure = "SHA-256 校验失败 —— 拒绝应用（fail-closed）"
                    } else {
                        patchPhase = PatchPhase.APPLYING
                        val applied = withContext(Dispatchers.IO) {
                            runCatching { installer.applyDataPatch(entry.id, file) }
                        }
                        applied
                            .onSuccess { report ->
                                patchPhase = PatchPhase.DONE
                                patchReport = report
                                refreshLocal()
                            }
                            .onFailure {
                                patchPhase = PatchPhase.FAILED
                                patchFailure = it.message ?: "补丁应用失败（已回滚）"
                            }
                    }
                }.onFailure {
                    patchPhase = PatchPhase.FAILED
                    patchFailure = it.message ?: "补丁下载失败"
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        DetailBar(title = entry.name, onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                end = 20.dp,
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Hero（液态玻璃，采样主内容）────────────────────────────
            item {
                AbyssGlassSurface(
                    backdrop = backdrop,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = entry.icon ?: "🧩",
                                    style = MaterialTheme.typography.headlineMedium,
                                )
                            }
                            Spacer(Modifier.size(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = entry.id,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.size(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusChip("通道 v${entry.version}", ChipTone.OK)
                            StatusChip(categoryLabel(entry.category), ChipTone.INFO)
                            if (localManifest != null) {
                                StatusChip("本机 v${localManifest!!.version}", ChipTone.OK)
                            } else {
                                StatusChip("未安装", ChipTone.WARN)
                            }
                        }
                    }
                }
            }

            // ── 描述 ─────────────────────────────────────────────────
            // UI-001：entry.summary 通道字段保留在 PluginIndexEntry（渠道
            // 数据契约不动），但插件卡片不再渲染描述段。

            // ── 安装（AP：数据框架 + APK 双形态）───────────────────────
            item {
                InfoCard(title = "安装插件（AP）") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // 数据层安装（真实可用：manifest 原子落盘 + 版本账本）
                        // N-EXP-006 FR-004：未审阅不允许一键静默安装，点击按钮
                        // 先走权限审阅对话框，用户勾选确认后才真正启动安装管线。
                        DataInstallUi(
                            phase = dataPhase,
                            failure = dataFailure,
                            installed = localManifest != null,
                            onInstall = { requestReview(ReviewTarget.DATA) },
                            onRetry = startDataInstall,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        // APK 层安装（资产发布后全链路生效）
                        InstallFlowUi(
                            phase = phase,
                            progress = progress,
                            failure = failure,
                            apkPublished = entry.apk != null,
                            onInstall = { requestReview(ReviewTarget.APK) },
                            onRetry = startInstall,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        InfoRow(label = "仓库", value = entry.repo)
                        InfoRow(label = "最低宿主", value = "v${entry.minApp}")
                        InfoRow(
                            label = "资产状态",
                            value = if (entry.apk != null) "APK 已发布" else "APK 待发布",
                        )
                        if (localManifest != null) {
                            InfoRow(label = "本机版本", value = "v${localManifest!!.version}")
                            InfoRow(label = "安装目录", value = "filesDir/plugins/${entry.id}/")
                        }
                    }
                }
            }

            // ── 已安装管理（卸载）──────────────────────────────────────
            if (localManifest != null) {
                item {
                    InfoCard(title = "已安装管理") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "移除本机安装登记与数据目录（补丁产物一并清除）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.size(10.dp))
                            OutlinedButton(onClick = { uninstallConfirm = true }) { Text("卸载") }
                        }

                        // APK 本体卸载引导（v0.3.0）：独立 APK 插件经系统安装器
                        // 安装，需在系统侧卸载 —— 从缓存 APK 解析包名直达系统卸载页；
                        // 缓存已清时降级为系统应用设置列表
                        if (entry.apk != null) {
                            val context = LocalContext.current
                            Spacer(Modifier.size(10.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(Modifier.size(10.dp))
                            val cachedApk = remember(localManifest) {
                                installer.cachedApkFile(entry)
                            }
                            val apkPackage = remember(cachedApk) {
                                cachedApk?.let {
                                    runCatching {
                                        context.packageManager
                                            .getPackageArchiveInfo(it.path, 0)
                                            ?.packageName
                                    }.getOrNull()
                                }
                            }
                            Text(
                                text = "APK 本体卸载（系统安装器管理）：" +
                                    if (apkPackage != null) {
                                        "已识别包名 $apkPackage，点击直达系统卸载确认页"
                                    } else {
                                        "本地缓存已清理，无法解析包名，请到系统应用管理手动卸载"
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.size(8.dp))
                            if (apkPackage != null) {
                                OutlinedButton(onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                Intent.ACTION_UNINSTALL_PACKAGE,
                                                Uri.parse("package:$apkPackage"),
                                            ),
                                        )
                                    }
                                }) { Text("卸载 APK 本体") }
                            } else {
                                OutlinedButton(onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS))
                                    }
                                }) { Text("打开系统应用设置") }
                            }
                        }
                    }
                }
            }

            // ── 权限 ─────────────────────────────────────────────────
            // N-EXP-006：结构化权限透明化卡（分组展示 + 外传标注 +
            // 审阅并安装入口）。已装插件不显示安装入口，仅展示权限明细。
            item {
                PermissionAuditCard(
                    entry = entry,
                    onReviewForInstall = if (localManifest == null) { {
                        reviewFor = ReviewTarget.DATA
                    } } else null,
                )
            }

            // ── Shizuku 独立授权（独立 APK 插件的 per-app 授权声明）────
            entry.shizuku?.let { decl ->
                item {
                    InfoCard(title = "Shizuku 授权") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusChip(
                                text = if (decl.ownAuth) "需要独立授权" else "随宿主授权",
                                tone = if (decl.ownAuth) ChipTone.WARN else ChipTone.OK,
                            )
                            Text(
                                text = if (decl.ownAuth) {
                                    "本插件为独立 APK，Shizuku 授权按应用包名逐一发放 —— " +
                                        "安装后需在 Shizuku 内单独授权一次（宿主的授权不能继承给插件）。"
                                } else {
                                    "本插件的 Shizuku 调用运行在宿主进程内，宿主授权后即可间接使用。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (decl.reason.isNotBlank()) {
                                Text(
                                    text = "用途：${decl.reason}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // ── 补丁更新通道（含应用闭环）─────────────────────────────
            item {
                SectionHeader(
                    title = "补丁更新",
                )
                when (val ch = channelState) {
                    is UiState.Loading -> LoadingState("读取补丁通道…")

                    is UiState.Error -> ErrorState("补丁通道读取失败：${ch.message}") {
                        channelState = UiState.Loading
                        scope.launch {
                            channelState = channelClient.fetchPatchChannel(channelUrl).toUiState()
                        }
                    }

                    is UiState.Data -> PatchChannelCard(
                        channel = ch.value,
                        installer = installer,
                        pluginId = entry.id,
                        localVersion = localManifest?.version,
                        patchPhase = patchPhase,
                        patchProgress = patchProgress,
                        patchFailure = patchFailure,
                        patchReport = patchReport,
                        onApply = applyPatch,
                    )
                }
            }

            // ── 路线图 ───────────────────────────────────────────────
            if (entry.roadmap.isNotEmpty()) {
                item {
                    InfoCard(title = "插件路线图") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            entry.roadmap.forEach { step ->
                                Text(
                                    text = "· $step",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (uninstallConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { uninstallConfirm = false },
            title = { Text("卸载「${entry.name}」？") },
            text = {
                Text(
                    "将移除本机安装登记（plugin.json）与插件数据目录（含补丁产物与备份）。" +
                        "插件仓库侧的通道数据不受影响，可随时重新安装。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = doUninstall) { Text("卸载") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { uninstallConfirm = false }) { Text("取消") }
            },
        )
    }

    // ── 安装前权限审阅对话框（N-EXP-006 FR-004）────────────────────
    // 点击「审阅并安装」/「安装插件（数据框架）」/「安装插件（APK）」时打开；
    // 用户必须勾选「我已逐条阅读并接受」复选框，「确认安装」按钮才会启用。
    if (reviewFor != null) {
        PermissionReviewDialog(
            entry = entry,
            onDismiss = { reviewFor = null },
            onConfirm = onReviewConfirm,
        )
    }
}

@Composable
private fun DataInstallUi(
    phase: DataInstallPhase,
    failure: String?,
    installed: Boolean,
    onInstall: () -> Unit,
    onRetry: () -> Unit = onInstall,
) {
    Column {
        Text(
            text = "数据层框架安装：索引项 manifest 经校验（id 形态 / SemVer / 宿主下限）后" +
                "原子落盘为本机安装登记（版本账本与补丁通道随即可用）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(10.dp))
        when (phase) {
            DataInstallPhase.IDLE -> {
                if (installed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusChip("已登记", ChipTone.OK)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "数据层已安装（可在下方应用补丁或升级版本）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Button(onClick = onInstall) { Text("安装插件（数据框架）") }
                }
            }

            DataInstallPhase.WORKING -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("校验并落盘 manifest…", style = MaterialTheme.typography.bodySmall)
            }

            DataInstallPhase.DONE -> Text(
                "数据层安装完成，本机登记已建立",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )

            DataInstallPhase.FAILED -> Column {
                Text(
                    text = failure ?: "安装失败",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.size(8.dp))
                Button(onClick = onRetry) { Text("重试") }
            }
        }
    }
}

@Composable
private fun PatchChannelCard(
    channel: PluginPatchChannel,
    installer: PluginInstaller,
    pluginId: String,
    localVersion: String?,
    patchPhase: PatchPhase,
    patchProgress: Float,
    patchFailure: String?,
    patchReport: PatchReport?,
    onApply: (PluginPatch) -> Unit,
) {
    InfoCard(title = "通道 · 最新 v${channel.latest}") {
        if (channel.patches.isEmpty()) {
            Text(
                text = "补丁通道已建立（首发版本）" +
                    "后续以 abyss-patch-v1 数据补丁增量下发（失败自动回滚）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 可应用补丁（from 命中本机版本）置顶，带应用按钮
                val applicable = if (localVersion != null) {
                    installer.updatablePatch(pluginId, channel)
                } else {
                    null
                }

                if (applicable != null) {
                    PatchApplyUi(
                        patch = applicable,
                        phase = patchPhase,
                        progress = patchProgress,
                        failure = patchFailure,
                        report = patchReport,
                        onApply = { onApply(applicable) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                } else if (localVersion == null) {
                    Text(
                        text = "安装数据层框架后，此处将出现可应用的增量补丁",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(2.dp))
                } else {
                    Text(
                        text = "本机 v$localVersion 已是通道可应用链最新（通道最新 v${channel.latest}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(2.dp))
                }

                channel.patches.take(8).forEach { patch ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusChip("v${patch.from} → v${patch.to}", ChipTone.INFO)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = patch.notes ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PatchApplyUi(
    patch: PluginPatch,
    phase: PatchPhase,
    progress: Float,
    failure: String?,
    report: PatchReport?,
    onApply: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip("v${patch.from} → v${patch.to}", ChipTone.WARN)
            Spacer(Modifier.size(8.dp))
            Text(
                text = patch.notes ?: "增量补丁",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.size(8.dp))
        when (phase) {
            PatchPhase.IDLE -> Button(onClick = onApply) { Text("下载并应用补丁") }

            PatchPhase.DOWNLOADING -> {
                Text("下载补丁包…", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.size(6.dp))
                if (progress >= 0f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            PatchPhase.VERIFYING -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("SHA-256 校验中…", style = MaterialTheme.typography.bodySmall)
            }

            PatchPhase.APPLYING -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("应用补丁（备份 → 替换 → 失败回滚）…", style = MaterialTheme.typography.bodySmall)
            }

            PatchPhase.DONE -> Column {
                Text(
                    text = buildString {
                        append("补丁应用完成：v")
                        append(report?.toVersion ?: patch.to)
                        if (report != null) {
                            append("（替换 ")
                            append(report.replaced.size)
                            append(" · 新增 ")
                            append(report.added.size)
                            append(" · 删除 ")
                            append(report.deleted.size)
                            append("）")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            PatchPhase.FAILED -> Column {
                Text(
                    text = failure ?: "补丁应用失败",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.size(8.dp))
                Button(onClick = onApply) { Text("重试应用") }
            }
        }
    }
}

@Composable
private fun InstallFlowUi(
    phase: InstallPhase,
    progress: Float,
    failure: String?,
    apkPublished: Boolean,
    onInstall: () -> Unit,
    onRetry: () -> Unit = onInstall,
) {
    Column {
        when (phase) {
            InstallPhase.IDLE -> {
                Text(
                    text = if (apkPublished) {
                        "APK 层安装：下载 → SHA-256 校验 → 系统安装器（同签名门禁 + 未知来源确认）"
                    } else {
                        "APK 层安装：仓库 APK 资产尚未发布（流水线已就绪，资产发布后即全链路生效）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(10.dp))
                Button(onClick = onInstall, enabled = apkPublished) { Text("安装插件（APK）") }
            }

            InstallPhase.DOWNLOADING -> {
                Text("下载安装包…", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.size(6.dp))
                if (progress >= 0f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            InstallPhase.VERIFYING -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("SHA-256 校验中…", style = MaterialTheme.typography.bodySmall)
            }

            InstallPhase.INSTALLING -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("提交系统安装器（确认页如弹出请允许）…", style = MaterialTheme.typography.bodySmall)
            }

            InstallPhase.REGISTERING -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("同步版本账本（plugin.json）…", style = MaterialTheme.typography.bodySmall)
            }

            InstallPhase.DONE -> Text(
                "安装完成（APK 已就位，版本登记同步至通道）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )

            InstallPhase.FAILED -> Column {
                Text(
                    text = failure ?: "安装失败",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.size(8.dp))
                Button(onClick = onRetry, enabled = apkPublished) { Text("重试安装") }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(8.dp))
        content()
    }
}

internal fun categoryLabel(id: String): String = when (id) {
    "terminal" -> "终端"
    "system" -> "系统"
    "sandbox" -> "沙箱"
    "disguise" -> "伪装"
    "media" -> "媒体"
    "automation" -> "自动化"
    "desktop" -> "桌面"
    "network" -> "网络"
    "data" -> "数据"
    "tools" -> "工具"
    else -> id
}

// ─── N-EXP-006 插件权限透明化面板（M1）────────────────────────────
//
// 设计原则（严格对齐 N-EXP-006 FR-001~FR-004）：
//  FR-001 权限条目由常量映射为人话说明，禁止直接展示原始权限字符串。
//  FR-002 独立 Shizuku 授权与宿主授权分别标注，展示 reason 全文。
//  FR-003 涉及网络外传（VirusTotal / 镜像下载 / Web 控制台）的条目
//         单独分组并标注默认状态。
//  FR-004 安装确认页需用户显式确认权限清单，不允许一键静默同意。

/**
 * 把 plugin.json 中 permissions: List<String>（旧字段）的原始
 * 权限 ID 映射为人话 PluginPermission，作为 declaredPermissions
 * 缺失时的兜底展示。插件仓逐步迁移到结构化 declaredPermissions
 * 后此映射仅起历史兼容作用。
 */
private fun legacyPermissionFromId(rawId: String): PluginPermission {
    val (id, label, risk, group, transmits, dataCategory, thirdParty, desc) = when (rawId) {
        "android.permission.INTERNET" -> PermissionRow(
            id = "internet",
            label = "访问网络（本组织通道）",
            risk = "低",
            group = PermissionGroup.NETWORK_INTERNAL,
            desc = "用于拉取插件更新、补丁通道与镜像清单，仅访问 abyss-tool 组织官方域名。",
        )
        "shizuku_shell", "shizuku" -> PermissionRow(
            id = "shizuku_shell",
            label = "执行 Shizuku Shell 命令",
            risk = "高",
            group = PermissionGroup.EXECUTION,
            desc = "通过 Shizuku 以 adb/shell 身份执行系统级指令（如 pm/cmd/appops）。",
        )
        "root" -> PermissionRow(
            id = "root",
            label = "执行 Root Shell 命令",
            risk = "高",
            group = PermissionGroup.EXECUTION,
            desc = "通过 su 以 root 身份执行系统级指令（最高权限）。",
        )
        "overlay", "android.permission.SYSTEM_ALERT_WINDOW" -> PermissionRow(
            id = "overlay",
            label = "显示悬浮窗",
            risk = "中",
            group = PermissionGroup.OVERLAY,
            desc = "在其它应用之上显示浮动控件（如灵动岛悬浮球、快捷入口）。",
        )
        "install_listener" -> PermissionRow(
            id = "install_listener",
            label = "监听应用安装/卸载事件",
            risk = "中",
            group = PermissionGroup.APP_OBSERVE,
            desc = "检测新安装/卸载的应用包名（用于病毒拦截插件的安装时扫描）。",
        )
        "package_query" -> PermissionRow(
            id = "package_query",
            label = "读取已装应用列表",
            risk = "低",
            group = PermissionGroup.APP_OBSERVE,
            desc = "枚举本机已安装的应用（用于应用管理、备份识别等）。",
        )
        "storage", "android.permission.WRITE_EXTERNAL_STORAGE" -> PermissionRow(
            id = "storage",
            label = "读写存储",
            risk = "中",
            group = PermissionGroup.USER_DATA,
            desc = "在插件私有目录与共享存储中读写文件（备份导出/媒体处理）。",
        )
        "cloud_hash_lookup" -> PermissionRow(
            id = "cloud_hash_lookup",
            label = "云查毒（文件哈希外传）",
            risk = "高",
            group = PermissionGroup.NETWORK_EXTERNAL,
            transmits = true,
            dataCategory = "文件 SHA-256 哈希",
            thirdParty = "VirusTotal 公共 API",
            desc = "将待判定文件的哈希值提交到 VirusTotal 查询命中结果；**不上传文件本体**，但哈希可被第三方关联到样本库。默认关闭。",
        )
        "external_api" -> PermissionRow(
            id = "external_api",
            label = "访问第三方 API",
            risk = "中",
            group = PermissionGroup.NETWORK_EXTERNAL,
            transmits = true,
            desc = "向宿主信任域外的第三方服务发起网络请求。",
        )
        "web_console" -> PermissionRow(
            id = "web_console",
            label = "Web 控制台（本地 HTTP 服务）",
            risk = "中",
            group = PermissionGroup.NETWORK_EXTERNAL,
            desc = "在本机启动 HTTP 服务供浏览器访问；不对外监听，但局域网内其他设备可能访问。",
        )
        else -> PermissionRow(
            id = rawId,
            label = rawId.substringAfterLast('.'),
            risk = "低",
            group = PermissionGroup.OTHER,
            desc = "插件声明的权限（未在本版本词典内）。",
        )
    }
    return PluginPermission(
        id = id,
        required = false,
        default = false,
        label = label,
        risk = risk,
        dataTransmitted = transmits,
        dataCategory = dataCategory,
        thirdParty = thirdParty,
        description = desc,
    )
}

private data class PermissionRow(
    val id: String,
    val label: String,
    val risk: String,
    val group: PermissionGroup,
    val transmits: Boolean = false,
    val dataCategory: String? = null,
    val thirdParty: String? = null,
    val desc: String? = null,
)

/** 合并 declaredPermissions（新）与 permissions（旧）的最终展示列表。 */
internal fun resolvePermissionsForDisplay(entry: PluginIndexEntry): List<PluginPermission> {
    val declared = entry.declaredPermissions
    if (declared.isNotEmpty()) return declared
    return entry.permissions.map { legacyPermissionFromId(it) }
}

/** 分组名 → 人话标签。 */
private fun PermissionGroup.displayName(): String = when (this) {
    PermissionGroup.EXECUTION -> "执行权限（Shizuku / Root）"
    PermissionGroup.NETWORK_INTERNAL -> "网络（本组织通道）"
    PermissionGroup.NETWORK_EXTERNAL -> "网络外传（第三方服务）"
    PermissionGroup.OVERLAY -> "界面覆盖（悬浮窗）"
    PermissionGroup.APP_OBSERVE -> "观察其它应用"
    PermissionGroup.USER_DATA -> "用户数据读写"
    PermissionGroup.OTHER -> "其它"
}

private fun riskTone(risk: String): ChipTone = when (risk) {
    "高" -> ChipTone.ERROR
    "中" -> ChipTone.WARN
    "低" -> ChipTone.INFO
    else -> ChipTone.NEUTRAL
}

/**
 * 插件详情页「权限需求」卡——按分组展示，外传项显式标注
 * 「会上传数据」，Shizuku 独立授权 reason 全文展示。
 */
@Composable
internal fun PermissionAuditCard(
    entry: PluginIndexEntry,
    onReviewForInstall: (() -> Unit)? = null,
) {
    val permissions = remember(entry.id) { resolvePermissionsForDisplay(entry) }
    val audit = remember(permissions) { PermissionAudit.audit(entry.id, permissions) }
    val grouped = remember(permissions) { permissions.groupBy { it.group } }

    InfoCard(title = "权限需求") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // 顶部摘要：风险项数量 + 外传项数量
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusChip("共 ${permissions.size} 项", ChipTone.INFO)
                if (audit.riskyDefaultOn > 0) {
                    StatusChip("${audit.riskyDefaultOn} 项默认开启且有风险", ChipTone.ERROR)
                }
                if (audit.externalTransmissions.isNotEmpty()) {
                    StatusChip(
                        "${audit.externalTransmissions.size} 项会向第三方传数据",
                        ChipTone.WARN,
                    )
                }
                if (permissions.isEmpty()) {
                    StatusChip("无静态权限", ChipTone.OK)
                }
            }

            if (permissions.isEmpty()) {
                Text(
                    text = "本插件未声明任何特殊权限（仅使用插件沙箱内能力）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // 按 FR-003：外传分组置顶展示，保证用户第一眼看到
                val orderedGroups = grouped.keys.sortedBy {
                    when (it) {
                        PermissionGroup.NETWORK_EXTERNAL -> 0
                        PermissionGroup.EXECUTION -> 1
                        PermissionGroup.USER_DATA -> 2
                        PermissionGroup.OVERLAY -> 3
                        PermissionGroup.APP_OBSERVE -> 4
                        PermissionGroup.NETWORK_INTERNAL -> 5
                        PermissionGroup.OTHER -> 6
                    }
                }
                orderedGroups.forEach { group ->
                    PermissionGroupBlock(
                        title = group.displayName(),
                        permissions = grouped[group] ?: emptyList(),
                        highlightTransmission = group == PermissionGroup.NETWORK_EXTERNAL,
                    )
                }
            }

            // 安装前审阅入口（FR-004：未审阅不允许安装）
            if (onReviewForInstall != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "安装前请逐条审阅权限，确认后再安装",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = onReviewForInstall) { Text("审阅并安装") }
                }
            }
        }
    }
}

@Composable
private fun PermissionGroupBlock(
    title: String,
    permissions: List<PluginPermission>,
    highlightTransmission: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (highlightTransmission) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        )
        permissions.forEach { perm ->
            PermissionItemRow(perm, highlightTransmission)
        }
    }
}

@Composable
private fun PermissionItemRow(perm: PluginPermission, highlightTransmission: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (highlightTransmission && perm.dataTransmitted)
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = perm.label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            StatusChip("风险：${perm.risk}", riskTone(perm.risk))
            if (perm.required) {
                StatusChip("必需", ChipTone.WARN)
            } else {
                StatusChip(
                    if (perm.default) "默认开启" else "默认关闭",
                    if (perm.default) ChipTone.WARN else ChipTone.OK,
                )
            }
        }
        if (perm.dataTransmitted) {
            Spacer(Modifier.size(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip("⚠ 会上传数据", ChipTone.ERROR)
                perm.dataCategory?.let { StatusChip(it, ChipTone.NEUTRAL) }
                perm.thirdParty?.let {
                    StatusChip("接收方：$it", ChipTone.NEUTRAL)
                }
            }
        }
        perm.description?.let {
            Spacer(Modifier.size(4.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 安装前权限审阅确认对话框（FR-004）。
 *
 * - 展示所有权限条目（人话 + 风险标签 + 外传标注）；
 * - 独立 Shizuku 授权与宿主授权分别标注 reason 全文；
 * - 用户必须勾选「我已逐条阅读并接受上述权限」复选框，「确认安装」按钮
 *   才会启用，杜绝一键静默同意。
 */
@Composable
internal fun PermissionReviewDialog(
    entry: PluginIndexEntry,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val permissions = remember(entry.id) { resolvePermissionsForDisplay(entry) }
    val audit = remember(permissions) { PermissionAudit.audit(entry.id, permissions) }
    var acknowledged by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("安装前审阅：${entry.name}") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "请逐条审阅本插件申请的权限，勾选确认后才可安装",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (audit.externalTransmissions.isNotEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
                            .padding(10.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "⚠ 存在向第三方传数据的能力",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.error,
                            )
                            audit.externalTransmissions.forEach { p ->
                                Text(
                                    text = "· ${p.label}${p.thirdParty?.let { " → $it" } ?: ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                // Shizuku 独立授权 reason 全文（FR-002）
                entry.shizuku?.let { decl ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f))
                            .padding(10.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = if (decl.ownAuth) "🔐 需要独立 Shizuku 授权"
                                else "🔐 使用宿主 Shizuku 授权",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (decl.reason.isNotBlank()) {
                                Text(
                                    text = "用途：${decl.reason}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                // 可滚动的权限清单（对话框内 LazyColumn 不支持高度自适应，
                // 用 Column + heightIn 约束最大高度后外层可滚动容器承载）
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                ) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(permissions) { perm ->
                            PermissionItemRow(perm, highlightTransmission = perm.dataTransmitted)
                        }
                        if (permissions.isEmpty()) {
                            item {
                                Text(
                                    text = "本插件未声明任何特殊权限。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // 显式确认复选框（FR-004：必须勾选才能继续）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { acknowledged = !acknowledged },
                        ),
                ) {
                    Checkbox(
                        checked = acknowledged,
                        onCheckedChange = { acknowledged = it },
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = "我已逐条阅读并接受上述权限与风险",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                enabled = acknowledged,
            ) { Text("确认安装") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}


// ═══════════════════════════════════════════════════════════════════════
//  深渊agent 置顶直达（FEAT-037）
// ═══════════════════════════════════════════════════════════════════════

/** 深渊agent 下载安装阶段（与自更新 SelfUpdate 同构的阶段语义）。 */
private enum class AgentPhase { IDLE, DOWNLOADING, INSTALLING, DONE, FAILED }

/** 体积人话（MB 级 APK 资产展示用；settings 侧同名函数不可跨模块私有复用）。 */
private fun agentBytesHuman(bytes: Long): String = when {
    bytes >= 1 shl 30 -> String.format(java.util.Locale.ROOT, "%.2f GB", bytes / 1073741824.0)
    bytes >= 1 shl 20 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0)
    bytes >= 1 shl 10 -> String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * 「深渊agent」置顶卡：姊妹项目 Ultra-Guru/Android-Guru-Agent 的独立 APK
 * 直达下载 —— 通道清单（Guru 发布仓 version.json）→ 多镜像下载
 * （MirrorRouter 自动择优回退）→ sha256 强校验（fail-closed）→ 系统安装器。
 *
 * UiState 三态纪律：清单 Loading / Error(重试) / Data，加载中禁止假数据；
 * 未知变体键不猜（assetOf 显式命中）。安装走 ApkInstallGateway（插件 APK
 * 同路径，无发布者指纹门禁），非插件、非自更新 —— 确认对话框如实声明。
 */
@Composable
private fun AgentPinnedCard(manager: AgentReleaseManager) {
    val scope = rememberCoroutineScope()

    var manifestState by remember { mutableStateOf<UiState<AgentReleaseManifest>>(UiState.Loading) }
    var retryKey by remember { mutableStateOf(0) }
    var variantKey by remember { mutableStateOf<String?>(null) }

    var phase by remember { mutableStateOf(AgentPhase.IDLE) }
    var progress by remember { mutableFloatStateOf(-1f) }
    var failure by remember { mutableStateOf<String?>(null) }
    var showConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(retryKey) {
        manifestState = UiState.Loading
        manifestState = withContext(Dispatchers.IO) {
            runCatching { manager.fetchManifest() }.toUiState()
        }
        // 设备 ABI 默认变体：arm64-v8a 设备优先 arm64（体积最小），否则 universal
        val manifest = (manifestState as? UiState.Data)?.value ?: return@LaunchedEffect
        variantKey = when {
            manifest.download.containsKey("arm64") &&
                android.os.Build.SUPPORTED_ABIS.any { it.startsWith("arm64-v8a") } -> "arm64"
            else -> manifest.defaultVariantKey()
        }
    }

    AbyssGlassSurface(
        backdrop = null,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "🤖", style = MaterialTheme.typography.headlineSmall)
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "深渊agent",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Ultra-Guru/Android-Guru-Agent · 置顶直达",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (val s = manifestState) {
                    is UiState.Data -> StatusChip(
                        text = "v" + s.value.versionName,
                        tone = ChipTone.INFO,
                    )
                    else -> {}
                }
            }
            Spacer(Modifier.size(10.dp))

            when (val s = manifestState) {
                is UiState.Loading -> LoadingState("正在拉取深渊agent通道清单…", Modifier.fillMaxWidth())
                is UiState.Error -> ErrorState(
                    message = "深渊agent通道拉取失败：" + (s.message ?: ""),
                    modifier = Modifier.fillMaxWidth(),
                    onRetry = { retryKey++ },
                )
                is UiState.Data -> {
                    val manifest = s.value
                    val asset = manifest.assetOf(variantKey)
                    val busy = phase == AgentPhase.DOWNLOADING || phase == AgentPhase.INSTALLING

                    // 变体选择（通道内实际存在的键，未知键不猜）
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        manifest.download.keys.forEach { key ->
                            androidx.compose.material3.FilterChip(
                                selected = variantKey == key,
                                onClick = { if (!busy) variantKey = key },
                                label = { Text(key) },
                            )
                        }
                    }
                    Spacer(Modifier.size(8.dp))

                    InfoRow(
                        label = "版本 / 发布",
                        value = manifest.resolvedTag + " · " + (manifest.publishedAt ?: "未知时间"),
                    )
                    if (asset != null) {
                        InfoRow(
                            label = "大小 / SHA-256",
                            value = agentBytesHuman(asset.sizeBytes) + " · sha256 " +
                                (asset.sha256?.take(8) ?: "—") + "…",
                        )
                    }

                    Spacer(Modifier.size(8.dp))
                    if (phase == AgentPhase.DOWNLOADING) {
                        if (progress >= 0f) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        Text(
                            text = "下载中（多镜像自动择优回退）…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (phase == AgentPhase.INSTALLING) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            text = "已交系统安装器，等待确认…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Button(
                            onClick = { showConfirm = true },
                            enabled = asset != null && !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (phase == AgentPhase.DONE) "再次下载安装" else "下载并安装")
                        }
                    }
                    if (phase == AgentPhase.DONE) {
                        Text(
                            text = "安装流程完成 ✓（若系统弹窗被取消，可重新安装）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (phase == AgentPhase.FAILED && failure != null) {
                        Text(
                            text = failure.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    val a = asset
                    if (a != null && showConfirm && !busy) {
                        AlertDialog(
                            onDismissRequest = { showConfirm = false },
                            title = { Text("安装深渊agent " + manifest.resolvedTag) },
                            text = {
                                Text(
                                    text = "来源：Ultra-Guru/Android-Guru-Agent（姊妹项目独立 APK，非插件）\n" +
                                        "变体：" + variantKey + " · " + agentBytesHuman(a.sizeBytes) + "\n" +
                                        "SHA-256：" + a.sha256 + "\n\n" +
                                        "下载经多镜像加速并强校验摘要后交系统安装器",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        showConfirm = false
                                        phase = AgentPhase.DOWNLOADING
                                        failure = null
                                        scope.launch {
                                            try {
                                                val file = withContext(Dispatchers.IO) {
                                                    manager.downloadVerified(a) { read, total ->
                                                        progress = if (total > 0) read.toFloat() / total else -1f
                                                    }
                                                }
                                                phase = AgentPhase.INSTALLING
                                                val installed = withContext(Dispatchers.IO) {
                                                    // B-18：携带清单期望（versionCode + 证书指纹），
                                                    // 网关提交安装器前硬校验
                                                    manager.install(file, manifest)
                                                }
                                                installed
                                                    .onSuccess { phase = AgentPhase.DONE }
                                                    .onFailure {
                                                        phase = AgentPhase.FAILED
                                                        failure = it.message ?: "安装失败"
                                                    }
                                            } catch (e: Exception) {
                                                phase = AgentPhase.FAILED
                                                failure = e.message ?: "下载失败"
                                            }
                                        }
                                    },
                                ) { Text("下载并安装") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showConfirm = false }) { Text("取消") }
                            },
                        )
                    }
                }
            }
        }
    }
}
