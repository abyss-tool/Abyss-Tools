package com.abyss.tools.feature.dashboard

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abyss.tools.core.common.AppInfo
import com.abyss.tools.core.common.ToolCatalog
import com.abyss.tools.core.common.toUiState
import com.abyss.tools.core.common.UiState
import com.abyss.tools.core.designsystem.component.AbyssIcons
import com.abyss.tools.core.designsystem.component.ChipTone
import com.abyss.tools.core.designsystem.component.SectionHeader
import com.abyss.tools.core.designsystem.component.StatusChip
import com.abyss.tools.core.designsystem.glass.AbyssGlassSurface
import com.abyss.tools.core.plugin.PluginIndex
import com.abyss.tools.core.plugin.PluginRepository
import com.abyss.tools.core.update.UpdateCheckResult
import com.abyss.tools.core.update.UpdateManager
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.launch

/**
 * 仪表盘 —— 主界面第一 tab：状态总览 / 快捷入口 / 补丁更新 / 插件本机态。
 */

/** 仪表盘更新卡片状态（app 不直接消费 UpdateCheckResult，映射到此）。 */
internal sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data object UpToDate : UpdateUi
    data class Available(
        val version: String,
        val notes: String?,
        val patchApplicable: Boolean,
    ) : UpdateUi

    data class Failed(val message: String) : UpdateUi
}

@Composable
fun DashboardScreen(
    backdrop: Backdrop?,
    pluginRepository: PluginRepository,
    updateManager: UpdateManager,
    onOpenTool: (String) -> Unit,
    onOpenPlugins: () -> Unit,
    bottomClearance: Dp,
    installedPluginCount: Int = 0,
    autoCheckUpdates: Boolean = false,
) {
    val scope = rememberCoroutineScope()

    // ── 插件索引（仪表盘只取统计）────────────────────────────────────
    var indexState by remember { mutableStateOf<UiState<PluginIndex>>(UiState.Loading) }
    LaunchedEffect(Unit) {
        indexState = pluginRepository.fetchIndex().toUiState()
    }

    // ── 更新检查 ─────────────────────────────────────────────────────
    var updateUi by remember { mutableStateOf<UpdateUi>(UpdateUi.Idle) }
    val checkUpdate: () -> Unit = {
        if (updateUi !is UpdateUi.Checking) {
            updateUi = UpdateUi.Checking
            scope.launch {
                updateUi = when (val r = updateManager.checkForUpdate(AppInfo.version)) {
                    is UpdateCheckResult.UpToDate -> UpdateUi.UpToDate
                    is UpdateCheckResult.Available -> UpdateUi.Available(
                        version = r.manifest.versionName,
                        notes = r.manifest.notes,
                        patchApplicable = r.patchApplicable,
                    )

                    is UpdateCheckResult.Failed -> UpdateUi.Failed(
                        r.error.message ?: r.error.javaClass.simpleName
                    )
                }
            }
        }
    }

    // ── 启动时自动检查（设置页开关接线）──────────────────────────────
    LaunchedEffect(autoCheckUpdates) {
        if (autoCheckUpdates && updateUi is UpdateUi.Idle) checkUpdate()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp)) {
            Spacer(Modifier.size(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "深渊工具",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.size(10.dp))
                StatusChip(text = "v${AppInfo.VERSION_NAME}", tone = ChipTone.OK)
            }
            Text(
                text = "系统级超级工具箱 · 无 Root · Shizuku + 沙箱 + 脚本引擎",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(10.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                end = 20.dp,
                bottom = bottomClearance,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ── 状态行：Shizuku / 权限链（框架占位）────────────────────
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(text = "Shizuku · 未连接", tone = ChipTone.NEUTRAL)
                    StatusChip(text = "权限链 · 框架占位", tone = ChipTone.NEUTRAL)
                }
            }

            // ── 统计卡 ────────────────────────────────────────────────
            item {
                StatsRow(
                    indexState = indexState,
                    installedPluginCount = installedPluginCount,
                )
            }

            // ── 补丁更新卡（液态玻璃件：采样主内容）────────────────────
            item {
                UpdateCard(
                    backdrop = backdrop,
                    updateUi = updateUi,
                    onCheck = checkUpdate,
                )
            }

            // ── 快捷工具 ──────────────────────────────────────────────
            item {
                SectionHeader(title = "快捷工具", subtitle = "高频入口 · 点击进入工具页")
            }
            items(count = 4, key = { it }) { index ->
                val tool = listOf(
                    "terminal", "app-manager", "sandbox-space", "device-disguise",
                )[index].let { ToolCatalog.byId(it) }
                if (tool != null) {
                    com.abyss.tools.core.designsystem.component.ListRowCard(
                        icon = AbyssIcons.icon(tool.iconKey),
                        title = tool.title,
                        summary = tool.summary,
                        onClick = { onOpenTool(tool.id) },
                        badge = { StatusChip(text = tool.status.label) },
                    )
                }
            }

            // ── 插件生态入口 ──────────────────────────────────────────
            item {
                SectionHeader(title = "插件生态")
                Box(Modifier.size(6.dp))
                PluginEntryCard(
                    indexState = indexState,
                    installedPluginCount = installedPluginCount,
                    onOpen = onOpenPlugins,
                )
            }

            // ── 路线图 ────────────────────────────────────────────────
            item {
                SectionHeader(title = "框架路线图", subtitle = "SRS §6 开发里程碑（对齐总体蓝图）")
                RoadmapCard()
            }
        }
    }
}

@Composable
private fun StatsRow(indexState: UiState<PluginIndex>, installedPluginCount: Int) {
    val pluginCount = (indexState as? UiState.Data)?.value?.plugins?.size ?: 8
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard(
            value = "${ToolCatalog.builtInCount}",
            label = "主壳内置工具",
            modifier = Modifier.weight(1f),
        )
        StatCard(
            value = "$pluginCount",
            label = "大插件仓库",
            modifier = Modifier.weight(1f),
        )
        StatCard(
            value = "$installedPluginCount",
            label = "本机已装插件",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(vertical = 14.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UpdateCard(
    backdrop: Backdrop?,
    updateUi: UpdateUi,
    onCheck: () -> Unit,
) {
    AbyssGlassSurface(
        backdrop = backdrop,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "补丁更新",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                when (updateUi) {
                    is UpdateUi.Checking -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )

                    is UpdateUi.UpToDate -> StatusChip("已是最新", ChipTone.OK)
                    is UpdateUi.Available -> StatusChip(
                        if (updateUi.patchApplicable) "增量可用" else "新版本",
                        ChipTone.WARN,
                    )

                    is UpdateUi.Failed -> StatusChip("检查失败", ChipTone.ERROR)
                    UpdateUi.Idle -> StatusChip("待检查", ChipTone.NEUTRAL)
                }
            }
            Spacer(Modifier.size(8.dp))
            val statusText = when (updateUi) {
                UpdateUi.Idle -> "渠道：Abyss-Tools-Release · version.json（GitHub Raw）"
                UpdateUi.Checking -> "正在读取发布仓清单…"
                UpdateUi.UpToDate -> "当前 v${AppInfo.VERSION_NAME} 已是通道最新。"
                is UpdateUi.Available -> buildString {
                    append("发现 v")
                    append(updateUi.version)
                    append("：")
                    append(updateUi.notes ?: "（未附说明）")
                    append(" · ")
                    append(
                        if (updateUi.patchApplicable) "支持从当前版本增量补丁升级"
                        else "需整包安装（当前版本与补丁链不连续）"
                    )
                }

                is UpdateUi.Failed -> "更新通道不可达：${updateUi.message}（App 首个 APK 发布前，发布仓仅含清单骨架 —— 属预期状态）"
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(12.dp))
            Button(onClick = onCheck, enabled = updateUi !is UpdateUi.Checking) {
                Text("检查补丁更新")
            }
        }
    }
}

@Composable
private fun PluginEntryCard(
    indexState: UiState<PluginIndex>,
    installedPluginCount: Int,
    onOpen: () -> Unit,
) {
    val count = (indexState as? UiState.Data)?.value?.plugins?.size ?: 13
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "插件中心 · $count 个大插件",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
            )
            if (installedPluginCount > 0) {
                StatusChip("本机已装 $installedPluginCount", ChipTone.OK)
            }
        }
        Spacer(Modifier.size(4.dp))
        Text(
            text = "终端引擎 / Python 游乐场 / 沙箱双开 / 设备伪装 / 媒体实验室…每个插件独立仓库、独立 CI、独立补丁通道。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
        )
        Spacer(Modifier.size(10.dp))
        Button(onClick = onOpen) { Text("进入插件中心") }
    }
}

@Composable
private fun RoadmapCard() {
    val phases = listOf(
        "Phase 1 框架与终端成型" to "本版本：三栏主界面 / 分类导航 / 插件框架 / 补丁更新引擎（当前）",
        "Phase 2 任务系统与插件内核" to "任务→模块→特征调用链 · DexClassLoader 插件加载",
        "Phase 3 硬核功能接入" to "沙箱双开 · Python 引擎 · 悬浮球与灵动岛",
        "Phase 4 完善与结项" to "Web 控制台 · 设备管理器 · 图标伪装",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        phases.forEachIndexed { index, (title, desc) ->
            Row {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (index == 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(end = 10.dp),
                )
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
