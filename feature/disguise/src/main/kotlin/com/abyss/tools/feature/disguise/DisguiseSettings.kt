package com.abyss.tools.feature.disguise

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.abyss.tools.core.shell.BatteryStatus

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

    // ── 机型伪装 ──────────────────────────────────────────────────────

    private var _modelProfileId by mutableStateOf(prefs.getString(KEY_MODEL_PROFILE, null))

    /** 当前选中的档案 id（null = 自定义档案）。 */
    var modelProfileId: String?
        get() = _modelProfileId
        set(value) {
            _modelProfileId = value
            prefs.edit().putString(KEY_MODEL_PROFILE, value).apply()
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

    /** 微漂移开关（±3 米随机游走，对抗「静止检测」类反作弊）。 */
    var jitter: Boolean
        get() = _jitter
        set(value) {
            _jitter = value
            prefs.edit().putBoolean(KEY_JITTER, value).apply()
        }

    private companion object {
        const val KEY_BATTERY_LEVEL = "battery_level"
        const val KEY_BATTERY_STATUS = "battery_status"
        const val KEY_BATTERY_UNPLUG = "battery_unplug"
        const val KEY_MODEL_PROFILE = "model_profile"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_CUSTOM_MODEL = "custom_model"
        const val KEY_CUSTOM_BRAND = "custom_brand"
        const val KEY_CUSTOM_FINGERPRINT = "custom_fingerprint"
        const val KEY_LAT = "last_lat"
        const val KEY_LNG = "last_lng"
        const val KEY_LOCATION_LABEL = "location_label"
        const val KEY_JITTER = "location_jitter"
    }
}
