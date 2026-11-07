package com.abyss.tools.feature.island

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.abyss.tools.core.designsystem.component.ChipTone
import com.abyss.tools.core.designsystem.component.StatusChip
import kotlin.math.roundToInt

/**
 * 灵动岛功能页：授权门禁 + 悬浮球开关 + 外观设置（大小 / 透明度 / 主题色）+
 * 使用说明 + 面板预览。
 *
 * 数据流：外观写 SharedPreferences("island_prefs")（单一数据源），运行中的
 * FloatingBallService 监听同源实时重绘 —— 页面与悬浮球之间不做内存直通，
 * 服务冷热启动都拿得到完整配置。
 */
@Composable
fun IslandScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // ── 实时状态：授权 + 服务运行 ──────────────────────────────────────────
    var overlayGranted by remember { mutableStateOf(OverlayPerm.canDrawOverlays(context)) }
    var running by remember { mutableStateOf(FloatingBallService.running) }
    var grantHint by remember { mutableStateOf<String?>(null) }

    // ON_RESUME 刷新：从系统授权页 / 服务开关返回时同步真实状态（生命周期
    // 事件感知，不做时间轮询）。LocalLifecycleOwner 用 compose.ui.platform
    // 旧位置 —— 新位置在 lifecycle-runtime-compose（额外依赖），此处不引入，
    // 旧位置仅为弃用警告，行为一致。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = OverlayPerm.canDrawOverlays(context)
                running = FloatingBallService.running
                grantHint = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── 外观（写同源 SharedPreferences，Service 侧实时重绘）────────────────
    var prefs by remember { mutableStateOf(IslandPrefs.read(context)) }

    fun updatePrefs(transform: (IslandPrefs) -> IslandPrefs) {
        val next = transform(prefs)
        prefs = next
        IslandPrefs.write(context, next)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        IslandHeader(onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.size(4.dp))

            // ── 授权状态卡 ──────────────────────────────────────────────────
            if (overlayGranted) {
                IslandCard(tone = ChipTone.OK) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "悬浮窗权限已授予",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = ChipTone.OK.content,
                            modifier = Modifier.weight(1f),
                        )
                        StatusChip(text = "已授权", tone = ChipTone.OK)
                    }
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = "深渊工具已可在其他应用上层绘制悬浮球。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                IslandCard(tone = ChipTone.WARN) {
                    Text(
                        text = "缺少悬浮窗权限",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = ChipTone.WARN.content,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = "悬浮球需要「显示在其他应用上层」的授权，各系统路径略有不同：\n" +
                            "· 原生 Android：设置 → 应用 → 深渊工具 → 显示在其他应用上层\n" +
                            "· MIUI / EMUI 等定制系统：多在「特殊权限」或「其他权限」内",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(10.dp))
                    Button(
                        onClick = {
                            runCatching { context.startActivity(OverlayPerm.overlaySettingsIntent()) }
                                .onFailure {
                                    grantHint = "该系统未提供直达授权页，请到 设置 → 应用 → 深渊工具 → " +
                                        "「显示在其他应用上层」手动开启"
                                }
                        },
                    ) {
                        Text(text = "授予悬浮窗权限")
                    }
                    grantHint?.let { hint ->
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = ChipTone.WARN.content,
                        )
                    }
                }
            }

            // ── 悬浮球开关卡 ────────────────────────────────────────────────
            IslandCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "悬浮球",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.size(2.dp))
                        Text(
                            text = when {
                                !overlayGranted -> "先授予悬浮窗权限"
                                running -> "运行中 · 通知栏常驻（前台服务的系统要求，静音）"
                                else -> "已停止"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = running,
                        enabled = overlayGranted,
                        onCheckedChange = { want ->
                            // 乐观更新即时反馈；若启动异常（如权限运行中被收回），
                            // ON_RESUME 时以 Service 真实状态兜底校正
                            running = want
                            if (want) FloatingBallService.start(context)
                            else FloatingBallService.stop(context)
                        },
                    )
                }
            }

            // ── 外观设置卡 ──────────────────────────────────────────────────
            IslandCard {
                IslandCardTitle("外观")
                Spacer(Modifier.size(10.dp))

                // 球大小（44–72dp，2dp 一档）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "球大小",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${prefs.ballSizeDp} dp",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = prefs.ballSizeDp.toFloat(),
                    onValueChange = { raw ->
                        updatePrefs {
                            it.copy(
                                ballSizeDp = raw.roundToInt()
                                    .coerceIn(IslandPrefs.SIZE_MIN_DP, IslandPrefs.SIZE_MAX_DP),
                            )
                        }
                    },
                    valueRange = IslandPrefs.SIZE_MIN_DP.toFloat()..IslandPrefs.SIZE_MAX_DP.toFloat(),
                    steps = 13,
                )
                Spacer(Modifier.size(8.dp))

                // 透明度（50%–100%，5% 一档）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "透明度",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${(prefs.ballAlpha * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = prefs.ballAlpha,
                    onValueChange = { raw ->
                        updatePrefs {
                            it.copy(ballAlpha = ((raw * 100).roundToInt() / 100f)
                                .coerceIn(IslandPrefs.ALPHA_MIN, IslandPrefs.ALPHA_MAX))
                        }
                    },
                    valueRange = IslandPrefs.ALPHA_MIN..IslandPrefs.ALPHA_MAX,
                    steps = 9,
                )
                Spacer(Modifier.size(8.dp))

                // 主题色（预设 4 色，避开蓝紫）
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    IslandColorPreset.entries.forEach { preset ->
                        val selected = prefs.ballColor == preset
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    updatePrefs { it.copy(ballColor = preset) }
                                }
                                .padding(4.dp),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(preset.color)
                                    .border(
                                        width = if (selected) 2.5.dp else 1.dp,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        },
                                        shape = CircleShape,
                                    ),
                            ) {
                                if (selected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = preset.label,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.size(4.dp))
                            Text(
                                text = preset.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.size(4.dp))
                Text(
                    text = "改动即时生效：运行中的悬浮球会实时重绘。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── 说明卡 ──────────────────────────────────────────────────────
            IslandCard {
                IslandCardTitle("使用说明")
                Spacer(Modifier.size(6.dp))
                listOf(
                    "点击球体：展开 / 收起快捷面板",
                    "按住拖动：移动位置；松手自动吸附到左 / 右屏幕边缘",
                    "面板展示系统实时时间与真实电量（取自系统电池广播，非估算）",
                    "点击面板以外区域：自动收起",
                    "部分定制系统（MIUI / EMUI 等）还需额外允许「后台弹窗」，否则面板可能无法弹出",
                ).forEach { tip ->
                    Text(
                        text = "· $tip",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }

            // ── 测一测：面板预览（页内样张，假数据已标注；真实面板为实时读数）──
            IslandCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IslandCardTitle(
                        text = "测一测 · 面板预览",
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(text = "预览", tone = ChipTone.NEUTRAL)
                }
                Spacer(Modifier.size(4.dp))
                Text(
                    text = "球点击展开后的样子。样张使用示例数据；真实面板的时间与电量均为系统实时读数。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(modifier = Modifier.width(PANEL_PREVIEW_WIDTH)) {
                        IslandPanel(
                            timeText = "12:34",
                            battery = BatteryInfo(percent = 82, charging = true),
                            onOpenApp = {},
                            onCollapse = {},
                        )
                    }
                }
            }

            Spacer(Modifier.size(24.dp))
        }
    }
}

private val PANEL_PREVIEW_WIDTH = 300.dp

/** 页头（返回 + 标题 + 徽章；与 appmgr / shizukukit 的页头同构）。 */
@Composable
private fun IslandHeader(onBack: () -> Unit) {
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
            text = "灵动岛 · 悬浮球",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        StatusChip(text = "实时电量", tone = ChipTone.INFO)
    }
}

/** 通用卡容器（视觉对齐 disguise / appmgr 卡片家族：tone 为空时 surfaceVariant 半透明）。 */
@Composable
private fun IslandCard(
    modifier: Modifier = Modifier,
    tone: ChipTone? = null,
    content: @Composable () -> Unit,
) {
    val containerColor = if (tone != null) {
        tone.container.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(containerColor)
            .padding(16.dp),
    ) {
        content()
    }
}

/** 卡内区块标题。 */
@Composable
private fun IslandCardTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}
