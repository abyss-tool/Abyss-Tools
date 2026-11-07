package com.abyss.tools.core.shell

/**
 * 系统级指令编排（纯 Kotlin，零 Android 依赖 —— 可单测）。
 *
 * 伪装四件套的每一条命令都在这里显式声明：UI 层只挑命令 + 解析输出，
 * 禁止散落字符串拼接（fail-closed：非法输入就地拒绝，不猜不补）。
 *
 * 能力边界（诚实声明，UI 同步展示）：
 *  - dumpsys battery set —— 全局真实生效（Android 官方电池测试通道）
 *  - svc / cmd connectivity —— 全局真实生效（连接性真实开关）
 *  - settings put global device_name —— 全局设置项，部分系统界面生效
 *  - appops mock_location —— 授权本 App 成为模拟位置源（Fake GPS 同款）
 */
object ShizukuCommands {

    /** sh 单引号安全转义：'…' 内的 ' 替换为 '\'' （唯一可靠的 sh 引用形态）。 */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

// ── 电量伪装（dumpsys battery：系统级，全 App 读到伪装值）─────────────────

/** BatteryManager 常量语义（dumpsys battery set status 的合法取值）。 */
enum class BatteryStatus(val code: Int, val label: String) {
    CHARGING(2, "充电中"),
    DISCHARGING(3, "放电中"),
    NOT_CHARGING(4, "未充电"),
    FULL(5, "已充满");

    companion object {
        fun fromCode(code: Int): BatteryStatus? = entries.firstOrNull { it.code == code }
    }
}

/** dumpsys battery 的关键读数快照。 */
data class BatterySnapshot(
    val level: Int?,
    val status: BatteryStatus?,
    val powered: Boolean?,
)

object BatteryCommands {

    fun setLevel(level: Int): String =
        "dumpsys battery set level " + level.coerceIn(0, 100)

    fun setStatus(status: BatteryStatus): String =
        "dumpsys battery set status ${status.code}"

    /** 强制「拔掉电源」：伪装放电态最稳的组合拳（否则充电图标压过等级显示）。 */
    fun unplug(): String = "dumpsys battery unplug"

    /** 恢复真实电池（清空全部伪装状态，系统重新同步）。 */
    fun reset(): String = "dumpsys battery reset"

    fun read(): String = "dumpsys battery"

    /**
     * 解析 dumpsys battery 输出。经典形态：
     * ```
     * Current Battery Service state:
     *   AC powered: false
     *   USB powered: true
     *   status: 2
     *   level: 85
     * ```
     * 兼容老形态（powered: / 无前导空格 / \r\n）。字段缺失 → null，绝不猜。
     */
    fun parseSnapshot(dump: String): BatterySnapshot {
        var level: Int? = null
        var status: BatteryStatus? = null
        var powered: Boolean? = null
        dump.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("level:") ->
                    level = line.removePrefix("level:").trim().toIntOrNull()

                line.startsWith("status:") ->
                    status = BatteryStatus.fromCode(
                        line.removePrefix("status:").trim().toIntOrNull() ?: -1,
                    )

                line.startsWith("powered:") ->
                    powered = line.removePrefix("powered:").trim().toBooleanStrictOrNull()

                // 老版本没有统一 powered 字段：任一外部电源在供 = powered
                line.endsWith("powered: true") -> if (powered == null) powered = true
            }
        }
        return BatterySnapshot(level = level, status = status, powered = powered)
    }
}

// ── 信号伪装（连接性真实开关：飞行模式 / 移动数据 / Wi-Fi）────────────────

object SignalCommands {

    /** Android 11+ 官方通道：连接性服务直接切飞行模式（设置项 + 广播一步到位）。 */
    fun setAirplaneMode(enable: Boolean): String =
        "cmd connectivity airplane-mode " + if (enable) "enable" else "disable"

    /** Android 8~10 回退形态：改全局设置 + 定向广播（部分 ROM 广播受限，诚实提示）。 */
    fun setAirplaneModeLegacy(enable: Boolean): String =
        "settings put global airplane_mode_on ${if (enable) 1 else 0}" +
            " && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enable"

    fun setMobileData(enable: Boolean): String =
        "svc data " + if (enable) "enable" else "disable"

    fun setWifi(enable: Boolean): String =
        "svc wifi " + if (enable) "enable" else "disable"

    fun readAirplaneMode(): String = "settings get global airplane_mode_on"

    fun readMobileData(): String = "settings get global mobile_data"

    fun readWifi(): String = "settings get global wifi_on"

    /** settings get 的 0/1 解析：缺省（"null"）→ null，绝不猜默认值。 */
    fun parseSettingFlag(output: String): Boolean? = when (output.trim()) {
        "1", "true" -> true
        "0", "false" -> false
        else -> null
    }
}

// ── 机型伪装（系统设备名 + Build 档案）────────────────────────────────────

object DeviceIdentityCommands {

    /**
     * 系统级设备名（Settings.Global.DEVICE_NAME，API 25+）：部分系统界面
     * （快速分享 / 投放 / 关于手机等）真实读这一项。
     */
    fun setDeviceName(name: String): String =
        "settings put global device_name " + ShizukuCommands.shellQuote(name)

    fun readDeviceName(): String = "settings get global device_name"
}

// ── 定位伪装（mock_location appop 授权本 App 为模拟位置源）────────────────

object MockLocationCommands {

    /**
     * Android 12+ appop 名带 android: 前缀；老版本不带 —— 先发新名，
     * 失败再发 [allowLegacy]。
     */
    fun allow(pkg: String): String = "appops set $pkg android:mock_location allow"

    fun allowLegacy(pkg: String): String = "appops set $pkg mock_location allow"

    /** 撤销模拟位置资格（隐私回收：不用伪装时归还系统默认）。 */
    fun reset(pkg: String): String = "appops set $pkg android:mock_location default"

    fun query(pkg: String): String = "appops get $pkg android:mock_location"

    /**
     * appops get 输出解析：
     *  - "…mock_location: allow / Allow" → true
     *  - "…: default / deny / Reject…" → false
     *  - "No such app ops" / 空 → null（无法判定，UI 引导走授权）
     */
    fun parseAllowed(output: String): Boolean? {
        val text = output.trim()
        if (text.isEmpty() || text.contains("No such", ignoreCase = true)) return null
        return when {
            text.contains("allow", ignoreCase = true) -> true
            text.contains("default", ignoreCase = true) ||
                text.contains("deny", ignoreCase = true) ||
                text.contains("reject", ignoreCase = true) -> false

            else -> null
        }
    }
}
