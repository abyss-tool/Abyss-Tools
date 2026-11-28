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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abyss.tools.core.common.Capability
import com.abyss.tools.core.common.PrivilegeSnapshot
import com.abyss.tools.core.common.ToolCatalog
import com.abyss.tools.core.common.ToolStatus
import com.abyss.tools.core.designsystem.component.AbyssIcons
import com.abyss.tools.core.designsystem.component.CapabilityGuardCard
import com.abyss.tools.core.designsystem.component.GatedToolCard
import com.abyss.tools.core.designsystem.component.SectionHeader
import com.abyss.tools.core.designsystem.component.StatusChip
import com.abyss.tools.core.designsystem.component.ToolModesCard
import com.abyss.tools.core.designsystem.component.entranceStagger
import com.abyss.tools.core.designsystem.component.tone

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
 *
 * FEAT-039（v0.10.2）：全量工具行换 [GatedToolCard] —— 锁定行显示
 * 「需 Shizuku」短文案，点击统一交给 onOpenTool（AbyssRoot 裁决进
 * 门禁说明页还是真实页）；三处消费同一份 [PrivilegeSnapshot]。
 */
@Composable
fun NavHubScreen(
    bottomClearance: Dp,
    onOpenTool: (String) -> Unit,
    snapshot: PrivilegeSnapshot? = null,
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
                val tools = ToolCatalog.toolsOf(category)
                items(
                    count = tools.size,
                    key = { index -> "tool-${tools[index].id}" },
                ) { index ->
                    val tool = tools[index]
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .entranceStagger(catIndex * ENTRIES_PER_CATEGORY + index + 1),
                    ) {
                        GatedToolCard(
                            tool = tool,
                            snapshot = snapshot,
                            onOpen = { onOpenTool(tool.id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 工具详情 / 门禁说明页。
 *
 * FEAT-039：`snapshot != null && !allows(required)` 时为**门禁模式** —— 顶部
 * [CapabilityGuardCard]（缺失能力 + 去授权）+ [ToolModesCard]（多模式 ✓/✗）+
 * 「以受限模式进入」（任一模式门槛已满足时经 [onEnterUngated] 进入真实页）。
 * 否则与 v0.10.1 占位页完全一致。
 */
@Composable
fun ToolScreen(
    toolId: String,
    onOpenPlugin: (String) -> Unit,
    onBack: () -> Unit,
    snapshot: PrivilegeSnapshot? = null,
    onOpenCapability: (Capability) -> Unit = {},
    onEnterUngated: (() -> Unit)? = null,
) {
    val tool = ToolCatalog.byId(toolId)
    val gated = tool != null && snapshot != null && !snapshot.allows(tool.access.required)
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
                            .clip(MaterialTheme.shapes.large)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = AbyssIcons.icon(tool.iconKey),
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
                }
            }
            if (gated && snapshot != null) {
                item {
                    CapabilityGuardCard(
                        required = tool.access.required,
                        snapshot = snapshot,
                        onOpenCapability = onOpenCapability,
                    )
                }
                item { ToolModesCard(tool = tool, snapshot = snapshot) }
                // 受限模式进入：任一模式门槛已满足 → 显式进入真实页（页面内守卫继续兜底）
                if (onEnterUngated != null && tool.access.modes.any { snapshot.allows(it.required) }) {
                    item {
                        OutlinedButton(onClick = onEnterUngated, modifier = Modifier.fillMaxWidth()) {
                            Text("以受限模式进入")
                        }
                    }
                }
                return@LazyColumn
            }
            item {
                SectionHeader(title = "所属分类 · ${tool.category.title}")
            }
            when (tool.status) {
                ToolStatus.PLUGIN -> {
                    item {
                        PluginCarrierCard(pluginId = tool.pluginId ?: "", onOpenPlugin = onOpenPlugin)
                    }
                }
                ToolStatus.BUILT_IN -> {
                    item { BuiltInFeatureCard() }
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
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
    ) {
        Text(
            text = "该工具由插件承载",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(10.dp))
        Button(onClick = { onOpenPlugin(pluginId) }) {
            Text(if (pluginId.isBlank()) "前往插件中心" else "查看插件 · $pluginId")
        }
    }
}

/**
 * 插件交付里程碑时间轴（v0.7.6 引入,v0.8.0 移除）：ToolDef.roadmap 随
 * 六重型插件转内置而退役 —— 插件矩阵收缩后里程碑数据失去载体。
 */

@Composable
private fun BuiltInFeatureCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
    ) {
        Text(
            text = "主壳内置 · 无需安装插件",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun FrameworkPlaceholderCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
    ) {
        Text(
            text = "路线图中 · 框架占位",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ReadyPlaceholderCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
    ) {
        Text(
            text = "壳内可用",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 错峰入场的分类偏移步长：每分类占 10 个入场序号（分类内工具数 < 10）。 */
private const val ENTRIES_PER_CATEGORY = 10
