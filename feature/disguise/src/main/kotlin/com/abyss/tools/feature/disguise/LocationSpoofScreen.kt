package com.abyss.tools.feature.disguise

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.content.ContextCompat
import com.abyss.tools.core.designsystem.component.SectionHeader
import com.abyss.tools.core.shell.MockLocationCommands
import com.abyss.tools.core.shell.ShellGateway
import com.abyss.tools.core.shell.ShizukuStatus
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 定位伪装 —— Fake GPS 官方通道（免 Root，全系统生效）。
 *
 * 链路：Shizuku 授权本 App 为模拟位置源（appops mock_location）→
 * 前台服务对 gps / network / fused 三源注入测试坐标 → 任何读取定位
 * 的 App（地图 / 打卡 / 网约车）都拿到伪装位置。
 *
 * 完善能力：
 *  - 移动模式：静止 / 微漂移 / 步行 / 骑行 / 驾车（轨迹 + 速度 +
 *    航向 + 精度自然化，对抗轨迹风控）；
 *  - 漂移幅度：±1 / ±3 / ±10 米三档；
 *  - 坐标收藏夹：保存常用点位（如打卡点），一键带入。
 */
private data class CityPreset(val name: String, val lat: Double, val lng: Double)

private val cityPresets = listOf(
    CityPreset("北京", 39.9042, 116.4074),
    CityPreset("上海", 31.2304, 121.4737),
    CityPreset("广州", 23.1291, 113.2644),
    CityPreset("深圳", 22.5431, 114.0579),
    CityPreset("杭州", 30.2741, 120.1551),
    CityPreset("成都", 30.5728, 104.0668),
    CityPreset("重庆", 29.5630, 106.5516),
    CityPreset("武汉", 30.5928, 114.3055),
    CityPreset("西安", 34.3416, 108.9398),
    CityPreset("南京", 32.0603, 118.7969),
    CityPreset("香港", 22.3193, 114.1694),
    CityPreset("台北", 25.0330, 121.5654),
    CityPreset("拉萨", 29.6520, 91.1721),
    CityPreset("乌鲁木齐", 43.8256, 87.6168),
    CityPreset("哈尔滨", 45.8038, 126.5349),
    CityPreset("纽约", 40.7128, -74.0060),
    CityPreset("旧金山", 37.7749, -122.4194),
    CityPreset("东京", 35.6762, 139.6503),
    CityPreset("首尔", 37.5665, 126.9780),
    CityPreset("新加坡", 1.3521, 103.8198),
    CityPreset("悉尼", -33.8688, 151.2093),
    CityPreset("伦敦", 51.5074, -0.1278),
    CityPreset("巴黎", 48.8566, 2.3522),
    CityPreset("柏林", 52.5200, 13.4050),
    CityPreset("莫斯科", 55.7558, 37.6173),
    CityPreset("迪拜", 25.2048, 55.2708),
)

/** 漂移幅度三档（米）。 */
private val driftOptions = listOf(1, 3, 10)

@Composable
fun LocationSpoofScreen(
    shizukuStatus: ShizukuStatus,
    gateway: ShellGateway,
    settings: DisguiseSettings,
    onRequestShizukuPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var appopAllowed by remember { mutableStateOf<Boolean?>(null) }
    var spoofing by remember { mutableStateOf(false) }
    var serviceError by remember { mutableStateOf<String?>(null) }
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var feedbackError by remember { mutableStateOf(false) }
    var feedbackDetail by remember { mutableStateOf<String?>(null) }
    var spotName by remember { mutableStateOf(settings.locationLabel) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        hasLocationPermission = grants.values.any { it }
    }

    /** 进入页面：查 appop 真实状态（Shizuku 可用时）。 */
    LaunchedEffect(Unit) {
        gateway.exec(MockLocationCommands.query(context.packageName))
            .onSuccess { result ->
                if (result.isSuccess) {
                    appopAllowed = MockLocationCommands.parseAllowed(result.stdout)
                }
            }
    }

    /** 运行态轮询（前台服务的 volatile 快照 → Compose 状态）。 */
    LaunchedEffect(Unit) {
        while (isActive) {
            spoofing = LocationSpoofService.isSpoofing
            serviceError = LocationSpoofService.lastError
            if (serviceError != null) {
                feedbackError = true
                feedback = serviceError
                feedbackDetail = null
            }
            delay(800)
        }
    }

    fun execThen(label: String, command: String, onOk: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            gateway.exec(command)
                .onSuccess { result ->
                    if (result.isSuccess) {
                        onOk()
                        feedbackError = false
                        feedback = label
                        feedbackDetail = null
                    } else {
                        feedbackError = true
                        feedback = "命令被系统拒绝"
                        feedbackDetail = result.stderr.ifBlank { result.stdout }.trim().takeIf { it.isNotBlank() }
                    }
                }
                .onFailure { e ->
                    feedbackError = true
                    feedback = e.message ?: e.javaClass.simpleName
                    feedbackDetail = null
                }
            busy = false
        }
    }

    fun parseCoordinates(): Pair<Double, Double>? {
        val lat = settings.latText.trim().toDoubleOrNull()
        val lng = settings.lngText.trim().toDoubleOrNull()
        if (lat == null || lat !in -90.0..90.0) {
            feedbackError = true
            feedback = "纬度必须是 -90 ~ 90 之间的数字"
            feedbackDetail = null
            return null
        }
        if (lng == null || lng !in -180.0..180.0) {
            feedbackError = true
            feedback = "经度必须是 -180 ~ 180 之间的数字"
            feedbackDetail = null
            return null
        }
        return lat to lng
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        SpoofHeader(title = "定位伪装", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ShizukuGuardBanner(shizukuStatus, onRequestShizukuPermission, onOpenShizuku) }

            // ── 引擎状态卡 ─────────────────────────────────────────────
            item {
                KeyValueCard(
                    title = "引擎状态",
                    rows = listOf(
                        "模拟位置资格" to when (appopAllowed) {
                            true -> "已授权（本 App 是合法模拟位置源）"
                            false -> "未授权（需 Shizuku 一键授予）"
                            null -> "未知（Shizuku 就绪后可查询）"
                        },
                        "注入引擎" to if (spoofing) "运行中 · 每秒供给伪装坐标" else "未运行",
                        "当前模式" to if (spoofing) {
                            LocationSpoofService.movementMode.let { m ->
                                m.label + if (m == MovementMode.JITTER) "（±${LocationSpoofService.driftMeters}米）" else ""
                            }
                        } else {
                            "—"
                        },
                        "注入源" to if (spoofing) "gps / network / fused 三源" else "—",
                    ),
                )
            }

            // ── 坐标输入 ───────────────────────────────────────────────
            item {
                SectionHeader(
                    title = "伪装坐标",
                    subtitle = "支持粘贴「纬度, 经度」（如 39.9042, 116.4074）",
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(16.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = settings.latText,
                            onValueChange = { settings.latText = it },
                            label = { Text("纬度") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = settings.lngText,
                            onValueChange = { settings.lngText = it },
                            label = { Text("经度") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(
                        value = spotName,
                        onValueChange = {
                            spotName = it
                            settings.locationLabel = it
                        },
                        label = { Text("位置备注（如「公司打卡点」，收藏时用作名称）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(text = "常用城市（${cityPresets.size} 个）", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.size(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(cityPresets.size) { index ->
                            val city = cityPresets[index]
                            SelectableChip(
                                text = city.name,
                                selected = false,
                                onClick = {
                                    settings.latText = String.format(Locale.US, "%.6f", city.lat)
                                    settings.lngText = String.format(Locale.US, "%.6f", city.lng)
                                    spotName = city.name
                                    settings.locationLabel = city.name
                                },
                            )
                        }
                    }
                    Spacer(Modifier.size(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                if (!hasLocationPermission) {
                                    permissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION,
                                        ),
                                    )
                                } else {
                                    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                                    val providers = runCatching { lm.getProviders(true) }
                                        .getOrDefault(emptyList())
                                    val lastLocation = providers.firstNotNullOfOrNull { provider ->
                                        runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
                                    }
                                    if (lastLocation != null) {
                                        settings.latText = String.format(Locale.US, "%.6f", lastLocation.latitude)
                                        settings.lngText = String.format(Locale.US, "%.6f", lastLocation.longitude)
                                        feedbackError = false
                                        feedback = "已带入当前位置为基准坐标"
                                        feedbackDetail = null
                                    } else {
                                        feedbackError = true
                                        feedback = "暂无已知位置（打开系统地图 App 刷一次定位再试）"
                                        feedbackDetail = null
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text(if (hasLocationPermission) "以当前位置为基准" else "授权定位并带入当前") }
                        OutlinedButton(
                            onClick = {
                                val coords = parseCoordinates() ?: return@OutlinedButton
                                if (spotName.isBlank()) {
                                    feedbackError = true
                                    feedback = "先填位置备注作为收藏名（如「公司打卡点」）"
                                    feedbackDetail = null
                                    return@OutlinedButton
                                }
                                settings.savedSpots = settings.savedSpots +
                                    SavedSpot(spotName.trim(), coords.first, coords.second)
                                feedbackError = false
                                feedback = "已收藏「${spotName.trim()}」"
                                feedbackDetail = null
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.size(4.dp))
                            Text("收藏当前坐标")
                        }
                    }
                }
            }

            // ── 坐标收藏夹 ─────────────────────────────────────────────
            if (settings.savedSpots.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "坐标收藏夹（${settings.savedSpots.size}）",
                        subtitle = "点击带入坐标 · 星标旁的删除图标移除",
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        settings.savedSpots.forEach { spot ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Star,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.size(8.dp))
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .padding(vertical = 2.dp),
                                ) {
                                    Text(
                                        text = spot.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = String.format(Locale.US, "%.6f, %.6f", spot.lat, spot.lng),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        settings.savedSpots = settings.savedSpots - spot
                                    },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "删除收藏 ${spot.name}",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                        // 点击行带入坐标（整行可点）
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            settings.savedSpots.take(3).forEach { spot ->
                                SelectableChip(
                                    text = spot.name,
                                    selected = false,
                                    onClick = {
                                        settings.latText = String.format(Locale.US, "%.6f", spot.lat)
                                        settings.lngText = String.format(Locale.US, "%.6f", spot.lng)
                                        spotName = spot.name
                                        settings.locationLabel = spot.name
                                        feedbackError = false
                                        feedback = "已带入「${spot.name}」"
                                        feedbackDetail = null
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }

            // ── 移动模式 ───────────────────────────────────────────────
            item {
                SectionHeader(
                    title = "移动模式",
                    subtitle = "轨迹 + 速度 + 航向 + 精度自然化 —— 对抗轨迹风控",
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(16.dp),
                ) {
                    MovementMode.entries.chunked(3).forEach { rowModes ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 3.dp),
                        ) {
                            rowModes.forEach { m ->
                                SelectableChip(
                                    text = m.label,
                                    selected = settings.movementMode == m,
                                    onClick = { settings.movementMode = m },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                    when (settings.movementMode) {
                        MovementMode.STATIC -> Text(
                            text = "固定坐标注入（speed=0）。适合打卡类场景；" +
                                "长时间完全静止可能被「位置无抖动」类检测识别。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        MovementMode.JITTER -> {
                            Text(
                                text = "微漂移幅度（随机游走半径）：",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.size(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                driftOptions.forEach { meters ->
                                    SelectableChip(
                                        text = "±${meters}米",
                                        selected = settings.driftMeters == meters,
                                        onClick = { settings.driftMeters = meters },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            Spacer(Modifier.size(6.dp))
                            Text(
                                text = "坐标在半径内随机游走，带微速度与随机航向 ——" +
                                    "形态最接近真实手机静止放置的 GPS 读数。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        MovementMode.WALK, MovementMode.RIDE, MovementMode.DRIVE -> Text(
                            text = "以 ${settings.movementMode.speedMps} m/s 沿缓慢变化的航向持续移动，" +
                                "speed / bearing / accuracy 按出行方式自然化 ——" +
                                "轨迹形态贴近真实行程，对抗网约车 / 打卡的轨迹风控。" +
                                "注意：坐标会持续前进，停止后从停点继续。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ── 动作区 ─────────────────────────────────────────────────
            item { ExecFeedback(feedback, feedbackError, feedbackDetail) }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(16.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                execThen(
                                    "模拟位置资格已授予（Android 12+ 通道）",
                                    MockLocationCommands.allow(context.packageName),
                                ) { appopAllowed = true }
                            },
                            enabled = !busy && appopAllowed != true,
                            modifier = Modifier.weight(1f),
                        ) { Text("① 授权模拟位置") }
                        OutlinedButton(
                            onClick = {
                                execThen(
                                    "模拟位置资格已授予（旧系统通道）",
                                    MockLocationCommands.allowLegacy(context.packageName),
                                ) { appopAllowed = true }
                            },
                            enabled = !busy && appopAllowed != true,
                            modifier = Modifier.weight(1f),
                        ) { Text("旧系统回退") }
                    }
                    Spacer(Modifier.size(10.dp))
                    Button(
                        onClick = {
                            val coords = parseCoordinates() ?: return@Button
                            LocationSpoofService.start(
                                context,
                                coords.first,
                                coords.second,
                                settings.movementMode,
                                settings.driftMeters,
                            )
                            feedbackError = false
                            feedback = if (spoofing) {
                                "坐标已热更新（引擎不重启 · ${settings.movementMode.label}）"
                            } else {
                                "注入引擎已启动（${settings.movementMode.label}）"
                            }
                            feedbackDetail = "lat=${coords.first}, lng=${coords.second}"
                        },
                        enabled = appopAllowed == true,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                        }
                        Text(if (spoofing) "② 更新坐标与模式（引擎运行中）" else "② 开始伪装")
                    }
                    Spacer(Modifier.size(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                LocationSpoofService.stop(context)
                                feedbackError = false
                                feedback = "注入引擎已停止，测试源归还系统"
                                feedbackDetail = null
                            },
                            enabled = spoofing,
                            modifier = Modifier.weight(1f),
                        ) { Text("停止伪装") }
                        OutlinedButton(
                            onClick = {
                                execThen(
                                    "模拟位置资格已撤销（隐私回收）",
                                    MockLocationCommands.reset(context.packageName),
                                ) {
                                    appopAllowed = false
                                    LocationSpoofService.stop(context)
                                }
                            },
                            enabled = !busy && appopAllowed == true,
                            modifier = Modifier.weight(1f),
                        ) { Text("撤销资格") }
                    }
                }
            }

            item {
                SectionHeader(title = "原理与边界", subtitle = null)
                InfoCard(
                    title = "Fake GPS 同款官方通道（免 Root）",
                    body = "① Shizuku 以 adb shell 身份把本 App 设为合法模拟位置源" +
                        "（appops mock_location，等价于开发者选项里的「选择模拟位置信息应用」）；" +
                        "② 前台服务经 LocationManager.addTestProvider 接管 gps / network / " +
                        "fused 三个位置源 —— 全系统所有 App 读到的都是注入坐标。" +
                        "停止后测试源自动归还，重启手机后 appop 仍在（可随时「撤销资格」回收）。" +
                        "注意：部分金融/出行类 App 会检测 mock 位置标记，属正常对抗。",
                )
            }
        }
    }
}
