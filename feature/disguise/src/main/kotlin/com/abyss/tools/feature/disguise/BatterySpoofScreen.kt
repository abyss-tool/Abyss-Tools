package com.abyss.tools.feature.disguise

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abyss.tools.core.designsystem.component.SectionHeader
import com.abyss.tools.core.shell.BatteryCommands
import com.abyss.tools.core.shell.BatterySnapshot
import com.abyss.tools.core.shell.BatteryStatus
import com.abyss.tools.core.shell.ShellGateway
import com.abyss.tools.core.shell.ShizukuStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 电量伪装 —— dumpsys battery 引擎（系统级，全 App 生效）。
 *
 * 能力说明（诚实边界）：
 *  - 生效范围 = 整个系统（任何 App 读 BatteryManager 都拿到伪装值）；
 *  - 「拔掉电源」用于压掉充电图标，否则充电动画会盖过等级显示；
 *  - 「持续保持」启动守护服务周期重申伪装值 —— 定制 ROM 回写真实
 *    电量时自动压回，系统显示恒为伪装值；
 *  - 「恢复真实电量」= 停守护 + dumpsys battery reset，系统重新同步。
 */
@Composable
fun BatterySpoofScreen(
    shizukuStatus: ShizukuStatus,
    gateway: ShellGateway,
    settings: DisguiseSettings,
    onRequestShizukuPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var snapshot by remember { mutableStateOf<BatterySnapshot?>(null) }
    var systemShowsSpoofed by remember { mutableStateOf<Boolean?>(null) }
    var guardRunning by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(settings.batteryLevel.toFloat()) }
    var status by remember { mutableStateOf(settings.batteryStatus) }
    var unplug by remember { mutableStateOf(settings.batteryUnplug) }
    var guard by remember { mutableStateOf(settings.batteryGuard) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var feedbackError by remember { mutableStateOf(false) }
    var feedbackDetail by remember { mutableStateOf<String?>(null) }

    /** 读系统当前电池态（进页面 / 应用后验证共用）。 */
    suspend fun readSystem(verifyAgainst: Int? = null) {
        gateway.exec(BatteryCommands.read()).onSuccess { result ->
            if (result.isSuccess) {
                snapshot = BatteryCommands.parseSnapshot(result.stdout)
                verifyAgainst?.let { expected ->
                    systemShowsSpoofed = snapshot?.level == expected
                }
            }
        }
    }

    /** 进入页面即读取真实电池态（Shizuku 可用时）。 */
    LaunchedEffect(Unit) {
        readSystem()
    }

    /** 守护服务运行态轮询（volatile 快照 → Compose 状态）。 */
    LaunchedEffect(Unit) {
        while (isActive) {
            guardRunning = BatteryGuardService.isGuarding
            BatteryGuardService.lastError?.let { err ->
                feedbackError = true
                feedback = err
                feedbackDetail = null
            }
            delay(1200)
        }
    }

    fun run(vararg commands: String, onDone: (Boolean, String) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            var ok = true
            var detail = ""
            for (command in commands) {
                gateway.exec(command)
                    .onSuccess { r ->
                        if (!r.isSuccess) {
                            ok = false
                            detail = r.stderr.ifBlank { r.stdout }.trim()
                        }
                    }
                    .onFailure { e ->
                        ok = false
                        detail = e.message ?: e.javaClass.simpleName
                    }
                if (!ok) break
            }
            busy = false
            onDone(ok, detail)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        SpoofHeader(title = "电量伪装", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ShizukuGuardBanner(shizukuStatus, onRequestShizukuPermission, onOpenShizuku) }

            // ── 系统当前显示（显示即伪装验证）─────────────────────────
            item {
                val rows = buildList {
                    snapshot?.let { s ->
                        add("系统当前显示" to "${s.level ?: "?"}% · ${s.status?.label ?: "未知"}")
                        add("外部供电" to when (s.powered) {
                            true -> "是（充电中）"
                            false -> "否"
                            null -> "未知"
                        })
                    }
                    add(
                        if (systemShowsSpoofed == true) "验证结果" to "✅ 系统读数 = 伪装值（显示即伪装）"
                        else if (systemShowsSpoofed == false) "验证结果" to "⚠ 系统读数 ≠ 伪装值（ROM 回写，建议开启持续保持）"
                        else "守护状态" to if (guardRunning) "运行中 · 每 20s 重申伪装值" else "未运行"
                    )
                }
                KeyValueCard(title = "电量伪装状态", rows = rows)
            }

            // ── 等级滑杆 ───────────────────────────────────────────────
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
                            text = "伪装等级",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${level.toInt()}%",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = level,
                        onValueChange = { level = it },
                        valueRange = 0f..100f,
                        steps = 99,
                    )
                    Text(
                        text = "全系统生效：任何 App（含系统界面）读到的都是这个等级。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── 状态选择 ───────────────────────────────────────────────
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(16.dp),
                ) {
                    Text(
                        text = "伪装状态",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.size(10.dp))
                    BatteryStatus.entries.chunked(2).forEach { rowItems ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 3.dp),
                        ) {
                            rowItems.forEach { s ->
                                SelectableChip(
                                    text = s.label,
                                    selected = status == s,
                                    onClick = { status = s },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "同时强制拔掉电源",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "压掉充电图标 / 动画，等级显示才干净",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = unplug, onCheckedChange = { unplug = it })
                    }
                    Spacer(Modifier.size(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "持续保持（防 ROM 回写）",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "守护服务每 20s 重申伪装值 —— 定制 ROM 把真实电量" +
                                    "回写时自动压回，系统显示恒为伪装值",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = guard, onCheckedChange = { guard = it })
                    }
                }
            }

            // ── 动作 ───────────────────────────────────────────────────
            item { ExecFeedback(feedback, feedbackError, feedbackDetail) }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val targetLevel = level.toInt()
                            val commands = buildList {
                                add(BatteryCommands.setLevel(targetLevel))
                                add(BatteryCommands.setStatus(status))
                                if (unplug) add(BatteryCommands.unplug())
                            }
                            run(*commands.toTypedArray()) { ok, detail ->
                                feedbackError = !ok
                                feedback = if (ok) {
                                    (if (guardRunning) "伪装值已更新" else "电量伪装已生效") +
                                        "：${targetLevel}% · ${status.label}" +
                                        (if (unplug) " · 已拔电" else "") +
                                        (if (guard) " · 守护中" else "")
                                } else {
                                    "应用失败（Shizuku 未就绪或系统拒绝）"
                                }
                                feedbackDetail = detail.ifBlank { null }
                                if (ok) {
                                    settings.batteryLevel = targetLevel
                                    settings.batteryStatus = status
                                    settings.batteryUnplug = unplug
                                    settings.batteryGuard = guard
                                    if (guard) {
                                        BatteryGuardService.start(context, targetLevel, status, unplug)
                                        guardRunning = true
                                    } else {
                                        BatteryGuardService.stop(context)
                                        guardRunning = false
                                    }
                                    // 800ms 后回读系统显示值 —— 「显示即伪装」验证
                                    scope.launch {
                                        delay(800)
                                        readSystem(verifyAgainst = targetLevel)
                                    }
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                        }
                        Text(if (guardRunning) "更新伪装值" else "应用伪装")
                    }
                    OutlinedButton(
                        onClick = {
                            BatteryGuardService.stop(context)
                            guardRunning = false
                            run(BatteryCommands.reset()) { ok, detail ->
                                feedbackError = !ok
                                feedback = if (ok) {
                                    "已恢复真实电量（守护已停，系统重新同步）"
                                } else {
                                    "恢复失败"
                                }
                                feedbackDetail = detail.ifBlank { null }
                                if (ok) systemShowsSpoofed = null
                                scope.launch {
                                    delay(600)
                                    readSystem()
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("恢复真实电量") }
                }
            }

            item {
                SectionHeader(title = "原理与边界", subtitle = null)
                InfoCard(
                    title = "dumpsys battery —— Android 官方电池测试通道",
                    body = "经 Shizuku 以 adb shell 权限写入电池服务状态，" +
                        "系统级生效、免 Root、重启后自动恢复真实值。" +
                        "侧录电池曲线的 App（如电池健康类工具）同样读到伪装值。" +
                        "部分定制 ROM 的电量管理服务可能周期性回写真实值 ——" +
                        "开启「持续保持」后守护服务每 20s 自动压回，显示恒为伪装值。",
                )
            }
        }
    }
}
