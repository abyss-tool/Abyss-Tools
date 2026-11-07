package com.abyss.tools.feature.shizukukit

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * 系统设置面板：namespace 三选（SegmentedButton）+ key / value 输入 +
 * 常用键预设（AssistChip 点填）→ 读取（null 诚实显示「未设置（null）」）；
 * 写入属危险操作，强制二次确认弹窗。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPanel(
    runner: KitRunner,
    record: ExecRecord?,
    namespace: SettingsCommands.Namespace,
    onNamespaceChange: (SettingsCommands.Namespace) -> Unit,
    key: String,
    onKeyChange: (String) -> Unit,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var inputError by remember { mutableStateOf<String?>(null) }
    var readResult by remember { mutableStateOf<String?>(null) }
    var presetNote by remember { mutableStateOf<String?>(null) }
    var pendingPut by remember { mutableStateOf<String?>(null) }

    fun read() {
        inputError = null
        val command = try {
            SettingsCommands.getSetting(namespace.cli, key)
        } catch (e: IllegalArgumentException) {
            inputError = e.message
            return
        }
        runner.execute(CommandType.SETTINGS, "读取 ${namespace.cli}.$key", command) { result ->
            // 读失败 → 清空旧读数（fail-closed：绝不让上一次的值冒充当前值）
            readResult = if (result.isSuccess) result.stdout.trim() else null
        }
    }

    fun requestPut() {
        inputError = null
        val command = try {
            SettingsCommands.putSetting(namespace.cli, key, value)
        } catch (e: IllegalArgumentException) {
            inputError = e.message
            return
        }
        pendingPut = command
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── 命名空间 + 键值 ─────────────────────────────────────────────
        item {
            KitCard {
                KitCardTitle("命名空间")
                Spacer(Modifier.size(6.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val namespaces = SettingsCommands.Namespace.entries
                    namespaces.forEachIndexed { index, ns ->
                        SegmentedButton(
                            selected = ns == namespace,
                            onClick = { onNamespaceChange(ns) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = namespaces.size,
                            ),
                        ) {
                            Text(ns.cli)
                        }
                    }
                }
                Spacer(Modifier.size(10.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = onKeyChange,
                    label = { Text("设置键名（key）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text("新值（写入时使用）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = ::read, enabled = !runner.busy) { Text("读取当前值") }
                    OutlinedButton(onClick = ::requestPut, enabled = !runner.busy) {
                        Text("写入（需确认）")
                    }
                }
                readResult?.let { raw ->
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = "读取结果",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(2.dp))
                    Text(
                        // settings get 对缺省项返回字面 "null" —— 诚实展示，绝不猜默认值
                        text = if (raw == "null" || raw.isEmpty()) "未设置（null）" else raw,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        // ── 常用键预设 ──────────────────────────────────────────────────
        item {
            KitCard {
                KitCardTitle("常用键预设（点填）")
                Spacer(Modifier.size(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsCommands.presets.forEach { preset ->
                        AssistChip(
                            onClick = {
                                onNamespaceChange(preset.namespace)
                                onKeyChange(preset.key)
                                presetNote =
                                    "${preset.label}（${preset.namespace.cli}.${preset.key}）：${preset.detail}"
                            },
                            label = { Text(preset.key) },
                        )
                    }
                }
                presetNote?.let { note ->
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item { KitErrorLine(inputError) }
        item { KitBusyLine(runner.busy) }
        item { ExecResultCard(record) }

        // ── 说明卡 ──────────────────────────────────────────────────────
        item {
            KitInfoCard(
                title = "settings 通道说明",
                body = "settings 命令直写系统设置数据库的三张表：global（全局）/ system（系统）/" +
                    "secure（安全）。读取安全；写入会立即改变系统行为（部分项需广播或重启才生效），" +
                    "错误键值可能导致系统异常 —— 本页写入强制二次确认。",
            )
        }
    }

    // 写入确认弹窗（危险操作提示）
    pendingPut?.let { command ->
        AlertDialog(
            onDismissRequest = { pendingPut = null },
            title = { Text("确认写入系统设置") },
            text = {
                Text(
                    text = "即将执行：\n$command\n\nsettings 是系统级配置通道（$namespace 表），" +
                        "错误键值可能影响系统行为，严重时需恢复出厂修复。请确认键名与取值无误。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingPut = null
                        runner.execute(
                            CommandType.SETTINGS,
                            "写入 ${namespace.cli}.$key",
                            command,
                        )
                    },
                ) {
                    Text(text = "确认写入", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPut = null }) { Text("取消") }
            },
        )
    }
}
