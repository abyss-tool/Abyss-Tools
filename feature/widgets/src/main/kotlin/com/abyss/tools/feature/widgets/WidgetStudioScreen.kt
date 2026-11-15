package com.abyss.tools.feature.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abyss.tools.core.common.ToolCatalog
import com.abyss.tools.core.designsystem.component.SectionHeader
import com.abyss.tools.core.designsystem.component.StatusChip
import com.abyss.tools.core.designsystem.component.ChipTone
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.isActive

/**
 * 组件工坊 —— 桌面能力的中枢页（v0.8.0 新增）：
 *
 *  - 黑洞时钟实时预览（与桌面组件共用 BlackHoleArt 单一绘制实现，所
 *    见即所得）；尺寸 / 主题 / 节奏三件套即时预览 + 写偏好；
 *  - 「添加到桌面」走系统 requestPinAppWidget 通道（Android 8+ 官方
 *    通道，启动器不支持时诚实回退到手动指引）；
 *  - 灵动岛 / 动态壁纸快捷入口（同一「桌面美化」域）。
 */
@Composable
fun WidgetStudioScreen(
    onOpenTool: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    var theme by remember {
        mutableStateOf(WidgetStudioPrefs.theme(context))
    }
    var minuteTick by remember {
        mutableStateOf(WidgetStudioPrefs.minuteTickEnabled(context))
    }
    var previewWide by remember { mutableStateOf(true) }
    var pinFeedback by remember { mutableStateOf<String?>(null) }

    // 实时时钟（驱动预览每帧重绘 —— 桌面组件为整分粒度，预览更流畅）
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (isActive) {
            withFrameNanos { }
            value = System.currentTimeMillis()
        }
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
            Text(
                text = "组件工坊",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(8.dp))
            StatusChip(text = "v0.8.0 新增", tone = ChipTone.OK)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp, end = 20.dp, bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── 实时预览（所见即所得）──────────────────────────────────
            item {
                SectionHeader(
                    title = "黑洞动态时钟",
                    subtitle = "事件视界 + 吸积盘 + 星场逐帧绘制 · 与桌面组件同源引擎",
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = previewWide,
                                onClick = { previewWide = true },
                                label = { Text("4×2") },
                            )
                            FilterChip(
                                selected = !previewWide,
                                onClick = { previewWide = false },
                                label = { Text("2×2") },
                            )
                        }
                    },
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(if (previewWide) 2.2f else 1f)
                        .clip(RoundedCornerShape(28.dp))
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawIntoCanvas { c ->
                            BlackHoleArt.renderInto(
                                canvas = c.nativeCanvas,
                                widthPx = size.width.toInt(),
                                heightPx = size.height.toInt(),
                                timeMs = nowMs,
                                theme = theme,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "预览按帧动画；桌面组件整分重绘 —— 吸积盘每小时旋转一圈，" +
                        "分钟粒度下肉眼可见气流的缓慢行进。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── 主题 ───────────────────────────────────────────────────
            item {
                SectionHeader(title = "吸积盘色系", subtitle = "切换后下一分钟生效，或点「立即刷新」")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BlackHoleTheme.entries.forEach { t ->
                        FilterChip(
                            selected = theme == t,
                            onClick = {
                                theme = t
                                WidgetStudioPrefs.setTheme(context, t)
                                BlackHoleTicker.refreshNow(context)
                            },
                            label = { Text(t.label) },
                            leadingIcon = if (theme == t) {
                                { Icon(Icons.Rounded.CheckCircle, null, Modifier.size(16.dp)) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }

            // ── 节奏 ───────────────────────────────────────────────────
            item {
                ListItem(
                    headlineContent = { Text("整分重绘（推荐）") },
                    supportingContent = {
                        Text(
                            "AlarmManager 精确闹钟链：每个整分重绘位图（targetSdk 28 免授权）。" +
                                "关闭后回到系统 30 分钟兜底节奏，仅时间跳变。",
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = minuteTick,
                            onCheckedChange = { enabled ->
                                minuteTick = enabled
                                WidgetStudioPrefs.setMinuteTickEnabled(context, enabled)
                                if (enabled) BlackHoleTicker.refreshNow(context)
                            },
                        )
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { BlackHoleTicker.refreshNow(context) }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("立即刷新")
                    }
                }
            }

            // ── 添加到桌面 ────────────────────────────────────────────
            item {
                SectionHeader(title = "添加到桌面", subtitle = "系统 requestPinAppWidget 官方通道（Android 8+）")
                pinFeedback?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.contains("不支持")) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            pinFeedback = requestPin(context, wide = true)
                        },
                    ) { Text("添加 4×2 宽版") }
                    FilledTonalButton(
                        onClick = {
                            pinFeedback = requestPin(context, wide = false)
                        },
                    ) { Text("添加 2×2 方版") }
                }
                Text(
                    text = "若你的启动器不支持快捷固定：长按桌面空白处 → 小组件 → 深渊工具 → 黑洞时钟。" +
                        "（Android 不允许应用未经用户确认直接添加桌面组件 —— 这是系统边界）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── 桌面美化家族 ──────────────────────────────────────────
            item {
                SectionHeader(title = "桌面美化家族", subtitle = "同一能力域的其他成员")
                ListItem(
                    headlineContent = { Text("灵动岛与悬浮球") },
                    supportingContent = { Text("WindowManager 悬浮件：拖拽 / 吸附 / 状态岛动画") },
                    trailingContent = {
                        TextButton(onClick = { onOpenTool(ToolCatalog.ID_DYNAMIC_ISLAND) }) { Text("打开") }
                    },
                )
                ListItem(
                    headlineContent = { Text("动态壁纸") },
                    supportingContent = { Text("WallpaperService 引擎：液态玻璃同源光斑工坊") },
                    trailingContent = {
                        TextButton(onClick = { onOpenTool(ToolCatalog.ID_LIVE_WALLPAPER) }) { Text("打开") }
                    },
                )
            }
        }
    }
}

/** 系统固定通道；返回人话反馈（不支持时给手动指引文案）。 */
private fun requestPin(context: Context, wide: Boolean): String {
    val manager = AppWidgetManager.getInstance(context)
    val component = ComponentName(
        context,
        if (wide) BlackHoleClockWidget::class.java else BlackHoleClockWidgetSmall::class.java,
    )
    return if (manager.isRequestPinAppWidgetSupported) {
        val ok = runCatching {
            manager.requestPinAppWidget(component, null, null)
        }.getOrDefault(false)
        if (ok) "已请求固定到桌面 —— 在系统确认框里点「添加」。" else "固定请求未发出（启动器拒绝）—— 请用下方手动指引。"
    } else {
        "此启动器不支持快捷固定 —— 请用下方手动指引添加。"
    }
}
