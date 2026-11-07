package com.abyss.tools.feature.wallpaper

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.abyss.tools.core.designsystem.component.ChipTone
import com.abyss.tools.core.designsystem.component.SectionHeader
import com.abyss.tools.core.designsystem.component.StatusChip
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * 动态壁纸：实时预览 + 主题 / 速度设置 + 一键设为壁纸。
 *
 * 双渲染层说明：壁纸引擎（AbyssWallpaperService）在无 Compose 运行时的
 * 服务进程里用 android.graphics 绘制；本页预览用 Compose Canvas 的
 * [drawAbyssPreview] 移植版。两端共享 WallpaperThemes / buildScene /
 * orbCenter 与 WallpaperScene 的全部几何常量 —— 同一几何、同一参数，
 * 只换画布 API，桌面与预览逐帧一致。
 *
 * @param onBack 返回上级路由
 */
@Composable
fun WallpaperScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(WallpaperPrefs.PREFS_NAME, Context.MODE_PRIVATE)
    }

    var themeId by remember { mutableStateOf(WallpaperPrefs.readThemeId(prefs)) }
    var speedFactor by remember { mutableFloatStateOf(WallpaperPrefs.readSpeedFactor(prefs)) }
    val theme = remember(themeId) { WallpaperThemes.byId(themeId) }

    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var feedbackError by remember { mutableStateOf(false) }

    fun selectTheme(id: String) {
        themeId = id
        WallpaperPrefs.writeThemeId(prefs, id) // 预览即时响应；引擎回到桌面时重读
    }

    fun applyAsWallpaper() {
        // 直达「设置此动态壁纸」系统确认页；部分 ROM 不支持 → 降级系统选择器
        val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(context, AbyssWallpaperService::class.java),
        )
        try {
            context.startActivity(direct)
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (e2: ActivityNotFoundException) {
                feedbackMessage = "未找到系统壁纸设置入口，请在系统设置的壁纸里手动选择"
                feedbackError = true
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        WallpaperHeader(onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.size(12.dp))

            SectionHeader(title = "实时预览", subtitle = "所见即桌面 · 液态玻璃光斑实时漂移")
            WallpaperPreviewCard(theme = theme, speedFactor = speedFactor)

            Spacer(Modifier.size(18.dp))

            SectionHeader(title = "主题", subtitle = "深色底 × 柔光光斑，四套预设")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WallpaperThemes.presets.forEach { preset ->
                    WallpaperThemeChip(
                        theme = preset,
                        selected = preset.id == themeId,
                        onClick = { selectTheme(preset.id) },
                    )
                }
            }

            Spacer(Modifier.size(18.dp))

            SectionHeader(title = "漂移速度", subtitle = "0.5×–2.0×，叠加在主题内建节奏之上")
            SpeedCard(
                speedFactor = speedFactor,
                onValueChange = { speedFactor = it },
                onValueChangeFinished = { WallpaperPrefs.writeSpeedFactor(prefs, speedFactor) },
            )

            Spacer(Modifier.size(18.dp))

            Button(
                onClick = { applyAsWallpaper() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(text = "设为动态壁纸", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(Modifier.size(10.dp))
            FeedbackBar(message = feedbackMessage, isError = feedbackError)

            Spacer(Modifier.size(18.dp))

            SectionHeader(title = "工作方式与功耗", subtitle = null)
            HowItWorksCard()

            Spacer(Modifier.size(32.dp))
        }
    }
}

// ── 页头 ────────────────────────────────────────────────────────────────────

/** 页头（返回键 + 标题 + 尾随徽章，与兄弟模块页头同构）。 */
@Composable
private fun WallpaperHeader(onBack: () -> Unit) {
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
            text = "动态壁纸",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        StatusChip(text = "系统托管", tone = ChipTone.INFO)
    }
}

// ── 实时预览（Compose Canvas 第二渲染层）────────────────────────────────────

/** 预览帧间隔：与引擎端 30 fps 对齐，预览不比桌面更费电。 */
private const val PREVIEW_FRAME_NANOS = 33_000_000L

/** 预览场景缓存（普通对象：只在 主题/尺寸 变化时重建，无需参与重组）。 */
private class PreviewSceneCache {
    var key: String? = null
    var orbs: List<Orb> = emptyList()
}

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun clockNow(): String = LocalTime.now().format(CLOCK_FORMAT)

/**
 * 实时预览大卡（16:9，圆角 28dp）：Compose Canvas 逐帧重绘 +
 * 状态岛（时间 + 「预览」徽章）示意桌面效果。
 */
@Composable
private fun WallpaperPreviewCard(
    theme: WallpaperTheme,
    speedFactor: Float,
) {
    // 30 fps 节流的漂移时钟：t 只按发射帧间的真实流逝累加（跳帧不偷时间）
    var tSeconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var lastEmitted = -1L
        while (true) {
            withFrameNanos { now ->
                if (lastEmitted < 0) lastEmitted = now
                if (now - lastEmitted >= PREVIEW_FRAME_NANOS) {
                    tSeconds += (now - lastEmitted) / 1_000_000_000f
                    lastEmitted = now
                }
            }
        }
    }

    // 状态岛时钟（分钟级刷新足够示意）
    var clock by remember { mutableStateOf(clockNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = clockNow()
            delay(60_000)
        }
    }

    val sceneCache = remember { PreviewSceneCache() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(28.dp)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width.toInt()
            val h = size.height.toInt()
            if (w > 0 && h > 0) {
                val key = "${theme.id}:$w:$h"
                if (sceneCache.key != key) {
                    sceneCache.key = key
                    sceneCache.orbs = buildScene(theme, w, h)
                }
                val effectiveT = tSeconds * theme.driftSpeedFactor * speedFactor
                drawAbyssPreview(theme, sceneCache.orbs, effectiveT)
            }
        }

        // 状态岛：时间 + 预览徽章（模拟桌面时钟卡）
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = clock,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.92f),
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text = "预览",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.78f),
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color.White.copy(alpha = 0.16f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * drawFrame 的 Compose 移植版（预览专用）。
 * 与引擎端共享几何 / 常量（ORB_INNER_ALPHA 等），此层每帧新建 Brush 属
 * Compose 常规开销（预览非电池敏感路径）；引擎端才做 Paint 池零分配。
 */
private fun DrawScope.drawAbyssPreview(theme: WallpaperTheme, orbs: List<Orb>, t: Float) {
    if (theme.colors.isEmpty()) return
    val w = size.width.toInt()
    val h = size.height.toInt()
    drawRect(color = Color(theme.base.toInt()))
    for (orb in orbs) {
        val glow = Color(theme.colors[orb.colorIndex.coerceIn(theme.colors.indices)].toInt())
        val fadeOut = glow.copy(alpha = 0f)
        val (cx, cy) = orbCenter(orb, t, w, h)
        val center = Offset(cx, cy)
        // 先外晕（140）后内核（220）—— 与引擎端同参，液态玻璃双层柔光
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glow.copy(alpha = ORB_OUTER_ALPHA / 255f), fadeOut),
                center = center,
                radius = orb.radius * ORB_OUTER_RADIUS_FACTOR,
            ),
            radius = orb.radius * ORB_OUTER_RADIUS_FACTOR,
            center = center,
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glow.copy(alpha = ORB_INNER_ALPHA / 255f), fadeOut),
                center = center,
                radius = orb.radius,
            ),
            radius = orb.radius,
            center = center,
        )
    }
}

// ── 主题选择 / 速度 ─────────────────────────────────────────────────────────

/** 主题 chip：名称 + 色点 Row（底色 + 三个光斑色，一眼读懂整套色板）。 */
@Composable
private fun WallpaperThemeChip(
    theme: WallpaperTheme,
    selected: Boolean,
    onClick: () -> Unit,
) {
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
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                (listOf(theme.base) + theme.colors).forEach { argb ->
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(Color(argb.toInt())),
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            Text(text = theme.name, style = MaterialTheme.typography.labelLarge)
            if (selected) {
                Spacer(Modifier.size(6.dp))
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "当前主题",
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/** 速度卡：0.1 步进 Slider + 慢 / 标准 / 快标注。 */
@Composable
private fun SpeedCard(
    speedFactor: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
    ) {
        val pace = when {
            speedFactor < 0.85f -> "慢"
            speedFactor <= 1.15f -> "标准"
            else -> "快"
        }
        Text(
            text = "当前 ${formatSpeed(speedFactor)} · $pace",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Slider(
            value = speedFactor,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = WallpaperPrefs.SPEED_MIN..WallpaperPrefs.SPEED_MAX,
            steps = 14, // 0.1 步进（0.5–2.0），标准档 1.0× 好对准
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "慢",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "标准",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            Text(
                text = "快",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatSpeed(factor: Float): String =
    String.format(Locale.ROOT, "%.1f×", factor)

// ── 反馈条 / 说明卡 ─────────────────────────────────────────────────────────

@Composable
private fun FeedbackBar(message: String?, isError: Boolean) {
    if (message == null) return
    val tone = if (isError) ChipTone.ERROR else ChipTone.OK
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(tone.container.copy(alpha = 0.45f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = tone.content,
        )
    }
}

/** 人话说明卡：托管机制 / 功耗代价 / 视差 / 生效时机。 */
@Composable
private fun HowItWorksCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "说明",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Bullet("动态壁纸由系统长驻托管：即使退出深渊工具，桌面上的光斑仍在缓慢漂移。")
        Bullet("常驻渲染的功耗高于静态壁纸 —— 我们把帧率锁在 30 fps，且壁纸不可见（锁屏、全屏应用）时系统自动暂停绘制。")
        Bullet("光斑随主页左右滑动产生轻微视差。")
        Bullet("主题与速度保存后，回到桌面即生效。")
    }
}

@Composable
private fun Bullet(text: String) {
    Text(
        text = "· $text",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
