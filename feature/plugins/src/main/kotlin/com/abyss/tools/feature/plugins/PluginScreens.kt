package com.abyss.tools.feature.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import com.abyss.tools.core.common.AbyssChannels
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
import com.abyss.tools.core.plugin.PluginChannelClient
import com.abyss.tools.core.plugin.PluginIndex
import com.abyss.tools.core.plugin.PluginIndexEntry
import com.abyss.tools.core.plugin.PluginInstaller
import com.abyss.tools.core.plugin.PluginPatch
import com.abyss.tools.core.plugin.PluginPatchChannel
import com.abyss.tools.core.plugin.PluginRepository
import com.abyss.tools.core.update.PatchReport
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 插件中心 —— 插件索引（Abyss-Plugins / plugins.json）的浏览页。
 *
 * v0.1.1 起「我的插件」分区落地：本机已装清单（installer.installed()）
 * 置顶展示；插件点击进入具体插件详情（APK / 数据双形态安装 AP 框架 +
 * 补丁通道应用闭环）。
 *
 * @param stateVersion 插件本机状态版本号（安装 / 卸载 / 补丁应用后自增，
 *   驱动「我的插件」与安装徽章重读磁盘）。
 * @param onPluginsChanged 插件本机状态变化回调（AbyssRoot 持有并自增版本号）。
 */
@Composable
fun PluginHubScreen(
    repository: PluginRepository,
    channelClient: PluginChannelClient,
    installer: PluginInstaller,
    onBack: () -> Unit,
    onOpenPlugin: (String) -> Unit,
    stateVersion: Int = 0,
    onPluginsChanged: () -> Unit = {},
) {
    var indexState by remember { mutableStateOf<UiState<PluginIndex>>(UiState.Loading) }
    var retryKey by remember { mutableStateOf(0) }
    var categoryFilter by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(retryKey) {
        indexState = UiState.Loading
        indexState = repository.fetchIndex().toUiState()
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
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column {
                Text(
                    text = "插件中心",
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
                message = "插件索引拉取失败：${state.message}\n（首个 APK 发布前，插件中心仓已建立索引骨架）",
                modifier = Modifier.fillMaxSize(),
                onRetry = { retryKey++ },
            )

            is UiState.Data -> {
                val entries = state.value.plugins
                val categories = entries.map { it.category }.distinct()
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        bottom = 32.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // ── 我的插件（本机已装）───────────────────────────────
                    if (installedPlugins.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = "我的插件",
                                subtitle = "本机已装 ${installedPlugins.size} 个 · 数据层登记 + 补丁通道",
                                trailing = {
                                    androidx.compose.material3.TextButton(
                                        onClick = { checkUpdates(state.value) },
                                    ) { Text(if (updatesChecked) "重新检查" else "检查更新") }
                                },
                            )
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
                                                "已装插件均为通道可应用链的最新版本。",
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
                            InstalledPluginRow(
                                name = manifest.name,
                                id = manifest.id,
                                localVersion = manifest.version,
                                updatable = manifest.id in updatableIds,
                                onClick = { onOpenPlugin(manifest.id) },
                            )
                        }
                        item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
                        item { Box(Modifier.size(4.dp)) }
                    }

                    item {
                        SectionHeader(title = "插件生态", subtitle = "${entries.size} 个大插件仓库 · 独立 CI · 独立补丁通道")
                    }
                    item {
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
                    val visible = entries.filter { categoryFilter == null || it.category == categoryFilter }
                    items(
                        count = visible.size,
                        key = { "hub-$stateVersion-${visible[it].id}" },
                    ) { index ->
                        val entry = visible[index]
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

@Composable
private fun InstalledPluginRow(
    name: String,
    id: String,
    localVersion: String,
    updatable: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
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
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun PluginCard(entry: PluginIndexEntry, installed: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
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
            Spacer(Modifier.size(2.dp))
            Text(
                text = entry.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
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
                    shape = RoundedCornerShape(28.dp),
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
            item {
                InfoCard(title = "描述") {
                    Text(
                        text = entry.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── 安装（AP：数据框架 + APK 双形态）───────────────────────
            item {
                InfoCard(title = "安装插件（AP）") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // 数据层安装（真实可用：manifest 原子落盘 + 版本账本）
                        DataInstallUi(
                            phase = dataPhase,
                            failure = dataFailure,
                            installed = localManifest != null,
                            onInstall = startDataInstall,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        // APK 层安装（资产发布后全链路生效）
                        InstallFlowUi(
                            phase = phase,
                            progress = progress,
                            failure = failure,
                            apkPublished = entry.apk != null,
                            onInstall = startInstall,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        InfoRow(label = "仓库", value = entry.repo)
                        InfoRow(label = "最低宿主", value = "v${entry.minApp}")
                        InfoRow(
                            label = "资产状态",
                            value = if (entry.apk != null) "APK 已发布" else "APK 待发布（框架预览）",
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
                                text = "移除本机安装登记与数据目录（补丁产物一并清除）。",
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
                                text = "APK 本体卸载：独立 APK 插件由系统安装器管理。" +
                                    if (apkPackage != null) {
                                        "已识别包名 $apkPackage —— 点击直达系统卸载确认页。"
                                    } else {
                                        "本地缓存已清理，无法解析包名 —— 请到系统设置的「应用管理」中手动卸载。"
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
            item {
                InfoCard(title = "权限需求") {
                    if (entry.permissions.isEmpty()) {
                        Text(
                            "无静态权限（运行时按需申请）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            entry.permissions.take(3).forEach { StatusChip(it, ChipTone.NEUTRAL) }
                        }
                        if (entry.permissions.size > 3) {
                            Spacer(Modifier.size(6.dp))
                            Text(
                                "…等 ${entry.permissions.size} 项（安装时逐项确认）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
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
                    subtitle = "插件仓库 patches/index.json · abyss-patch-v1 数据补丁引擎（应用闭环已可用）",
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
}

@Composable
private fun DataInstallUi(
    phase: DataInstallPhase,
    failure: String?,
    installed: Boolean,
    onInstall: () -> Unit,
) {
    Column {
        Text(
            text = "数据层框架安装：索引项 manifest 经校验（id 形态 / SemVer / 宿主下限）后" +
                "原子落盘为本机安装登记 —— 安装态、版本账本与补丁通道随即可用。",
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
                            "数据层已安装 —— 可在下方应用补丁或升级版本。",
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
                "数据层安装完成 —— 本机登记已建立。",
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
                Button(onClick = onInstall) { Text("重试") }
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
                text = "补丁通道已建立，当前为首发版本 —— 后续更新将以数据补丁" +
                    "（abyss-patch-v1）形式增量下发：下载 → SHA-256 校验 → 备份 → 应用 → 失败自动回滚。",
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
                        text = "安装数据层框架后，此处将出现可应用的增量补丁。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(2.dp))
                } else {
                    Text(
                        text = "本机 v$localVersion 已是通道可应用链的最新（通道最新 v${channel.latest}）。",
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
) {
    Column {
        when (phase) {
            InstallPhase.IDLE -> {
                Text(
                    text = if (apkPublished) {
                        "APK 层安装：下载 → SHA-256 校验 → 系统安装器（同签名门禁 + 未知来源确认页自动转发）。"
                    } else {
                        "APK 层安装：插件仓库 APK 资产尚未发布 —— 流水线已就绪（下载 / 校验 / PackageInstaller 网关真实实现），资产发布后即全链路生效。"
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
                "安装完成 —— APK 已就位，本机版本登记已同步至通道版本。",
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
                Button(onClick = onInstall, enabled = apkPublished) { Text("重试安装") }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
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
