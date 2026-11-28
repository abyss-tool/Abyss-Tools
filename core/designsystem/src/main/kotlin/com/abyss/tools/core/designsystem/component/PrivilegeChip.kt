package com.abyss.tools.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abyss.tools.core.common.Capability
import com.abyss.tools.core.common.CapabilityGuide
import com.abyss.tools.core.common.PrivilegeSnapshot
import com.abyss.tools.core.common.PrivilegeTier
import com.abyss.tools.core.common.TierCalculator
import com.abyss.tools.core.common.ToolDef
import com.abyss.tools.core.common.ToolStatus
import com.abyss.tools.core.common.lockedLabel

/**
 * 权限档位与门禁组件（FEAT-038 / FEAT-039，v0.10.2）。
 *
 *  - [TierBadge]：五档文字徽章（BASIC 旁常驻问号）；
 *  - [TierExplanationDialog]：五档说明表 + 「去授权」；
 *  - [GatedToolCard]：锁定 / 解锁两态工具行（仪表盘快捷工具与功能页共用）；
 *  - [CapabilityGuardCard]：门禁说明页顶部的缺失能力卡；
 *  - [ToolModesCard]：多模式逐条展示（✓ / ✗）。
 * 全部消费同一份 [PrivilegeSnapshot]，不在 UI 层复制判定逻辑。
 */

/** 五档固定色调：灰 / 蓝 / 绿 / 橙 / 紫（从 ChipTone 派生）。 */
fun PrivilegeTier.tone(): ChipTone = when (this) {
    PrivilegeTier.BASIC -> ChipTone.NEUTRAL
    PrivilegeTier.ADVANCED -> ChipTone.INFO
    PrivilegeTier.SENIOR -> ChipTone.OK
    PrivilegeTier.ABYSS_MAX -> ChipTone.MAX
    PrivilegeTier.ABYSS_ULTRA -> ChipTone.ULTRA
}

/** 工具状态圆点语气（功能页 / 仪表盘 / 详情页共用，替代各页私有扩展）。 */
fun ToolStatus.tone(): ChipTone = when (this) {
    ToolStatus.READY -> ChipTone.OK
    ToolStatus.BUILT_IN -> ChipTone.OK
    ToolStatus.PLUGIN -> ChipTone.INFO
    ToolStatus.FRAMEWORK -> ChipTone.NEUTRAL
}

/** 缺失能力的人话列表（按引导优先级）。 */
fun capabilityLabels(capabilities: Set<Capability>): String =
    capabilities.sortedBy { it.priority }.joinToString("、") { it.label }

/** 「去授权」目标：缺失且可引导的能力里优先级最高者。 */
fun PrivilegeSnapshot.guideTarget(required: Set<Capability> = Capability.entries.toSet()): Capability? =
    missing(required).filter { it.guide != CapabilityGuide.NONE }.minByOrNull { it.priority }

@Composable
private fun Dot(tone: ChipTone, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(tone.content),
    )
}

/**
 * 档位徽章：文字 = tier.label；BASIC 档旁常驻问号；max 档缺「安装未知应用」
 * 时提示「待补 1 项」（max 不要求该能力，只是如实提醒）。
 */
@Composable
fun TierBadge(
    snapshot: PrivilegeSnapshot,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tier = snapshot.tier
    val tone = tier.tone()
    val pendingInstall = tier == PrivilegeTier.ABYSS_MAX && !snapshot.has(Capability.INSTALL_UNKNOWN)
    val description = buildString {
        append("权限档位 ")
        append(tier.label)
        if (pendingInstall) append("，待补安装未知应用")
    }
    Surface(
        onClick = onClick,
        color = tone.container.copy(alpha = 0.55f),
        contentColor = tone.content,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.semantics { contentDescription = description },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dot(tone)
            Spacer(Modifier.size(6.dp))
            Text(
                text = tier.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (tier == PrivilegeTier.BASIC) {
                Spacer(Modifier.size(2.dp))
                Icon(
                    imageVector = Icons.Rounded.HelpOutline,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (pendingInstall) {
                Spacer(Modifier.size(6.dp))
                Text(text = "待补 1 项", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** 五档说明对话框：当前档 + 到下一档还差什么 + 五档表 + 去授权。 */
@Composable
fun TierExplanationDialog(
    snapshot: PrivilegeSnapshot,
    onDismiss: () -> Unit,
    onOpenCapability: (Capability) -> Unit,
) {
    val tier = snapshot.tier
    val target = snapshot.guideTarget()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "权限档位说明", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (tier == PrivilegeTier.BASIC) "当前未授予任何权限，只能启动极少功能" else "当前档位：${tier.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val next = snapshot.toNextTier
                Text(
                    text = when {
                        next == null -> "已是最高档"
                        next.second.isEmpty() -> "「${next.first.label}」条件已满足"
                        else -> "距「${next.first.label}」还差：${capabilityLabels(next.second)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TierCalculator.ladder.forEach { row ->
                    val missing = TierCalculator.requirements.getValue(row) - snapshot.capabilities
                    val rowStatus = if (missing.isEmpty()) "✓" else "待补 ${capabilityLabels(missing)}"
                    Row(
                        // mergeDescendants：整行已给出完整 description，行内三段
                        // Text 不再各自成焦点（TalkBack 不重复朗读）。
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) { contentDescription = "${row.label}：${row.condition}，$rowStatus" },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Dot(row.tone())
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = row.label,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(0.36f),
                        )
                        Text(
                            text = row.condition,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(0.44f),
                        )
                        Text(
                            text = rowStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (missing.isEmpty()) ChipTone.OK.content else ChipTone.WARN.content,
                            modifier = Modifier.weight(0.2f),
                        )
                    }
                }
                snapshot.environmentNotes.forEach { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (target != null) {
                TextButton(onClick = { onOpenCapability(target) }) { Text("去授权") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

/**
 * 门禁工具行：解锁 = 原状态圆点；锁定 = 锁形 + 「需 …」短文案 + 整卡降透明度。
 * 点击统一交给 [onOpen]（由 app 壳的 AbyssRoot 裁决进门禁说明页还是真实页）。
 */
@Composable
fun GatedToolCard(
    tool: ToolDef,
    snapshot: PrivilegeSnapshot?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unlocked = snapshot == null || snapshot.allows(tool.access.required)
    val missing = if (snapshot == null) emptySet() else snapshot.missing(tool.access.required)
    ListRowCard(
        icon = AbyssIcons.icon(tool.iconKey),
        title = tool.title,
        modifier = modifier.lockedAlpha(!unlocked),
        onClick = onOpen,
        badge = {
            if (unlocked) {
                StatusChip(text = tool.status.label, tone = tool.status.tone())
            } else {
                LockBadge(
                    lockedText = lockedLabel(missing),
                    a11y = "需${TierCalculator.minimumTier(tool.access.required).label}：${capabilityLabels(missing)}",
                )
            }
        },
    )
}

/** 门禁说明页顶部：缺失能力 + 「去授权」。required 已满足时不渲染。 */
@Composable
fun CapabilityGuardCard(
    required: Set<Capability>,
    snapshot: PrivilegeSnapshot,
    onOpenCapability: (Capability) -> Unit,
    modifier: Modifier = Modifier,
) {
    val missing = snapshot.missing(required)
    if (missing.isEmpty()) return
    val target = snapshot.guideTarget(required)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(ChipTone.WARN.container.copy(alpha = 0.35f))
            .padding(16.dp),
    ) {
        Text(
            text = "还缺：${capabilityLabels(missing)}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = ChipTone.WARN.content,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "补齐后即可解锁「${TierCalculator.minimumTier(required).label}」级功能",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        missing.sortedBy { it.priority }.forEach { cap ->
            val reason = snapshot.reasons[cap]
            if (!reason.isNullOrBlank()) {
                Text(
                    text = "${cap.label}：$reason",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (target != null) {
            Spacer(Modifier.size(10.dp))
            OutlinedButton(onClick = { onOpenCapability(target) }) { Text("去授权") }
        }
    }
}

/** 多模式卡：逐条列出该工具的模式与当前是否可用（✓ / ✗）。 */
@Composable
fun ToolModesCard(
    tool: ToolDef,
    snapshot: PrivilegeSnapshot,
    modifier: Modifier = Modifier,
) {
    if (tool.access.modes.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "可用模式",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        tool.access.modes.forEach { mode ->
            val ok = snapshot.allows(mode.required)
            Row(
                // mergeDescendants：整行已给出完整 description（同上，防重复朗读）。
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${mode.name}：${if (ok) "可用" else "缺 ${capabilityLabels(mode.required)}"}"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(if (ok) ChipTone.OK else ChipTone.NEUTRAL)
                Spacer(Modifier.size(8.dp))
                Text(
                    text = mode.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(0.42f),
                )
                Text(
                    text = if (ok) mode.note else "缺 ${capabilityLabels(mode.required)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.58f),
                )
            }
        }
    }
}
