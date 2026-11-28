package com.abyss.tools.feature.disguise

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abyss.tools.core.common.Capability
import com.abyss.tools.core.common.DisguiseMode
import com.abyss.tools.core.common.DisguiseModes
import com.abyss.tools.core.common.ModeState
import com.abyss.tools.core.common.PrivilegeSnapshot
import com.abyss.tools.core.designsystem.component.CapabilityGuardCard
import com.abyss.tools.core.designsystem.component.ChipTone
import com.abyss.tools.core.designsystem.component.InfoRow
import com.abyss.tools.core.designsystem.component.StatusChip
import com.abyss.tools.core.designsystem.component.capabilityLabels
import com.abyss.tools.core.designsystem.component.guideTarget
import com.abyss.tools.core.designsystem.component.pressScale
import com.abyss.tools.core.shell.ShizukuStatus

/**
 * 伪装四件套共享组件：页头 / Shizuku 引导横幅 / 命令反馈条 / 说明卡 /
 * 可选档案 chip。视觉纪律与 ToolScreen 的卡片家族完全同构。
 */

/** 页头（返回 + 标题 + 生效层级徽章）。 */
@Composable
internal fun SpoofHeader(
    title: String,
    badge: String = "主壳内置 · Shizuku 免 Root",
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        StatusChip(text = badge, tone = ChipTone.OK)
    }
}

/**
 * Shizuku 引导横幅：未运行 / 未授权时置顶展示，直达两个修复动作。
 * 已授权则什么都不显示（不打扰）。
 */
@Composable
internal fun ShizukuGuardBanner(
    shizukuStatus: ShizukuStatus,
    onRequestPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
) {
    val running = shizukuStatus as? ShizukuStatus.Running
    if (running?.authorized == true) return
    InfoCard(
        title = if (running == null) "Shizuku 未运行" else "Shizuku 未授权",
        body = if (running == null) {
            "伪装功能经 Shizuku 以 adb shell 上下文执行（免 Root）。" +
                "请先启动 Shizuku（无线调试激活），装好后回来继续。"
        } else {
            "Shizuku 已在运行，但深渊工具还没有获得授权 —— 授权一次，全功能开放。"
        },
        tone = ChipTone.WARN,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running == null) {
                OutlinedButton(onClick = onOpenShizuku) { Text("打开 Shizuku") }
            } else {
                Button(onClick = onRequestPermission) { Text("立即授权") }
            }
        }
    }
}

/**
 * 泛化守卫横幅（FEAT-040 FR-006）：`required ⊆ capabilities` 时不渲染。
 *  - snapshot == null → 退回 Shizuku 单维语义（旧调用零破坏）；
 *  - 缺 Shizuku → 复用 [ShizukuGuardBanner]（保留「打开 Shizuku / 立即授权」两动作）；
 *  - 缺其它能力 → designsystem [CapabilityGuardCard]（缺失短标签 + 去授权）。
 */
@Composable
internal fun CapabilityGuardBanner(
    required: Set<Capability>,
    snapshot: PrivilegeSnapshot?,
    shizukuStatus: ShizukuStatus,
    onRequestPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onOpenCapability: (Capability) -> Unit = {},
) {
    if (snapshot == null) {
        if (Capability.SHIZUKU_SHELL in required) {
            ShizukuGuardBanner(shizukuStatus, onRequestPermission, onOpenShizuku)
        }
        return
    }
    val missing = snapshot.missing(required)
    if (missing.isEmpty()) return
    if (Capability.SHIZUKU_SHELL in missing) {
        ShizukuGuardBanner(shizukuStatus, onRequestPermission, onOpenShizuku)
    }
    val others = missing - Capability.SHIZUKU_SHELL
    if (others.isNotEmpty()) {
        CapabilityGuardCard(required = others, snapshot = snapshot, onOpenCapability = onOpenCapability)
    }
}

/**
 * 伪装五级模式卡（FEAT-040 FR-004 / FR-005）：五枚模式 chip + 每级生效真值
 * （生效 / 仅档案 / 不可用）+ 顶部「当前生效」按实时能力计算；能力不足的
 * 模式置灰，点击只弹「缺 …」提示与去授权，不写偏好、不切换。
 */
@Composable
internal fun DisguiseModeCard(
    snapshot: PrivilegeSnapshot,
    preferred: DisguiseMode,
    onPrefer: (DisguiseMode) -> Unit,
    onOpenCapability: (Capability) -> Unit,
) {
    var denied by remember { mutableStateOf<DisguiseMode?>(null) }
    val highest = DisguiseModes.highestEffective(snapshot)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "伪装模式",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            StatusChip(text = "当前生效：${highest.label}", tone = ChipTone.OK)
        }
        Spacer(Modifier.size(8.dp))
        DisguiseMode.entries.forEach { mode ->
            val effect = DisguiseModes.effect(mode, snapshot)
            val (stateLabel, stateTone) = when (effect.state) {
                ModeState.EFFECTIVE -> "生效" to ChipTone.OK
                ModeState.PROFILE_ONLY -> "仅档案" to ChipTone.WARN
                ModeState.UNAVAILABLE -> "不可用" to ChipTone.NEUTRAL
            }
            Row(
                modifier = Modifier.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectableChip(
                    text = mode.label,
                    selected = preferred == mode,
                    // 能力不足只置灰、不禁点击：点击弹「缺 …」提示与去授权（FEAT-040 FR-005）
                    modifier = Modifier.alpha(if (effect.state == ModeState.UNAVAILABLE) 0.5f else 1f),
                    onClick = {
                        // NB-31（深挖第三轮）：成功切换即清除上一枚不可用模式的
                        // 「缺 …」提示行 —— 否则旧提示与当前选中态自相矛盾。
                        if (effect.state == ModeState.UNAVAILABLE) denied = mode else {
                            denied = null
                            onPrefer(mode)
                        }
                    },
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    text = stateLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = stateTone.content,
                    modifier = Modifier.weight(0.3f),
                )
                Text(
                    text = effect.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.7f),
                )
            }
        }
        denied?.let { mode ->
            val missing = snapshot.missing(mode.required)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${mode.label} 缺 ${capabilityLabels(missing)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = ChipTone.WARN.content,
                    modifier = Modifier.weight(1f),
                )
                snapshot.guideTarget(mode.required)?.let { target ->
                    TextButton(onClick = { onOpenCapability(target) }) { Text("去授权") }
                }
            }
        }
    }
}

/** 信息卡（说明 / 能力边界 / 引导）。 */
@Composable
internal fun InfoCard(
    title: String,
    body: String,
    tone: ChipTone = ChipTone.NEUTRAL,
    actions: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(tone.container.copy(alpha = 0.45f))
            .padding(16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = tone.content,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actions != null) {
            Spacer(Modifier.size(10.dp))
            actions()
        }
    }
}

/** 命令执行反馈条（成功绿 / 失败红，带系统原始输出摘要）。 */
@Composable
internal fun ExecFeedback(
    message: String?,
    isError: Boolean,
    detail: String? = null,
) {
    if (message == null) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (isError) {
                    ChipTone.ERROR.container.copy(alpha = 0.45f)
                } else {
                    ChipTone.OK.container.copy(alpha = 0.45f)
                },
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (isError) ChipTone.ERROR.content else ChipTone.OK.content,
        )
        if (!detail.isNullOrBlank()) {
            Spacer(Modifier.size(2.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 可选中档案 chip（自绘：零实验性 API 依赖）。 */
@Composable
internal fun SelectableChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** FEAT-040：置灰态（能力不足）—— 不响应点击、降透明度。 */
    enabled: Boolean = true,
) {
    // 按压反馈（v0.7.6）：interactionSource 自持同步喂给 clickable +
    // pressScale —— 电量档位 / 信号格数等选项按下有「按进液体」手感
    // （四件套共用本组件，一处生效）。
    val interaction = remember { MutableInteractionSource() }
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .alpha(if (enabled) 1f else 0.5f)
            .pressScale(interaction)
            .clip(MaterialTheme.shapes.medium)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** 键值卡（真机信息 / 状态读数通用容器）。 */
@Composable
internal fun KeyValueCard(title: String, rows: List<Pair<String, String>>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(6.dp))
        rows.forEach { (k, v) -> InfoRow(label = k, value = v) }
    }
}
