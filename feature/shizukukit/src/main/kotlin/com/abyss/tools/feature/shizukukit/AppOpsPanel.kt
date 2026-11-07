package com.abyss.tools.feature.shizukukit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abyss.tools.core.designsystem.component.ChipTone
import com.abyss.tools.core.designsystem.component.StatusChip

/** 已装应用条目（BottomSheet 选择器数据）。 */
internal data class AppEntry(
    val label: String,
    val packageName: String,
)

/**
 * AppOps 面板：目标包名（手填 / 已装应用选择）+ op（预设下拉 / 自定义）→
 * 查询状态徽章（ALLOW 绿 / DENY 红 / DEFAULT 灰 / 无法判定 WARN）→
 * 允许 / 拒绝 / 恢复默认按钮组（写入成功后自动回读系统真实状态）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppOpsPanel(
    runner: KitRunner,
    record: ExecRecord?,
    pkg: String,
    onPkgChange: (String) -> Unit,
    op: String,
    onOpChange: (String) -> Unit,
    appEntries: List<AppEntry>?,
) {
    var queryState by remember { mutableStateOf<AppOpsState?>(null) }
    var queried by remember { mutableStateOf(false) }
    var inputError by remember { mutableStateOf<String?>(null) }
    var opMenuExpanded by remember { mutableStateOf(false) }
    var pickerVisible by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }

    fun query() {
        inputError = null
        val command = try {
            AppOpsCommands.query(pkg, op)
        } catch (e: IllegalArgumentException) {
            inputError = e.message
            return
        }
        runner.execute(CommandType.APPOPS, "查询 $op 状态", command) { result ->
            queried = true
            queryState = if (result.isSuccess) AppOpsCommands.parseAppOps(result.stdout) else null
        }
    }

    // 局部命名 applyMode：避开 stdlib 的 T.apply 作用域函数，防解析歧义
    fun applyMode(verb: String, build: () -> String) {
        inputError = null
        val command = try {
            build()
        } catch (e: IllegalArgumentException) {
            inputError = e.message
            return
        }
        runner.execute(CommandType.APPOPS, "设置 $op → $verb", command) { result ->
            // 生效状态以系统为准：写入成功后自动回读（不信任本地乐观值）
            if (result.isSuccess) query()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── 目标应用 ────────────────────────────────────────────────────
        item {
            KitCard {
                KitCardTitle("目标应用")
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = pkg,
                    onValueChange = onPkgChange,
                    label = { Text("包名（如 com.abyss.tools）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                OutlinedButton(
                    onClick = { pickerVisible = true },
                    enabled = appEntries != null,
                ) {
                    Text(if (appEntries == null) "应用清单加载中…" else "从已装应用选择")
                }
            }
        }

        // ── op 与状态 ───────────────────────────────────────────────────
        item {
            KitCard {
                KitCardTitle("op 与状态")
                Spacer(Modifier.size(6.dp))
                ExposedDropdownMenuBox(
                    expanded = opMenuExpanded,
                    onExpandedChange = { opMenuExpanded = it },
                ) {
                    OutlinedTextField(
                        value = op,
                        onValueChange = onOpChange,
                        label = { Text("op 名（预设下拉或自定义输入）") },
                        supportingText = {
                            Text("Android 12+ 若提示 No such appops，请改用 android: 前缀（如 android:mock_location）")
                        },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { opMenuExpanded = !opMenuExpanded }) {
                                Icon(
                                    imageVector = Icons.Rounded.KeyboardArrowDown,
                                    contentDescription = "选择预设 op",
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = opMenuExpanded,
                        onDismissRequest = { opMenuExpanded = false },
                    ) {
                        AppOpsCommands.presets.forEach { preset ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = "${preset.label}（${preset.op}）",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            text = preset.detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    onOpChange(preset.op)
                                    opMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.size(10.dp))
                Button(onClick = ::query, enabled = !runner.busy) {
                    Text("查询状态")
                }
                if (queried) {
                    Spacer(Modifier.size(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "当前状态：",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        when (queryState) {
                            AppOpsState.ALLOW -> StatusChip(text = "ALLOW", tone = ChipTone.OK)
                            AppOpsState.DENY -> StatusChip(text = "DENY", tone = ChipTone.ERROR)
                            AppOpsState.DEFAULT -> StatusChip(text = "DEFAULT", tone = ChipTone.NEUTRAL)
                            null -> StatusChip(text = "无法判定", tone = ChipTone.WARN)
                        }
                    }
                }
                Spacer(Modifier.size(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { applyMode("allow") { AppOpsCommands.setAllow(pkg, op) } },
                        enabled = !runner.busy,
                    ) { Text("允许") }
                    OutlinedButton(
                        onClick = { applyMode("deny") { AppOpsCommands.setDeny(pkg, op) } },
                        enabled = !runner.busy,
                    ) { Text("拒绝") }
                    OutlinedButton(
                        onClick = { applyMode("default") { AppOpsCommands.setDefault(pkg, op) } },
                        enabled = !runner.busy,
                    ) { Text("恢复默认") }
                }
            }
        }

        item { KitErrorLine(inputError) }
        item { KitBusyLine(runner.busy) }
        item { ExecResultCard(record) }

        // ── 说明卡 ──────────────────────────────────────────────────────
        item {
            KitInfoCard(
                title = "appops 是什么",
                body = "App Operations 是 Android 的免 Root 权限精细管控通道：比运行时权限更细粒度" +
                    "（后台运行 / 悬浮窗 / 模拟位置 / 安装包 / 闲置回收……）。allow = 显式放行，" +
                    "deny = 显式拒绝（比撤销权限更狠），default = 归还系统默认策略。" +
                    "改动即时生效，重启不丢失。",
            )
        }
    }

    if (pickerVisible) {
        AppPickerSheet(
            entries = appEntries.orEmpty(),
            search = search,
            onSearchChange = { search = it },
            onSelect = { packageName ->
                onPkgChange(packageName)
                pickerVisible = false
            },
            onDismiss = { pickerVisible = false },
        )
    }
}

/** 「从已装应用选择」BottomSheet：搜索 + 列表（max-h 限制 + contentPadding）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerSheet(
    entries: List<AppEntry>,
    search: String,
    onSearchChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = "选择应用",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(10.dp))
            OutlinedTextField(
                value = search,
                onValueChange = onSearchChange,
                label = { Text("搜索应用名 / 包名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(8.dp))
            if (entries.isEmpty()) {
                Text(
                    text = "未获取到应用清单",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            } else {
                val keyword = search.trim().lowercase()
                val filtered = remember(entries, keyword) {
                    if (keyword.isEmpty()) {
                        entries
                    } else {
                        entries.filter {
                            it.label.lowercase().contains(keyword) ||
                                it.packageName.lowercase().contains(keyword)
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    Text(
                        text = "无匹配应用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 24.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        contentPadding = PaddingValues(bottom = 32.dp),
                    ) {
                        items(filtered) { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onSelect(entry.packageName) }
                                    .padding(horizontal = 4.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text = entry.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
