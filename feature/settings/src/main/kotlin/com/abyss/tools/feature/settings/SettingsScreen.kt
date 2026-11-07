package com.abyss.tools.feature.settings

import android.content.Context
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abyss.tools.core.common.AppInfo
import com.abyss.tools.core.common.AbyssChannels
import com.abyss.tools.core.common.UiState
import com.abyss.tools.core.common.toUiState
import com.abyss.tools.core.designsystem.component.ChipTone
import com.abyss.tools.core.designsystem.component.InfoRow
import com.abyss.tools.core.designsystem.component.LoadingState
import com.abyss.tools.core.designsystem.component.SectionHeader
import com.abyss.tools.core.designsystem.component.StatusChip
import com.abyss.tools.core.designsystem.glass.AbyssGlassSurface
import com.abyss.tools.core.designsystem.glass.LocalGlassIntensity
import com.abyss.tools.core.update.ChangelogManifest
import com.abyss.tools.core.update.UpdateCheckResult
import com.abyss.tools.core.update.UpdateManager
import com.abyss.tools.core.update.VersionManifest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置仓储（SharedPreferences + Compose 状态直读直写）。
 * 手动 DI：app 壳构造单例注入，不引 Hilt —— 框架期保持零代码生成依赖。
 */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("abyss_settings", Context.MODE_PRIVATE)

    private var _glassIntensity by androidx.compose.runtime.mutableFloatStateOf(
        prefs.getFloat(KEY_GLASS_INTENSITY, 1f)
    )

    /** 液态玻璃强度（0.4 弱 ~ 1.6 强）：实时驱动 blur / lens 参数。 */
    var glassIntensity: Float
        get() = _glassIntensity
        set(value) {
            _glassIntensity = value.coerceIn(0.4f, 1.6f)
            prefs.edit().putFloat(KEY_GLASS_INTENSITY, _glassIntensity).apply()
        }

    private var _checkOnLaunch by mutableStateOf(prefs.getBoolean(KEY_CHECK_ON_LAUNCH, false))

    /** 启动时自动检查补丁更新。 */
    var checkOnLaunch: Boolean
        get() = _checkOnLaunch
        set(value) {
            _checkOnLaunch = value
            prefs.edit().putBoolean(KEY_CHECK_ON_LAUNCH, value).apply()
        }

    private var _lastCheckSummary by mutableStateOf(prefs.getString(KEY_LAST_CHECK, null) ?: "尚未检查")

    /** 上次检查结果摘要（人话）。 */
    var lastCheckSummary: String
        get() = _lastCheckSummary
        set(value) {
            _lastCheckSummary = value
            prefs.edit().putString(KEY_LAST_CHECK, value).apply()
        }

    private var _lastCheckAt by mutableStateOf(prefs.getLong(KEY_LAST_CHECK_AT, 0L))

    var lastCheckAt: Long
        get() = _lastCheckAt
        set(value) {
            _lastCheckAt = value
            prefs.edit().putLong(KEY_LAST_CHECK_AT, value).apply()
        }

    private var _onboardingCompleted by mutableStateOf(prefs.getBoolean(KEY_ONBOARDING_DONE, false))

    /** 首启新手引导（权限一站式）是否已完成 —— 未完成不进主界面。 */
    var onboardingCompleted: Boolean
        get() = _onboardingCompleted
        set(value) {
            _onboardingCompleted = value
            prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply()
        }

    private companion object {
        const val KEY_GLASS_INTENSITY = "glass_intensity"
        const val KEY_CHECK_ON_LAUNCH = "check_updates_on_launch"
        const val KEY_LAST_CHECK = "last_check_summary"
        const val KEY_LAST_CHECK_AT = "last_check_at"
        const val KEY_ONBOARDING_DONE = "onboarding_completed"
    }
}

private sealed interface CheckUi {
    data object Idle : CheckUi
    data object Checking : CheckUi
    data object UpToDate : CheckUi
    data class Available(
        val manifest: VersionManifest,
        val patchApplicable: Boolean,
    ) : CheckUi
    data class Failed(val message: String) : CheckUi
}

/** 自更新整包下载安装阶段。 */
private enum class SelfUpdatePhase { IDLE, DOWNLOADING, VERIFYING, INSTALLING, DONE, FAILED }

@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    updateManager: UpdateManager,
    versionName: String,
    onClearPluginCache: () -> Unit,
    bottomClearance: Dp,
    onRestartOnboarding: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var checkUi by remember { mutableStateOf<CheckUi>(CheckUi.Idle) }
    var showLicense by remember { mutableStateOf(false) }

    // ── 更新日志查看 ─────────────────────────────────────────────────
    var showChangelog by remember { mutableStateOf(false) }
    var changelogState by remember { mutableStateOf<UiState<ChangelogManifest>>(UiState.Loading) }
    val openChangelog: () -> Unit = {
        showChangelog = true
        scope.launch {
            changelogState = updateManager.fetchChangelog().toUiState()
        }
    }

    // ── 自更新整包流水线 ─────────────────────────────────────────────
    var selfPhase by remember { mutableStateOf(SelfUpdatePhase.IDLE) }
    var selfProgress by remember { mutableFloatStateOf(-1f) }
    var selfFailure by remember { mutableStateOf<String?>(null) }

    val downloadAndInstall: () -> Unit = {
        val state = checkUi as? CheckUi.Available
        val download = state?.manifest?.download
        val busy = selfPhase == SelfUpdatePhase.DOWNLOADING ||
            selfPhase == SelfUpdatePhase.VERIFYING ||
            selfPhase == SelfUpdatePhase.INSTALLING
        if (state != null && download != null && !busy) {
            selfPhase = SelfUpdatePhase.DOWNLOADING
            selfFailure = null
            scope.launch {
                updateManager.downloadApk(download) { read, total ->
                    selfProgress = if (total > 0) read.toFloat() / total else -1f
                }.onSuccess { file ->
                    selfPhase = SelfUpdatePhase.VERIFYING
                    if (!updateManager.verify(file, download.sha256)) {
                        selfPhase = SelfUpdatePhase.FAILED
                        selfFailure = "SHA-256 校验失败 —— 拒绝安装（fail-closed）"
                    } else {
                        selfPhase = SelfUpdatePhase.INSTALLING
                        val result = withContext(Dispatchers.IO) { updateManager.installApk(file) }
                        result
                            .onSuccess { selfPhase = SelfUpdatePhase.DONE }
                            .onFailure {
                                selfPhase = SelfUpdatePhase.FAILED
                                selfFailure = it.message ?: "安装失败"
                            }
                    }
                }.onFailure {
                    selfPhase = SelfUpdatePhase.FAILED
                    selfFailure = it.message ?: "下载失败"
                }
            }
        }
    }

    val checkUpdate: () -> Unit = {
        if (checkUi !is CheckUi.Checking) {
            checkUi = CheckUi.Checking
            selfPhase = SelfUpdatePhase.IDLE
            selfFailure = null
            scope.launch {
                checkUi = when (val r = updateManager.checkForUpdate(AppInfo.version)) {
                    is UpdateCheckResult.UpToDate -> CheckUi.UpToDate
                    is UpdateCheckResult.Available -> CheckUi.Available(
                        manifest = r.manifest,
                        patchApplicable = r.patchApplicable,
                    )

                    is UpdateCheckResult.Failed -> CheckUi.Failed(
                        r.error.message ?: r.error.javaClass.simpleName
                    )
                }
                settings.lastCheckAt = System.currentTimeMillis()
                settings.lastCheckSummary = when (val state = checkUi) {
                    CheckUi.UpToDate -> "已是最新（v${AppInfo.VERSION_NAME}）"
                    is CheckUi.Available -> "发现 v${state.manifest.versionName}"
                    is CheckUi.Failed -> "检查失败：${state.message}"
                    else -> "—"
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp)) {
            Spacer(Modifier.size(10.dp))
            Text(
                text = "设置",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.size(10.dp))
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
            // ── 外观：液态玻璃强度（实时生效）─────────────────────────
            item {
                SectionHeader(title = "外观", subtitle = "Material 3 · 深渊色板 · 液态玻璃")
                GlassIntensityCard(settings)
            }

            // ── 补丁更新 ─────────────────────────────────────────────
            item {
                SectionHeader(title = "补丁更新", subtitle = "双仓库模式：开发仓 → 发布仓 → 客户端")
                UpdateSectionCard(
                    checkUi = checkUi,
                    settings = settings,
                    selfPhase = selfPhase,
                    selfProgress = selfProgress,
                    selfFailure = selfFailure,
                    onCheck = checkUpdate,
                    onDownloadAndInstall = downloadAndInstall,
                    onOpenChangelog = openChangelog,
                )
            }

            // ── 插件 ─────────────────────────────────────────────────
            item {
                SectionHeader(title = "插件", subtitle = "插件中心索引 / 本地缓存")
                PluginSectionCard(onClearPluginCache = onClearPluginCache)
            }

            // ── 权限与新手引导 ──────────────────────────────────────
            if (onRestartOnboarding != null) {
                item {
                    SectionHeader(title = "权限与引导", subtitle = "Shizuku / 安装未知应用 / 可选权限一站式复查")
                    OnboardingSectionCard(onRestart = onRestartOnboarding)
                }
            }

            // ── 关于 ─────────────────────────────────────────────────
            item {
                SectionHeader(title = "关于")
                AboutCard(
                    versionName = versionName,
                    onShowLicense = { showLicense = true },
                )
            }
        }
    }

    if (showLicense) {
        AlertDialog(
            onDismissRequest = { showLicense = false },
            title = { Text("许可与免责声明") },
            text = {
                Text(
                    text = "深渊工具为受限许可（UGRL）软件：仅供个人学习与安全研究，" +
                        "在你拥有全部控制权或已获书面授权的设备上编译、运行与测试。\n\n" +
                        "禁止再分发、商业使用与未授权测试。本软件内置系统级能力（Shell 执行、" +
                        "沙箱虚拟化、设备伪装），滥用可能违反你所在司法辖区的法律 —— 由此产生" +
                        "的一切风险与合规义务由使用者独自承担。\n\n" +
                        "完整协议见仓库根目录 LICENSE。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicense = false }) { Text("我已阅读") }
            },
        )
    }

    if (showChangelog) {
        ChangelogDialog(
            state = changelogState,
            onDismiss = { showChangelog = false },
            onRetry = {
                scope.launch {
                    changelogState = UiState.Loading
                    changelogState = updateManager.fetchChangelog().toUiState()
                }
            },
        )
    }
}

@Composable
private fun GlassIntensityCard(settings: SettingsRepository) {
    AbyssGlassSurface(
        backdrop = null,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "玻璃强度",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(
                    text = String.format(Locale.ROOT, "%.1f×", LocalGlassIntensity.current),
                    tone = ChipTone.INFO,
                )
            }
            Spacer(Modifier.size(6.dp))
            Text(
                text = "实时驱动底部导航与卡片的 blur / lens 折射参数（0.4 弱 ~ 1.6 强）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            Slider(
                value = settings.glassIntensity,
                onValueChange = { settings.glassIntensity = it },
                valueRange = 0.4f..1.6f,
            )
        }
    }
}

@Composable
private fun UpdateSectionCard(
    checkUi: CheckUi,
    settings: SettingsRepository,
    selfPhase: SelfUpdatePhase,
    selfProgress: Float,
    selfFailure: String?,
    onCheck: () -> Unit,
    onDownloadAndInstall: () -> Unit,
    onOpenChangelog: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "更新渠道",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            when (checkUi) {
                CheckUi.Checking -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                CheckUi.UpToDate -> StatusChip("已是最新", ChipTone.OK)
                is CheckUi.Available -> StatusChip("有新版本", ChipTone.WARN)
                is CheckUi.Failed -> StatusChip("失败", ChipTone.ERROR)
                CheckUi.Idle -> StatusChip("待检查", ChipTone.NEUTRAL)
            }
        }
        Spacer(Modifier.size(8.dp))
        InfoRow(label = "发布仓", value = AbyssChannels.RELEASE_REPO)
        InfoRow(label = "清单", value = "version.json / patches.json / changelog.json")
        InfoRow(label = "当前版本", value = "v${AppInfo.VERSION_NAME}（${AppInfo.VERSION_CODE}）")
        val time = settings.lastCheckAt
        InfoRow(
            label = "上次检查",
            value = if (time > 0) {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(time))
            } else {
                "尚未检查"
            } + " · ${settings.lastCheckSummary}",
        )

        val available = checkUi as? CheckUi.Available
        val detail = when {
            selfPhase != SelfUpdatePhase.IDLE && selfPhase != SelfUpdatePhase.FAILED -> null
            checkUi is CheckUi.Checking -> "正在读取发布仓 version.json…"
            checkUi is CheckUi.UpToDate -> "当前已是通道最新版本。"
            available != null -> buildString {
                append("发现 v")
                append(available.manifest.versionName)
                append("：")
                append(available.manifest.notes ?: "（未附说明）")
                append(" · ")
                append(
                    if (available.patchApplicable) "支持增量补丁升级"
                    else "需整包安装"
                )
            }

            checkUi is CheckUi.Failed ->
                "检查失败：${(checkUi as CheckUi.Failed).message}（首个 APK 发布前，发布仓仅含清单骨架 —— 属预期状态）"

            else -> null
        }
        if (detail != null) {
            Spacer(Modifier.size(8.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── 自更新整包流水线（下载 → 校验 → PackageInstaller 安装）────
        if (available != null) {
            Spacer(Modifier.size(10.dp))
            SelfUpdateUi(
                phase = selfPhase,
                progress = selfProgress,
                failure = selfFailure,
                downloadUrl = available.manifest.download?.url,
                onInstall = onDownloadAndInstall,
            )
        }

        Spacer(Modifier.size(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onCheck, enabled = checkUi !is CheckUi.Checking) {
                Text("检查补丁更新")
            }
            Spacer(Modifier.size(10.dp))
            OutlinedButton(onClick = onOpenChangelog) { Text("更新日志") }
        }
        Spacer(Modifier.size(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "启动时自动检查",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = settings.checkOnLaunch,
                onCheckedChange = { settings.checkOnLaunch = it },
            )
        }
    }
}

@Composable
private fun SelfUpdateUi(
    phase: SelfUpdatePhase,
    progress: Float,
    failure: String?,
    downloadUrl: String?,
    onInstall: () -> Unit,
) {
    Column {
        when (phase) {
            SelfUpdatePhase.IDLE -> {
                if (downloadUrl != null) {
                    Button(onClick = onInstall) { Text("下载并安装整包") }
                } else {
                    Text(
                        text = "通道尚未发布 APK 资产（download 为空）—— v0.2 发布流水线起提供整包下载。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SelfUpdatePhase.DOWNLOADING -> {
                Text("下载整包…", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.size(6.dp))
                if (progress >= 0f) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            SelfUpdatePhase.VERIFYING -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("SHA-256 校验中…", style = MaterialTheme.typography.bodySmall)
            }

            SelfUpdatePhase.INSTALLING -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("提交系统安装器（确认页如弹出请允许）…", style = MaterialTheme.typography.bodySmall)
            }

            SelfUpdatePhase.DONE -> Text(
                "安装完成 —— 重启后生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )

            SelfUpdatePhase.FAILED -> Column {
                Text(
                    text = failure ?: "安装失败",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.size(8.dp))
                if (downloadUrl != null) {
                    Button(onClick = onInstall) { Text("重试下载并安装") }
                }
            }
        }
    }
}

/** 更新日志弹层：latest + history 全量条目（标题 / 日期 / 要点）。 */
@Composable
private fun ChangelogDialog(
    state: UiState<ChangelogManifest>,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更新日志") },
        text = {
            when (state) {
                is UiState.Loading -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
                    LoadingState("正在读取发布仓 changelog.json…")
                }

                is UiState.Error -> Column {
                    Text(
                        text = "更新日志拉取失败：${state.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.size(8.dp))
                    TextButton(onClick = onRetry) { Text("重试") }
                }

                is UiState.Data -> Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (state.value.entries.isEmpty()) {
                        Text(
                            "通道暂无更新日志条目。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.value.entries.forEachIndexed { index, entry ->
                        if (index > 0) {
                            Spacer(Modifier.size(10.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(Modifier.size(10.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "v${entry.versionName}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            entry.date?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        entry.title?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        entry.notes.forEach { note ->
                            Text(
                                text = "· $note",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun OnboardingSectionCard(onRestart: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(16.dp),
    ) {
        InfoRow(label = "Shizuku", value = "系统级功能钥匙（伪装四件套 / 提权指令）")
        InfoRow(label = "安装未知应用", value = "插件安装 / App 自更新")
        InfoRow(label = "可选权限", value = "定位基准（伪装定位）/ 通知")
        Spacer(Modifier.size(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.size(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "换机 / 授权失效 / 新增权限？重跑一遍新手引导即可",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onRestart) { Text("重新运行") }
        }
    }
}

@Composable
private fun PluginSectionCard(onClearPluginCache: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(16.dp),
    ) {
        InfoRow(label = "索引源", value = AbyssChannels.PLUGIN_HUB_REPO)
        InfoRow(label = "补丁引擎", value = "abyss-patch-v1 · 备份 + 回滚 + SHA-256")
        InfoRow(label = "插件目录", value = "filesDir/plugins/<id>/plugin.json")
        Spacer(Modifier.size(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.size(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "本地插件缓存（下载中的补丁 / 安装包）",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onClearPluginCache) { Text("清除") }
        }
    }
}

@Composable
private fun AboutCard(versionName: String, onShowLicense: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(16.dp),
    ) {
        Text(
            text = "深渊工具 Abyss Tools",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "面向极客的系统级超级工具箱 —— Shizuku / 沙箱 / 终端 / 脚本引擎的模块化终端。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        InfoRow(label = "版本", value = "v$versionName（框架预览）")
        InfoRow(label = "开发仓库", value = AbyssChannels.DEV_REPO)
        InfoRow(label = "发布仓库", value = AbyssChannels.RELEASE_REPO)
        InfoRow(label = "插件中心", value = AbyssChannels.PLUGIN_HUB_REPO)
        Spacer(Modifier.size(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.size(10.dp))
        TextButton(onClick = onShowLicense) { Text("许可与免责声明（UGRL）") }
    }
}
