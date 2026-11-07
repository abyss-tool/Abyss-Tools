package com.abyss.tools.feature.disguise

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abyss.tools.core.designsystem.component.SectionHeader
import com.abyss.tools.core.shell.BatteryCommands
import com.abyss.tools.core.shell.BatterySnapshot
import com.abyss.tools.core.shell.BatteryStatus
import com.abyss.tools.core.shell.ShellGateway
import com.abyss.tools.core.shell.ShizukuStatus
import kotlinx.coroutines.launch

/**
 * 电量伪装 —— dumpsys battery 引擎（系统级，全 App 生效）。
 *
 * 能力说明（诚实边界）：
 *  - 生效范围 = 整个系统（任何 App 读 BatteryManager 都拿到伪装值）；
 *  - 「拔掉电源」用于压掉充电图标，否则充电动画会盖过等级显示；
 *  - 「恢复真实电量」= dumpsys battery reset，系统重新同步真实电池。
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
    val scope = rememberCoroutineScope()

    var snapshot by remember { mutableStateOf<BatterySnapshot?>(null) }
    var level by remember { mutableStateOf(settings.batteryLevel.toFloat()) }
    var status by remember { mutableStateOf(settings.batteryStatus) }
    var unplug by remember { mutableStateOf(settings.batteryUnplug) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var feedbackError by remember { mutableStateOf(false) }
    var feedbackDetail by remember { mutableStateOf<String?>(null) }

    /** 进入页面即读取真实电池态（Shizuku 可用时）。 */
    LaunchedEffect(Unit) {
        gateway.exec(BatteryCommands.read()).onSuccess { result ->
            if (result.isSuccess) {
                snapshot = BatteryCommands.parseSnapshot(result.stdout)
                snapshot?.level?.let { level = it.toFloat() }
                snapshot?.status?.let { status = it }
            }
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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                end = 20.dp,
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ShizukuGuardBanner(shizukuStatus, onRequestShizukuPermission, onOpenShizuku) }

            // ── 真实读数 ───────────────────────────────────────────────
            item {
                snapshot?.let { s ->
                    KeyValueCard(
                        title = "当前系统读数（伪装前的真实态）",
                        rows = listOfNotNull(
                            "电量等级" to "${s.level ?: "?"}%",
                            "电池状态" to (s.status?.label ?: "未知"),
                            "外部供电" to when (s.powered) {
                                true -> "是（充电中）"
                                false -> "否"
                                null -> "未知"
                            },
                        ),
                    )
                }
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
                }
            }

            // ── 动作 ───────────────────────────────────────────────────
            item { ExecFeedback(feedback, feedbackError, feedbackDetail) }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val commands = buildList {
                                add(BatteryCommands.setLevel(level.toInt()))
                                add(BatteryCommands.setStatus(status))
                                if (unplug) add(BatteryCommands.unplug())
                            }
                            run(*commands.toTypedArray()) { ok, detail ->
                                feedbackError = !ok
                                feedback = if (ok) {
                                    "电量伪装已生效：${level.toInt()}% · ${status.label}" +
                                        if (unplug) " · 已拔电" else ""
                                } else {
                                    "应用失败（Shizuku 未就绪或系统拒绝）"
                                }
                                feedbackDetail = detail.ifBlank { null }
                                if (ok) {
                                    settings.batteryLevel = level.toInt()
                                    settings.batteryStatus = status
                                    settings.batteryUnplug = unplug
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
                        Text("应用伪装")
                    }
                    OutlinedButton(
                        onClick = {
                            run(BatteryCommands.reset()) { ok, detail ->
                                feedbackError = !ok
                                feedback = if (ok) "已恢复真实电量（系统重新同步）" else "恢复失败"
                                feedbackDetail = detail.ifBlank { null }
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
                        "部分定制 ROM 的电量管理服务可能周期性回写真实值，" +
                        "如发现读数跳回，重新应用即可。",
                )
            }
        }
    }
}
