package com.abyss.tools.feature.appmgr

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.abyss.tools.core.designsystem.component.ChipTone
import com.abyss.tools.core.designsystem.component.InfoRow
import com.abyss.tools.core.designsystem.component.SectionHeader
import com.abyss.tools.core.designsystem.component.StatusChip
import com.abyss.tools.core.shell.ShellGateway
import com.abyss.tools.core.shell.ShizukuStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * FileProvider authority 约定 —— 分享提取的 APK 用。
 * 对应 :app 的 AndroidManifest 需声明
 * `android:name="androidx.core.content.FileProvider"` +
 * `android:authorities="com.abyss.tools.fileprovider"`（含
 * external-files-path/files-path 的 extracted/ 路径，由主壳统一接线）。
 */
internal const val FILE_PROVIDER_AUTHORITY = "com.abyss.tools.fileprovider"

/** 详情页可执行动作（执行态机：null = 空闲，同一时刻至多一个在跑）。 */
private enum class DetailAction {
    FREEZE,
    FORCE_STOP,
    HIDE,
    EXTRACT,
}

/**
 * 应用详情页：信息区 + 动作区（启动 / 提取安装包 / 冻结解冻 / 强制停止 /
 * 隐藏显示 / 卸载）。
 *
 * 状态机纪律（模仿 SignalSpoofScreen 的执行模式）：
 *  - Shizuku 依赖动作（冻结 / 强停 / 隐藏）：未授权时禁用（冻结主按钮
 *    变形为「去授权」），门禁卡置顶引导；
 *  - 每个动作 = 执行中 CircularProgressIndicator + 结果 Snackbar；
 *  - 冻结 / 隐藏成功后：本地标记立即刷新 + 重读系统真值对账；
 *  - Result → 成功 / 被系统拒绝 / 异常三分支齐全，错误只给人话摘要。
 */
@Composable
fun AppDetailScreen(
    app: AbyssAppInfo,
    provider: AppInfoProvider,
    gateway: ShellGateway,
    shizukuStatus: ShizukuStatus,
    onRequestShizukuPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var appSnapshot by remember(app) { mutableStateOf(app) }

    /** null = 未知（Shizuku 未授权 / 读取失败）—— 按钮取默认方向，绝不显示猜测值。 */
    var frozen by remember(app) { mutableStateOf<Boolean?>(null) }
    var hidden by remember(app) { mutableStateOf<Boolean?>(null) }
    var busyAction by remember(app) { mutableStateOf<DetailAction?>(null) }
    var extractedFile by remember(app) { mutableStateOf<File?>(null) }
    var showForceStopDialog by remember(app) { mutableStateOf(false) }

    val shizukuAuthorized = (shizukuStatus as? ShizukuStatus.Running)?.authorized == true

    fun showSnackbar(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    /** 重读系统真值：该应用是否在禁用 / 隐藏集合里（fail-closed：读不到 → null）。 */
    fun refreshShellStatus() {
        scope.launch {
            gateway.exec(AppManagerCommands.listDisabled())
                .onSuccess { result ->
                    frozen = if (result.isSuccess) {
                        AppManagerCommands.parseDisabledPackages(result.stdout)
                            .contains(appSnapshot.packageName)
                    } else {
                        null
                    }
                }
                .onFailure { frozen = null }
            gateway.exec(AppManagerCommands.listHidden())
                .onSuccess { result ->
                    hidden = if (result.isSuccess) {
                        AppManagerCommands.parseHiddenPackages(result.stdout)
                            .contains(appSnapshot.packageName)
                    } else {
                        null
                    }
                }
                .onFailure { hidden = null }
        }
    }

    /** pm 系动作统一执行入口：执行中态 + Snackbar 反馈 + 成功回调。 */
    fun runPm(action: DetailAction, successLabel: String, command: String, onOk: () -> Unit) {
        if (busyAction != null) return
        busyAction = action
        scope.launch {
            gateway.exec(command)
                .onSuccess { result ->
                    if (result.isSuccess) {
                        onOk()
                        showSnackbar("$successLabel 成功")
                    } else {
                        val detail = result.stderr.ifBlank { result.stdout }.trim()
                        showSnackbar(
                            "$successLabel 被系统拒绝" +
                                if (detail.isNotBlank()) "：${detail.take(160)}" else "",
                        )
                    }
                }
                .onFailure { e ->
                    showSnackbar("$successLabel 失败：${e.message ?: e.javaClass.simpleName}")
                }
            busyAction = null
        }
    }

    fun launchApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(appSnapshot.packageName)
        if (intent == null) {
            showSnackbar("该应用没有可启动的入口（无主界面，或已被冻结）")
        } else {
            runCatching { context.startActivity(intent) }
                .onFailure {
                    showSnackbar("启动失败：${it.message ?: it.javaClass.simpleName}")
                }
        }
    }

    fun extractApk() {
        if (busyAction != null) return
        busyAction = DetailAction.EXTRACT
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { extractApkTo(context, appSnapshot) }
            }
            result
                .onSuccess { file ->
                    extractedFile = file
                    showSnackbar("安装包已提取：${file.absolutePath}")
                }
                .onFailure { e ->
                    showSnackbar("提取失败：${e.message ?: e.javaClass.simpleName}")
                }
            busyAction = null
        }
    }

    fun shareExtracted(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "分享安装包"))
        }.onFailure { e ->
            showSnackbar(
                "分享失败：${e.message ?: "FileProvider 未配置（authority=$FILE_PROVIDER_AUTHORITY）"}",
            )
        }
    }

    // 进入页面 / 授权态变化时读取冻结与隐藏真值；未授权 → 未知。
    LaunchedEffect(shizukuAuthorized, app) {
        if (shizukuAuthorized) {
            refreshShellStatus()
        } else {
            frozen = null
            hidden = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppManagerHeader(
                title = appSnapshot.label,
                onBack = onBack,
                trailing = {
                    StatusChip(
                        text = if (appSnapshot.isSystem) "系统应用" else "用户应用",
                        tone = if (appSnapshot.isSystem) ChipTone.NEUTRAL else ChipTone.OK,
                    )
                },
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 4.dp,
                    bottom = 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!shizukuAuthorized) {
                    item(key = "gate") {
                        ShizukuGateCard(
                            shizukuStatus = shizukuStatus,
                            onRequestPermission = onRequestShizukuPermission,
                            onOpenShizuku = onOpenShizuku,
                        )
                    }
                }

                // ── 应用概览 ─────────────────────────────────────────
                item(key = "overview") {
                    AppCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(icon = appSnapshot.icon, modifier = Modifier.size(56.dp))
                            Spacer(Modifier.size(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = appSnapshot.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.size(2.dp))
                                Text(
                                    text = appSnapshot.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.size(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (frozen == true) {
                                StatusChip(text = "已冻结", tone = ChipTone.WARN)
                            }
                            if (hidden == true) {
                                StatusChip(text = "已隐藏", tone = ChipTone.WARN)
                            }
                        }
                    }
                }

                // ── 信息区 ───────────────────────────────────────────
                item(key = "info") {
                    SectionHeader(title = "应用信息", subtitle = null)
                    AppCard {
                        InfoRow(label = "版本名", value = appSnapshot.versionName ?: "未知")
                        InfoRow(label = "版本号", value = appSnapshot.versionCode.toString())
                        InfoRow(label = "包名", value = appSnapshot.packageName)
                        InfoRow(label = "安装时间", value = formatTimestamp(appSnapshot.installTime))
                        InfoRow(label = "更新时间", value = formatTimestamp(appSnapshot.lastUpdateTime))
                        InfoRow(label = "APK 大小", value = formatBytes(context, appSnapshot.apkSize))
                        InfoRow(label = "APK 路径", value = appSnapshot.sourceDir.ifBlank { "未知" })
                        InfoRow(label = "类型", value = if (appSnapshot.isSystem) "系统应用" else "用户应用")
                    }
                }

                // ── 动作区 ───────────────────────────────────────────
                item(key = "actions") {
                    SectionHeader(title = "操作", subtitle = null)
                    AppCard {
                        // 启动 / 提取安装包（均不依赖 Shizuku）
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = { launchApp() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.size(6.dp))
                                Text("启动")
                            }
                            OutlinedButton(
                                onClick = { extractApk() },
                                enabled = busyAction == null,
                                modifier = Modifier.weight(1f),
                            ) {
                                BusyIndicator(busy = busyAction == DetailAction.EXTRACT)
                                Text("提取安装包")
                            }
                        }

                        extractedFile?.let { file ->
                            Spacer(Modifier.size(10.dp))
                            Text(
                                text = "已提取：${file.absolutePath}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.size(6.dp))
                            OutlinedButton(onClick = { shareExtracted(file) }) {
                                Icon(
                                    imageVector = Icons.Rounded.Share,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.size(6.dp))
                                Text("分享安装包")
                            }
                        }

                        Spacer(Modifier.size(10.dp))

                        // 冻结（Shizuku 门禁：未授权时主按钮变形为「去授权」）/ 强制停止
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (!shizukuAuthorized) {
                                Button(
                                    onClick = onRequestShizukuPermission,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.size(6.dp))
                                    Text("去授权")
                                }
                            } else {
                                Button(
                                    onClick = {
                                        val willFreeze = frozen != true
                                        runPm(
                                            action = DetailAction.FREEZE,
                                            successLabel = if (willFreeze) "冻结" else "解冻",
                                            command = if (willFreeze) {
                                                AppManagerCommands.freeze(appSnapshot.packageName)
                                            } else {
                                                AppManagerCommands.unfreeze(appSnapshot.packageName)
                                            },
                                        ) {
                                            // 本地标记立即刷新 + 清缓存 + 与系统真值对账
                                            frozen = willFreeze
                                            provider.invalidate()
                                            refreshShellStatus()
                                        }
                                    },
                                    enabled = busyAction == null,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    BusyIndicator(busy = busyAction == DetailAction.FREEZE)
                                    Text(text = if (frozen == true) "解冻" else "冻结")
                                }
                            }
                            OutlinedButton(
                                onClick = { showForceStopDialog = true },
                                enabled = shizukuAuthorized && busyAction == null,
                                modifier = Modifier.weight(1f),
                            ) {
                                BusyIndicator(busy = busyAction == DetailAction.FORCE_STOP)
                                Text("强制停止")
                            }
                        }

                        Spacer(Modifier.size(10.dp))

                        // 隐藏 / 卸载
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val willHide = hidden != true
                                    runPm(
                                        action = DetailAction.HIDE,
                                        successLabel = if (willHide) "隐藏" else "显示",
                                        command = if (willHide) {
                                            AppManagerCommands.hide(appSnapshot.packageName)
                                        } else {
                                            AppManagerCommands.unhide(appSnapshot.packageName)
                                        },
                                    ) {
                                        hidden = willHide
                                        provider.invalidate()
                                        refreshShellStatus()
                                    }
                                },
                                enabled = shizukuAuthorized && busyAction == null,
                                modifier = Modifier.weight(1f),
                            ) {
                                BusyIndicator(busy = busyAction == DetailAction.HIDE)
                                Text(text = if (hidden == true) "显示" else "隐藏")
                            }
                            OutlinedButton(
                                onClick = {
                                    // 系统卸载确认页由用户把关；先清缓存，
                                    // 返回列表页时拉 PackageManager 真值。
                                    provider.invalidate()
                                    val intent = Intent(
                                        Intent.ACTION_UNINSTALL_PACKAGE,
                                        Uri.fromParts("package", appSnapshot.packageName, null),
                                    )
                                    runCatching { context.startActivity(intent) }
                                        .onFailure {
                                            showSnackbar(
                                                "无法打开卸载页面：" +
                                                    (it.message ?: it.javaClass.simpleName),
                                            )
                                        }
                                },
                                enabled = !appSnapshot.isSystem,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.size(6.dp))
                                Text("卸载")
                            }
                        }

                        if (appSnapshot.isSystem) {
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = "系统应用受系统保护无法卸载 —— 可用「冻结」停用（数据保留，随时解冻恢复）。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // ── 操作语义（诚实边界）────────────────────────────────
                item(key = "notes") {
                    SectionHeader(title = "操作语义", subtitle = null)
                    AppCard {
                        Text(
                            text = listOf(
                                "冻结 = pm disable-user --user 0：免 Root 停用，图标消失、后台清零，数据完整保留，解冻即恢复；",
                                "强制停止 = am force-stop：立即终止全部进程，不删数据；",
                                "隐藏 = pm hide：从系统整体隐藏（比冻结更彻底），数据保留，可随时恢复；",
                                "提取安装包：复制 APK 到本应用专属目录（无需 Shizuku），可分享给其他设备安装。",
                            ).joinToString(separator = "\n"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 24.dp),
        )
    }

    // ── 强制停止确认弹窗 ─────────────────────────────────────────────
    if (showForceStopDialog) {
        AlertDialog(
            onDismissRequest = { showForceStopDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                )
            },
            title = { Text(text = "强制停止「${appSnapshot.label}」？") },
            text = {
                Text(text = "应用的全部进程会被立即终止：未保存的临时状态可能丢失，已写入磁盘的数据不受影响。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showForceStopDialog = false
                        runPm(
                            action = DetailAction.FORCE_STOP,
                            successLabel = "强制停止",
                            command = AppManagerCommands.forceStop(appSnapshot.packageName),
                        ) {
                            // 强停不改变冻结 / 隐藏态，无需本地标记
                        }
                    },
                ) { Text("强制停止") }
            },
            dismissButton = {
                TextButton(onClick = { showForceStopDialog = false }) { Text("取消") }
            },
        )
    }
}

/** 按钮内的小号执行中指示（空闲时不占位）。 */
@Composable
private fun BusyIndicator(busy: Boolean) {
    if (busy) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.size(6.dp))
    }
}

/**
 * 提取安装包：复制 sourceDir 的 APK 到应用专属目录
 * `getExternalFilesDir(null)/extracted/`（外部存储不可用时兜底 `filesDir`）。
 * 文件名 = 包名_版本（非法字符一律替换为 _，杜绝路径注入）。
 */
private fun extractApkTo(context: Context, app: AbyssAppInfo): File {
    if (app.sourceDir.isBlank()) {
        throw IllegalStateException("该应用没有可提取的安装包路径")
    }
    val source = File(app.sourceDir)
    if (!source.exists()) {
        throw IllegalStateException("源安装包不存在（应用可能已被卸载或隐藏）")
    }
    val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
    val outDir = File(baseDir, "extracted")
    if (!outDir.exists() && !outDir.mkdirs()) {
        throw IllegalStateException("无法创建导出目录：${outDir.absolutePath}")
    }
    val target = File(
        outDir,
        "${sanitizeFileName(app.packageName)}_" +
            "${sanitizeFileName(app.versionName ?: app.versionCode.toString())}.apk",
    )
    source.copyTo(target, overwrite = true)
    return target
}

/** 文件名白名单：仅保留字母 / 数字 / 点 / 下划线 / 连字符，其余替换为 _。 */
private fun sanitizeFileName(raw: String): String =
    raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
