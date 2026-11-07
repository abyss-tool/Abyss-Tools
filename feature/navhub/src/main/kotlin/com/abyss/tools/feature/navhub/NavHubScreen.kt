package com.abyss.tools.feature.navhub

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abyss.tools.core.common.ToolCatalog
import com.abyss.tools.core.common.ToolCategory
import com.abyss.tools.core.common.ToolDef
import com.abyss.tools.core.common.ToolStatus
import com.abyss.tools.core.designsystem.component.AbyssIcons
import com.abyss.tools.core.designsystem.component.ChipTone
import com.abyss.tools.core.designsystem.component.ListRowCard
import com.abyss.tools.core.designsystem.component.SectionHeader
import com.abyss.tools.core.designsystem.component.StatusChip
import com.abyss.tools.core.designsystem.glass.AbyssGlassSurface
import com.kyant.backdrop.Backdrop

/**
 * 导航页 —— 全部工具的分类目录（主界面的第二 tab）。
 *
 * 结构铁律：**插件永远排第一分类** —— 深渊工具「主壳轻、能力重」的
 * 产品表达：点击插件分类进入插件中心。
 */
@Composable
fun NavHubScreen(
    bottomClearance: Dp,
    onOpenTool: (String) -> Unit,
    onOpenPluginHub: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // ── 页头 ─────────────────────────────────────────────────────
        Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp)) {
            Spacer(Modifier.size(8.dp))
            Text(
                text = "导航",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "深渊工具的全部能力域 · ${ToolCatalog.tools.size} 项工具 · ${ToolCatalog.categories.size} 个分类",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                end = 20.dp,
                bottom = bottomClearance,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ToolCatalog.categories.forEach { category ->
                item(key = "header-${category.id}") {
                    if (category == ToolCategory.PLUGINS) {
                        PluginHubEntry(onOpenPluginHub = onOpenPluginHub)
                    } else {
                        SectionHeader(
                            title = category.title,
                            subtitle = category.description,
                        )
                    }
                }
                items(
                    count = ToolCatalog.toolsOf(category).size,
                    key = { index -> "tool-${ToolCatalog.toolsOf(category)[index].id}" },
                ) { index ->
                    val tool = ToolCatalog.toolsOf(category)[index]
                    ToolRow(tool = tool, onClick = { onOpenTool(tool.id) })
                }
            }
        }
    }
}

/** 插件分类的特殊入口卡（玻璃件，导航页的门面）。 */
@Composable
private fun PluginHubEntry(onOpenPluginHub: () -> Unit) {
    AbyssGlassSurface(
        backdrop = null, // NavHub 内无采样源 → Frosted 档（霜面 + 边缘光）
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "🧩", style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "插件",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "七大插件仓库 · 小功能内置 · 大功能插件化 · 独立补丁更新",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "进入 ›",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ToolRow(tool: ToolDef, onClick: () -> Unit) {
    ListRowCard(
        icon = AbyssIcons.icon(tool.iconKey),
        title = tool.title,
        summary = tool.summary,
        onClick = onClick,
        badge = { StatusChip(text = tool.status.label, tone = tool.status.tone()) },
    )
}

private fun ToolStatus.tone(): ChipTone = when (this) {
    ToolStatus.READY -> ChipTone.OK
    ToolStatus.BUILT_IN -> ChipTone.OK
    ToolStatus.PLUGIN -> ChipTone.INFO
    ToolStatus.FRAMEWORK -> ChipTone.NEUTRAL
}

/** 工具占位详情页（框架期统一承接 READY / FRAMEWORK 状态的工具）。 */
@Composable
fun ToolScreen(
    toolId: String,
    onOpenPlugin: (String) -> Unit,
    onBack: () -> Unit,
) {
    val tool = ToolCatalog.byId(toolId)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        DetailHeader(title = tool?.title ?: "工具", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                end = 20.dp,
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (tool == null) {
                item { Text("未知工具：$toolId", color = MaterialTheme.colorScheme.error) }
                return@LazyColumn
            }
            item {
                Column(Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = AbyssIcons.icon(tool.iconKey) as ImageVector,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = tool.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.size(8.dp))
                        StatusChip(text = tool.status.label, tone = tool.status.tone())
                    }
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = tool.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SectionHeader(title = "所属分类", subtitle = "${tool.category.title} · ${tool.category.description}")
            }
            when (tool.status) {
                ToolStatus.PLUGIN -> {
                    item {
                        PluginCarrierCard(pluginId = tool.pluginId ?: "", onOpenPlugin = onOpenPlugin)
                    }
                }
                ToolStatus.BUILT_IN -> {
                    item { BuiltInFeatureCard(tool = tool) }
                }
                ToolStatus.FRAMEWORK -> {
                    item { FrameworkPlaceholderCard() }
                }
                ToolStatus.READY -> {
                    item { ReadyPlaceholderCard() }
                }
            }
        }
    }
}

@Composable
internal fun DetailHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.IconButton(onClick = onBack) {
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

@Composable
private fun PluginCarrierCard(pluginId: String, onOpenPlugin: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
    ) {
        Text(
            text = "该工具由插件承载",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "安装对应插件后可用。插件与主壳解耦，独立仓库 / 独立 CI / 独立补丁更新。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(10.dp))
        androidx.compose.material3.Button(onClick = { onOpenPlugin(pluginId) }) {
            Text(if (pluginId.isBlank()) "前往插件中心" else "查看插件 · $pluginId")
        }
    }
}

@Composable
private fun BuiltInFeatureCard(tool: ToolDef) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
    ) {
        Text(
            text = "主壳内置 · 无需安装插件",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "自 v0.2 起本工具由独立插件仓并入主壳，随深渊工具版本更新直接获得，" +
                "不再占用插件中心位次（v0.3 起：伪装四件套同款内置路线，功能页真实可用）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = "内置路线：「${tool.title}」随主壳迭代（原插件仓已归档）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun FrameworkPlaceholderCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
    ) {
        Text(
            text = "路线图中 · 框架占位",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "本工具已在深渊工具的总体蓝图（SRS §6 开发里程碑）中排期，" +
                "当前版本专注框架成型：导航 / 插件体系 / 补丁更新引擎。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReadyPlaceholderCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
    ) {
        Text(
            text = "壳内可用",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "该入口为宿主内置能力，功能实现随 v0.2 起排期落地。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
