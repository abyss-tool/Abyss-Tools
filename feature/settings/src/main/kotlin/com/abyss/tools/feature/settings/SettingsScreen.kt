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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.abyss.tools.core.designsystem.component.ChipTone
import com.abyss.tools.core.designsystem.component.InfoRow
import com.abyss.tools.core.designsystem.component.SectionHeader
import com.abyss.tools.core.designsystem.component.StatusChip
import com.abyss.tools.core.designsystem.glass.AbyssGlassSurface
import com.abyss.tools.core.designsystem.glass.LocalGlassIntensity
import com.abyss.tools.core.update.UpdateCheckResult
import com.abyss.tools.core.update.UpdateManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

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

    private companion object {
        const val KEY_GLASS_INTENSITY = "glass_intensity"
        const val KEY_CHECK_ON_LAUNCH = "check_updates_on_launch"
        const val KEY_LAST_CHECK = "last_check_summary"
        const val KEY_LAST_CHECK_AT = "last_check_at"
    }
}

private sealed interface CheckUi {
    data object Idle : CheckUi
    data object Checking : CheckUi
    data object UpToDate : CheckUi
    data class Available(val version: String, val notes: String?, val patchApplicable: Boolean) : CheckUi
    data class Failed(val message: String) : CheckUi
}

@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    updateManager: UpdateManager,
    versionName: String,
    onClearPluginCache: () -> Unit,
    bottomClearance: Dp,
) {
    val scope = rememberCoroutineScope()
    var checkUi by remember { mutableStateOf<CheckUi>(CheckUi.Idle) }
    var showLicense by remember { mutableStateOf(false) }

    val checkUpdate: () -> Unit = {
        if (checkUi !is CheckUi.Checking) {
            checkUi = CheckUi.Checking
            scope.launch {
                checkUi = when (val r = updateManager.checkForUpdate(AppInfo.version)) {
                    is UpdateCheckResult.UpToDate -> CheckUi.UpToDate
                    is UpdateCheckResult.Available -> CheckUi.Available(
                        r.manifest.versionName, r.manifest.notes, r.patchApplicable
                    )

                    is UpdateCheckResult.Failed -> CheckUi.Failed(
                        r.error.message ?: r.error.javaClass.simpleName
                    )
                }
                settings.lastCheckAt = System.currentTimeMillis()
                settings.lastCheckSummary = when (checkUi) {
                    CheckUi.UpToDate -> "已是最新（v${AppInfo.VERSION_NAME}）"
                    is CheckUi.Available -> "发现 v${checkUi.version}"
                    is CheckUi.Failed -> "检查失败：${checkUi.message}"
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
                    onCheck = checkUpdate,
                )
            }

            // ── 插件 ─────────────────────────────────────────────────
            item {
                SectionHeader(title = "插件", subtitle = "插件中心索引 / 本地缓存")
                PluginSectionCard(onClearPluginCache = onClearPluginCache)
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
    onCheck: () -> Unit,
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
        InfoRow(label = "当前版本", value = "v$versionName(v${AppInfo.VERSION_CODE})")
        val time = settings.lastCheckAt
        InfoRow(
            label = "上次检查",
            value = if (time > 0) {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(time))
            } else {
                "尚未检查"
            } + " · ${settings.lastCheckSummary}",
        )

        val detail = when (checkUi) {
            CheckUi.Idle -> null
            CheckUi.Checking -> "正在读取发布仓 version.json…"
            CheckUi.UpToDate -> "当前已是通道最新版本。"
            is CheckUi.Available -> "发现 v${checkUi.version}：${checkUi.notes ?: "（未附说明）"}" +
                if (checkUi.patchApplicable) " · 支持增量补丁升级" else " · 需整包安装"

            is CheckUi.Failed -> "检查失败：${checkUi.message}（首个 APK 发布前，发布仓仅含清单骨架 —— 属预期状态）"
        }
        if (detail != null) {
            Spacer(Modifier.size(8.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onCheck, enabled = checkUi !is CheckUi.Checking) {
                Text("检查补丁更新")
            }
            Spacer(Modifier.size(10.dp))
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
