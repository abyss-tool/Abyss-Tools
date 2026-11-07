package com.abyss.tools.feature.island

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 灵动岛 UI 层：悬浮球 / 快捷面板 / 自绘电池指示器。
 *
 * Service 的两个 overlay 窗口与本页「测一测」预览共用这里的同一套 composable
 * —— 页内看到的即是球点击展开后的真实样子。
 *
 * 电池图标说明：material-icons-core 的核心集不含 Battery 系列（扩展包才有），
 * 按仓库纪律不引 extended —— 电池用 Canvas 自绘（外壳 + 按真实电量比例填充 +
 * 充电闪电），零新增依赖。
 */

// 局部视觉令牌（衬底 / 描边这类「效果色」，主题色一律由 AbyssTheme 派生）
private val BallEdgeLight = Color.White.copy(alpha = 0.35f)
private val BallContentScrim = Color.Black.copy(alpha = 0.16f)
private const val PANEL_CORNER_DP = 24
private const val PANEL_SURFACE_ALPHA = 0.97f

/**
 * 悬浮球（纯展示，无内部手势 —— 拖拽 / 点击判定全部在 Service 层的
 * View.OnTouchListener 处理，返回 true 消费，与 Compose 互不抢事件）。
 */
@Composable
internal fun FloatingBall(
    prefs: IslandPrefs,
    battery: BatteryInfo,
    modifier: Modifier = Modifier,
) {
    val ballColor = prefs.ballRenderColor()

    // 充电呼吸：外圈光环 alpha 0.6 ↔ 1.0 循环（非充电态静止）
    var ringAlpha = 1f
    if (battery.charging) {
        val breath = rememberInfiniteTransition(label = "island_breath")
        ringAlpha = breath.animateFloat(
            initialValue = 0.6f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "island_breath_alpha",
        ).value
    }

    Box(modifier = modifier.size(prefs.ballSizeDp.dp), contentAlignment = Alignment.Center) {
        // 充电呼吸外圈（贴边细光）
        if (battery.charging) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        width = 2.dp,
                        color = ballColor.copy(alpha = ringAlpha),
                        shape = CircleShape,
                    ),
            )
        }
        // 球体：预设色 × 用户透明度 + 1dp 细光描边
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(ballColor)
                .border(width = 1.dp, color = BallEdgeLight, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // 中心读数：轻衬底保证任意壁纸上可读（内缩 2dp 不盖住细光描边）
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(2.dp)
                    .background(BallContentScrim, CircleShape),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val large = prefs.ballSizeDp >= 56
                BatteryIndicator(
                    levelPercent = battery.percent,
                    charging = battery.charging,
                    color = Color.White,
                    modifier = Modifier.size(
                        width = if (large) 22.dp else 18.dp,
                        height = if (large) 12.dp else 10.dp,
                    ),
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    text = if (battery.percent >= 0) "${battery.percent}%" else "--%",
                    style = if (large) {
                        MaterialTheme.typography.labelLarge
                    } else {
                        MaterialTheme.typography.labelMedium
                    },
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
    }
}

/**
 * 快捷面板（球点击展开 / 页内预览共用）。
 * 状态岛：系统实时时间 + 真实电量读数；快捷动作只做展示类操作。
 */
@Composable
internal fun IslandPanel(
    timeText: String,
    battery: BatteryInfo,
    onOpenApp: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PANEL_CORNER_DP.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = PANEL_SURFACE_ALPHA))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(PANEL_CORNER_DP.dp),
            )
            .padding(16.dp),
    ) {
        // 状态岛：HH:mm + 电池真实读数（充电态点亮）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = timeText,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            BatteryIndicator(
                levelPercent = battery.percent,
                charging = battery.charging,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(width = 26.dp, height = 14.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = if (battery.percent >= 0) "${battery.percent}%" else "--%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (battery.charging) {
                Spacer(Modifier.size(6.dp))
                Text(
                    text = "充电中",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.size(14.dp))
        // 快捷动作行（诚实边界：无 shell 通道，不做 dumpsys battery reset 这类
        // 系统级动作 —— 面板只保留展示 / 跳转类操作）
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onOpenApp,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = "打开深渊工具")
            }
            OutlinedButton(
                onClick = onCollapse,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = "收起面板")
            }
        }
        Spacer(Modifier.size(12.dp))
        Text(
            text = "深渊工具 · 灵动岛",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

/**
 * 自绘电池指示器：横置外壳 + 正极小盖 + 按真实电量比例的填充 + 充电闪电
 * （深色描边保证满电白底上也可辨）。[levelPercent] < 0 视为未知 —— 只画壳不填充。
 */
@Composable
internal fun BatteryIndicator(
    levelPercent: Int,
    charging: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
) {
    Canvas(modifier = modifier) {
        val strokeW = size.height * 0.13f
        val bodyW = size.width * 0.80f
        val bodyH = size.height - strokeW * 2f
        val bodyTop = strokeW

        // 外壳
        drawRoundRect(
            color = color.copy(alpha = 0.85f),
            topLeft = Offset(0f, bodyTop),
            size = Size(bodyW, bodyH),
            cornerRadius = CornerRadius(size.height * 0.16f),
            style = Stroke(width = strokeW),
        )
        // 正极小盖
        drawRoundRect(
            color = color.copy(alpha = 0.85f),
            topLeft = Offset(bodyW + size.height * 0.14f, size.height * 0.30f),
            size = Size(size.width * 0.08f, size.height * 0.40f),
            cornerRadius = CornerRadius(size.width * 0.02f),
        )
        // 电量填充（真实读数；未知 → 不填充）
        if (levelPercent >= 0) {
            val level = levelPercent.coerceIn(0, 100) / 100f
            val fillW = (bodyW - strokeW * 3f) * level
            if (fillW > 0f) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(strokeW * 1.5f, bodyTop + strokeW * 1.5f),
                    size = Size(fillW, bodyH - strokeW * 3f),
                    cornerRadius = CornerRadius(size.height * 0.08f),
                )
            }
        }
        // 充电闪电
        if (charging) {
            val cx = bodyW / 2f
            val cy = size.height / 2f
            val bolt = Path().apply {
                moveTo(cx + size.width * 0.045f, cy - size.height * 0.34f)
                lineTo(cx - size.width * 0.175f, cy + size.height * 0.07f)
                lineTo(cx - size.width * 0.020f, cy + size.height * 0.07f)
                lineTo(cx - size.width * 0.055f, cy + size.height * 0.34f)
                lineTo(cx + size.width * 0.175f, cy - size.height * 0.07f)
                lineTo(cx + size.width * 0.020f, cy - size.height * 0.07f)
                close()
            }
            drawPath(
                path = bolt,
                color = Color.Black.copy(alpha = 0.55f),
                style = Stroke(width = size.height * 0.10f),
            )
            drawPath(path = bolt, color = color)
        }
    }
}
