package com.abyss.tools.feature.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abyss.tools.core.common.ToolCatalog
import com.abyss.tools.core.designsystem.component.SectionHeader
import com.abyss.tools.core.designsystem.component.StatusChip
import com.abyss.tools.core.designsystem.component.ChipTone
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 组件工坊 —— 桌面能力的中枢页（v0.8.0 新增；v0.9.0「引力场」重构为
 * 四组件银河画廊）：
 *
 *  - 黑洞动态时钟实时预览（与桌面组件共用 BlackHoleArt 单一绘制实现，
 *    所见即所得）；尺寸 / 主题 / 节奏三件套即时预览 + 写偏好 —— 主题
 *    与整分开关现在统辖全部四组件；
 *  - 引力家族三卡片（引力电池 / 星轨日历 / 深渊引力仪）：真实数据驱
 *    动的动画预览（电池 / 日历逐帧读，内存 / 存储每秒采样一次 —— 每
 *    帧读 ActivityManager/StatFs 过重）+ 各自的「添加到桌面」；
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
    var pinFeedbackClock by remember { mutableStateOf<String?>(null) }
    var pinFeedbackBattery by remember { mutableStateOf<String?>(null) }
    var pinFeedbackCalendar by remember { mutableStateOf<String?>(null) }
    var pinFeedbackGauge by remember { mutableStateOf<String?>(null) }

    // 实时时钟（驱动时钟预览每帧重绘 —— 桌面组件为整分粒度，预览更流畅）
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
            StatusChip(text = "v0.9.0 引力场", tone = ChipTone.OK)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp, bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── 黑洞动态时钟 · 实时预览（所见即所得）────────────────────
            item {
                SectionHeader(
                    title = "黑洞动态时钟",
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
                        .clip(MaterialTheme.shapes.extraLarge)
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
            }

            // ── 吸积盘色系（全局主题：四组件共用）────────────────────────
            item {
                SectionHeader(title = "吸积盘色系")
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

            // ── 节奏（一个开关管全部四组件）─────────────────────────────
            item {
                ListItem(
                    headlineContent = { Text("整分重绘（推荐）") },
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
                        Text("立即刷新 · 全部四组件")
                    }
                }
            }

            // ── 黑洞时钟 · 添加到桌面 ──────────────────────────────────
            item {
                SectionHeader(title = "添加黑洞时钟")
                PinFeedbackText(pinFeedbackClock)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            pinFeedbackClock = requestPin(context, BlackHoleClockWidget::class.java)
                        },
                    ) { Text("添加 4×2 宽版") }
                    FilledTonalButton(
                        onClick = {
                            pinFeedbackClock = requestPin(context, BlackHoleClockWidgetSmall::class.java)
                        },
                    ) { Text("添加 2×2 方版") }
                }
            }

            // ── 引力家族（v0.9.0 三位新成员 · 组件银河）─────────────────
            item {
                SectionHeader(title = "引力家族")
                GravityFamilyCard(
                    title = "引力电池",
                    pinFeedback = pinFeedbackBattery,
                    onPin = {
                        pinFeedbackBattery = requestPin(context, GravityBatteryWidget::class.java)
                    },
                    preview = { GravityBatteryPreview(theme) },
                )
                Spacer(Modifier.height(12.dp))
                GravityFamilyCard(
                    title = "星轨日历",
                    pinFeedback = pinFeedbackCalendar,
                    onPin = {
                        pinFeedbackCalendar = requestPin(context, StellarCalendarWidget::class.java)
                    },
                    preview = { StellarCalendarPreview(theme) },
                )
                Spacer(Modifier.height(12.dp))
                GravityFamilyCard(
                    title = "深渊引力仪",
                    pinFeedback = pinFeedbackGauge,
                    onPin = {
                        pinFeedbackGauge = requestPin(context, AbyssGaugeWidget::class.java)
                    },
                    preview = { AbyssGaugePreview(theme) },
                )
            }

            // ── 桌面美化家族 ──────────────────────────────────────────
            item {
                SectionHeader(title = "桌面美化家族")
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

// ── 引力家族卡片 ────────────────────────────────────────────────────

/**
 * 引力家族卡片：动画预览（1:1 圆角裁切）+ 单行标题 + 「添加到桌面」+
 * 固定反馈文案。预览内容由调用方注入（三种数据源各取所需）。
 * UI-001：summary 摘要行删除 —— 预览即说明。
 */
@Composable
private fun GravityFamilyCard(
    title: String,
    pinFeedback: String?,
    onPin: () -> Unit,
    preview: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        preview()
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        FilledTonalButton(onClick = onPin) { Text("添加到桌面") }
        PinFeedbackText(pinFeedback)
    }
}

/** 引力家族共享预览框（1:1 · 圆角 24dp · 深色底承托星空）。 */
@Composable
private fun FamilyPreview(onDraw: DrawScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Canvas(modifier = Modifier.fillMaxSize(), onDraw = onDraw)
    }
}

/** 引力电池预览：真实电池数据 + 逐帧时钟（粘性广播只读不注册，逐帧读取开销可忽略）。 */
@Composable
private fun GravityBatteryPreview(theme: BlackHoleTheme) {
    val context = LocalContext.current
    val tick by produceState(
        initialValue = System.currentTimeMillis() to GravityBatteryWidget.readSnapshot(context),
    ) {
        while (isActive) {
            withFrameNanos { }
            value = System.currentTimeMillis() to GravityBatteryWidget.readSnapshot(context)
        }
    }
    FamilyPreview {
        drawIntoCanvas { c ->
            GravityBatteryArt.renderInto(
                canvas = c.nativeCanvas,
                widthPx = size.width.toInt(),
                heightPx = size.height.toInt(),
                timeMs = tick.first,
                theme = theme,
                snapshot = tick.second,
            )
        }
    }
}

/** 星轨日历预览：日历即当前时刻（纯 JVM 折算，无需系统读取）。 */
@Composable
private fun StellarCalendarPreview(theme: BlackHoleTheme) {
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (isActive) {
            withFrameNanos { }
            value = System.currentTimeMillis()
        }
    }
    FamilyPreview {
        drawIntoCanvas { c ->
            StellarCalendarArt.renderInto(
                canvas = c.nativeCanvas,
                widthPx = size.width.toInt(),
                heightPx = size.height.toInt(),
                timeMs = nowMs,
                theme = theme,
            )
        }
    }
}

/**
 * 深渊引力仪预览：时钟逐帧驱动动画，内存 / 存储每秒采样一次缓存复用
 * （逐帧读 ActivityManager / StatFs 过重 —— 帧率优先，读数秒级足够）。
 */
@Composable
private fun AbyssGaugePreview(theme: BlackHoleTheme) {
    val context = LocalContext.current
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (isActive) {
            withFrameNanos { }
            value = System.currentTimeMillis()
        }
    }
    val snapshot by produceState(initialValue = AbyssGaugeWidget.readSnapshot(context)) {
        while (isActive) {
            value = AbyssGaugeWidget.readSnapshot(context)
            delay(1000)
        }
    }
    FamilyPreview {
        drawIntoCanvas { c ->
            AbyssGaugeArt.renderInto(
                canvas = c.nativeCanvas,
                widthPx = size.width.toInt(),
                heightPx = size.height.toInt(),
                timeMs = nowMs,
                theme = theme,
                snapshot = snapshot,
            )
        }
    }
}

/** 固定通道反馈文案（不支持快捷固定时红字提示，与黑洞时钟同款语义）。 */
@Composable
private fun PinFeedbackText(text: String?) {
    if (text != null) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (text.contains("不支持")) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

/** 系统固定通道（按 provider 家族泛化）；返回人话反馈（不支持时给手动指引文案）。 */
private fun requestPin(context: Context, provider: Class<out AppWidgetProvider>): String {
    val manager = AppWidgetManager.getInstance(context)
    val component = ComponentName(context, provider)
    return if (manager.isRequestPinAppWidgetSupported) {
        val ok = runCatching {
            manager.requestPinAppWidget(component, null, null)
        }.getOrDefault(false)
        if (ok) {
            "已请求固定到桌面，请在系统确认框点「添加」"
        } else {
            "固定请求未发出（启动器拒绝），请改用长按桌面 → 小组件手动添加"
        }
    } else {
        "此启动器不支持快捷固定，请改用长按桌面 → 小组件手动添加"
    }
}
