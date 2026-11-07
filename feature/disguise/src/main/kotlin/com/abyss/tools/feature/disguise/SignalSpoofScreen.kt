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
import com.abyss.tools.core.shell.ShellGateway
import com.abyss.tools.core.shell.ShizukuStatus
import com.abyss.tools.core.shell.SignalCommands
import com.abyss.tools.core.shell.failureSummary
import kotlinx.coroutines.launch

/**
 * 信号伪装 —— 连接性真实开关（飞行模式 / 移动数据 / Wi-Fi）。
 *
 * 诚实边界：本页切换的是**真实的连接性状态**（与系统开关完全等价，
 * svc / cmd connectivity 官方通道）；「信号格数图标」的伪造属于
 * SystemUI 渲染层，需沙箱 Hook（沙箱双开插件路线）—— 这里不装能。
 */
@Composable
fun SignalSpoofScreen(
    shizukuStatus: ShizukuStatus,
    gateway: ShellGateway,
    onRequestShizukuPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    /** null = 未知（读取失败 / 未授权），绝不显示猜测值。 */
    var airplane by remember { mutableStateOf<Boolean?>(null) }
    var mobileData by remember { mutableStateOf<Boolean?>(null) }
    var wifi by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var feedbackError by remember { mutableStateOf(false) }
    var feedbackDetail by remember { mutableStateOf<String?>(null) }

    fun refreshStates() {
        if (busy) return
        busy = true
        scope.launch {
            fun read(setting: String): Boolean? {
                val result = gateway.exec(setting).getOrNull() ?: return null
                return if (result.isSuccess) SignalCommands.parseSettingFlag(result.stdout) else null
            }
            airplane = read(SignalCommands.readAirplaneMode())
            mobileData = read(SignalCommands.readMobileData())
            wifi = read(SignalCommands.readWifi())
            busy = false
        }
    }

    LaunchedEffect(Unit) { refreshStates() }

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
                    feedback = "$label 切换失败：${e.failureSummary()}"
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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                end = 20.dp,
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ShizukuGuardBanner(shizukuStatus, onRequestShizukuPermission, onOpenShizuku) }

            // ── 三个真实开关 ───────────────────────────────────────────
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

            item { ExecFeedback(feedback, feedbackError, feedbackDetail) }

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
                InfoCard(
                    title = "真实开关 ≠ 信号格数伪造",
                    body = "本页切换的是与系统快捷开关完全等价的真实连接性状态" +
                        "（svc / cmd connectivity 官方通道，全系统生效）。" +
                        "「让状态栏显示 5 格但实际断网」属于 SystemUI 渲染层伪造，" +
                        "需要对目标 App 做 Hook —— 排期在沙箱双开插件的虚拟空间内实现，" +
                        "本页不伪造、不越界。",
                )
            }
        }
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
