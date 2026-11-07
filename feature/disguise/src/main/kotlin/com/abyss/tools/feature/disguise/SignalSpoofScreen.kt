package com.abyss.tools.feature.disguise

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abyss.tools.core.designsystem.component.SectionHeader
import com.abyss.tools.core.shell.OverlayCommands
import com.abyss.tools.core.shell.ShellGateway
import com.abyss.tools.core.shell.ShizukuStatus
import com.abyss.tools.core.shell.SignalCommands
import com.abyss.tools.core.shell.failureSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.graphics.Paint as NativePaint

/**
 * 信号伪装 —— 两层能力，一页诚实分层：
 *
 * 1. 状态栏显示伪装（覆盖层）：无卡 / 关网也能让状态栏显示
 *    5G-A / 5G / 4G + 指定格数信号条 + Wi-Fi 图标。经 Shizuku 授予
 *    悬浮窗授权（与系统设置页开关等价），前台服务维持一个不可触摸的
 *    透明覆盖窗口 —— 截图 / 屏幕共享与真实屏幕同视图。「停止并恢复」
 *    即移除窗口，状态栏回到真实显示，零残留。
 * 2. 连接性真实开关（svc / cmd connectivity）：飞行模式 / 移动数据 /
 *    Wi-Fi 的系统级真实切换，与快捷开关完全等价。
 */
@Composable
fun SignalSpoofScreen(
    shizukuStatus: ShizukuStatus,
    gateway: ShellGateway,
    settings: DisguiseSettings,
    onRequestShizukuPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── 覆盖层（显示伪装）状态 ────────────────────────────────────────
    var spec by remember { mutableStateOf(settings.signalSpec) }
    var overlayRunning by remember { mutableStateOf(false) }
    var overlayPermGranted by remember { mutableStateOf(false) }
    var overlayBusy by remember { mutableStateOf(false) }

    // ── 真实开关状态 ──────────────────────────────────────────────────
    /** null = 未知（读取失败 / 未授权），绝不显示猜测值。 */
    var airplane by remember { mutableStateOf<Boolean?>(null) }
    var mobileData by remember { mutableStateOf<Boolean?>(null) }
    var wifi by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var feedbackError by remember { mutableStateOf(false) }
    var feedbackDetail by remember { mutableStateOf<String?>(null) }

    fun refreshPerm() {
        overlayPermGranted = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
    }

    fun refreshStates() {
        if (busy) return
        busy = true
        scope.launch {
            suspend fun read(setting: String): Boolean? {
                val result = gateway.exec(setting).getOrNull() ?: return null
                return if (result.isSuccess) SignalCommands.parseSettingFlag(result.stdout) else null
            }
            airplane = read(SignalCommands.readAirplaneMode())
            mobileData = read(SignalCommands.readMobileData())
            wifi = read(SignalCommands.readWifi())
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        refreshPerm()
        refreshStates()
    }

    /** 覆盖层运行态轮询（volatile 快照 → Compose 状态，含错误上报）。 */
    LaunchedEffect(Unit) {
        while (isActive) {
            overlayRunning = SignalOverlayService.isRunning
            SignalOverlayService.lastError?.let { err ->
                feedbackError = true
                feedback = err
                feedbackDetail = null
            }
            delay(1200)
        }
    }

    /** 规格热更新：运行中即时重绘，停止态只落盘。 */
    fun applySpecChange(newSpec: SignalSpoofSpec) {
        spec = newSpec
        settings.signalSpec = newSpec
        if (SignalOverlayService.isRunning) SignalOverlayService.update(newSpec)
    }

    fun apply(label: String, command: String, newFlag: Boolean, onApplied: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            gateway.exec(command)
                .onSuccess { result ->
                    if (result.isSuccess) {
                        onApplied()
                        feedbackError = false
                        feedback = "$label 已${if (newFlag) "开启" else "关闭"}（真实生效）"
                        feedbackDetail = null
                    } else {
                        feedbackError = true
                        feedback = "$label 切换被系统拒绝"
                        feedbackDetail = result.stderr.ifBlank { result.stdout }.trim().ifBlank { null }
                    }
                }
                .onFailure { e ->
                    feedbackError = true
                    feedback = "$label 切换失败：${e.message ?: e.javaClass.simpleName}"
                    feedbackDetail = null
                }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        SpoofHeader(title = "信号伪装", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ShizukuGuardBanner(shizukuStatus, onRequestShizukuPermission, onOpenShizuku) }

            // ══ 1. 状态栏信号显示伪装（覆盖层）════════════════════════
            item {
                SectionHeader(
                    title = "状态栏信号显示伪装",
                    subtitle = "覆盖层绘制：无卡 / 关网也能显示指定Assertion与信号格，截图与屏幕共享同视图",
                )
            }

            item {
                val rows = buildList {
                    add("覆盖层状态" to if (overlayRunning) "运行中 · 停止即恢复真实显示" else "未运行")
                    add("当前Assertion" to spec.summary)
                    add(
                        "悬浮窗授权" to when {
                            overlayPermGranted -> "已授予（SYSTEM_ALERT_WINDOW）"
                            shizukuStatus is ShizukuStatus.Running && shizukuStatus.authorized ->
                                "未授予 —— 可用 Shizuku 一键授权"
                            else -> "未授予 —— 需 Shizuku 或系统设置页"
                        },
                    )
                }
                KeyValueCard(title = "显示伪装状态", rows = rows)
            }

            // 实时预览（深 / 浅两种底，所见即覆盖层所得）
            item { SignalPreview(spec) }

            // Assertion 配置
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(16.dp),
                ) {
                    Text(
                        text = "网络Assertion",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.size(10.dp))
                    FakeNetworkType.entries.chunked(3).forEach { rowItems ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 3.dp),
                        ) {
                            rowItems.forEach { type ->
                                SelectableChip(
                                    text = type.label,
                                    selected = spec.networkType == type,
                                    onClick = { applySpecChange(spec.copy(networkType = type)) },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.size(12.dp))
                    Text(
                        text = "信号格数",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.size(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..SignalSpoofSpec.TOTAL_BARS).forEach { level ->
                            SelectableChip(
                                text = "$level 格",
                                selected = spec.bars == level,
                                onClick = { applySpecChange(spec.copy(bars = level)) },
                            )
                        }
                    }
                    Spacer(Modifier.size(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("伪装 Wi-Fi 已连接", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "无 Wi-Fi / 关 Wi-Fi 时状态栏同样显示连接图标",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = spec.wifi,
                            onCheckedChange = { applySpecChange(spec.copy(wifi = it)) },
                        )
                    }
                    if (spec.wifi) {
                        Spacer(Modifier.size(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (1..SignalSpoofSpec.TOTAL_ARCS).forEach { level ->
                                SelectableChip(
                                    text = "$level 弧",
                                    selected = spec.wifiBars == level,
                                    onClick = { applySpecChange(spec.copy(wifiBars = level)) },
                                )
                            }
                        }
                    }
                }
            }

            // 显示位置与遮挡（对齐真机图标布局）
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "图标右缘留白",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${spec.rightMarginDp}dp",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = spec.rightMarginDp.toFloat(),
                        onValueChange = {
                            applySpecChange(spec.copy(rightMarginDp = it.toInt()))
                        },
                        valueRange = SignalSpoofSpec.RIGHT_MARGIN_MIN_DP.toFloat()..
                            SignalSpoofSpec.RIGHT_MARGIN_MAX_DP.toFloat(),
                    )
                    Text(
                        text = "默认 68dp 让位真实电池图标；不同 ROM 图标布局不同，拖到与真机对齐为准。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("遮挡条", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "盖住真实信号位（如无 SIM 角标）的实心底条",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = spec.coverEnabled,
                            onCheckedChange = { applySpecChange(spec.copy(coverEnabled = it)) },
                        )
                    }
                    if (spec.coverEnabled) {
                        Slider(
                            value = spec.coverWidthDp.toFloat(),
                            onValueChange = {
                                applySpecChange(spec.copy(coverWidthDp = it.toInt()))
                            },
                            valueRange = SignalSpoofSpec.COVER_WIDTH_MIN_DP.toFloat()..
                                SignalSpoofSpec.COVER_WIDTH_MAX_DP.toFloat(),
                        )
                    }
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = "图标配色",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.size(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectableChip(
                            text = "跟随系统",
                            selected = spec.iconTheme == SignalIconTheme.AUTO,
                            onClick = { applySpecChange(spec.copy(iconTheme = SignalIconTheme.AUTO)) },
                        )
                        SelectableChip(
                            text = "深底白图标",
                            selected = spec.iconTheme == SignalIconTheme.ON_DARK,
                            onClick = { applySpecChange(spec.copy(iconTheme = SignalIconTheme.ON_DARK)) },
                        )
                        SelectableChip(
                            text = "浅底黑图标",
                            selected = spec.iconTheme == SignalIconTheme.ON_LIGHT,
                            onClick = { applySpecChange(spec.copy(iconTheme = SignalIconTheme.ON_LIGHT)) },
                        )
                    }
                }
            }

            // 授权 + 启停
            item { ExecFeedback(feedback, feedbackError, feedbackDetail) }

            if (!overlayPermGranted) {
                item {
                    InfoCard(
                        title = "需要悬浮窗授权（一次性）",
                        body = "与系统「设置 → 应用 → 在其他应用上层显示」同一开关。" +
                            "Shizuku 已授权时可免跳设置页一键开启（appops 官方通道）。",
                        tone = com.abyss.tools.core.designsystem.component.ChipTone.WARN,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    if (overlayBusy) return@Button
                                    overlayBusy = true
                                    scope.launch {
                                        gateway.exec(OverlayCommands.grant(context.packageName))
                                            .onSuccess { r ->
                                                if (r.isSuccess) {
                                                    // appops 即时生效，回读校验（不依赖重组时序）
                                                    delay(400)
                                                    val granted = runCatching {
                                                        Settings.canDrawOverlays(context)
                                                    }.getOrDefault(false)
                                                    overlayPermGranted = granted
                                                    feedbackError = !granted
                                                    feedback = if (granted) {
                                                        "悬浮窗授权已开启"
                                                    } else {
                                                        "授权命令已执行但未生效（ROM 可能限制 appops，请走系统设置页）"
                                                    }
                                                } else {
                                                    feedbackError = true
                                                    feedback = "授权被系统拒绝（ROM 可能限制 appops）"
                                                    feedbackDetail = r.stderr.ifBlank { r.stdout }.trim().ifBlank { null }
                                                }
                                            }
                                            .onFailure { e ->
                                                feedbackError = true
                                                feedback = "授权失败：${e.message ?: e.javaClass.simpleName}"
                                                feedbackDetail = null
                                            }
                                        overlayBusy = false
                                    }
                                },
                                enabled = !overlayBusy &&
                                    (shizukuStatus as? ShizukuStatus.Running)?.authorized == true,
                            ) { Text("Shizuku 一键授权") }
                            OutlinedButton(onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            android.net.Uri.parse("package:${context.packageName}"),
                                        ),
                                    )
                                }
                            }) { Text("打开系统设置") }
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            when {
                                !overlayPermGranted -> {
                                    feedbackError = true
                                    feedback = "请先授予悬浮窗授权"
                                }
                                overlayRunning -> {
                                    SignalOverlayService.update(spec)
                                    feedbackError = false
                                    feedback = "Assertion已更新：${spec.summary}"
                                }
                                else -> {
                                    SignalOverlayService.start(context, spec)
                                    overlayRunning = true
                                    feedbackError = false
                                    feedback = "状态栏显示伪装已启动（截图 / 屏幕共享同视图）"
                                }
                            }
                        },
                        enabled = overlayPermGranted,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (overlayBusy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                        }
                        Text(if (overlayRunning) "更新Assertion" else "启动显示伪装")
                    }
                    OutlinedButton(
                        onClick = {
                            SignalOverlayService.stop(context)
                            overlayRunning = false
                            feedbackError = false
                            feedback = "已停止并恢复真实状态栏显示"
                        },
                        enabled = overlayRunning,
                        modifier = Modifier.weight(1f),
                    ) { Text("停止并恢复") }
                }
            }

            // ══ 2. 连接性真实开关 ══════════════════════════════════════
            item {
                SectionHeader(
                    title = "连接性真实开关",
                    subtitle = "与系统快捷开关等价的真实切换（svc / cmd connectivity 官方通道，全系统生效）",
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(16.dp),
                ) {
                    SwitchRow(
                        title = "飞行模式",
                        subtitle = when (airplane) {
                            null -> "状态未知（需 Shizuku 授权后读取）"
                            true -> "已开启 · 全部射频关闭"
                            false -> "已关闭"
                        },
                        flag = airplane,
                        busy = busy,
                        onToggle = { enable ->
                            apply(
                                "飞行模式",
                                SignalCommands.setAirplaneMode(enable),
                                enable,
                            ) { airplane = enable }
                        },
                    )
                    Spacer(Modifier.size(6.dp))
                    SwitchRow(
                        title = "移动数据",
                        subtitle = when (mobileData) {
                            null -> "状态未知（部分 ROM 无此设置项）"
                            true -> "已开启"
                            false -> "已关闭"
                        },
                        flag = mobileData,
                        busy = busy,
                        onToggle = { enable ->
                            apply("移动数据", SignalCommands.setMobileData(enable), enable) {
                                mobileData = enable
                            }
                        },
                    )
                    Spacer(Modifier.size(6.dp))
                    SwitchRow(
                        title = "Wi-Fi",
                        subtitle = when (wifi) {
                            null -> "状态未知"
                            true -> "已开启"
                            false -> "已关闭"
                        },
                        flag = wifi,
                        busy = busy,
                        onToggle = { enable ->
                            apply("Wi-Fi", SignalCommands.setWifi(enable), enable) { wifi = enable }
                        },
                    )
                    Spacer(Modifier.size(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { refreshStates() }, enabled = !busy) {
                            Text("刷新真实状态")
                        }
                    }
                }
            }

            // ── 旧系统回退 ─────────────────────────────────────────────
            item {
                SectionHeader(
                    title = "旧系统回退（Android 8~10）",
                    subtitle = "cmd connectivity 需 Android 11+；老系统改用 settings + 广播形态",
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(16.dp),
                ) {
                    Text(
                        text = "若上方飞行模式开关提示「命令不存在 / 未知命令」，" +
                            "说明系统低于 Android 11 —— 用回退命令（设置项 + 定向广播）。" +
                            "个别 ROM 对该广播有限制，以系统开关页为准。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                apply("飞行模式（回退）", SignalCommands.setAirplaneModeLegacy(true), true) {
                                    airplane = true
                                }
                            },
                            enabled = !busy,
                        ) { Text("开启（回退）") }
                        OutlinedButton(
                            onClick = {
                                apply("飞行模式（回退）", SignalCommands.setAirplaneModeLegacy(false), false) {
                                    airplane = false
                                }
                            },
                            enabled = !busy,
                        ) { Text("关闭（回退）") }
                    }
                }
            }

            item {
                SectionHeader(title = "能力边界（诚实声明）", subtitle = null)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    InfoCard(
                        title = "显示伪装的生效范围",
                        body = "覆盖层画在状态栏区域，全部 App 界面、截图、屏幕共享、" +
                            "录屏看到的都是伪装的Assertion与信号格。锁屏与下拉快捷" +
                            "设置面板头部是系统安全屏，不显示覆盖层（免 Root 无解，" +
                            "属 SystemUI 进程领域）。",
                    )
                    InfoCard(
                        title = "App 代码读到的仍是真实值",
                        body = "覆盖层只影响「显示」。目标 App 用 TelephonyManager / " +
                            "WifiManager 代码读取的信号与网络Assertion仍是真机真实值 —— " +
                            "让 App 读到假值需要对目标进程 Hook（LSPatch / 沙箱双开插件路线，" +
                            "见插件中心）。电量 / 机型伪装不受此限（dumpsys / 属性层，全系统真实）。",
                    )
                    InfoCard(
                        title = "真实开关 ≠ 信号格数伪造",
                        body = "下方连接性开关是与系统快捷开关完全等价的真实状态切换；" +
                            "上方覆盖层才是「显示层伪造」。两者可独立使用，也可组合" +
                            "（如：真实关数据 + 覆盖层显示满格 5G-A）。",
                    )
                }
            }
        }
    }
}

/** 预览条：深 / 浅两底渲染（左：时钟示意；右：与覆盖层同一套绘制）。 */
@Composable
private fun SignalPreview(spec: SignalSpoofSpec) {
    val paint = remember { NativePaint(NativePaint.ANTI_ALIAS_FLAG) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PreviewStrip(spec, dark = true, paint = paint)
        PreviewStrip(spec, dark = false, paint = paint)
    }
}

@Composable
private fun PreviewStrip(spec: SignalSpoofSpec, dark: Boolean, paint: NativePaint) {
    val bg = if (dark) Color(0xFF101014) else Color(0xFFF2F2F5)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg),
    ) {
        // 左侧时钟示意（原生 Paint 与图标同一套绘制逻辑）
        val clockPaint = paint.apply {
            color = if (dark) 0xE6FFFFFF.toInt() else 0xE61B1B21.toInt()
            textSize = size.height * 0.44f
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF,
                android.graphics.Typeface.BOLD,
            )
        }
        drawContext.canvas.nativeCanvas.drawText("12:00", 14f * density, size.height * 0.68f, clockPaint)

        // 右侧伪装图标（遮挡条预览同样生效）
        SignalStatusArt.draw(
            canvas = drawContext.canvas.nativeCanvas,
            width = size.width,
            height = size.height,
            rawSpec = spec.copy(iconTheme = if (dark) SignalIconTheme.ON_DARK else SignalIconTheme.ON_LIGHT),
            nightMode = dark,
            density = density,
            paint = paint,
        )

        // 描边一圈，避免浅底与页面背景融为一体
        drawRoundRect(
            cornerRadius = CornerRadius(10.dp.toPx()),
            color = Color(0x14888888),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    flag: Boolean?,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (busy && flag == null) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
        }
        Switch(checked = flag == true, onCheckedChange = { onToggle(it) })
    }
}
