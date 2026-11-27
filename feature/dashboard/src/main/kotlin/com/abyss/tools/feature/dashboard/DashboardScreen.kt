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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import kotlin.math.roundToInt
import com.abyss.tools.core.common.AppInfo
import com.abyss.tools.core.common.ToolCatalog
import com.abyss.tools.core.common.toUiState
import com.abyss.tools.core.common.UiState
import com.abyss.tools.core.designsystem.component.AbyssIcons
import com.abyss.tools.core.designsystem.component.ChipTone
import com.abyss.tools.core.designsystem.component.SectionHeader
import com.abyss.tools.core.designsystem.component.StatusChip
import com.abyss.tools.core.designsystem.component.entranceStagger
import com.abyss.tools.core.designsystem.component.rememberReducedMotion
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

/** Shizuku 徽章语义（app 壳从 core:shell 状态映射，避免 dashboard 依赖 Shizuku AAR）。 */
sealed interface ShizukuUi {
    data object NotRunning : ShizukuUi
    data object RunningUnauthorized : ShizukuUi
    data object Authorized : ShizukuUi
}

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
    shizukuUi: ShizukuUi = ShizukuUi.NotRunning,
    installPermGranted: Boolean = false,
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
            // ── 状态行：Shizuku / 安装权限（真实态，新手引导页可修）────
            // 错峰入场（v0.7.6）：包裹 Box 挂 entranceStagger，卡片零改动。
            item {
                Box(Modifier.fillMaxWidth().entranceStagger(0)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val (shizukuLabel, shizukuTone) = when (shizukuUi) {
                            ShizukuUi.Authorized -> "Shizuku · 已授权" to ChipTone.OK
                            ShizukuUi.RunningUnauthorized -> "Shizuku · 未授权" to ChipTone.WARN
                            ShizukuUi.NotRunning -> "Shizuku · 未运行" to ChipTone.NEUTRAL
                        }
                        StatusChip(text = shizukuLabel, tone = shizukuTone)
                        StatusChip(
                            text = if (installPermGranted) "安装未知应用 · 已允许" else "安装未知应用 · 未允许",
                            tone = if (installPermGranted) ChipTone.OK else ChipTone.WARN,
                        )
                    }
                }
            }

            // ── 统计卡 ────────────────────────────────────────────────
            item {
                Box(Modifier.fillMaxWidth().entranceStagger(1)) {
                    StatsRow(
                        indexState = indexState,
                        installedPluginCount = installedPluginCount,
                    )
                }
            }

            // ── 补丁更新卡（液态玻璃件：采样主内容）────────────────────
            item {
                Box(Modifier.fillMaxWidth().entranceStagger(2)) {
                    UpdateCard(
                        backdrop = backdrop,
                        updateUi = updateUi,
                        onCheck = checkUpdate,
                    )
                }
            }

            // ── 快捷工具 ──────────────────────────────────────────────
            item {
                Box(Modifier.fillMaxWidth().entranceStagger(3)) {
                    SectionHeader(title = "快捷工具")
                }
            }
            items(count = 4, key = { it }) { index ->
                val tool = listOf(
                    "system-control", "terminal", "media-lab", "app-builder",
                )[index].let { ToolCatalog.byId(it) }
                if (tool != null) {
                    Box(Modifier.fillMaxWidth().entranceStagger(4 + index)) {
                        com.abyss.tools.core.designsystem.component.ListRowCard(
                            icon = AbyssIcons.icon(tool.iconKey),
                            title = tool.title,
                            onClick = { onOpenTool(tool.id) },
                            badge = { StatusChip(text = tool.status.label) },
                        )
                    }
                }
            }

            // ── 插件生态入口 ──────────────────────────────────────────
            item {
                // 标题在错峰 Box 外（Box 子项堆叠会让标题与半透明卡面重叠）
                Column {
                    SectionHeader(title = "插件生态")
                    Spacer(Modifier.size(6.dp))
                    Box(Modifier.fillMaxWidth().entranceStagger(8)) {
                        PluginEntryCard(
                            indexState = indexState,
                            installedPluginCount = installedPluginCount,
                            onOpen = onOpenPlugins,
                        )
                    }
                }
            }

            // ── 路线图 ────────────────────────────────────────────────
            item {
                Column {
                    SectionHeader(title = "框架路线图")
                    Box(Modifier.fillMaxWidth().entranceStagger(9)) {
                        RoadmapCard()
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsRow(indexState: UiState<PluginIndex>, installedPluginCount: Int) {
    val pluginCount = (indexState as? UiState.Data)?.value?.plugins?.size ?: 3
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard(
            value = ToolCatalog.builtInCount,
            label = "主壳内置工具",
            modifier = Modifier.weight(1f),
        )
        StatCard(
            value = pluginCount,
            label = "引擎级插件",
            modifier = Modifier.weight(1f),
        )
        StatCard(
            value = installedPluginCount,
            label = "本机已装插件",
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 单张统计卡：数值从 0 滚动到目标值（v0.7.6）—— 与卡片错峰入场同步的
 * 仪式感。系统移除动画时直接显示终值；目标值变化时从当前值续动
 * （插件安装 / 索引刷新不跳变）。
 */
@Composable
private fun StatCard(value: Int, label: String, modifier: Modifier = Modifier) {
    val reducedMotion = rememberReducedMotion()
    val count = remember { Animatable(if (reducedMotion) value.toFloat() else 0f) }
    LaunchedEffect(value) {
        if (reducedMotion) {
            count.snapTo(value.toFloat())
        } else {
            count.animateTo(
                targetValue = value.toFloat(),
                animationSpec = tween(durationMillis = 900, delayMillis = 220, easing = FastOutSlowInEasing),
            )
        }
    }
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(vertical = 14.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "${count.value.roundToInt()}",
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
        shape = MaterialTheme.shapes.large,
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
                UpdateUi.UpToDate -> "当前 v${AppInfo.VERSION_NAME} 已是通道最新"
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

                is UpdateUi.Failed -> "更新通道不可达：${updateUi.message}"
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
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "插件中心 · $count 个引擎级插件",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
            )
            if (installedPluginCount > 0) {
                StatusChip("本机已装 $installedPluginCount", ChipTone.OK)
            }
        }
        Spacer(Modifier.size(10.dp))
        Button(onClick = onOpen) { Text("进入插件中心") }
    }
}

@Composable
private fun RoadmapCard() {
    val phases = listOf(
        "Phase 1 框架与终端成型" to "三栏主界面 / 分类导航 / 插件框架 / 补丁更新引擎",
        "Phase 2 任务系统与插件内核" to "任务→模块→特征调用链 · DexClassLoader 插件加载",
        "Phase 3 硬核功能接入" to "沙箱双开 · Python 引擎 · 悬浮球与灵动岛",
        "Phase 4 完善与结项" to "Web 控制台 · 设备管理器 · 图标伪装",
        "v0.8.0 大内置化（已落地）" to "六插件内置化 · 系统控制中心 · 黑洞时钟组件 · M3 全面进化",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
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
