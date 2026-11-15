package com.abyss.tools.feature.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abyss.tools.core.common.UiState
import com.abyss.tools.core.common.toUiState
import com.abyss.tools.core.designsystem.component.InfoRow
import com.abyss.tools.core.designsystem.component.SectionHeader
import com.abyss.tools.core.designsystem.component.StatusChip
import com.abyss.tools.core.designsystem.component.entranceStagger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 可导出应用（与 feature/appmgr 解耦的窄接口 —— 数据由 AbyssRoot 适配注入）。 */
data class ExportableApp(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val sourceDir: String,
    val apkSize: Long,
    val isSystem: Boolean,
)

/**
 * 备份迁移（v0.8.1 真实功能页，替换通用占位）：
 *
 *  1. **主壳配置包**：任务蓝图 JSON + 渲染探针现场 + 渲染安全模式状态
 *     打成 abyss-backup-v1 zip（经 SAF 导出 / 导入，零存储权限）；恢复
 *     只回放 blueprints/（取证是现场记录，不回放），两阶段原子落位。
 *  2. **APK 资料库**：已装应用 base APK 导出到 SAF 目标（PackageManager
 *     sourceDir 直拷，无需授权）—— 诚实边界：split APK 应用只导 base
 *     不完整；应用私有数据抽取属系统边界（引擎级插件路线）。
 */
@Composable
fun BackupScreen(
    appSource: suspend () -> List<ExportableApp>,
    onBack: () -> Unit,
    bottomClearance: Dp,
) {
    val context = LocalContext.current
    val blueprintsDir = remember { File(context.filesDir, "blueprints") }

    // ── 主壳配置包：来源文件收集（存在什么打什么，诚实清单）────────────
    fun collectSources(): List<Pair<String, File>> = buildList {
        blueprintsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?.forEach { add("blueprints/${it.name}" to it) }
        listOf("glass-probe.txt", "render-safemode.txt").forEach { name ->
            File(context.filesDir, name).takeIf { it.exists() }?.let { add(name to it) }
        }
    }

    // ── 配置包导出（SAF CreateDocument）────────────────────────────────
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        exporting = true
        val result = runCatching {
            val sources = collectSources()
            if (sources.isEmpty()) {
                throw IllegalStateException("没有可打包的内容（暂无用户蓝图与取证文件）")
            }
            val builder = BackupArchiveBuilder()
            for ((path, file) in sources) builder.add(path, file)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                // 引擎产出到临时文件再灌进 SAF 流（zip 包体量小，内存拷贝安全）
                val staged = File(context.cacheDir, "abyss-backup-staged.zip")
                val report = builder.build(staged, appVersion = com.abyss.tools.core.common.AppInfo.VERSION_NAME)
                staged.inputStream().use { it.copyTo(out) }
                staged.delete()
                "已导出 ${report.entries.size} 项 · ${humanize(report.sizeBytes)}"
            } ?: throw IllegalStateException("无法写入所选位置")
        }
        exporting = false
        exportMessage = result.fold(
            onSuccess = { it },
            onFailure = { "导出失败：${it.message}" },
        )
    }

    // ── 配置包恢复（SAF OpenDocument → 引擎两阶段落位）──────────────────
    var restoreMessage by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf(false) }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        restoring = true
        val result = runCatching {
            val staged = File(context.cacheDir, "abyss-restore-staged.zip")
            context.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { input.copyTo(it) }
            } ?: throw IllegalStateException("无法读取所选文件")
            val report = BackupRestoreEngine().restore(staged, blueprintsDir)
            staged.delete()
            "恢复完成：${report.restored.size} 个蓝图已回放" +
                (if (report.archivedOnly.isNotEmpty()) " · ${report.archivedOnly.size} 项仅归档不回放" else "")
        }
        restoring = false
        restoreMessage = result.fold(
            onSuccess = { it },
            onFailure = { "恢复失败：${it.message}" },
        )
    }

    // ── APK 资料库：已装应用（用户态默认，可切全量）─────────────────────
    var appsUi by remember { mutableStateOf<UiState<List<ExportableApp>>>(UiState.Loading) }
    var showSystemApps by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        appsUi = runCatching { appSource() }.toUiState()
    }

    // ── APK 导出（SAF CreateDocument；单应用单次，挂起当前选中项）───────
    var pendingApp by remember { mutableStateOf<ExportableApp?>(null) }
    var apkExportMessage by remember { mutableStateOf<String?>(null) }
    val apkExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.android.package-archive"),
    ) { uri ->
        val app = pendingApp
        pendingApp = null
        if (uri == null || app == null) return@rememberLauncherForActivityResult
        val result = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                File(app.sourceDir).inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("无法写入所选位置")
            "已导出 ${app.label}（base APK · ${humanize(app.apkSize)}）"
        }
        apkExportMessage = result.fold(
            onSuccess = { it },
            onFailure = { "导出失败：${it.message}" },
        )
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
            IconButton(onClick = onBack) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "备份迁移",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                end = 20.dp,
                bottom = bottomClearance,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── 主壳配置包 ────────────────────────────────────────────
            item {
                Box(Modifier.fillMaxWidth().entranceStagger(0)) {
                    SectionCard(title = "主壳配置包", subtitle = "abyss-backup-v1 · SAF 零存储权限") {
                        InfoRow(label = "包含", value = "任务蓝图 JSON · 渲染探针现场 · 安全模式状态")
                        InfoRow(label = "恢复范围", value = "仅蓝图回放（取证是现场记录，不回放）")
                        Spacer(Modifier.size(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    exportMessage = null
                                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())
                                    exportLauncher.launch("abyss-backup-$stamp.zip")
                                },
                                enabled = !exporting && !restoring,
                            ) {
                                if (exporting) {
                                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.size(8.dp))
                                }
                                Text("导出配置包")
                            }
                            OutlinedButton(
                                onClick = {
                                    restoreMessage = null
                                    restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                                },
                                enabled = !exporting && !restoring,
                            ) {
                                if (restoring) {
                                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.size(8.dp))
                                }
                                Text("从包恢复")
                            }
                        }
                        BackupMessage(exportMessage)
                        BackupMessage(restoreMessage)
                    }
                }
            }

            // ── APK 资料库 ────────────────────────────────────────────
            item {
                Box(Modifier.fillMaxWidth().entranceStagger(1)) {
                    SectionCard(title = "APK 资料库", subtitle = "base APK 导出到所选位置 · split 应用仅导 base 不完整") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "显示系统应用",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(checked = showSystemApps, onCheckedChange = { showSystemApps = it })
                        }
                    }
                }
            }

            when (val ui = appsUi) {
                is UiState.Loading -> item {
                    Box(Modifier.fillMaxWidth().entranceStagger(2)) { LoadingRow() }
                }

                is UiState.Error -> item {
                    Box(Modifier.fillMaxWidth().entranceStagger(2)) {
                        Text("应用列表加载失败：${ui.message}", color = MaterialTheme.colorScheme.error)
                    }
                }

                is UiState.Data -> {
                    val visible = ui.value.filter { showSystemApps || !it.isSystem }
                    item {
                        Box(Modifier.fillMaxWidth().entranceStagger(2)) {
                            SectionHeader(
                                title = "已装应用",
                                subtitle = "${visible.size} 个（${if (showSystemApps) "含系统应用" else "仅用户应用"}）",
                            )
                        }
                    }
                    items(
                        count = visible.size,
                        key = { "apk-${visible[it].packageName}" },
                    ) { index ->
                        val app = visible[index]
                        Box(Modifier.fillMaxWidth().entranceStagger(minOf(3 + index, 12))) {
                            AppExportRow(
                                app = app,
                                onExport = {
                                    apkExportMessage = null
                                    pendingApp = app
                                    val safeLabel = app.label.filter { it.isLetterOrDigit() || it in "-_ " }
                                        .ifBlank { app.packageName }
                                    apkExportLauncher.launch("$safeLabel-${app.versionName ?: "unknown"}.apk")
                                },
                            )
                        }
                    }
                    item {
                        Box(Modifier.fillMaxWidth().entranceStagger(13)) {
                            if (apkExportMessage != null) {
                                Text(
                                    text = apkExportMessage.orEmpty(),
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

@Composable
private fun SectionCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(2.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(10.dp))
        content()
    }
}

@Composable
private fun AppExportRow(app: ExportableApp, onExport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${app.packageName} · v${app.versionName ?: "?"}（${app.versionCode}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusChip(humanize(app.apkSize), modifier = Modifier)
                OutlinedButton(onClick = onExport) { Text("导出") }
            }
        }
    }
}

@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun BackupMessage(message: String?) {
    if (message != null) {
        Spacer(Modifier.size(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun humanize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024f / 1024f / 1024f)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024f / 1024f)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024f)
    else -> "$bytes B"
}
