package com.abyss.tools.feature.disguise

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.abyss.tools.core.shell.BatteryStatus

/** 定位伪装移动模式（存 prefs 用名字，UI 展示用 [label]）。 */
enum class MovementMode(val label: String, val speedMps: Double) {
    STATIC("静止", 0.0),
    JITTER("微漂移", 0.0),
    WALK("步行", 1.4),
    RIDE("骑行", 5.0),
    DRIVE("驾车", 15.0),
}

/** 坐标收藏夹条目（prefs 私有格式 name|lat|lng，非法行解析时丢弃）。 */
data class SavedSpot(val name: String, val lat: Double, val lng: Double)

/**
 * 伪装四件套的本地状态（SharedPreferences + Compose 直读直写，
 * 手动 DI —— 语义与 SettingsRepository 完全同构）。
 *
 * 存的是「用户上次的伪装配置」：重进页面不用重填；真正的生效状态
 * 以系统为准（dumpsys / appops / 前台服务运行态），页面进入时重新读取。
 */
class DisguiseSettings(context: Context) {

    private val prefs = context.getSharedPreferences("abyss_disguise", Context.MODE_PRIVATE)

    // ── 电量伪装 ──────────────────────────────────────────────────────

    private var _batteryLevel by mutableIntStateOf(prefs.getInt(KEY_BATTERY_LEVEL, 50))

    var batteryLevel: Int
        get() = _batteryLevel
        set(value) {
            _batteryLevel = value.coerceIn(0, 100)
            prefs.edit().putInt(KEY_BATTERY_LEVEL, _batteryLevel).apply()
        }

    private var _batteryStatus by mutableStateOf(
        runCatching {
            BatteryStatus.valueOf(prefs.getString(KEY_BATTERY_STATUS, null) ?: BatteryStatus.DISCHARGING.name)
        }.getOrDefault(BatteryStatus.DISCHARGING),
    )

    var batteryStatus: BatteryStatus
        get() = _batteryStatus
        set(value) {
            _batteryStatus = value
            prefs.edit().putString(KEY_BATTERY_STATUS, value.name).apply()
        }

    private var _batteryUnplug by mutableStateOf(prefs.getBoolean(KEY_BATTERY_UNPLUG, true))

    /** 应用伪装时是否同时强制拔电（压掉充电图标，等级显示才干净）。 */
    var batteryUnplug: Boolean
        get() = _batteryUnplug
        set(value) {
            _batteryUnplug = value
            prefs.edit().putBoolean(KEY_BATTERY_UNPLUG, value).apply()
        }

    private var _batteryGuard by mutableStateOf(prefs.getBoolean(KEY_BATTERY_GUARD, false))

    /** 持续保持：应用后启动守护服务周期重申伪装值，防定制 ROM 回写真实电量。 */
    var batteryGuard: Boolean
        get() = _batteryGuard
        set(value) {
            _batteryGuard = value
            prefs.edit().putBoolean(KEY_BATTERY_GUARD, value).apply()
        }

    // ── 机型伪装 ──────────────────────────────────────────────────────

    private var _modelProfileId by mutableStateOf(prefs.getString(KEY_MODEL_PROFILE, null))

    /** 当前选中的档案 id（null = 自定义档案）。 */
    var modelProfileId: String?
        get() = _modelProfileId
        set(value) {
            _modelProfileId = value
            prefs.edit().putString(KEY_MODEL_PROFILE, value).apply()
        }

    private var _modelSpoofLevel by mutableStateOf(
        runCatching { SpoofLevel.valueOf(prefs.getString(KEY_MODEL_LEVEL, null) ?: "") }
            .getOrDefault(SpoofLevel.STANDARD),
    )

    /** 机型伪装程度（L1 设备名 / L2 标准档案 / L3 深度全字段 + 蓝牙名）。 */
    var modelSpoofLevel: SpoofLevel
        get() = _modelSpoofLevel
        set(value) {
            _modelSpoofLevel = value
            prefs.edit().putString(KEY_MODEL_LEVEL, value.name).apply()
        }

    private var _deviceName by mutableStateOf(prefs.getString(KEY_DEVICE_NAME, "") ?: "")

    /** 系统设备名（Settings.Global.DEVICE_NAME 写入项）。 */
    var deviceName: String
        get() = _deviceName
        set(value) {
            _deviceName = value
            prefs.edit().putString(KEY_DEVICE_NAME, value).apply()
        }

    /** 自定义机型字段（沙箱 Hook 档案导出用）。 */
    var customModel: String
        get() = prefs.getString(KEY_CUSTOM_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_MODEL, value).apply()

    var customBrand: String
        get() = prefs.getString(KEY_CUSTOM_BRAND, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_BRAND, value).apply()

    var customFingerprint: String
        get() = prefs.getString(KEY_CUSTOM_FINGERPRINT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_FINGERPRINT, value).apply()

    // ── 定位伪装 ──────────────────────────────────────────────────────

    private var _latText by mutableStateOf(prefs.getString(KEY_LAT, "") ?: "")

    var latText: String
        get() = _latText
        set(value) {
            _latText = value
            prefs.edit().putString(KEY_LAT, value).apply()
        }

    private var _lngText by mutableStateOf(prefs.getString(KEY_LNG, "") ?: "")

    var lngText: String
        get() = _lngText
        set(value) {
            _lngText = value
            prefs.edit().putString(KEY_LNG, value).apply()
        }

    private var _locationLabel by mutableStateOf(prefs.getString(KEY_LOCATION_LABEL, "") ?: "")

    var locationLabel: String
        get() = _locationLabel
        set(value) {
            _locationLabel = value
            prefs.edit().putString(KEY_LOCATION_LABEL, value).apply()
        }

    private var _jitter by mutableStateOf(prefs.getBoolean(KEY_JITTER, true))

    /** 微漂移开关（兼容旧版：true 时映射 JITTER 模式，新逻辑由 movementMode 接管）。 */
    var jitter: Boolean
        get() = _jitter
        set(value) {
            _jitter = value
            prefs.edit().putBoolean(KEY_JITTER, value).apply()
        }

    private var _movementMode by mutableStateOf(
        runCatching { MovementMode.valueOf(prefs.getString(KEY_MOVEMENT_MODE, null) ?: "") }
            .getOrDefault(MovementMode.JITTER),
    )

    /** 移动模式：静止 / 微漂移 / 步行 / 骑行 / 驾车（速度自然化 + 坐标推进）。 */
    var movementMode: MovementMode
        get() = _movementMode
        set(value) {
            _movementMode = value
            prefs.edit().putString(KEY_MOVEMENT_MODE, value.name).apply()
        }

    private var _driftMeters by mutableIntStateOf(prefs.getInt(KEY_DRIFT_METERS, 3))

    /** 微漂移幅度（米）：小 1 / 中 3 / 大 10。 */
    var driftMeters: Int
        get() = _driftMeters
        set(value) {
            _driftMeters = value
            prefs.edit().putInt(KEY_DRIFT_METERS, value).apply()
        }

    private var _savedSpots by mutableStateOf(parseSpots(prefs.getString(KEY_SAVED_SPOTS, null)))

    /** 坐标收藏夹（name|lat|lng 多行格式，损坏行解析时丢弃）。 */
    var savedSpots: List<SavedSpot>
        get() = _savedSpots
        set(value) {
            _savedSpots = value
            prefs.edit()
                .putString(KEY_SAVED_SPOTS, value.joinToString("\n") { encodeSpot(it) })
                .apply()
        }

    // ── 信号显示伪装（状态栏覆盖层规格）──────────────────────────────

    /** 当前覆盖层规格（聚合读写：单一入口，坏值在 fromPrefs 内回默认）。 */
    var signalSpec: SignalSpoofSpec
        get() = SignalSpoofSpec.fromPrefs(
            networkTypeName = prefs.getString(KEY_SIGNAL_NETWORK, null),
            bars = prefs.getInt(KEY_SIGNAL_BARS, SignalSpoofSpec.TOTAL_BARS),
            wifi = prefs.getBoolean(KEY_SIGNAL_WIFI, false),
            wifiBars = prefs.getInt(KEY_SIGNAL_WIFI_BARS, SignalSpoofSpec.TOTAL_ARCS),
            rightMarginDp = prefs.getInt(KEY_SIGNAL_RIGHT_MARGIN, 68),
            coverEnabled = prefs.getBoolean(KEY_SIGNAL_COVER, false),
            coverWidthDp = prefs.getInt(KEY_SIGNAL_COVER_WIDTH, 96),
            iconThemeName = prefs.getString(KEY_SIGNAL_THEME, null),
        )
        set(value) {
            prefs.edit()
                .putString(KEY_SIGNAL_NETWORK, value.networkType.name)
                .putInt(KEY_SIGNAL_BARS, value.bars.coerceIn(1, SignalSpoofSpec.TOTAL_BARS))
                .putBoolean(KEY_SIGNAL_WIFI, value.wifi)
                .putInt(KEY_SIGNAL_WIFI_BARS, value.wifiBars.coerceIn(1, SignalSpoofSpec.TOTAL_ARCS))
                .putInt(KEY_SIGNAL_RIGHT_MARGIN, value.rightMarginDp)
                .putBoolean(KEY_SIGNAL_COVER, value.coverEnabled)
                .putInt(KEY_SIGNAL_COVER_WIDTH, value.coverWidthDp)
                .putString(KEY_SIGNAL_THEME, value.iconTheme.name)
                .apply()
        }

    private companion object {
        const val KEY_BATTERY_LEVEL = "battery_level"
        const val KEY_BATTERY_STATUS = "battery_status"
        const val KEY_BATTERY_UNPLUG = "battery_unplug"
        const val KEY_BATTERY_GUARD = "battery_guard"
        const val KEY_MODEL_PROFILE = "model_profile"
        const val KEY_MODEL_LEVEL = "model_spoof_level"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_CUSTOM_MODEL = "custom_model"
        const val KEY_CUSTOM_BRAND = "custom_brand"
        const val KEY_CUSTOM_FINGERPRINT = "custom_fingerprint"
        const val KEY_LAT = "last_lat"
        const val KEY_LNG = "last_lng"
        const val KEY_LOCATION_LABEL = "location_label"
        const val KEY_JITTER = "location_jitter"
        const val KEY_MOVEMENT_MODE = "movement_mode"
        const val KEY_DRIFT_METERS = "drift_meters"
        const val KEY_SAVED_SPOTS = "saved_spots"
        const val KEY_SIGNAL_NETWORK = "signal_network_type"
        const val KEY_SIGNAL_BARS = "signal_bars"
        const val KEY_SIGNAL_WIFI = "signal_wifi"
        const val KEY_SIGNAL_WIFI_BARS = "signal_wifi_bars"
        const val KEY_SIGNAL_RIGHT_MARGIN = "signal_right_margin_dp"
        const val KEY_SIGNAL_COVER = "signal_cover_enabled"
        const val KEY_SIGNAL_COVER_WIDTH = "signal_cover_width_dp"
        const val KEY_SIGNAL_THEME = "signal_icon_theme"

        /** name 里的分隔符/换行剥掉（单行安全，解析零歧义）。 */
        fun encodeSpot(spot: SavedSpot): String =
            spot.name.replace("|", "/").replace("\n", " ").trim() +
                "|" + spot.lat.toString() + "|" + spot.lng.toString()

        /** fail-closed：字段数不对 / 数值非法的行直接丢弃，绝不猜。 */
        fun parseSpots(raw: String?): List<SavedSpot> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.lineSequence().mapNotNull { line ->
                val parts = line.split('|')
                if (parts.size != 3) return@mapNotNull null
                val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
                val lng = parts[2].toDoubleOrNull() ?: return@mapNotNull null
                if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return@mapNotNull null
                SavedSpot(name = parts[0].ifBlank { "未命名" }, lat = lat, lng = lng)
            }.toList()
        }
    }
}
