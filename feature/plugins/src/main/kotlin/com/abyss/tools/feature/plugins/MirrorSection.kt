package com.abyss.tools.feature.plugins

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abyss.tools.core.common.AbyssChannels
import com.abyss.tools.core.common.MIRROR_AUTO
import com.abyss.tools.core.common.MirrorCatalog
import com.abyss.tools.core.designsystem.component.ChipTone
import com.abyss.tools.core.designsystem.component.SectionHeader
import com.abyss.tools.core.designsystem.component.StatusChip
import com.abyss.tools.core.update.MirrorLatencyProbe
import com.abyss.tools.core.update.MirrorRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 下载镜像分区（v0.7.0：国内镜像 + 测延迟）。
 *
 * 插件中心首屏分区：
 *  - 「自动（延迟择优）」与全量镜像单选；
 *  - 「测速」并发探测全部镜像对**真实资产路径**的 TTFB（Release 资产 +
 *    raw 文本双形态各测一遍，镜像按其服务形态取值）；
 *  - 延迟徽章三态：<300ms OK / <1s WARN / 其余 ERROR；未测 NEUTRAL。
 *
 * 选择即刻持久化（settings.downloadMirror），MirrorRouter 经 provider
 * 闭包实时读取 —— 后续所有下载立即走新偏好，无需重启。
 *
 * @param mirrorRouter  宿主 ServiceRegistry 的共享路由器（探测缓存互通）
 * @param preference    当前偏好（"auto" 或镜像 id）
 * @param onPreference  写回设置存储
 */
@Composable
fun MirrorSection(
    mirrorRouter: MirrorRouter,
    preference: String,
    onPreference: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var probing by remember { mutableStateOf(false) }
    var probes by remember { mutableStateOf<Map<String, Long?>>(emptyMap()) }

    // 进入分区时若路由器已有新鲜缓存则直接展示（跨页面 / 二次进入免重测）
    LaunchedEffect(Unit) {
        if (mirrorRouter.probeCache.isNotEmpty()) probes = mirrorRouter.probeCache
    }

    val runProbe: () -> Unit = {
        if (!probing) {
            probing = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    val probe = MirrorLatencyProbe()
                    // Release 资产路径（APK 下载的真实链路）+ raw 文本路径
                    // （索引 / 清单）双形态实测；镜像各取其服务形态的值。
                    val byRelease = probe.probeAll(RELEASE_PROBE_URL)
                        .associate { it.mirror.id to it.latencyMs }
                    val byRaw = probe.probeAll(AbyssChannels.PLUGIN_INDEX_JSON)
                        .associate { it.mirror.id to it.latencyMs }
                    buildMap {
                        putAll(byRelease)
                        byRaw.forEach { (id, latency) ->
                            if (latency != null && (id !in this || this[id] == null)) {
                                put(id, latency)
                            }
                        }
                    }
                }
                probes = result
                mirrorRouter.probeCache = result // 共享缓存：下载管线同批择优
                probing = false
            }
        }
    }

    SectionHeader(
        title = "下载镜像",
        subtitle = "GitHub 直连不佳时走国内反代 · 自动择优或手动锁定",
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (probing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(6.dp))
                }
                IconButton(onClick = runProbe, enabled = !probing) {
                    Icon(
                        Icons.Rounded.Speed,
                        contentDescription = "测速",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
    )

    // ── 自动（延迟择优）───────────────────────────────────────────────
    MirrorRow(
        title = "自动（延迟择优）",
        host = "按实测 TTFB 排序 · 失败自动回退",
        latency = if (probes.isEmpty()) null else -1L, // -1 = 「有数据」哨兵
        selected = preference == MIRROR_AUTO,
        onClick = { onPreference(MIRROR_AUTO) },
    )

    // ── 全量镜像 ──────────────────────────────────────────────────────
    MirrorCatalog.all
        .filter { it.strategy != com.abyss.tools.core.common.MirrorStrategy.DIRECT }
        .forEach { mirror ->
            MirrorRow(
                title = mirror.label,
                host = mirror.host,
                latency = probes[mirror.id],
                selected = preference == mirror.id,
                onClick = { onPreference(mirror.id) },
            )
        }

    // 直连（兜底，永在列）
    MirrorRow(
        title = MirrorCatalog.DIRECT.label,
        host = "github.com 直连（不经过任何反代）",
        latency = probes[MirrorCatalog.DIRECT.id],
        selected = preference == MirrorCatalog.DIRECT.id,
        onClick = { onPreference(MirrorCatalog.DIRECT.id) },
    )
}

@Composable
private fun MirrorRow(
    title: String,
    host: String,
    latency: Long?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onClick)
                Spacer(Modifier.width(4.dp))
                androidx.compose.foundation.layout.Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        text = host,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LatencyChip(latency)
        }
    }
}

/** 延迟徽章：三态色阶 + 未测态；-1 = 「自动」行的有数据哨兵。 */
@Composable
private fun LatencyChip(latency: Long?) {
    val text = when {
        latency == null -> "未测速"
        latency == -1L -> "已就绪"
        latency < 300 -> "${latency} ms"
        latency < 1_000 -> "${latency} ms"
        else -> "${latency / 1000.0} s"
    }
    val tone = when {
        latency == null -> ChipTone.NEUTRAL
        latency == -1L -> ChipTone.OK
        latency < 300 -> ChipTone.OK
        latency < 1_000 -> ChipTone.WARN
        else -> ChipTone.ERROR
    }
    StatusChip(text, tone)
}

/** 探测用的真实资产路径（Release 形态：一个真实存在的 APK 资产）。 */
private const val RELEASE_PROBE_URL =
    "https://github.com/abyss-tool/Abyss-Tools-Release/releases/download/v0.3.0/abyss-tools-v0.3.0.apk"
