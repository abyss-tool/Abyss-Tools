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
import com.abyss.tools.core.common.ToolDef
import com.abyss.tools.core.common.ToolStatus
import com.abyss.tools.core.common.privilege.PrivilegeSnapshot
import com.abyss.tools.core.common.privilege.TierCalculator
import com.abyss.tools.core.designsystem.component.AbyssIcons
import com.abyss.tools.core.designsystem.component.ChipTone
import com.abyss.tools.core.designsystem.component.ListRowCard
import com.abyss.tools.core.designsystem.component.SectionHeader
import com.abyss.tools.core.designsystem.component.entranceStagger
import com.abyss.tools.core.designsystem.component.StatusChip

/**
 * 功能页 —— 全部工具的分类目录（主界面的第二 tab）。
 *
 * v0.9.13（FEAT-034）：插件分类与门面入口卡移除 —— 引擎级插件能力由
 * 底部导航「插件」独立 tab 承载，功能页只登记工具目录，页头统计由
 * ToolCatalog 数据驱动。
 *
 * UI-001（功能文案精简）：页头统计副标、分类 description、工具
 * summary、占位详情页五段长说明全部清零 —— 分类头单行、工具行
 * 图标 + 标题 + 状态圆点；占位页只留一句短状态标签。
 */
@Composable
fun NavHubScreen(
    bottomClearance: Dp,
    onOpenTool: (String) -> Unit,
    privilegeSnapshot: PrivilegeSnapshot,
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
                text = "功能",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
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
            ToolCatalog.categories.forEachIndexed { catIndex, category ->
                // 错峰入场（v0.7.6）：屏内序号 = 分类偏移 + 分类内序号 ——
                // 前 ENTRANCE_MAX_STAGGER_STEPS 张依次延迟形成波浪，更深的
                // 卡片立即入场（滚动复用不让人等）。分类内工具数 < 10。
                item(key = "header-${category.id}") {
                    SectionHeader(
                        title = category.title,
                        modifier = Modifier.entranceStagger(catIndex * ENTRIES_PER_CATEGORY),
                    )
                }
                items(
                    count = ToolCatalog.toolsOf(category).size,
                    key = { index -> "tool-${ToolCatalog.toolsOf(category)[index].id}" },
                ) { index ->
                    val tool = ToolCatalog.toolsOf(category)[index]
                    val toolAvailable = TierCalculator.satisfies(tool.requiredCapabilities, privilegeSnapshot)
                    val requiredTier = TierCalculator.compute(PrivilegeSnapshot(tool.requiredCapabilities))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .entranceStagger(catIndex * ENTRIES_PER_CATEGORY + index + 1),
                    ) {
                        ToolRow(
                            tool = tool,
                            available = toolAvailable,
                            requiredTierLabel = requiredTier.label,
                            onClick = {
                                if (toolAvailable) onOpenTool(tool.id)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolRow(tool: ToolDef, available: Boolean, requiredTierLabel: String, onClick: () -> Unit) {
    ListRowCard(
        icon = AbyssIcons.icon(tool.iconKey),
        title = tool.title,
        onClick = onClick,
        badge = {
            if (available) {
                StatusChip(text = tool.status.label, tone = tool.status.tone())
            } else {
                StatusChip(text = "需${requiredTierLabel}", tone = ChipTone.WARN)
            }
        },
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
    privilegeSnapshot: PrivilegeSnapshot,
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
            val toolAvailable = TierCalculator.satisfies(tool.requiredCapabilities, privilegeSnapshot)
            val requiredTier = TierCalculator.compute(PrivilegeSnapshot(tool.requiredCapabilities))
            item {
                Column(Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(MaterialTheme.shapes.large)
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
                        if (toolAvailable) {
                            StatusChip(text = tool.status.label, tone = tool.status.tone())
                        } else {
                            StatusChip(text = "需${requiredTier.label}", tone = ChipTone.WARN)
                        }
                    }
                }
            }
            item {
                SectionHeader(title = "所属分类 · ${tool.category.title}")
            }
            when (tool.status) {
                ToolStatus.PLUGIN -> {
                    item {
                        PluginCarrierCard(pluginId = tool.pluginId ?: "", onOpenPlugin = onOpenPlugin, enabled = toolAvailable)
                    }
                }
                ToolStatus.BUILT_IN -> {
                    item { BuiltInFeatureCard(available = toolAvailable, requiredTier = requiredTier.label) }
                }
                ToolStatus.FRAMEWORK -> {
                    item { FrameworkPlaceholderCard(available = toolAvailable, requiredTier = requiredTier.label) }
                }
                ToolStatus.READY -> {
                    item { ReadyPlaceholderCard(available = toolAvailable, requiredTier = requiredTier.label) }
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
private fun PluginCarrierCard(pluginId: String, onOpenPlugin: (String) -> Unit, enabled: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
    ) {
        Text(
            text = if (enabled) "该工具由插件承载" else "该工具需要更高权限才能使用",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (enabled) {
            Spacer(Modifier.size(10.dp))
            androidx.compose.material3.Button(onClick = { onOpenPlugin(pluginId) }, enabled = enabled) {
                Text(if (pluginId.isBlank()) "前往插件中心" else "查看插件 · $pluginId")
            }
        }
    }
}

/**
 * 插件交付里程碑时间轴（v0.7.6 引入,v0.8.0 移除）：ToolDef.roadmap 随
 * 六重型插件转内置而退役 —— 插件矩阵收缩后里程碑数据失去载体。
 */

@Composable
private fun BuiltInFeatureCard(available: Boolean, requiredTier: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
    ) {
        Text(
            text = if (available) "主壳内置 · 无需安装插件" else "需要${requiredTier}权限才能使用",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun FrameworkPlaceholderCard(available: Boolean, requiredTier: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
    ) {
        Text(
            text = if (available) "路线图中 · 框架占位" else "路线图中 · 需要${requiredTier}权限",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ReadyPlaceholderCard(available: Boolean, requiredTier: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
    ) {
        Text(
            text = if (available) "壳内可用" else "需要${requiredTier}权限才能使用",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 错峰入场的分类偏移步长：每分类占 10 个入场序号（分类内工具数 < 10）。 */
private const val ENTRIES_PER_CATEGORY = 10
