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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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

/**
 * 蓝图编辑页（独立 composable，整屏接管）：
 *  - JSON 文本编辑（等宽多行）+「校验 JSON」按钮触发解析 ——
 *    成功显示摘要卡（N 步 / 类型分布），失败显示人话错误（kotlinx
 *    报文原文透传，缺字段已被 parser 点名）；
 *  - 「填入示例模板」一键回填；
 *  - 定时调度开关 + 间隔分钟输入（≥15）：单一事实来源在开关，JSON 里的
 *    schedule 字段会被忽略（编辑态始终剥离 schedule）；
 *  - 保存 = parse 校验 → store.saveUser（原子写）→ 写调度 / 撤调度。
 *
 * @param initial null = 新建；非 null = 编辑该蓝图（内置蓝图「复制为用户版」
 * 也走这里，同 id 保存即覆盖内置呈现）。
 */
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
    var scheduleEnabled by remember { mutableStateOf(initial?.schedule != null) }
    var intervalText by remember {
        mutableStateOf((initial?.schedule?.intervalMinutes ?: 30).toString())
    }
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
        val schedule = if (scheduleEnabled) {
            val minutes = intervalText.trim().toIntOrNull()
            if (minutes == null || minutes < BlueprintParser.MIN_INTERVAL_MINUTES) {
                saveError = "调度间隔必须是不小于 ${BlueprintParser.MIN_INTERVAL_MINUTES} 的分钟数"
                return
            }
            BlueprintSchedule(intervalMinutes = minutes)
        } else {
            null
        }
        val toSave = manifest.copy(schedule = schedule)
        saving = true
        saveError = null
        scope.launch {
            withContext(Dispatchers.IO) { store.saveUser(toSave) }
                .onSuccess {
                    // 保存时写调度 / 取消调度（spec 语义）
                    if (schedule != null) {
                        ScheduleAlarmReceiver.scheduleNext(context, toSave.id, schedule.intervalMinutes)
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
                subtitle = "点「校验 JSON」或保存时解析；调度在下方单独设置",
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

            // ── 调度区 ───────────────────────────────────────────────
            SectionHeader(
                title = "定时调度",
                subtitle = "AlarmManager 精确闹钟 · 重启自动恢复 · 最小间隔 " +
                    "${BlueprintParser.MIN_INTERVAL_MINUTES} 分钟",
            )
            BlueprintCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "启用定时执行",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "到期在后台自动执行本蓝图（Shizuku 未就绪会如实记录失败）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = scheduleEnabled,
                        onCheckedChange = {
                            scheduleEnabled = it
                            saveError = null
                        },
                    )
                }
                if (scheduleEnabled) {
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
                        text = "省电纪律：系统级最小间隔 ${BlueprintParser.MIN_INTERVAL_MINUTES} 分钟。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
}

/** 类型分布文案（摘要卡）：命令 ×2 · 提示 ×1 · 等待 ×0。 */
private fun typeDistribution(manifest: BlueprintManifest): String {
    val counts = manifest.steps.groupingBy { it.type }.eachCount()
    return StepType.entries.joinToString(" · ") { type ->
        "${stepTypeLabel(type)} ×${counts[type] ?: 0}"
    }
}
