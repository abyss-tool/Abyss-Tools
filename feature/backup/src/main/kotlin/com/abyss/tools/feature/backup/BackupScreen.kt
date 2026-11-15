package com.abyss.tools.feature.backup

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import com.abyss.tools.core.common.UiState
import com.abyss.tools.core.designsystem.component.ChipTone
import com.abyss.tools.core.designsystem.component.InfoRow
import com.abyss.tools.core.designsystem.component.SectionHeader
import com.abyss.tools.core.designsystem.component.StatusChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── 屏幕侧常量（与 BackupEngine 的 zip 布局口径一致）──────────────────

/** APK 的标准 MIME。 */
private const val APK_MIME = "application/vnd.android.package-archive"

/** zip 的标准 MIME（选择备份包 / 发布备份包共用）。 */
private const val ZIP_MIME = "application/zip"

/** 公共下载目录下的子目录（MediaStore 相对路径 / 旧版公共目录共用名）。 */
private const val BACKUP_DIR_NAME = "AbyssBackup"

/** FileProvider authority 约定（主壳已接线 filesDir/extracted/ 白名单路径）。 */
private const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"

// ── 屏幕侧数据模型（屏幕私有，不进引擎）──────────────────────────────

/** 已装应用行（APK 资料库列表用；icon 为引用语义 —— 一律以 packageName 为键）。 */
private data class ApkEntry(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val sourceDir: String,
    val icon: Drawable?,
)

/** 一次成功的 APK 导出（uri：API 29+ 的 MediaStore 内容 Uri；file：旧版公共目录文件）。 */
private data class ExportedApk(
    val packageName: String,
    val displayName: String,
    val uri: Uri?,
    val file: File?,
)

/** 已发布到下载库的备份包（zip）。 */
private data class PublishedZip(
    val displayName: String,
    val sizeBytes: Long,
    val uri: Uri?,
    val file: File?,
)

/** 会话日志行（内存态，退出即清 —— 如实，不假装是持久历史）。 */
private data class LogEntry(val timestamp: Long, val message: String)

/**
 * 备份迁移 —— 主壳内置级备份的诚实边界页。
 *
 * 能做（内置级）：APK 资料库（提取安装包归档）、主壳配置与插件清单包
 * （zip 导出）、恢复（ZipGuard 三道闸 + 暂存区合并，带回滚安全防护）。
 * 不能做（插件级，无 Root 系统边界）：应用私有数据 /data/data/&lt;pkg&gt;
 * 的完整抽取与跨设备迁移 —— 顶部边界卡如实告知，不装作在路线图内。
 *
 * 状态纪律：重活全部经 BackupEngine（内部强制 Dispatchers.IO）；
 * 按钮执行中一律置 busy 并禁用；失败内联 StatusChip(ERROR) 给人话
 * message，绝不静默；导出 / 备份 / 恢复的每个动作都进会话日志。
 */
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── 资产盘点（null = 加载中）──────────────────────────────────
    var installedCount by remember { mutableStateOf<Int?>(null) }
    var pluginCount by remember { mutableStateOf<Int?>(null) }
    var blueprintCount by remember { mutableStateOf<Int?>(null) }

    // ── APK 资料库 ─────────────────────────────────────────────────
    var apps by remember { mutableStateOf<List<ApkEntry>>(emptyList()) }
    var appsLoading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    // ── APK 导出执行态 ─────────────────────────────────────────────
    var exportBusy by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf(0f) }
    var exported by remember { mutableStateOf<List<ExportedApk>>(emptyList()) }
    var exportError by remember { mutableStateOf<String?>(null) }

    // ── 配置与插件备份 ─────────────────────────────────────────────
    var backupBusy by remember { mutableStateOf(false) }
    var backupResult by remember { mutableStateOf<PublishedZip?>(null) }
    var backupError by remember { mutableStateOf<String?>(null) }

    // ── 恢复 ───────────────────────────────────────────────────────
    var restoreState by remember { mutableStateOf<UiState<BackupEngine.RestorePreview>?>(null) }
    var restoreBusy by remember { mutableStateOf(false) }
    var restoreReport by remember { mutableStateOf<BackupEngine.RestoreReport?>(null) }
    var restoreError by remember { mutableStateOf<String?>(null) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var pickedZip by remember { mutableStateOf<File?>(null) }

    // ── 会话日志（内存态，最新在前）────────────────────────────────
    val sessionLog = remember { mutableStateListOf<LogEntry>() }

    fun appendLog(message: String) {
        sessionLog.add(0, LogEntry(System.currentTimeMillis(), message))
    }

    // ── 盘点 + 应用列表加载（一次拉全量，IO 派发在 LaunchedEffect 内）──
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            // getInstalledPackages(0)：targetSdk 28（本仓钉死）不受 Android 11+
            // 包可见性过滤影响，flags=0 即全量 —— 与 spec 口径一致
            val installed = runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())
            installedCount = installed.size
            pluginCount = countPluginManifests(File(context.filesDir, "plugins"))
            blueprintCount = countBlueprintFiles(File(context.filesDir, "blueprints"))
            apps = installed
                .mapNotNull { packageInfo -> runCatching { packageInfo.toApkEntry(pm) }.getOrNull() }
                .filter { it.sourceDir.isNotBlank() }
                .sortedWith(compareBy({ it.label.lowercase(Locale.getDefault()) }, { it.packageName }))
            appsLoading = false
        }
    }

    // ── APK 导出（旧版 Android 公共目录写需运行时权限，qrcraft 同款闸门）──
    var pendingExport by remember { mutableStateOf<(() -> Unit)?>(null) }
    val writePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingExport
        pendingExport = null
        if (granted) {
            action?.invoke()
        } else {
            exportError = "未获存储写入权限 —— Android 9 及以下写公共下载目录需要它"
            appendLog("APK 导出未开始：缺存储写入权限（旧版 Android）")
        }
    }

    fun performExport() {
        if (exportBusy || selected.isEmpty()) return
        exportBusy = true
        exportError = null
        exportProgress = 0f
        exported = emptyList()
        scope.launch {
            val byPackage = apps.associateBy { it.packageName }
            val targets = selected.mapNotNull { byPackage[it] }
            if (targets.isEmpty()) {
                exportBusy = false
                return@launch
            }
            val results = mutableListOf<ExportedApk>()
            var ok = 0
            var fail = 0
            targets.forEachIndexed { index, entry ->
                val result = withContext(Dispatchers.IO) { exportApkToDownloads(context, entry) }
                result.fold(
                    onSuccess = { apk ->
                        results.add(apk)
                        ok++
                        appendLog("APK 导出成功：${apk.displayName}")
                    },
                    onFailure = { e ->
                        fail++
                        appendLog("APK 导出失败：${entry.label} —— ${e.message ?: e.javaClass.simpleName}")
                    },
                )
                exportProgress = (index + 1) / targets.size.toFloat()
            }
            exported = results
            appendLog("APK 导出完成：成功 $ok 个 / 失败 $fail 个（Download/$BACKUP_DIR_NAME）")
            exportBusy = false
        }
    }

    fun launchExport() {
        if (exportBusy || selected.isEmpty()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingExport = { performExport() }
            writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            performExport()
        }
    }

    // ── 配置与插件备份（引擎生成 → 发布到下载库）────────────────────
    fun performConfigBackup() {
        if (backupBusy) return
        backupBusy = true
        backupError = null
        scope.launch {
            val temp = File(File(context.cacheDir, "backup"), "config-backup.zip")
            // 引擎生成（IO）→ 发布（IO）：两级 Result 用 fold 串接（stdlib 的
            // Result 无 flatMap —— 不引额外扩展，保持依赖口径干净）
            val published: Result<PublishedZip> =
                BackupEngine.buildConfigBackup(context, temp).fold(
                    onSuccess = { built ->
                        withContext(Dispatchers.IO) { publishZipToDownloads(context, built) }
                    },
                    onFailure = { Result.failure(it) },
                )
            published.fold(
                onSuccess = { zip ->
                    backupResult = zip
                    appendLog(
                        "备份包已生成：${zip.displayName}" +
                            "（${Formatter.formatFileSize(context, zip.sizeBytes)}）",
                    )
                },
                onFailure = { e ->
                    backupError = e.message ?: e.javaClass.simpleName
                    appendLog("备份包生成失败：$backupError")
                },
            )
            backupBusy = false
        }
    }

    // ── 恢复：选择 → 拷贝进缓存 → 预校验 → 确认 → 执行 ──────────────
    fun performRestore() {
        val zip = pickedZip ?: return
        if (restoreBusy) return
        restoreBusy = true
        restoreError = null
        scope.launch {
            BackupEngine.restore(context, zip).fold(
                onSuccess = { report ->
                    restoreReport = report
                    restoreState = null
                    pickedZip = null
                    appendLog("恢复完成：写入 ${report.restoredFiles} 个文件 / 跳过 ${report.skippedFiles} 项")
                },
                onFailure = { e ->
                    restoreError = e.message ?: e.javaClass.simpleName
                    appendLog("恢复失败：$restoreError")
                },
            )
            restoreBusy = false
        }
    }

    val zipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult // 用户取消，保持安静
        restoreState = UiState.Loading
        restoreReport = null
        restoreError = null
        scope.launch {
            val temp = pickedZipFile(context)
            // SAF Uri → 缓存 File（IO）→ 引擎预校验（IO）：两级 Result 用 fold 串接
            val validated: Result<BackupEngine.RestorePreview> =
                withContext(Dispatchers.IO) { copyUriToCache(context, uri, temp) }.fold(
                    onSuccess = { file -> BackupEngine.validateForRestore(file) },
                    onFailure = { Result.failure(it) },
                )
            validated.fold(
                onSuccess = { preview ->
                    pickedZip = temp
                    restoreState = UiState.Data(preview)
                    appendLog("备份包校验通过：${preview.manifest.appVersion} / ${preview.fileCount} 个文件")
                },
                onFailure = { e ->
                    pickedZip = null
                    restoreState = UiState.Error(e.message ?: e.javaClass.simpleName, retryable = false)
                },
            )
        }
    }

    // ── 列表筛选 / 全选（口径：只对当前可见（搜索后）列表生效）──────
    val filtered = remember(apps, query) {
        if (query.isBlank()) {
            apps
        } else {
            apps.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }
    }
    val allFilteredSelected = remember(filtered, selected) {
        filtered.isNotEmpty() && filtered.all { it.packageName in selected }
    }

    fun toggleSelectAll() {
        selected = if (allFilteredSelected) emptySet() else filtered.map { it.packageName }.toSet()
    }

    // ── UI 树 ──────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        BackupHeader(title = "备份迁移", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "intro") { HonestIntroCard() }

            item(key = "inventory-header") {
                SectionHeader(title = "资产盘点", subtitle = "主壳视角的本机资产面 —— 诚实计数，不猜")
            }
            item(key = "inventory") {
                StatCardsRow(
                    installedCount = installedCount,
                    pluginCount = pluginCount,
                    blueprintCount = blueprintCount,
                )
            }

            item(key = "apk-header") {
                SectionHeader(
                    title = "APK 资料库",
                    subtitle = "提取已装应用的安装包副本到 Download/$BACKUP_DIR_NAME —— 只复制，不动原应用",
                )
            }
            item(key = "apk-library") {
                ApkLibraryCard(
                    query = query,
                    onQueryChange = { query = it },
                    appsLoading = appsLoading,
                    filtered = filtered,
                    selected = selected,
                    onToggle = { packageName, checked ->
                        selected = if (checked) selected + packageName else selected - packageName
                    },
                    allSelected = allFilteredSelected,
                    exportBusy = exportBusy,
                    exportProgress = exportProgress,
                    exportError = exportError,
                    onExport = { launchExport() },
                    onToggleSelectAll = { toggleSelectAll() },
                )
            }
            if (exported.isNotEmpty()) {
                item(key = "apk-export-result") {
                    ExportResultCard(
                        exported = exported,
                        onShare = { apk ->
                            shareApk(context, apk).onFailure { e ->
                                appendLog("分享失败：${e.message ?: e.javaClass.simpleName}")
                            }
                        },
                    )
                }
            }

            item(key = "config-header") {
                SectionHeader(
                    title = "配置与插件备份",
                    subtitle = "插件目录 + 蓝图目录 + 主壳设置 → 单个 zip 清单包（含 manifest.json）",
                )
            }
            item(key = "config-backup") {
                ConfigBackupCard(
                    busy = backupBusy,
                    result = backupResult,
                    error = backupError,
                    onBuild = { performConfigBackup() },
                    onShare = { zip ->
                        shareZip(context, zip).onFailure { e ->
                            appendLog("分享失败：${e.message ?: e.javaClass.simpleName}")
                        }
                    },
                    onOpen = { zip ->
                        openZip(context, zip).onFailure { e ->
                            appendLog("打开失败：${e.message ?: e.javaClass.simpleName}")
                        }
                    },
                )
            }

            item(key = "restore-header") {
                SectionHeader(
                    title = "恢复",
                    subtitle = "先校验（条目名 / 体量 / 根目录三道闸）再合并 —— 同名覆盖、绝不删除本机文件",
                )
            }
            item(key = "restore") {
                RestoreCard(
                    state = restoreState,
                    report = restoreReport,
                    busy = restoreBusy,
                    error = restoreError,
                    onPick = { zipPicker.launch(arrayOf(ZIP_MIME)) },
                    onConfirm = { showRestoreDialog = true },
                )
            }

            item(key = "session-log") { SessionLogCard(sessionLog) }
        }
    }

    // 确认弹窗：预览在场才允许确认（fail-closed：没看清就不给恢复入口）
    val preview: BackupEngine.RestorePreview? = when (val state = restoreState) {
        is UiState.Data -> state.value
        else -> null
    }
    if (showRestoreDialog && preview != null) {
        RestoreConfirmDialog(
            preview = preview,
            onConfirm = {
                showRestoreDialog = false
                performRestore()
            },
            onDismiss = { showRestoreDialog = false },
        )
    }
}

// ── 通用小件 ────────────────────────────────────────────────────────

/** 页头（navhub 的 DetailHeader 是 internal 跨模块不可复用 —— 按 DetailBar 模式自写，视觉同构）。 */
@Composable
private fun BackupHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 通用卡容器（appmgr AppCard 同款视觉：surfaceVariant 半透明家族）。 */
@Composable
private fun BackupCard(
    modifier: Modifier = Modifier,
    tone: ChipTone? = null,
    content: @Composable () -> Unit,
) {
    val containerColor = if (tone != null) {
        tone.container.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(containerColor)
            .padding(16.dp),
    ) {
        content()
    }
}

/** 内联失败行：StatusChip(ERROR) + 人话 message（绝不静默）。 */
@Composable
private fun ErrorChipRow(message: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusChip(text = "失败", tone = ChipTone.ERROR)
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
    }
}

// ── 诚实边界卡 ──────────────────────────────────────────────────────

/** 顶部边界卡：内置级能做什么 / 插件级为什么不能 —— 系统边界而非工程取舍。 */
@Composable
private fun HonestIntroCard() {
    BackupCard {
        Text(
            text = "内置级备份：应用 APK 资料库（提取安装包归档）/ 主壳配置与插件清单包（zip）/ 恢复（带回滚安全防护）。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = "应用私有数据（/data/data/<pkg>）的完整抽取与跨设备迁移属插件能力 —— 无 Root 不可达，这是系统边界而非工程取舍。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── 资产盘点 ─────────────────────────────────────────────────────────

/** 三卡盘点行（null = 加载中，显示省略号而非猜测值）。 */
@Composable
private fun StatCardsRow(
    installedCount: Int?,
    pluginCount: Int?,
    blueprintCount: Int?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard(value = installedCount, label = "已装应用", modifier = Modifier.weight(1f))
        StatCard(value = pluginCount, label = "插件", modifier = Modifier.weight(1f))
        StatCard(value = blueprintCount, label = "蓝图", modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(value: Int?, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value?.toString() ?: "…",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── APK 资料库 ───────────────────────────────────────────────────────

@Composable
private fun ApkLibraryCard(
    query: String,
    onQueryChange: (String) -> Unit,
    appsLoading: Boolean,
    filtered: List<ApkEntry>,
    selected: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    allSelected: Boolean,
    exportBusy: Boolean,
    exportProgress: Float,
    exportError: String?,
    onExport: () -> Unit,
    onToggleSelectAll: () -> Unit,
) {
    BackupCard {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("按名称 / 包名搜索") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Rounded.Clear, contentDescription = "清空搜索")
                    }
                }
            },
        )
        Spacer(Modifier.size(10.dp))
        // 高度封顶的内层列表：固定上限 320dp —— 可滚动且不吞掉整页
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (appsLoading) {
                item(key = "apk-loading") {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(
                            text = "正在读取应用列表…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (filtered.isEmpty()) {
                item(key = "apk-empty") {
                    Text(
                        text = "无匹配应用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            } else {
                items(filtered, key = { it.packageName }) { entry ->
                    AppRow(
                        entry = entry,
                        checked = entry.packageName in selected,
                        onCheckedChange = { checked -> onToggle(entry.packageName, checked) },
                    )
                }
            }
        }
        Spacer(Modifier.size(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = onExport,
                enabled = !exportBusy && selected.isNotEmpty(),
            ) {
                Text(if (selected.isEmpty()) "导出选中 APK" else "导出 ${selected.size} 个 APK")
            }
            TextButton(
                onClick = onToggleSelectAll,
                enabled = !exportBusy && filtered.isNotEmpty(),
            ) {
                Text(if (allSelected) "清空选择" else "全选列表")
            }
        }
        if (exportBusy) {
            Spacer(Modifier.size(8.dp))
            Text(
                text = "正在导出（逐个复制，进度见下条）…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(4.dp))
            LinearProgressIndicator(
                progress = { exportProgress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        exportError?.let { message ->
            Spacer(Modifier.size(8.dp))
            ErrorChipRow(message)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRow(
    entry: ApkEntry,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        leadingContent = {
            BadgedBox(
                badge = {
                    val version = entry.versionName
                    if (!version.isNullOrBlank()) {
                        Badge { Text(text = version, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                },
            ) {
                AppAvatar(entry = entry)
            }
        },
        headlineContent = {
            Text(text = entry.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                text = entry.packageName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        },
    )
}

/** 应用图标位：优先 loadIcon 位图（remember 按 pkg 缓存 + runCatching 兜底），
 *  失败 / 无图标 → 首字母方块 —— 绝不因图标问题丢行。 */
@Composable
private fun AppAvatar(entry: ApkEntry) {
    val bitmap = remember(entry.packageName, entry.icon) {
        entry.icon?.let { icon ->
            runCatching { icon.toBitmap(72, 72).asImageBitmap() }.getOrNull()
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            Text(
                text = initialLetter(entry.label),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** 导出结果卡：逐文件一行 + 分享动作（安装包是文件级分享 —— 文件夹整体分享不可行）。 */
@Composable
private fun ExportResultCard(
    exported: List<ExportedApk>,
    onShare: (ExportedApk) -> Unit,
) {
    BackupCard {
        Text(
            text = "导出结果（Download/$BACKUP_DIR_NAME）",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(6.dp))
        exported.forEach { apk ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = apk.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = apk.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { onShare(apk) }) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = "分享 ${apk.displayName}",
                    )
                }
            }
        }
    }
}

// ── 配置与插件备份 ───────────────────────────────────────────────────

@Composable
private fun ConfigBackupCard(
    busy: Boolean,
    result: PublishedZip?,
    error: String?,
    onBuild: () -> Unit,
    onShare: (PublishedZip) -> Unit,
    onOpen: (PublishedZip) -> Unit,
) {
    val context = LocalContext.current
    BackupCard {
        Text(
            text = "打包范围：插件目录（plugin.json 清单 + 已装数据）/ 蓝图目录 / 主壳设置 abyss_settings.xml。" +
                "不含应用私有数据库 —— 那需要插件能力（见顶部边界说明）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(10.dp))
        FilledTonalButton(onClick = onBuild, enabled = !busy) {
            Text(if (busy) "正在生成…" else "生成备份包 (.zip)")
        }
        if (busy) {
            Spacer(Modifier.size(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        result?.let { zip ->
            Spacer(Modifier.size(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = zip.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = Formatter.formatFileSize(context, zip.sizeBytes) +
                            " · Download/$BACKUP_DIR_NAME",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onShare(zip) }) {
                    Icon(imageVector = Icons.Rounded.Share, contentDescription = "分享备份包")
                }
                IconButton(onClick = { onOpen(zip) }) {
                    Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = "打开备份包")
                }
            }
        }
        error?.let { message ->
            Spacer(Modifier.size(8.dp))
            ErrorChipRow(message)
        }
    }
}

// ── 恢复 ─────────────────────────────────────────────────────────────

@Composable
private fun RestoreCard(
    state: UiState<BackupEngine.RestorePreview>?,
    report: BackupEngine.RestoreReport?,
    busy: Boolean,
    error: String?,
    onPick: () -> Unit,
    onConfirm: () -> Unit,
) {
    BackupCard {
        Button(onClick = onPick, enabled = !busy) {
            Text("选择备份包")
        }
        Spacer(Modifier.size(8.dp))
        when (state) {
            is UiState.Loading -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(
                        text = "校验中（条目名 / 体量 / 根目录三道闸）…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            is UiState.Error -> {
                ErrorChipRow(message = state.message)
                Text(
                    text = "换一个深渊工具的备份包再试 —— 不合规的包就地拒绝，绝不「尽力恢复」。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is UiState.Data -> RestorePreviewCard(preview = state.value, busy = busy, onConfirm = onConfirm)
            null -> Text(
                text = "选择 .zip 备份包后先校验再恢复：路径穿越 / zip 炸弹 / 未知根目录一律整包拒绝。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        report?.let { restoreReport ->
            Spacer(Modifier.size(10.dp))
            RestoreReportCard(report = restoreReport)
        }
        error?.let { message ->
            Spacer(Modifier.size(8.dp))
            ErrorChipRow(message)
        }
    }
}

/** 预校验通过后的「看清再动刀」卡：manifest 字段 + 体量 + 根 chips + 确认入口。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RestorePreviewCard(
    preview: BackupEngine.RestorePreview,
    busy: Boolean,
    onConfirm: () -> Unit,
) {
    val context = LocalContext.current
    Column {
        Text(
            text = "校验通过 —— 备份包预览",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        InfoRow(label = "来源版本", value = preview.manifest.appVersion)
        InfoRow(label = "创建时间", value = formatTimestamp(preview.manifest.createdAt))
        InfoRow(label = "文件数", value = preview.fileCount.toString())
        InfoRow(label = "体量", value = Formatter.formatFileSize(context, preview.totalBytes))
        InfoRow(label = "插件数", value = preview.manifest.pluginCount.toString())
        InfoRow(label = "含设置", value = if (preview.manifest.settingsIncluded) "是（重启后生效）" else "否")
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(vertical = 6.dp),
        ) {
            preview.roots.forEach { root ->
                AssistChip(onClick = {}, label = { Text(root) })
            }
        }
        Spacer(Modifier.size(6.dp))
        Button(onClick = onConfirm, enabled = !busy) {
            Text(if (busy) "恢复中…" else "恢复这份备份")
        }
    }
}

/** 确认弹窗：把合并语义说清楚才给「恢复」按钮。 */
@Composable
private fun RestoreConfirmDialog(
    preview: BackupEngine.RestorePreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认恢复？") },
        text = {
            Text(
                text = "将把备份包里的插件与蓝图合并进本机：同名文件覆盖，" +
                    "本机现有文件一律保留（不删除任何未知文件）。" +
                    (
                        if (preview.manifest.settingsIncluded) {
                            "包含设置文件：将写入 shared_prefs/abyss_settings.xml，重启 App 后生效。"
                        } else {
                            "不含设置文件，现有设置不动。"
                        }
                        ) + "来源版本 v${preview.manifest.appVersion}，共 ${preview.fileCount} 个文件。",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("恢复") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 恢复报告卡：restored / skipped + 引擎 notes（含重启生效说明）。 */
@Composable
private fun RestoreReportCard(report: BackupEngine.RestoreReport) {
    Column {
        Text(
            text = "恢复完成 —— 执行报告",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        InfoRow(label = "写入文件", value = report.restoredFiles.toString())
        InfoRow(label = "跳过项", value = report.skippedFiles.toString())
        Spacer(Modifier.size(4.dp))
        report.notes.forEach { note ->
            Text(
                text = "· $note",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── 会话日志 ─────────────────────────────────────────────────────────

/** 内存态动作记录（最新在前；退出即清 —— 如实，不假装是持久历史）。 */
@Composable
private fun SessionLogCard(sessionLog: List<LogEntry>) {
    BackupCard {
        Text(
            text = "本次会话记录",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "仅本页内存记录，退出即清（不入盘、不上报）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(6.dp))
        if (sessionLog.isEmpty()) {
            Text(
                text = "暂无动作",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            sessionLog.forEach { entry ->
                Text(
                    text = "${formatClock(entry.timestamp)}　${entry.message}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── 屏幕侧 IO（MediaStore / 公共目录 / FileProvider —— 引擎之外的第二层）──

/**
 * 单个 APK 导出到下载库（QrImageIo 同款双分支纪律）：
 *  - API 29+：MediaStore.Downloads + RELATIVE_PATH Download/AbyssBackup（免权限）；
 *  - API 26–28：公共下载目录直写 + MediaScanner 扫描入库（运行时写权限由 UI 层闸门）。
 */
private fun exportApkToDownloads(context: Context, entry: ApkEntry): Result<ExportedApk> = runCatching {
    if (entry.sourceDir.isBlank()) {
        throw IllegalStateException("该应用没有可提取的安装包路径：${entry.label}")
    }
    val source = File(entry.sourceDir)
    if (!source.isFile) {
        throw IllegalStateException("源安装包不存在（应用可能已被卸载或隐藏）：${entry.label}")
    }
    val displayName = sanitizeFileName(
        "${entry.packageName}_${entry.versionName ?: entry.versionCode}",
    ) + ".apk"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, APK_MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + BACKUP_DIR_NAME)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("系统下载库拒绝了写入请求（MediaStore insert 失败）")
        context.contentResolver.openOutputStream(uri)?.use { out ->
            source.inputStream().use { input -> input.copyTo(out) }
        } ?: throw IOException("无法打开下载库输出流")
        ExportedApk(packageName = entry.packageName, displayName = displayName, uri = uri, file = null)
    } else {
        val file = exportLegacyToDownloads(context, source, displayName, APK_MIME)
        ExportedApk(packageName = entry.packageName, displayName = displayName, uri = null, file = file)
    }
}

/** 生成的备份包发布到下载库（zip / 同款双分支）。 */
private fun publishZipToDownloads(context: Context, built: File): Result<PublishedZip> = runCatching {
    val displayName = "abyss-backup_" + timestampName() + ".zip"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, ZIP_MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + BACKUP_DIR_NAME)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("系统下载库拒绝了写入请求（MediaStore insert 失败）")
        context.contentResolver.openOutputStream(uri)?.use { out ->
            built.inputStream().use { input -> input.copyTo(out) }
        } ?: throw IOException("无法打开下载库输出流")
        PublishedZip(displayName = displayName, sizeBytes = built.length(), uri = uri, file = null)
    } else {
        val file = exportLegacyToDownloads(context, built, displayName, ZIP_MIME)
        PublishedZip(displayName = displayName, sizeBytes = file.length(), uri = null, file = file)
    }
}

/** API 26–28：公共下载目录直写（需 WRITE_EXTERNAL_STORAGE，UI 层已闸门）。 */
@Suppress("DEPRECATION")
private fun exportLegacyToDownloads(context: Context, source: File, displayName: String, mime: String): File {
    val dir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        BACKUP_DIR_NAME,
    )
    if (!dir.exists() && !dir.mkdirs()) {
        throw IOException("无法创建公共下载目录：${dir.absolutePath}")
    }
    val target = File(dir, displayName)
    source.copyTo(target, overwrite = true)
    // 直写不经 MediaStore → 手动扫进媒体库（下载类应用立即可见）
    MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
    return target
}

/** 分享导出的 APK（文件级 ACTION_SEND —— 文件夹整体分享不可行，逐文件给入口）。 */
private fun shareApk(context: Context, apk: ExportedApk): Result<Unit> = runCatching {
    val uri = shareableUri(context, apk.uri, apk.file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = APK_MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newUri(context.contentResolver, apk.displayName, uri)
    }
    context.startActivity(Intent.createChooser(send, "分享安装包"))
}

/** 分享备份包（zip）。 */
private fun shareZip(context: Context, zip: PublishedZip): Result<Unit> = runCatching {
    val uri = shareableUri(context, zip.uri, zip.file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = ZIP_MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newUri(context.contentResolver, zip.displayName, uri)
    }
    context.startActivity(Intent.createChooser(send, "分享备份包"))
}

/** 用系统 zip 查看器打开备份包（本机没有能打开的 → 人话失败，不 crash）。 */
private fun openZip(context: Context, zip: PublishedZip): Result<Unit> = runCatching {
    val uri = shareableUri(context, zip.uri, zip.file)
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, ZIP_MIME)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newUri(context.contentResolver, zip.displayName, uri)
    }
    try {
        context.startActivity(view)
    } catch (e: ActivityNotFoundException) {
        throw IllegalStateException("本机没有能打开 zip 的应用 —— 请安装文件管理器后再试", e)
    }
}

/**
 * 可分享 Uri 解析：优先 MediaStore 内容 Uri；旧版公共目录的 file:// 直分享
 * 在 API 24+ 会触发 FileUriExposedException → 复制进主壳已接线的
 * filesDir/extracted/（FileProvider 白名单路径）再授权分享。
 */
private fun shareableUri(context: Context, uri: Uri?, legacyFile: File?): Uri {
    uri?.let { return it }
    val legacy = legacyFile ?: throw IllegalStateException("导出记录缺少可分享的文件")
    val shareDir = File(context.filesDir, "extracted")
    if (!shareDir.exists() && !shareDir.mkdirs()) {
        throw IOException("无法创建分享缓存目录：${shareDir.absolutePath}")
    }
    val copy = File(shareDir, legacy.name)
    legacy.copyTo(copy, overwrite = true)
    return FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_AUTHORITY_SUFFIX, copy)
}

/** 选择的备份包落地缓存（引擎按 File 工作 —— SAF Uri 先拷进自家缓存再校验）。 */
private fun pickedZipFile(context: Context): File {
    val dir = File(context.cacheDir, "backup")
    if (!dir.exists() && !dir.mkdirs()) {
        throw IOException("无法创建备份缓存目录：${dir.absolutePath}")
    }
    return File(dir, "picked.zip")
}

private fun copyUriToCache(context: Context, uri: Uri, dest: File): Result<File> = runCatching {
    if (dest.exists()) dest.delete()
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(dest).use { out -> input.copyTo(out) }
    } ?: throw IOException("无法读取所选的备份包（Uri 不可打开）")
    dest
}

// ── 列表数据 / 格式化（appmgr 的 AppManagerShared 是 internal 跨模块不可见 —— 本地同款实现）──

@Suppress("DEPRECATION") // PackageInfo.versionCode：minSdk 26 的回退分支
private fun PackageInfo.toApkEntry(pm: PackageManager): ApkEntry {
    val appInfo = applicationInfo ?: error("包 ${packageName ?: "?"} 无 applicationInfo（跳过）")
    val pkg = packageName ?: error("包信息缺失 packageName（跳过）")
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        longVersionCode
    } else {
        this.versionCode.toLong()
    }
    return ApkEntry(
        packageName = pkg,
        label = runCatching { appInfo.loadLabel(pm).toString() }.getOrDefault(pkg),
        versionName = versionName,
        versionCode = versionCode,
        sourceDir = appInfo.sourceDir ?: "",
        icon = runCatching { appInfo.loadIcon(pm) }.getOrNull(),
    )
}

/** 插件计数：filesDir/plugins 下的 plugin.json（跳过直属 cache/ —— 与引擎打包口径一致）。 */
private fun countPluginManifests(pluginsRoot: File): Int {
    if (!pluginsRoot.isDirectory) return 0
    return pluginsRoot.walkTopDown()
        .onEnter { dir ->
            dir == pluginsRoot || dir.parentFile != pluginsRoot || dir.name != "cache"
        }
        .filter { it.isFile && it.name == "plugin.json" }
        .count()
}

/** 蓝图计数：filesDir/blueprints 下的 .json（排除 runs.json —— 执行历史不是蓝图，与 BlueprintStore 口径一致）。 */
private fun countBlueprintFiles(blueprintsRoot: File): Int {
    if (!blueprintsRoot.isDirectory) return 0
    return blueprintsRoot.listFiles { file ->
        file.isFile && file.name.endsWith(".json") && file.name != "runs.json"
    }?.size ?: 0
}

/** 文件名白名单：仅保留字母 / 数字 / 点 / 下划线 / 连字符（appmgr 同款纪律）。 */
private fun sanitizeFileName(raw: String): String =
    raw.replace(Regex("[^A-Za-z0-9._-]"), "_")

private fun initialLetter(label: String): String =
    label.trim().firstOrNull()?.uppercase(Locale.getDefault())?.toString() ?: "?"

/** 时间戳人话格式化（预览卡 / 报告共用；未知值 ≤0 → 「未知」）。 */
private fun formatTimestamp(millis: Long): String =
    if (millis <= 0) {
        "未知"
    } else {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
    }

/** 会话日志用时钟（时分秒）。 */
private fun formatClock(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))

/** 导出文件名时间戳（固定 Locale.US —— 文件名不随系统语言漂移）。 */
private fun timestampName(): String =
    SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
