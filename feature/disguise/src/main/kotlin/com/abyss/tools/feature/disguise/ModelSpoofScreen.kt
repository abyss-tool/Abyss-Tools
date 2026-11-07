package com.abyss.tools.feature.disguise

import android.os.Build
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abyss.tools.core.designsystem.component.SectionHeader
import com.abyss.tools.core.shell.DeviceIdentityCommands
import com.abyss.tools.core.shell.ShellGateway
import com.abyss.tools.core.shell.ShizukuStatus
import java.io.File
import kotlinx.coroutines.launch

/**
 * 机型伪装 —— 两层能力，边界明示：
 *
 *  ✅ 第一层（即刻生效）：系统设备名（Settings.Global.DEVICE_NAME）——
 *     Shizuku 写入，部分系统界面（快速分享 / 投放 / 关于手机）真实读取；
 *  ✅ 第二层（档案层）：机型档案库 + 自定义档案 → 导出 JSON
 *     （abyss-model-profile-v1），由沙箱双开插件的 Hook 层在虚拟空间内
 *     对目标 App 生效（Build.MODEL / BRAND / FINGERPRINT…）。
 *
 *  不装能：无 Root 无法全局改 ro.product.*（系统属性只读）—— 这是
 *  Android 的硬边界，应用级伪装只能走虚拟空间 Hook。
 */
@Composable
fun ModelSpoofScreen(
    shizukuStatus: ShizukuStatus,
    gateway: ShellGateway,
    settings: DisguiseSettings,
    onRequestShizukuPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedProfileId by remember { mutableStateOf(settings.modelProfileId) }
    var deviceName by remember { mutableStateOf(settings.deviceName) }
    var customModel by remember { mutableStateOf(settings.customModel) }
    var customBrand by remember { mutableStateOf(settings.customBrand) }
    var customFingerprint by remember { mutableStateOf(settings.customFingerprint) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var feedbackError by remember { mutableStateOf(false) }
    var feedbackDetail by remember { mutableStateOf<String?>(null) }
    var exportedPath by remember { mutableStateOf<String?>(null) }

    fun execThen(label: String, command: String, onOk: (String) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            gateway.exec(command)
                .onSuccess { result ->
                    if (result.isSuccess) {
                        onOk(result.stdout.trim())
                        feedbackError = false
                        feedback = "$label 成功"
                        feedbackDetail = result.stdout.trim().takeIf { it.isNotBlank() }
                    } else {
                        feedbackError = true
                        feedback = "$label 被系统拒绝"
                        feedbackDetail = result.stderr.ifBlank { result.stdout }.trim().takeIf { it.isNotBlank() }
                    }
                }
                .onFailure { e ->
                    feedbackError = true
                    feedback = "$label 失败：${e.message ?: e.javaClass.simpleName}"
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
        SpoofHeader(title = "机型伪装", onBack = onBack)
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

            // ── 真机信息 ───────────────────────────────────────────────
            item {
                KeyValueCard(
                    title = "真机信息（伪装基线）",
                    rows = listOf(
                        "品牌 BRAND" to Build.BRAND,
                        "厂商 MANUFACTURER" to Build.MANUFACTURER,
                        "型号 MODEL" to Build.MODEL,
                        "设备 DEVICE" to Build.DEVICE,
                        "产品 PRODUCT" to Build.PRODUCT,
                        "指纹 FINGERPRINT" to Build.FINGERPRINT,
                        "系统版本" to "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
                    ),
                )
            }

            // ── 第一层：系统设备名 ─────────────────────────────────────
            item {
                SectionHeader(
                    title = "系统设备名（即刻生效）",
                    subtitle = "Settings.Global.DEVICE_NAME —— 快速分享 / 投放 / 部分系统界面读取",
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(16.dp),
                ) {
                    OutlinedTextField(
                        value = deviceName,
                        onValueChange = { deviceName = it },
                        label = { Text("设备名（如 Xiaomi 14 Ultra）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                if (deviceName.isBlank()) {
                                    feedbackError = true
                                    feedback = "设备名不能为空"
                                    feedbackDetail = null
                                } else {
                                    execThen("写入设备名", DeviceIdentityCommands.setDeviceName(deviceName)) {
                                        settings.deviceName = deviceName
                                    }
                                }
                            },
                            enabled = !busy,
                        ) { Text("写入系统") }
                        OutlinedButton(
                            onClick = {
                                execThen("读取当前设备名", DeviceIdentityCommands.readDeviceName()) { name ->
                                    if (name != "null") deviceName = name
                                }
                            },
                            enabled = !busy,
                        ) { Text("读取当前") }
                    }
                }
            }

            // ── 第二层：机型档案库 ─────────────────────────────────────
            item {
                SectionHeader(
                    title = "机型档案库（沙箱 Hook 层消费）",
                    subtitle = "选中档案 → 导出 JSON → 沙箱双开插件在虚拟空间内对目标 App 生效",
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(16.dp),
                ) {
                    DeviceProfiles.presets.chunked(3).forEach { rowProfiles ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 3.dp),
                        ) {
                            rowProfiles.forEach { profile ->
                                SelectableChip(
                                    text = profile.name,
                                    selected = selectedProfileId == profile.id,
                                    onClick = {
                                        selectedProfileId = if (selectedProfileId == profile.id) null else profile.id
                                        settings.modelProfileId = selectedProfileId
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = "自定义档案（不选预置时生效）：",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.size(6.dp))
                    OutlinedTextField(
                        value = customModel,
                        onValueChange = {
                            customModel = it
                            settings.customModel = it
                        },
                        label = { Text("自定义型号（如 Pixel 9 Pro）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(6.dp))
                    OutlinedTextField(
                        value = customBrand,
                        onValueChange = {
                            customBrand = it
                            settings.customBrand = it
                        },
                        label = { Text("自定义品牌（如 google）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(6.dp))
                    OutlinedTextField(
                        value = customFingerprint,
                        onValueChange = {
                            customFingerprint = it
                            settings.customFingerprint = it
                        },
                        label = { Text("自定义构建指纹 FINGERPRINT") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                val custom = customModel.isNotBlank()
                                val profile = DeviceProfiles.byId(selectedProfileId ?: "")
                                if (profile == null && !custom) {
                                    feedbackError = true
                                    feedback = "先选择预置档案，或填写自定义型号"
                                    feedbackDetail = null
                                    return@Button
                                }
                                val effective = profile ?: ModelProfile(
                                    id = "custom",
                                    name = customModel,
                                    brand = customBrand.ifBlank { "custom" },
                                    manufacturer = customBrand.ifBlank { "custom" },
                                    model = customModel,
                                    device = "custom",
                                    product = "custom",
                                    fingerprint = customFingerprint.ifBlank { "custom/build/build:15/AB:1/user/release-keys" },
                                )
                                val json = DeviceProfiles.exportJson(effective)
                                val dest = File(context.filesDir, "disguise-model-profile.json")
                                runCatching {
                                    dest.parentFile?.mkdirs()
                                    dest.writeText(json)
                                }.onSuccess {
                                    exportedPath = dest.absolutePath
                                    feedbackError = false
                                    feedback = "档案已导出（abyss-model-profile-v1）"
                                    feedbackDetail = dest.absolutePath
                                }.onFailure { e ->
                                    feedbackError = true
                                    feedback = "导出失败：${e.message ?: e.javaClass.simpleName}"
                                    feedbackDetail = null
                                }
                            },
                            enabled = !busy,
                        ) { Text("导出当前档案") }
                        if (busy) {
                            Spacer(Modifier.size(10.dp))
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }
                    if (exportedPath != null) {
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = exportedPath!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            item { ExecFeedback(feedback, feedbackError, feedbackDetail) }

            // ── 能力边界 ───────────────────────────────────────────────
            item {
                SectionHeader(title = "能力矩阵（诚实边界）", subtitle = null)
                InfoCard(
                    title = "✅ 即刻生效 · 系统设备名",
                    body = "经 Shizuku 写入全局设置项 DEVICE_NAME：快速分享、" +
                        "无线投送、部分 ROM 的关于手机界面会真实显示新名字。",
                )
                InfoCard(
                    title = "✅ 档案层 · 沙箱 Hook 消费",
                    body = "预置十款旗舰档案 + 自定义字段，导出 JSON 由沙箱双开插件" +
                        "（VirtualApp 血统虚拟空间）的 Hook 层读取：虚拟空间内的目标 App" +
                        "读到全新 Build.MODEL / BRAND / FINGERPRINT —— 应用级伪装的正路。",
                )
                InfoCard(
                    title = "⛔ 硬边界 · 全局 Build 伪装",
                    body = "无 Root 无法改 ro.product.*（系统属性只读，Android 硬边界）。" +
                        "宣称「免 Root 全局改机型」的工具都做不到系统级 —— 别被骗。",
                )
            }
        }
    }
}
