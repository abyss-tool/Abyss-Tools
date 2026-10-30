package com.abyss.tools.feature.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.abyss.tools.core.common.toUiState
import com.abyss.tools.core.common.UiState
import com.abyss.tools.core.designsystem.component.ChipTone
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
import com.abyss.tools.core.plugin.PluginPatchChannel
import com.abyss.tools.core.plugin.PluginRepository
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.launch

/**
 * 插件中心 —— 插件索引（Abyss-Plugins / plugins.json）的浏览页。
 * 插件点击进入具体插件详情（安装 AP 框架 + 补丁通道）。
 */
@Composable
fun PluginHubScreen(
    repository: PluginRepository,
    installer: PluginInstaller,
    onBack: () -> Unit,
    onOpenPlugin: (String) -> Unit,
) {
    var indexState by remember { mutableStateOf<UiState<PluginIndex>>(UiState.Loading) }
    var retryKey by remember { mutableStateOf(0) }
    var categoryFilter by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(retryKey) {
        indexState = UiState.Loading
        indexState = repository.fetchIndex().toUiState()
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
                    text = "索引源：Ultra-Guru/Abyss-Plugins · plugins.json",
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
                    item {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding(),
                        ) {
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
                    items(count = visible.size, key = { visible[it].id }) { index ->
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
 * 插件详情 —— 具体插件的安装 AP 流水线（框架）与补丁更新通道。
 */
@Composable
fun PluginDetailScreen(
    pluginId: String,
    repository: PluginRepository,
    channelClient: PluginChannelClient,
    installer: PluginInstaller,
    backdrop: Backdrop?,
    onBack: () -> Unit,
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
                    ErrorState("索引中不存在插件：$pluginId", Modifier.fillMaxSize())
                }
            } else {
                PluginDetailContent(
                    entry = entry,
                    channelClient = channelClient,
                    installer = installer,
                    backdrop = backdrop,
                    onBack = onBack,
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

private enum class InstallPhase { IDLE, DOWNLOADING, VERIFYING, INSTALLING, DONE, FAILED }

@Composable
private fun PluginDetailContent(
    entry: PluginIndexEntry,
    channelClient: PluginChannelClient,
    installer: PluginInstaller,
    backdrop: Backdrop?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // ── 补丁通道 ─────────────────────────────────────────────────────
    var channelState by remember {
        mutableStateOf<UiState<PluginPatchChannel>>(UiState.Loading)
    }
    val channelUrl = entry.patchChannel
        ?: "https://raw.githubusercontent.com/Ultra-Guru/${entry.id}/main/patches/index.json"
    LaunchedEffect(entry.id) {
        channelState = channelClient.fetchPatchChannel(channelUrl).toUiState()
    }

    // ── 安装流水线状态机 ─────────────────────────────────────────────
    var phase by remember { mutableStateOf(InstallPhase.IDLE) }
    var progress by remember { mutableFloatStateOf(-1f) }
    var failure by remember { mutableStateOf<String?>(null) }

    val startInstall: () -> Unit = {
        if (phase != InstallPhase.DOWNLOADING && phase != InstallPhase.VERIFYING) {
            phase = InstallPhase.DOWNLOADING
            failure = null
            scope.launch {
                installer.downloadApk(entry) { read, total ->
                    progress = if (total > 0) read.toFloat() / total else -1f
                }.onSuccess { file ->
                    phase = InstallPhase.VERIFYING
                    if (!installer.verifyApk(entry, file)) {
                        phase = InstallPhase.FAILED
                        failure = "SHA-256 校验失败 —— 拒绝安装（fail-closed）"
                    } else {
                        phase = InstallPhase.INSTALLING
                        installer.installApk(file)
                            .onSuccess { phase = InstallPhase.DONE }
                            .onFailure {
                                phase = InstallPhase.FAILED
                                failure = it.message ?: "安装失败"
                            }
                    }
                }.onFailure {
                    phase = InstallPhase.FAILED
                    failure = it.message ?: "下载失败"
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
        // ── Hero（液态玻璃，采样主内容）───────────────────────────────
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
                        StatusChip("v${entry.version}", ChipTone.OK)
                        StatusChip(categoryLabel(entry.category), ChipTone.INFO)
                        StatusChip(
                            if (installer.isInstalled(entry.id)) "已安装" else "未安装",
                            if (installer.isInstalled(entry.id)) ChipTone.OK else ChipTone.WARN,
                        )
                    }
                }
            }
        }

        // ── 描述 ─────────────────────────────────────────────────────
        item {
            InfoCard(title = "描述") {
                Text(
                    text = entry.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── 安装（AP）────────────────────────────────────────────────
        item {
            InfoCard(title = "安装插件（APK）") {
                Column {
                    InstallFlowUi(
                        phase = phase,
                        progress = progress,
                        failure = failure,
                        apkPublished = entry.apk != null,
                        onInstall = startInstall,
                    )
                    Spacer(Modifier.size(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.size(10.dp))
                    InfoRow(label = "仓库", value = entry.repo)
                    InfoRow(label = "最低宿主", value = "v${entry.minApp}")
                    InfoRow(label = "资产状态", value = if (entry.apk != null) "APK 已发布" else "APK 待发布（框架预览）")
                }
            }
        }

        // ── 权限 ─────────────────────────────────────────────────────
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

        // ── 补丁更新通道 ─────────────────────────────────────────────
        item {
            SectionHeader(
                title = "补丁更新",
                subtitle = "插件仓库 patches/index.json · abyss-patch-v1 数据补丁引擎（已可用）",
            )
            when (val ch = channelState) {
                is UiState.Loading -> LoadingState("读取补丁通道…")
                is UiState.Error -> ErrorState("补丁通道读取失败：${ch.message}") {
                    channelState = UiState.Loading
                    scope.launch {
                        channelState = channelClient.fetchPatchChannel(channelUrl).toUiState()
                    }
                }

                is UiState.Data -> {
                    InfoCard(title = "通道 · 最新 v${ch.value.latest}") {
                        if (ch.value.patches.isEmpty()) {
                            Text(
                                text = "补丁通道已建立，当前为首发版本 —— 后续更新将以数据补丁" +
                                    "（abyss-patch-v1）形式增量下发：下载 → SHA-256 校验 → 备份 → 应用 → 失败自动回滚。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ch.value.patches.take(8).forEach { patch ->
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
            }
        }

        // ── 路线图 ───────────────────────────────────────────────────
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
                        "下载 → 校验 → 安装，全程带 SHA-256 完整性门禁。"
                    } else {
                        "框架预览期：插件仓库已建立（manifest / 补丁通道 / CI），APK 资产自 v0.2 发布流水线起发布。" +
                            "安装流水线（下载 / 校验 / 安装）已就绪。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(10.dp))
                Button(onClick = onInstall) { Text("安装插件（AP）") }
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
                Text("提交系统安装器…", style = MaterialTheme.typography.bodySmall)
            }

            InstallPhase.DONE -> Text(
                "安装完成。",
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
                Button(onClick = onInstall) { Text("重试安装") }
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
