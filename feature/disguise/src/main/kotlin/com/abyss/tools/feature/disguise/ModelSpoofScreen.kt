package com.abyss.tools.feature.disguise

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
 * 机型伪装 —— 三级程度 × 26 款档案，边界明示：
 *
 *  ✅ L1 轻度（即刻生效）：系统设备名（Settings.Global.DEVICE_NAME）——
 *     Shizuku 写入，部分系统界面（快速分享 / 投放 / 关于手机）真实读取；
 *  ✅ L2 标准（档案层）：L1 + Build 指纹档案 → 导出 JSON
 *     （abyss-model-profile-v2），由沙箱双开插件的 Hook 层在虚拟空间内
 *     对目标 App 生效（Build.MODEL / BRAND / FINGERPRINT…）；
 *  ✅ L3 深度：L2 + 全量 Build 字段（BOOTLOADER / HARDWARE / BOARD /
 *     RADIO / ID / TAGS / INCREMENTAL / ABIS…）+ 蓝牙名同步改写 ——
 *     对抗读取多字段做设备指纹的风控 SDK。
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

    var level by remember { mutableStateOf(settings.modelSpoofLevel) }
    var vendorFilter by remember { mutableStateOf<Vendor?>(null) }
    var selectedProfileId by remember { mutableStateOf(settings.modelProfileId) }
    var deviceName by remember { mutableStateOf(settings.deviceName) }
    var bluetoothName by remember { mutableStateOf(settings.deviceName) }
    var customModel by remember { mutableStateOf(settings.customModel) }
    var customBrand by remember { mutableStateOf(settings.customBrand) }
    var customFingerprint by remember { mutableStateOf(settings.customFingerprint) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var feedbackError by remember { mutableStateOf(false) }
    var feedbackDetail by remember { mutableStateOf<String?>(null) }
    var exportedPath by remember { mutableStateOf<String?>(null) }

    /** 当前生效档案（预置优先，其次自定义字段）。 */
    fun effectiveProfile(): ModelProfile? {
        DeviceProfiles.byId(selectedProfileId ?: "")?.let { return it }
        if (customModel.isBlank()) return null
        return ModelProfile(
            id = "custom",
            name = customModel,
            brand = customBrand.ifBlank { "custom" },
            manufacturer = customBrand.ifBlank { "custom" },
            model = customModel,
            device = "custom",
            product = "custom",
            fingerprint = customFingerprint.ifBlank { "custom/build/build:15/AB:1/user/release-keys" },
        )
    }

    /** 依档位执行动作序列（命令 + 动作标签配对，逐条 fail-fast）。 */
    fun applySpoof() {
        val profile = effectiveProfile()
        if (profile == null) {
            feedbackError = true
            feedback = "先选择预置档案，或填写自定义型号"
            feedbackDetail = null
            return
        }
        val name = deviceName.ifBlank { profile.name }
        val commands = buildList {
            add("settings:设备名" to DeviceIdentityCommands.setDeviceName(name))
            if (level == SpoofLevel.DEEP) {
                add("settings:蓝牙名" to DeviceIdentityCommands.setBluetoothName(bluetoothName.ifBlank { name }))
            }
        }
        if (busy) return
        busy = true
        scope.launch {
            var ok = true
            var detail = ""
            for ((_, command) in commands) {
                gateway.exec(command)
                    .onSuccess { r ->
                        if (!r.isSuccess) {
                            ok = false
                            detail = r.stderr.ifBlank { r.stdout }.trim()
                        }
                    }
                    .onFailure { e ->
                        ok = false
                        detail = e.message ?: e.javaClass.simpleName
                    }
                if (!ok) break
            }
            if (ok) {
                // 档案导出（L2 / L3）：沙箱 Hook 层消费
                var exportNote = ""
                if (level != SpoofLevel.NAME_ONLY) {
                    val json = DeviceProfiles.exportJson(profile, level)
                    val dest = File(context.filesDir, "disguise-model-profile.json")
                    runCatching {
                        dest.parentFile?.mkdirs()
                        dest.writeText(json)
                    }.onSuccess {
                        exportedPath = dest.absolutePath
                        exportNote = " · 档案已导出（${if (level == SpoofLevel.DEEP) "全字段" else "标准"}）"
                    }
                } else {
                    File(context.filesDir, "disguise-model-profile.json").delete()
                    exportedPath = null
                }
                settings.modelSpoofLevel = level
                settings.modelProfileId = selectedProfileId
                settings.deviceName = name
                feedbackError = false
                feedback = "机型伪装已应用（L${level.ordinal + 1} ${level.label}）：$name$exportNote"
                feedbackDetail = exportedPath
            } else {
                feedbackError = true
                feedback = "应用失败（Shizuku 未就绪或系统拒绝）"
                feedbackDetail = detail.ifBlank { null }
            }
            busy = false
        }
    }

    /** 恢复真实身份：删除全部覆写项 + 清掉导出档案。 */
    fun resetSpoof() {
        if (busy) return
        busy = true
        scope.launch {
            val commands = listOf(
                DeviceIdentityCommands.resetDeviceName(),
                DeviceIdentityCommands.resetBluetoothName(),
            )
            var ok = true
            var detail = ""
            for (command in commands) {
                gateway.exec(command)
                    .onSuccess { r ->
                        if (!r.isSuccess) {
                            ok = false
                            detail = r.stderr.ifBlank { r.stdout }.trim()
                        }
                    }
                    .onFailure { e ->
                        ok = false
                        detail = e.message ?: e.javaClass.simpleName
                    }
            }
            File(context.filesDir, "disguise-model-profile.json").delete()
            exportedPath = null
            feedbackError = !ok
            feedback = if (ok) {
                "已恢复真实身份（设备名 / 蓝牙名覆写项已删除，档案已清理）"
            } else {
                "恢复失败（部分命令被系统拒绝）"
            }
            feedbackDetail = detail.ifBlank { null }
            busy = false
        }
    }

    val visibleProfiles = remember(vendorFilter) {
        vendorFilter?.let { f -> DeviceProfiles.presets.filter { it.vendor == f } }
            ?: DeviceProfiles.presets
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        SpoofHeader(title = "机型伪装", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
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

            // ── 伪装程度 ───────────────────────────────────────────────
            item {
                SectionHeader(
                    title = "伪装程度",
                    subtitle = "决定应用到系统的深度 —— 档位越高，对抗的指纹读取越全",
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(16.dp),
                ) {
                    SpoofLevel.entries.forEach { l ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp),
                        ) {
                            SelectableChip(
                                text = "L${l.ordinal + 1} ${l.label}",
                                selected = level == l,
                                onClick = { level = l },
                            )
                            Spacer(Modifier.size(10.dp))
                            Text(
                                text = l.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                    when (level) {
                        SpoofLevel.NAME_ONLY -> OutlinedTextField(
                            value = deviceName,
                            onValueChange = { deviceName = it },
                            label = { Text("系统设备名（如 Xiaomi 15 Ultra）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SpoofLevel.STANDARD -> OutlinedTextField(
                            value = deviceName,
                            onValueChange = { deviceName = it },
                            label = { Text("系统设备名（留空 = 档案名）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SpoofLevel.DEEP -> {
                            OutlinedTextField(
                                value = deviceName,
                                onValueChange = { deviceName = it },
                                label = { Text("系统设备名（留空 = 档案名）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.size(6.dp))
                            OutlinedTextField(
                                value = bluetoothName,
                                onValueChange = { bluetoothName = it },
                                label = { Text("蓝牙名（留空 = 同设备名）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            // ── 机型档案库 ─────────────────────────────────────────────
            item {
                SectionHeader(
                    title = "机型档案库（${DeviceProfiles.presets.size} 款）",
                    subtitle = "选中档案 → 按伪装程度一键应用（沙箱 Hook 层消费导出档案）",
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(16.dp),
                ) {
                    // 厂商过滤
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(1 + Vendor.entries.size) { index ->
                            if (index == 0) {
                                SelectableChip(
                                    text = "全部",
                                    selected = vendorFilter == null,
                                    onClick = { vendorFilter = null },
                                )
                            } else {
                                val vendor = Vendor.entries[index - 1]
                                SelectableChip(
                                    text = vendor.label,
                                    selected = vendorFilter == vendor,
                                    onClick = { vendorFilter = vendor },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.size(10.dp))
                    visibleProfiles.chunked(3).forEach { rowProfiles ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 3.dp),
                        ) {
                            rowProfiles.forEach { profile ->
                                SelectableChip(
                                    text = profile.name,
                                    selected = selectedProfileId == profile.id,
                                    onClick = {
                                        selectedProfileId =
                                            if (selectedProfileId == profile.id) null else profile.id
                                        if (deviceName.isBlank()) deviceName = profile.name
                                        if (bluetoothName.isBlank()) bluetoothName = profile.name
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            // 补位（chunked 尾行不满 3 个时对齐）
                            repeat(3 - rowProfiles.size) { Spacer(Modifier.weight(1f)) }
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
                        label = { Text("自定义型号（如 Pixel 10 Pro）") },
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
                }
            }

            // ── 动作 ───────────────────────────────────────────────────
            item { ExecFeedback(feedback, feedbackError, feedbackDetail) }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { applySpoof() },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                        }
                        Text("应用伪装（L${level.ordinal + 1}）")
                    }
                    OutlinedButton(
                        onClick = { resetSpoof() },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("恢复真实身份") }
                }
            }

            // ── 能力边界 ───────────────────────────────────────────────
            item {
                SectionHeader(title = "能力矩阵（诚实边界）", subtitle = null)
                InfoCard(
                    title = "✅ L1 即刻生效 · 系统设备名",
                    body = "经 Shizuku 写入全局设置项 DEVICE_NAME：快速分享、" +
                        "无线投送、部分 ROM 的关于手机界面会真实显示新名字。",
                )
                InfoCard(
                    title = "✅ L2 档案层 · 沙箱 Hook 消费",
                    body = "预置 ${DeviceProfiles.presets.size} 款旗舰档案 + 自定义字段，导出 JSON 由沙箱双开插件" +
                        "（VirtualApp 血统虚拟空间）的 Hook 层读取：虚拟空间内的目标 App" +
                        "读到全新 Build.MODEL / BRAND / FINGERPRINT —— 应用级伪装的正路。",
                )
                InfoCard(
                    title = "✅ L3 深度 · 全字段指纹 + 蓝牙名",
                    body = "档案扩展 BOOTLOADER / HARDWARE / BOARD / RADIO / ID / TAGS / " +
                        "VERSION.INCREMENTAL / SUPPORTED_ABIS 全量字段（对抗多字段风控指纹 SDK），" +
                        "同时改写蓝牙显示名（Settings.Secure.BLUETOOTH_NAME —— 部分 ROM 需重启蓝牙生效）。",
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
