package com.abyss.tools.feature.syspower

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abyss.tools.core.designsystem.component.InfoRow
import com.abyss.tools.core.designsystem.component.SectionHeader
import com.abyss.tools.core.shell.ShellGateway
import com.abyss.tools.core.shell.ShizukuStatus

/** 动画速度档位（0x = 全关提速感；0.5x 半速；1x 系统默认）。 */
private val ANIM_OPTIONS: List<Pair<String, Float>> = listOf(
    "0x" to 0f,
    "0.5x" to 0.5f,
    "1x" to 1f,
)

/** 熄屏档位（秒）。 */
private val TIMEOUT_OPTIONS: List<Pair<String, Int>> = listOf(
    "15秒" to 15,
    "30秒" to 30,
    "1分" to 60,
    "5分" to 300,
    "10分" to 600,
)

/** 常用分辨率预设（主流旗舰形态；1080x1800 为动态刷新屏的工作区间）。 */
private val SIZE_PRESETS: List<Pair<Int, Int>> = listOf(
    1080 to 2340,
    1080 to 2400,
    720 to 1600,
    1080 to 1800,
)

/** 常用 DPI 预设。 */
private val DENSITY_PRESETS: List<Int> = listOf(320, 360, 400, 420, 460)

/** 自定义分辨率输入门禁：3-4 位数字。 */
private val SIZE_INPUT = Regex("^\\d{3,4}$")

/** 自定义 DPI 输入门禁：3 位数字（120-640 全域覆盖）。 */
private val DENSITY_INPUT = Regex("^\\d{3}$")

/**
 * 四域分页（M3 SegmentedButton）：显示 / 电源 / 授权 / 隐私与管控 ——
 * 会话日志是全部页动作的撤销面，不随页签切换（始终在页尾）。选中页经
 * rememberSaveable 持久，旋转 / 重建不丢。
 */
private enum class SysTab(val label: String) {
    DISPLAY("显示"),
    POWER("电源"),
    AUTH("授权"),
    PRIVACY("隐私管控"),
}

private fun flagText(flag: Boolean?): String = when (flag) {
    true -> "开"
    false -> "关"
    null -> "未知"
}

private fun scaleLabel(scale: Float): String =
    ANIM_OPTIONS.firstOrNull { it.second == scale }?.first ?: "${scale}x"

private fun timeoutLabel(seconds: Int): String =
    TIMEOUT_OPTIONS.firstOrNull { it.second == seconds }?.first ?: "${seconds}s"

/**
 * 系统控制中心 —— Shizuku 系统级通道的深度利用（显示 / 电源 / 授权 /
 * 隐私与管控四域，M3 SegmentedButton 分页）。
 *
 * 分区（对齐伪装四件套的「官方通道 + 诚实边界」纪律）：
 *  - 显示与外观：settings / cmd uimode 官方设置项，即时生效、重启保持；
 *  - 显示进阶（危险区）：wm size / density 覆写 —— 误设可致 UI 错乱，reset 可救；
 *  - 电源与性能：充电常亮位掩码 / 强制 Doze / 省电模式；
 *  - 授权总管：任意应用的敏感 AppOps 与运行时权限直改（adb 官方通道）；
 *  - 隐私与管控：权限审计 / 敏感操作记录 / Doze 白名单 / 流量策略。
 *
 * 门禁卡 / inline 错误条 / 会话日志在页签外共用（日志是每个域已应用
 * 动作的撤销面）；只读回读留在页面级，面板内读发生在面板里。
 *
 * 诚实纪律：读不到 = 「未知」/「读取失败」，绝不用假数据占位；未授权时
 * 页面照常展示，动作以 ShizukuUnavailableException 失败并经 inline 错误条
 * 如实回报。
 */
@Composable
fun SysControlScreen(
    shizukuStatus: ShizukuStatus,
    gateway: ShellGateway,
    onRequestShizukuPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val journal = remember { SessionJournal() }
    val runner = remember { SysRunner(gateway, journal) }

    // ── 状态（null = 未知：读取失败 / 未授权 —— 绝不猜默认值）────────────
    var darkMode by remember { mutableStateOf<Boolean?>(null) }
    var brightnessRead by remember { mutableStateOf<Int?>(null) }
    var brightnessSlider by remember { mutableStateOf(128f) } // 滑杆 UI 位；读取成功后对齐真值
    var autoBrightness by remember { mutableStateOf<Boolean?>(null) }
    var animScale by remember { mutableStateOf<Float?>(null) }
    var timeoutSeconds by remember { mutableStateOf<Int?>(null) }
    var activeSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var activeDensity by remember { mutableStateOf<Int?>(null) }
    var customW by remember { mutableStateOf("") }
    var customH by remember { mutableStateOf("") }
    var customDensity by remember { mutableStateOf("") }
    var stayOnPlugged by remember { mutableStateOf<Boolean?>(null) }
    var lowPower by remember { mutableStateOf<Boolean?>(null) }
    var selectedApp by remember { mutableStateOf<InstalledAppInfo?>(null) }
    var journalRows by remember { mutableStateOf<List<Pair<SysAction, Boolean>>>(emptyList()) }
    var selectedTab by rememberSaveable { mutableStateOf(SysTab.DISPLAY) }

    fun refreshJournal() {
        journalRows = journal.entriesWithUndo()
    }

    /** 只读回读：query 失败 / 解析失败 → null →「未知」（诚实降级，绝不猜）。 */
    suspend fun <T> read(command: String, parse: (String) -> T?): T? {
        val output = runner.query(command) ?: return null
        return parse(output)
    }

    /** 全量回读：每项独立解析，坏一项不拖垮整页 —— 单项就地显示「未知」。 */
    suspend fun refreshAll() {
        darkMode = read(DisplayCommands.readDarkMode(), DisplayCommands::parseNightMode)
        read(DisplayCommands.readBrightness(), DisplayCommands::parseBrightness)?.let {
            brightnessRead = it
            brightnessSlider = it.toFloat()
        }
        autoBrightness = read(DisplayCommands.readAutoBrightness(), DisplayCommands::parseAutoBrightness)
        animScale = read(DisplayCommands.readAnimationScale(), DisplayCommands::parseAnimationScale)
        timeoutSeconds = read(DisplayCommands.readScreenTimeout(), DisplayCommands::parseTimeoutSeconds)
        activeSize = read(DisplayCommands.readSize(), DisplayCommands::parseSize)
        activeDensity = read(DisplayCommands.readDensity(), DisplayCommands::parseDensity)
        stayOnPlugged = read(PowerCommands.readStayOnWhilePlugged(), PowerCommands::parseStayOnFlag)
        lowPower = read(PowerCommands.readLowPower(), PowerCommands::parseLowPowerFlag)
    }

    /** 写动作统一尾曲：成功后刷日志 + 全量回读（拿系统真值，不信任乐观值）。 */
    val afterWrite: suspend (Boolean) -> Unit = { ok ->
        if (ok) {
            refreshJournal()
            refreshAll()
        }
    }

    // 进页面全量回读一次（未授权时各项失败 → 「未知」+ 错误条如实展示）
    LaunchedEffect(Unit) {
        runner.exclusive { refreshAll() }
    }

    /** 写动作单一出口：busy 守卫 + 日志 + 成功回读（本页全部控制共用）。 */
    fun run(title: String, command: String, undo: SysAction? = null) {
        runner.execute(scope, SysAction(title, command), undo, onDone = afterWrite)
    }

    fun applyDarkMode(enable: Boolean) = run(
        if (enable) "开启深色模式" else "关闭深色模式",
        DisplayCommands.setDarkMode(enable),
        SysAction(if (enable) "关闭深色模式" else "开启深色模式", DisplayCommands.setDarkMode(!enable)),
    )

    fun applyAutoBrightness(enable: Boolean) = run(
        if (enable) "自动亮度开" else "自动亮度关",
        DisplayCommands.setAutoBrightness(enable),
        SysAction(if (enable) "自动亮度关" else "自动亮度开", DisplayCommands.setAutoBrightness(!enable)),
    )

    fun applyBrightness() {
        val target = brightnessSlider.toInt()
        val undo = brightnessRead?.takeIf { it in 1..255 }
            ?.let { SysAction("亮度恢复 $it/255", DisplayCommands.setBrightness(it)) }
        run("亮度 $target/255", DisplayCommands.setBrightness(target), undo)
    }

    fun applyAnimScale(value: Float) = run(
        "动画速度 ${scaleLabel(value)}",
        DisplayCommands.setAnimationScale(value),
        animScale?.let { SysAction("动画速度恢复 ${scaleLabel(it)}", DisplayCommands.setAnimationScale(it)) },
    )

    fun applyTimeout(seconds: Int) = run(
        "熄屏时间 ${timeoutLabel(seconds)}",
        DisplayCommands.setScreenTimeout(seconds),
        timeoutSeconds?.let { SysAction("熄屏时间恢复 ${timeoutLabel(it)}", DisplayCommands.setScreenTimeout(it)) },
    )

    fun applySize(w: Int, h: Int) = run(
        "分辨率 ${w}x${h}",
        DisplayCommands.setSize(w, h),
        activeSize?.let { (pw, ph) -> SysAction("分辨率恢复 ${pw}x${ph}", DisplayCommands.setSize(pw, ph)) }
            ?: SysAction("分辨率恢复默认", DisplayCommands.resetSize()),
    )

    fun applyCustomSize() {
        // 双保险门禁（按钮已按同正则禁用）：非 3-4 位数字就地拒执行
        if (customW.matches(SIZE_INPUT) && customH.matches(SIZE_INPUT)) {
            applySize(customW.toInt(), customH.toInt())
        }
    }

    fun applySizeReset() = run(
        "分辨率恢复默认",
        DisplayCommands.resetSize(),
        activeSize?.let { (pw, ph) -> SysAction("分辨率恢复 ${pw}x${ph}", DisplayCommands.setSize(pw, ph)) },
    )

    fun applyDensity(d: Int) = run(
        "DPI $d",
        DisplayCommands.setDensity(d),
        activeDensity?.let { SysAction("DPI 恢复 $it", DisplayCommands.setDensity(it)) }
            ?: SysAction("DPI 恢复默认", DisplayCommands.resetDensity()),
    )

    fun applyCustomDensity() {
        if (customDensity.matches(DENSITY_INPUT)) applyDensity(customDensity.toInt())
    }

    fun applyDensityReset() = run(
        "DPI 恢复默认",
        DisplayCommands.resetDensity(),
        activeDensity?.let { SysAction("DPI 恢复 $it", DisplayCommands.setDensity(it)) },
    )

    fun applyStayOn(enable: Boolean) = run(
        if (enable) "充电常亮开" else "充电常亮关",
        PowerCommands.setStayOnWhilePlugged(enable),
        SysAction(if (enable) "充电常亮关" else "充电常亮开", PowerCommands.setStayOnWhilePlugged(!enable)),
    )

    fun applyLowPower(enable: Boolean) = run(
        if (enable) "省电模式开" else "省电模式关",
        PowerCommands.setLowPower(enable),
        SysAction(if (enable) "省电模式关" else "省电模式开", PowerCommands.setLowPower(!enable)),
    )

    fun applyDoze(force: Boolean) {
        if (force) {
            run("强制 Doze", PowerCommands.forceDoze(), SysAction("退出强制 Doze", PowerCommands.unforceDoze()))
        } else {
            run("退出强制 Doze", PowerCommands.unforceDoze())
        }
    }

    fun undoLastAction() {
        val undo = journal.undoLast()
        refreshJournal()
        if (undo != null) runner.executeUnrecorded(scope, undo) { ok -> if (ok) refreshAll() }
    }

    fun undoRow(action: SysAction) {
        val undo = journal.undoOf(action)
        refreshJournal()
        if (undo != null) runner.executeUnrecorded(scope, undo) { ok -> if (ok) refreshAll() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        SysControlHeader(onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ShizukuGateCard(shizukuStatus, onRequestShizukuPermission, onOpenShizuku) }
            item { InlineErrorChip(runner.error) }

            // 域分页（M3 SegmentedButton）：门禁卡 / 错误条 / 会话日志在页签外共用
            item {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SysTab.entries.forEachIndexed { index, tab ->
                        SegmentedButton(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = SysTab.entries.size,
                            ),
                        ) { Text(tab.label) }
                    }
                }
            }

            when (selectedTab) {
                SysTab.DISPLAY -> {
                    item {
                        SectionHeader(
                            title = "显示与外观",
                            subtitle = "深色模式 / 亮度 / 动画速度 / 熄屏 —— 官方设置项，即时生效",
                        )
                    }
                    item {
                        DisplayAppearanceCard(
                            darkMode = darkMode,
                            onDarkModeChange = { applyDarkMode(it) },
                            brightnessRead = brightnessRead,
                            brightnessSlider = brightnessSlider,
                            onBrightnessChange = { brightnessSlider = it },
                            onBrightnessApply = { applyBrightness() },
                            autoBrightness = autoBrightness,
                            onAutoBrightnessChange = { applyAutoBrightness(it) },
                            animScale = animScale,
                            onAnimScaleSelect = { applyAnimScale(it) },
                            timeoutSeconds = timeoutSeconds,
                            onTimeoutSelect = { applyTimeout(it) },
                            busy = runner.busy,
                        )
                    }
                    item {
                        SectionHeader(
                            title = "显示进阶（危险区）",
                            subtitle = "wm 分辨率 / DPI 覆写 —— 误设可致 UI 错乱，reset 可救",
                        )
                    }
                    item {
                        DisplayDangerCard(
                            activeSize = activeSize,
                            activeDensity = activeDensity,
                            customW = customW,
                            onCustomWChange = { customW = it.filter(Char::isDigit).take(4) },
                            customH = customH,
                            onCustomHChange = { customH = it.filter(Char::isDigit).take(4) },
                            onApplyCustomSize = { applyCustomSize() },
                            customDensity = customDensity,
                            onCustomDensityChange = { customDensity = it.filter(Char::isDigit).take(3) },
                            onApplyCustomDensity = { applyCustomDensity() },
                            onSizePreset = { w, h -> applySize(w, h) },
                            onResetSize = { applySizeReset() },
                            onDensityPreset = { applyDensity(it) },
                            onResetDensity = { applyDensityReset() },
                            busy = runner.busy,
                        )
                    }
                }

                SysTab.POWER -> {
                    item {
                        SectionHeader(
                            title = "电源与性能",
                            subtitle = "充电常亮 / 强制 Doze / 省电模式 —— dumpsys 与官方设置项",
                        )
                    }
                    item {
                        PowerCard(
                            stayOnPlugged = stayOnPlugged,
                            onStayOnChange = { applyStayOn(it) },
                            lowPower = lowPower,
                            onLowPowerChange = { applyLowPower(it) },
                            onForceDoze = { applyDoze(true) },
                            onUnforceDoze = { applyDoze(false) },
                            busy = runner.busy,
                        )
                    }
                }

                SysTab.AUTH -> {
                    item {
                        SectionHeader(
                            title = "授权总管（AppOps + 运行时权限）",
                            subtitle = "对任意应用的敏感授权直改 —— adb 官方通道，免 Root",
                        )
                    }
                    item {
                        AuthorizationPanel(
                            runner = runner,
                            onAppSelected = { selectedApp = it },
                        )
                    }
                }

                SysTab.PRIVACY -> {
                    item {
                        SectionHeader(
                            title = "隐私与管控",
                            subtitle = "Shizuku 深度利用：权限审计 / 敏感操作记录 / Doze 白名单 / 流量策略",
                        )
                    }
                    item {
                        PermissionAuditPanel(
                            runner = runner,
                            onJournalChanged = { refreshJournal() },
                        )
                    }
                    item { SensitiveOpsPanel(runner = runner) }
                    item {
                        DeviceIdlePanel(
                            runner = runner,
                            onJournalChanged = { refreshJournal() },
                        )
                    }
                    item {
                        NetPolicyPanel(
                            runner = runner,
                            onJournalChanged = { refreshJournal() },
                        )
                    }
                }
            }

            // 会话日志：全部页签可见 —— 它是每个域已应用动作的撤销面
            item {
                SectionHeader(
                    title = "会话日志",
                    subtitle = "本页已应用动作（时间序）—— 携带逆动作的可逐条撤销",
                )
            }
            item {
                JournalCard(
                    rows = journalRows,
                    busy = runner.busy,
                    onUndoLast = { undoLastAction() },
                    onUndoRow = { undoRow(it) },
                )
            }
        }

        selectedApp?.let { app ->
            AppAuthorizationSheet(
                app = app,
                runner = runner,
                onDismiss = { selectedApp = null },
                onJournalChanged = { refreshJournal() },
            )
        }
    }
}

/** 显示与外观卡：深色模式 / 亮度滑杆 / 自动亮度 / 动画速度 / 熄屏档位。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplayAppearanceCard(
    darkMode: Boolean?,
    onDarkModeChange: (Boolean) -> Unit,
    brightnessRead: Int?,
    brightnessSlider: Float,
    onBrightnessChange: (Float) -> Unit,
    onBrightnessApply: () -> Unit,
    autoBrightness: Boolean?,
    onAutoBrightnessChange: (Boolean) -> Unit,
    animScale: Float?,
    onAnimScaleSelect: (Float) -> Unit,
    timeoutSeconds: Int?,
    onTimeoutSelect: (Int) -> Unit,
    busy: Boolean,
) {
    SectionCard {
        ListItem(
            headlineContent = { Text("深色模式") },
            supportingContent = { Text("当前: ${flagText(darkMode)} · cmd uimode night 全局即时切换") },
            trailingContent = {
                Switch(checked = darkMode ?: false, onCheckedChange = onDarkModeChange, enabled = !busy)
            },
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        ListItem(
            headlineContent = { Text("亮度") },
            supportingContent = { Text("当前: ${brightnessRead?.toString() ?: "未知"} / 255 · 松手生效") },
            trailingContent = {
                Text(
                    text = "亮度 ${brightnessSlider.toInt()}/255",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
        )
        Slider(
            value = brightnessSlider,
            onValueChange = onBrightnessChange,
            valueRange = 1f..255f,
            steps = 253,
            onValueChangeFinished = onBrightnessApply,
            enabled = !busy,
        )
        ListItem(
            headlineContent = { Text("自动亮度（感光调节）") },
            supportingContent = { Text("当前: ${flagText(autoBrightness)} · 开启后手动亮度被系统接管") },
            trailingContent = {
                Switch(checked = autoBrightness ?: false, onCheckedChange = onAutoBrightnessChange, enabled = !busy)
            },
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        ListItem(
            headlineContent = { Text("动画速度") },
            supportingContent = { Text("当前: ${animScale?.let { scaleLabel(it) } ?: "未知"} · 三张动画表同步写入") },
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ANIM_OPTIONS.forEachIndexed { index, (label, value) ->
                SegmentedButton(
                    selected = animScale == value,
                    onClick = { onAnimScaleSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ANIM_OPTIONS.size),
                ) { Text(label) }
            }
        }
        ListItem(
            headlineContent = { Text("休眠时间（熄屏）") },
            supportingContent = { Text("当前: ${timeoutSeconds?.let { timeoutLabel(it) } ?: "未知"} · 毫秒存储") },
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            TIMEOUT_OPTIONS.forEachIndexed { index, (label, value) ->
                SegmentedButton(
                    selected = timeoutSeconds == value,
                    onClick = { onTimeoutSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = TIMEOUT_OPTIONS.size),
                ) { Text(label) }
            }
        }
    }
}

/** 显示进阶（危险区）卡：warning 色系 + 分辨率 / DPI 预设与自定义覆写。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplayDangerCard(
    activeSize: Pair<Int, Int>?,
    activeDensity: Int?,
    customW: String,
    onCustomWChange: (String) -> Unit,
    customH: String,
    onCustomHChange: (String) -> Unit,
    onApplyCustomSize: () -> Unit,
    customDensity: String,
    onCustomDensityChange: (String) -> Unit,
    onApplyCustomDensity: () -> Unit,
    onSizePreset: (Int, Int) -> Unit,
    onResetSize: () -> Unit,
    onDensityPreset: (Int) -> Unit,
    onResetDensity: () -> Unit,
    busy: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = "危险区：改的是 wm 全局覆写，误设可致 UI 错乱",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        Spacer(Modifier.size(8.dp))
        InfoRow(label = "当前分辨率", value = activeSize?.let { (w, h) -> "${w} x $h" } ?: "读取失败")
        InfoRow(label = "当前 DPI", value = activeDensity?.toString() ?: "读取失败")
        Spacer(Modifier.size(10.dp))
        Text(text = "分辨率预设", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.size(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SIZE_PRESETS) { (w, h) ->
                FilterChip(
                    selected = activeSize == (w to h),
                    onClick = { onSizePreset(w, h) },
                    label = { Text("${w}x${h}") },
                    enabled = !busy,
                )
            }
            item {
                AssistChip(onClick = onResetSize, label = { Text("恢复默认") }, enabled = !busy)
            }
        }
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = customW,
                onValueChange = onCustomWChange,
                label = { Text("宽（3-4 位）") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = customH,
                onValueChange = onCustomHChange,
                label = { Text("高（3-4 位）") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.size(6.dp))
        Button(
            onClick = onApplyCustomSize,
            enabled = !busy && customW.matches(SIZE_INPUT) && customH.matches(SIZE_INPUT),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("应用自定义分辨率") }
        Spacer(Modifier.size(12.dp))
        HorizontalDivider()
        Spacer(Modifier.size(12.dp))
        Text(text = "DPI（密度）预设", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.size(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(DENSITY_PRESETS) { d ->
                FilterChip(
                    selected = activeDensity == d,
                    onClick = { onDensityPreset(d) },
                    label = { Text("$d") },
                    enabled = !busy,
                )
            }
            item {
                AssistChip(onClick = onResetDensity, label = { Text("恢复默认") }, enabled = !busy)
            }
        }
        Spacer(Modifier.size(8.dp))
        OutlinedTextField(
            value = customDensity,
            onValueChange = onCustomDensityChange,
            label = { Text("自定义 DPI（3 位数字，写入时夹取 120-640）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.size(6.dp))
        Button(
            onClick = onApplyCustomDensity,
            enabled = !busy && customDensity.matches(DENSITY_INPUT),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("应用自定义 DPI") }
        Spacer(Modifier.size(8.dp))
        Text(
            text = "诚实边界：重启或对应「恢复默认」可还原；部分 ROM 立即生效，部分需重启。" +
                "Override 值即当前活跃覆写（解析已优先取它）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
        )
    }
}

/** 电源与性能卡：充电常亮开关 / 强制与退出 Doze / 省电模式开关。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PowerCard(
    stayOnPlugged: Boolean?,
    onStayOnChange: (Boolean) -> Unit,
    lowPower: Boolean?,
    onLowPowerChange: (Boolean) -> Unit,
    onForceDoze: () -> Unit,
    onUnforceDoze: () -> Unit,
    busy: Boolean,
) {
    SectionCard {
        ListItem(
            headlineContent = { Text("充电时保持常亮") },
            supportingContent = { Text("当前: ${flagText(stayOnPlugged)} · 位掩码 7 = AC|USB|无线 全源") },
            trailingContent = {
                Switch(checked = stayOnPlugged ?: false, onCheckedChange = onStayOnChange, enabled = !busy)
            },
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        ListItem(
            headlineContent = { Text("强制 Doze（深睡压测）") },
            supportingContent = { Text("跳过空闲等待直入 Doze —— 排障 / 省电压测通道，退出即归还系统调度") },
            trailingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onForceDoze, enabled = !busy) { Text("强制") }
                    FilledTonalButton(onClick = onUnforceDoze, enabled = !busy) { Text("退出") }
                }
            },
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        ListItem(
            headlineContent = { Text("省电模式") },
            supportingContent = { Text("当前: ${flagText(lowPower)} · 官方设置项 low_power（部分 ROM 需广播才刷新界面）") },
            trailingContent = {
                Switch(checked = lowPower ?: false, onCheckedChange = onLowPowerChange, enabled = !busy)
            },
        )
    }
}

/** 会话日志卡：已应用动作逐条渲染（命令原文）+ 撤销上一步 / 单条撤销。 */
@Composable
private fun JournalCard(
    rows: List<Pair<SysAction, Boolean>>,
    busy: Boolean,
    onUndoLast: () -> Unit,
    onUndoRow: (SysAction) -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "已应用 ${rows.size} 条动作",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onUndoLast, enabled = !busy && rows.any { it.second }) {
                Text("撤销上一步")
            }
        }
        if (rows.isEmpty()) {
            Text(
                text = "暂无动作 —— 应用任意控制后此处记录原始命令，携带逆动作的可撤销。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            rows.forEach { (action, undoable) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(text = action.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = action.command,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (undoable) {
                        TextButton(onClick = { onUndoRow(action) }, enabled = !busy) { Text("撤销") }
                    }
                }
            }
        }
    }
}
