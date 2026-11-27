package com.abyss.tools.feature.blueprint

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.abyss.tools.core.designsystem.component.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 编辑器预填模板（内置 battery-reset 的 JSON 结构，点按钮一键回填）。 */
internal val BLUEPRINT_TEMPLATE_JSON = """
{
    "id": "battery-reset",
    "name": "电池状态复位",
    "description": "示例模板：撤销电池伪装，恢复系统真实电量读数",
    "steps": [
        {
            "type": "SHELL",
            "content": "dumpsys battery reset"
        }
    ]
}
""".trim()

/** 调度模式（v0.9.0 双模式 + 关闭档）。 */
private enum class ScheduleMode(val label: String) {
    OFF("关闭"),
    INTERVAL("间隔循环"),
    DAILY("每日定时"),
}

/**
 * 蓝图编辑页（独立 composable，整屏接管）：
 *  - JSON 文本编辑（等宽多行）+「校验 JSON」按钮触发解析 ——
 *    成功显示摘要卡（N 步 / 类型分布），失败显示人话错误（kotlinx
 *    报文原文透传，缺字段已被 parser 点名）；
 *  - 「填入示例模板」一键回填；
 *  - 定时调度（v0.9.0 双模式）：关闭 / 间隔分钟（≥15）/ 每日定时
 *    （M3 TimePicker 选时刻）；单一事实来源在编辑器，JSON 里的
 *    schedule 字段会被忽略（编辑态始终剥离 schedule）；
 *  - 保存 = parse 校验 → store.saveUser（原子写）→ 写调度 / 撤调度。
 *
 * @param initial null = 新建；非 null = 编辑该蓝图（内置蓝图「复制为用户版」
 * 也走这里，同 id 保存即覆盖内置呈现）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BlueprintEditor(
    initial: BlueprintManifest?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { BlueprintStore.create(context) }

    var json by remember {
        mutableStateOf(initial?.let(BlueprintParser::toEditableJson) ?: BLUEPRINT_TEMPLATE_JSON)
    }
    var parsed by remember { mutableStateOf<BlueprintManifest?>(null) }
    var parseError by remember { mutableStateOf<String?>(null) }
    var scheduleMode by remember {
        mutableStateOf(
            when {
                initial?.schedule?.intervalMinutes != null -> ScheduleMode.INTERVAL
                initial?.schedule?.dailyAtMinute != null -> ScheduleMode.DAILY
                else -> ScheduleMode.OFF
            },
        )
    }
    var intervalText by remember {
        mutableStateOf((initial?.schedule?.intervalMinutes ?: 30).toString())
    }
    var dailyMinute by remember {
        mutableStateOf(initial?.schedule?.dailyAtMinute ?: (8 * 60)) // 缺省 08:00
    }
    var timePickerVisible by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    /** 按钮触发的解析（校验按钮 / 保存前共用）；成功回填摘要态。 */
    fun doParse(): BlueprintManifest? {
        val outcome = BlueprintParser.parse(json)
        parsed = outcome.getOrNull()
        parseError = outcome.exceptionOrNull()?.message
        return parsed
    }

    fun attemptSave() {
        val manifest = doParse() ?: return // 解析失败：错误已就地上屏
        val schedule = when (scheduleMode) {
            ScheduleMode.OFF -> null
            ScheduleMode.INTERVAL -> {
                val minutes = intervalText.trim().toIntOrNull()
                if (minutes == null || minutes < BlueprintParser.MIN_INTERVAL_MINUTES) {
                    saveError = "调度间隔必须是不小于 ${BlueprintParser.MIN_INTERVAL_MINUTES} 的分钟数"
                    return
                }
                BlueprintSchedule(intervalMinutes = minutes)
            }

            ScheduleMode.DAILY -> BlueprintSchedule(dailyAtMinute = dailyMinute)
        }
        val toSave = manifest.copy(schedule = schedule)
        saving = true
        saveError = null
        scope.launch {
            withContext(Dispatchers.IO) { store.saveUser(toSave) }
                .onSuccess {
                    // 保存时写调度 / 取消调度（spec 语义）
                    if (schedule != null) {
                        ScheduleAlarmReceiver.scheduleNext(context, toSave.id, schedule)
                    } else {
                        ScheduleAlarmReceiver.cancel(context, toSave.id)
                    }
                    saving = false
                    onSaved()
                    onDismiss()
                }
                .onFailure { e ->
                    saving = false
                    saveError = "保存失败：${e.message}"
                }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // ── 页头 ─────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (!saving) onDismiss() }) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "关闭编辑页",
                )
            }
            Text(
                text = if (initial == null) "新建蓝图" else "编辑蓝图",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            // ── JSON 编辑区 ──────────────────────────────────────────
            SectionHeader(
                title = "蓝图 JSON",
            )
            OutlinedTextField(
                value = json,
                onValueChange = {
                    json = it
                    parsed = null
                    parseError = null
                    saveError = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                singleLine = false,
            )
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { doParse() }) { Text("校验 JSON") }
                OutlinedButton(
                    onClick = {
                        json = BLUEPRINT_TEMPLATE_JSON
                        parsed = null
                        parseError = null
                        saveError = null
                    },
                ) {
                    Text("填入示例模板")
                }
            }

            parseError?.let {
                Spacer(Modifier.size(8.dp))
                BlueprintWarnLine(it)
            }

            parsed?.let { manifest ->
                Spacer(Modifier.size(10.dp))
                BlueprintCard {
                    Text(
                        text = "校验通过：${manifest.name}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = "id ${manifest.id} · 共 ${manifest.steps.size} 步 · " +
                            typeDistribution(manifest),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (manifest.description.isNotBlank()) {
                        Spacer(Modifier.size(2.dp))
                        Text(
                            text = manifest.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.size(8.dp))
                    StepPreviewList(manifest.steps)
                }
            }

            // ── 调度区（v0.9.0 双模式：间隔循环 / 每日定时 TimePicker）──
            SectionHeader(
                title = "定时调度",
            )
            BlueprintCard {
                Text(
                    text = "调度模式",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.size(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ScheduleMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = scheduleMode == mode,
                            onClick = {
                                scheduleMode = mode
                                saveError = null
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = ScheduleMode.entries.size,
                            ),
                        ) { Text(mode.label) }
                    }
                }
                when (scheduleMode) {
                    ScheduleMode.OFF -> {
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = "仅手动执行（保存后不排闹钟，已排调度会被撤销）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    ScheduleMode.INTERVAL -> {
                        Spacer(Modifier.size(10.dp))
                        OutlinedTextField(
                            value = intervalText,
                            onValueChange = { raw ->
                                intervalText = raw.filter(Char::isDigit).take(5)
                                saveError = null
                            },
                            label = { Text("间隔（分钟）") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            text = "省电纪律：最小间隔 ${BlueprintParser.MIN_INTERVAL_MINUTES} 分钟",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    ScheduleMode.DAILY -> {
                        Spacer(Modifier.size(10.dp))
                        // 每日模式：M3 TimePicker 选时刻 —— 比手输 HH:mm 更
                        // 贴近 Google 时钟的交互语言
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DateRange,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.size(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "每天 ${SchedulePlanner.formatDailyMinute(dailyMinute)}",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = "到期在后台自动执行本蓝图（时区跟随系统）",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                FilledTonalButton(onClick = { timePickerVisible = true }) {
                                    Text("选时刻")
                                }
                            }
                        }
                    }
                }
            }

            saveError?.let {
                Spacer(Modifier.size(8.dp))
                BlueprintWarnLine(it)
            }

            Spacer(Modifier.size(16.dp))
            Button(
                onClick = { attemptSave() },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text("保存蓝图")
            }
            Spacer(Modifier.size(32.dp))
        }
    }

    // ── 每日时刻选择（M3 TimePicker · ModalBottomSheet）────────────────
    if (timePickerVisible) {
        val pickerState = rememberTimePickerState(
            initialHour = dailyMinute / 60,
            initialMinute = dailyMinute % 60,
            is24Hour = true,
        )
        ModalBottomSheet(onDismissRequest = { timePickerVisible = false }) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
            ) {
                Text(
                    text = "选择每日执行时刻",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.size(12.dp))
                TimePicker(state = pickerState)
                Spacer(Modifier.size(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { timePickerVisible = false }) { Text("取消") }
                    Button(
                        onClick = {
                            dailyMinute = pickerState.hour * 60 + pickerState.minute
                            timePickerVisible = false
                            saveError = null
                        },
                    ) { Text("确定") }
                }
            }
        }
    }
}

/** 类型分布文案（摘要卡）：命令 ×2 · 提示 ×1 · 等待 ×0。 */
private fun typeDistribution(manifest: BlueprintManifest): String {
    val counts = manifest.steps.groupingBy { it.type }.eachCount()
    return StepType.entries.joinToString(" · ") { type ->
        "${stepTypeLabel(type)} ×${counts[type] ?: 0}"
    }
}
