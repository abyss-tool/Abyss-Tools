package com.abyss.tools.feature.syspower

import com.abyss.tools.core.shell.ShizukuCommands

/**
 * 系统控制中心的指令编排（纯 Kotlin，零 Android 依赖 —— 可单测）。
 *
 * 结构对齐 core:shell 的 ShizukuCommands 纪律：每一条命令都在这里显式
 * 声明，UI 层只挑命令 + 解析输出，禁止散落字符串拼接；裸参数（包名 /
 * appop 名 / 权限名）一律经 [ShizukuCommands.requireSafeToken] 门禁
 * （fail-closed：非法输入就地 IllegalArgumentException，不猜不补）。
 *
 * 能力边界（诚实声明，UI 同步展示）：
 *  - settings / cmd uimode —— 官方设置项，即时生效、重启保持；
 *  - wm size / density —— 显示驱动层覆写，对应 reset 可还原；
 *  - dumpsys deviceidle —— Doze 状态机强制通道（排障 / 省电压测）；
 *  - appops / pm —— 授权直改的 adb 官方通道，免 Root。
 */

// ── 显示与外观（settings / cmd uimode / wm）───────────────────────────────

object DisplayCommands {

    private val SIZE_LINE = Regex("^(?:Physical|Override) size:\\s*(\\d+)x(\\d+)$")
    private val DENSITY_LINE = Regex("^(?:Physical|Override) density:\\s*(\\d+)(?:dpi)?$")

    /** 全局夜间模式（cmd uimode night：官方 CLI 通道，切向即时生效）。 */
    fun setDarkMode(enable: Boolean): String =
        "cmd uimode night " + if (enable) "yes" else "no"

    fun readDarkMode(): String = "settings get secure ui_night_mode"

    /**
     * ui_night_mode 解析：0 → 否；1 → 是；2 → 自定义时刻表（日落/定时，
     * 开关双态不可判定）；"null" / 未知 → null —— 绝不猜。
     */
    fun parseNightMode(output: String): Boolean? = when (output.trim()) {
        "0" -> false
        "1" -> true
        else -> null // "2" 自定义时刻表与 "null" 缺省统一诚实归「未知」
    }

    fun setBrightness(value: Int): String =
        "settings put system screen_brightness ${value.coerceIn(1, 255)}"

    fun readBrightness(): String = "settings get system screen_brightness"

    /** 非数字 / 缺省输出 → null（fail-closed，读失败由 UI 显示「未知」）。 */
    fun parseBrightness(output: String): Int? = output.trim().toIntOrNull()

    /** 自动亮度（感光调节）：1 = 系统接管手动亮度，0 = 手动。 */
    fun setAutoBrightness(enable: Boolean): String =
        "settings put system screen_brightness_mode ${if (enable) 1 else 0}"

    fun readAutoBrightness(): String = "settings get system screen_brightness_mode"

    fun parseAutoBrightness(output: String): Boolean? = parseZeroOne(output)

    /**
     * 动画三表同步写入（窗口 / 转场 / 属性动画共用刻度，链式 && 保持原子
     * 语义：任一失败后续不执行，UI 如实报错）。
     */
    fun setAnimationScale(scale: Float): String {
        val value = formatScale(scale)
        return "settings put global window_animation_scale $value" +
            " && settings put global transition_animation_scale $value" +
            " && settings put global animator_duration_scale $value"
    }

    fun readAnimationScale(): String = "settings get global window_animation_scale"

    fun parseAnimationScale(output: String): Float? = output.trim().toFloatOrNull()

    /** 熄屏秒数 → 毫秒（settings 官方项毫秒存储）。 */
    fun setScreenTimeout(seconds: Int): String {
        require(seconds > 0) { "熄屏秒数须为正：$seconds" }
        return "settings put system screen_off_timeout ${seconds * 1000}"
    }

    fun readScreenTimeout(): String = "settings get system screen_off_timeout"

    /** 毫秒 → 秒（非整千向下取整；怪值如实折算，不猜预设档）。 */
    fun parseTimeoutSeconds(output: String): Int? =
        output.trim().toIntOrNull()?.let { it / 1000 }

    /** 分辨率覆写（wm size：显示驱动层 Override，允许偏离物理面板值）。 */
    fun setSize(w: Int, h: Int): String {
        require(w > 0 && h > 0) { "非法分辨率（须为正整数）：${w}x${h}" }
        return "wm size ${w}x${h}"
    }

    /** 清除覆写、回物理分辨率。 */
    fun resetSize(): String = "wm size reset"

    fun readSize(): String = "wm size"

    /**
     * wm size 输出解析（Override 优先 —— 覆写值即当前活跃态）：
     * ```
     * Physical size: 1080x2400
     * Override size: 1080x2340
     * ```
     * 无匹配 / 坏行 → null，绝不猜。
     */
    fun parseSize(output: String): Pair<Int, Int>? {
        var physical: Pair<Int, Int>? = null
        var override: Pair<Int, Int>? = null
        output.lineSequence().forEach { raw ->
            val line = raw.trim()
            val match = SIZE_LINE.find(line) ?: return@forEach
            val w = match.groupValues[1].toIntOrNull() ?: return@forEach
            val h = match.groupValues[2].toIntOrNull() ?: return@forEach
            when {
                line.startsWith("Override", ignoreCase = true) -> override = w to h
                line.startsWith("Physical", ignoreCase = true) -> physical = w to h
            }
        }
        return override ?: physical
    }

    /** DPI 覆写（wm density；120-640 为人眼可用域，超域夹取）。 */
    fun setDensity(d: Int): String = "wm density ${d.coerceIn(120, 640)}"

    fun resetDensity(): String = "wm density reset"

    fun readDensity(): String = "wm density"

    /** wm density 输出解析（Override 优先，容忍 dpi 尾缀）。 */
    fun parseDensity(output: String): Int? {
        var physical: Int? = null
        var override: Int? = null
        output.lineSequence().forEach { raw ->
            val line = raw.trim()
            val match = DENSITY_LINE.find(line) ?: return@forEach
            val value = match.groupValues[1].toIntOrNull() ?: return@forEach
            when {
                line.startsWith("Override", ignoreCase = true) -> override = value
                line.startsWith("Physical", ignoreCase = true) -> physical = value
            }
        }
        return override ?: physical
    }
}

// ── 电源与性能（常亮位掩码 / Doze / 省电模式）──────────────────────────────

object PowerCommands {

    /**
     * 充电时屏幕常亮：7 = AC(1)|USB(2)|Wireless(4) 的位掩码并集 —— 任一
     * 电源在供即保持常亮（比单写 1 的「仅 AC」覆盖更全）；关闭清 0。
     */
    fun setStayOnWhilePlugged(enable: Boolean): String =
        "settings put global stay_on_while_plugged_in " + if (enable) "7" else "0"

    fun readStayOnWhilePlugged(): String = "settings get global stay_on_while_plugged_in"

    /** 位掩码语义：0 → 否；1..7 任一电源组合 → 是；其余 / 缺省 → null。 */
    fun parseStayOnFlag(output: String): Boolean? {
        val value = output.trim().toIntOrNull() ?: return null
        return when {
            value == 0 -> false
            value in 1..7 -> true
            else -> null
        }
    }

    /** 强制直入 Doze 深睡（跳过空闲等待；省电压测 / 排障通道）。 */
    fun forceDoze(): String = "dumpsys deviceidle force-idle"

    /** 退出强制 Doze（归还系统空闲调度）。 */
    fun unforceDoze(): String = "dumpsys deviceidle unforce"

    /**
     * 电池省电模式（官方设置项 low_power）。诚实边界：部分 ROM 的省电
     * 界面需叠加 POWER_SAVE_MODE_CHANGED 广播才即时刷新 —— 写入成功
     * 但开关不回显属正常，勿重复狂按。
     */
    fun setLowPower(enable: Boolean): String =
        "settings put global low_power " + if (enable) "1" else "0"

    fun readLowPower(): String = "settings get global low_power"

    fun parseLowPowerFlag(output: String): Boolean? = parseZeroOne(output)
}

// ── 授权总管：AppOps（敏感操作直改）────────────────────────────────────────

/** appops 可直设的三态（deny / foreground 是只读汇报态，CLI 不支持直设）。 */
enum class AppOpsMode(val cli: String, val label: String) {
    ALLOW("allow", "允许"),
    IGNORE("ignore", "拒绝"),
    DEFAULT("default", "默认"),
}

object AppOpsCommands {

    /**
     * 本中心聚焦的敏感 op 清单（顺序即授权面板展示序；Android 12+ 若报
     * No such app ops 可改用 android: 前缀形态 —— 两种形态均原样透传）。
     */
    val INTERESTING_OPS: List<String> = listOf(
        "CAMERA", "MICROPHONE", "FINE_LOCATION", "COARSE_LOCATION",
        "SYSTEM_ALERT_WINDOW", "RUN_IN_BACKGROUND", "WAKE_LOCK",
        "START_FOREGROUND", "PLAY_AUDIO", "VIBRATE",
        "READ_CLIPBOARD", "POST_NOTIFICATION",
    )

    fun state(pkg: String, op: String, mode: AppOpsMode): String =
        "appops set ${ShizukuCommands.requireSafeToken(pkg, "包名")} " +
            "${ShizukuCommands.requireSafeToken(op, "appop 名")} ${mode.cli}"

    fun query(pkg: String, op: String): String =
        "appops get ${ShizukuCommands.requireSafeToken(pkg, "包名")} " +
            ShizukuCommands.requireSafeToken(op, "appop 名")

    /**
     * appops get 输出解析：找 "xxx: mode" 形态返回小写 mode
     * （allow / ignore / default / foreground / deny，容忍大小写、附加
     * 说明片段与尾随括注）；"No such app ops" / 空 / 无法识别 → null。
     */
    fun parseMode(output: String): String? {
        val text = output.trim()
        if (text.isEmpty() || text.contains("No such", ignoreCase = true)) return null
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            val colon = line.lastIndexOf(':')
            if (colon < 0) return@forEach
            val mode = line.substring(colon + 1)
                .trim()
                .lowercase()
                .substringBefore(' ')
                .substringBefore(';')
                .substringBefore('(')
                .trim()
            when (mode) {
                "allow", "ignore", "default", "foreground", "deny" -> return mode
            }
        }
        return null
    }
}

// ── 授权总管：运行时权限（pm grant / revoke）───────────────────────────────

object RuntimePermCommands {

    fun grant(pkg: String, perm: String): String =
        "pm grant ${ShizukuCommands.requireSafeToken(pkg, "包名")} " +
            ShizukuCommands.requireSafeToken(perm, "权限名")

    fun revoke(pkg: String, perm: String): String =
        "pm revoke ${ShizukuCommands.requireSafeToken(pkg, "包名")} " +
            ShizukuCommands.requireSafeToken(perm, "权限名")

    /** pm check-permission（注意参数序：权限在前、包在后 —— 与 grant 相反）。 */
    fun check(pkg: String, perm: String): String =
        "pm check-permission ${ShizukuCommands.requireSafeToken(perm, "权限名")} " +
            ShizukuCommands.requireSafeToken(pkg, "包名")

    /**
     * check-permission 输出宽容解析（历代 CLI 形态）：granted=true /
     * granted=false / granted / denied / true / false（尾逗号、尾句点一并
     * 容忍）；其余 → null —— 绝不猜。
     */
    fun parseGranted(output: String): Boolean? {
        output.lineSequence().forEach { raw ->
            when (val line = raw.trim().trimEnd(',', '.').lowercase()) {
                "granted=true", "granted", "true" -> return true
                "granted=false", "denied", "false" -> return false
            }
        }
        return null
    }
}

// ── 会话日志（内存态撤销链，纯 Kotlin）──────────────────────────────────────

/** 一次已应用的系统动作（人话标题 + 原始命令 —— 日志与撤销的最小单元）。 */
data class SysAction(val title: String, val command: String)

/**
 * 会话撤销日志（内存态）：record 记「动作 + 可选逆动作」；undoLast 弹出
 * 最近一条可撤销动作的逆（其间无逆动作的条目一并出栈 —— 它们本就无逆，
 * 弹出即弃）；undoOf 按动作相等性定位单条撤销。页面底部「会话日志」卡
 * 消费 entriesWithUndo 渲染逐行撤销入口。
 */
class SessionJournal {

    private data class Entry(val action: SysAction, val undo: SysAction?)

    private val log = mutableListOf<Entry>()

    fun record(action: SysAction, undo: SysAction?) {
        log.add(Entry(action, undo))
    }

    /** 已应用动作（时间序）。 */
    fun entries(): List<SysAction> = log.map { it.action }

    /** 逐行渲染用：动作 + 是否携带逆动作。 */
    fun entriesWithUndo(): List<Pair<SysAction, Boolean>> =
        log.map { it.action to (it.undo != null) }

    /** 撤销最近一条可撤销动作：返回其逆动作并收缩日志；无可撤销 → null。 */
    fun undoLast(): SysAction? {
        while (log.isNotEmpty()) {
            val entry = log.removeAt(log.lastIndex)
            entry.undo?.let { return it }
        }
        return null
    }

    /**
     * 单条撤销：按 SysAction 相等性（title+command）定位首个匹配并移除，
     * 返回其逆动作；未命中 → null。重复动作场景退化为首条匹配（标题与
     * 命令一致的条目撤销语义等价，无副作用）。
     */
    fun undoOf(action: SysAction): SysAction? {
        val index = log.indexOfFirst { it.action == action }
        if (index < 0) return null
        return log.removeAt(index).undo
    }
}

/** settings 0/1 布尔解析（缺省 "null" / 怪值 → null）。 */
private fun parseZeroOne(output: String): Boolean? = when (output.trim()) {
    "1" -> true
    "0" -> false
    else -> null
}

/** settings 动画刻度形态：0 → "0"；0.5 → "0.5"；1 → "1.0"（官方 CLI 同形态）。 */
private fun formatScale(scale: Float): String = when {
    scale == 0f -> "0"
    scale == scale.toInt().toFloat() -> "${scale.toInt()}.0"
    else -> scale.toString()
}

// ── 隐私与管控：权限审计（dumpsys package 只读盘点）───────────────────────

/** 单条危险运行时权限的真实授予态（dumpsys package 的 runtime permissions 段）。 */
data class RuntimePermissionState(
    val perm: String,
    val granted: Boolean,
)

/**
 * 权限审计（隐私域）：dumpsys package 原始输出解析 —— 盘点目标应用声明的
 * 危险运行时权限与真实授予态。为什么走 dumpsys 而不是 PackageManager：
 * granted 标志位（USER_SET / USER_FIXED 等）与其它工具改过的痕迹只有
 * dumpsys 全量吐出，这里是 adb 通道看到的系统真值。
 */
object PermissionAuditCommands {

    /**
     * 审计聚焦的危险权限白名单（顺序即展示序）。为什么收窄：全量 dump
     * 出几十条安装期权限只会淹没视线 —— 隐私敏感面就这十六类。
     */
    val DANGEROUS_PERMS: List<String> = listOf(
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.READ_CONTACTS",
        "android.permission.READ_CALL_LOG",
        "android.permission.READ_SMS",
        "android.permission.SEND_SMS",
        "android.permission.READ_PHONE_STATE",
        "android.permission.CALL_PHONE",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.READ_CALENDAR",
        "android.permission.BODY_SENSORS",
        "android.permission.ACTIVITY_RECOGNITION",
    )

    /** granted 行形态（缩进 + 权限名 + granted=，容忍尾随 flags 括注）。 */
    private val GRANTED_LINE = Regex("^\\s+([a-zA-Z0-9._]+): granted=(true|false)")

    /** 段头边界：全小写开头的裸「xxx:」行（install permissions: 等）。 */
    private val SECTION_HEADER = Regex("^[a-z][a-zA-Z ]*:$")

    /** requested 段的裸权限名行（缩进、无 granted= 尾缀）。 */
    private val PERM_NAME_LINE = Regex("^\\s+([a-zA-Z0-9._]+)$")

    fun dumpPackage(pkg: String): String =
        "dumpsys package " + ShizukuCommands.requireSafeToken(pkg, "包名")

    /**
     * runtime permissions 段解析（历代形态）：
     * ```
     * runtime permissions:
     *   android.permission.CAMERA: granted=true, flags=[ USER_SET ]
     *   android.permission.RECORD_AUDIO: granted=false, flags=[0x0]
     * ```
     * 段边界 = 下一个全小写裸段头（install permissions: / requested
     * permissions:…）；「User 0:」「Queries:」等非小写行不终止段（其后
     * 无 granted= 形态行，不会误收）。顶层段与 User 段会重复同名权限 ——
     * 首见为准（两处数据一致，多用户工作资料不在审计面）。整段缺失 →
     * null（fail-closed：UI 显示「读取失败」，绝不当空表假装成功）。
     */
    fun parseRuntimePermissions(output: String): List<RuntimePermissionState>? {
        var sawSection = false
        var inSection = false
        val collected = mutableListOf<RuntimePermissionState>()
        output.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            val trimmed = line.trim()
            if (trimmed == "runtime permissions:") {
                sawSection = true
                inSection = true
                return@forEach
            }
            if (!inSection) return@forEach
            if (SECTION_HEADER.matches(trimmed)) {
                inSection = false
                return@forEach
            }
            val match = GRANTED_LINE.find(line) ?: return@forEach
            val perm = match.groupValues[1]
            if (collected.none { it.perm == perm }) {
                collected.add(RuntimePermissionState(perm, match.groupValues[2].toBoolean()))
            }
        }
        return if (sawSection) collected else null
    }

    /**
     * requested permissions 段解析（裸权限名行，顺序保留）。与 runtime 段
     * 不同，裸名形态太泛（dump 其它段也全是包名行）—— 段内首个「非空、
     * 非裸名」行即视为段尾（fail-closed），避免把 Queries 段的包名误收
     * 成权限。整段缺失 → null。
     */
    fun parseRequestedPermissions(output: String): List<String>? {
        var sawSection = false
        var inSection = false
        val collected = mutableListOf<String>()
        output.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            val trimmed = line.trim()
            if (trimmed == "requested permissions:") {
                sawSection = true
                inSection = true
                return@forEach
            }
            if (!inSection) return@forEach
            if (SECTION_HEADER.matches(trimmed)) {
                inSection = false
                return@forEach
            }
            if (trimmed.isEmpty()) return@forEach // 段内空行容忍，继续等名单行
            val match = PERM_NAME_LINE.find(line)
            if (match == null) {
                inSection = false // 陌生形态 → 段尾（宁可少收，不误收）
                return@forEach
            }
            if (!collected.contains(match.groupValues[1])) {
                collected.add(match.groupValues[1])
            }
        }
        return if (sawSection) collected else null
    }

    /** 白名单序交集（requested ∩ 危险白名单；null 透传 —— 解析失败不猜）。 */
    fun filterDangerous(requested: List<String>?): List<String>? =
        requested?.let { list -> DANGEROUS_PERMS.filter { perm -> perm in list } }
}

// ── 隐私与管控：敏感操作记录（appops query-op 记账回溯）───────────────────

/** 单条 op 记账行：哪个包 + 处于什么模式。 */
data class OpUsage(
    val pkg: String,
    val mode: String,
)

/**
 * 敏感操作记录（隐私域）：appops query-op 按 op 反查「谁用过」—— 记账
 * 只在操作发生后有条目，空结果是合法答案（近期无使用）而非错误。
 */
object SensitiveOpsCommands {

    /**
     * 记账查询 op 集合（拼写即 appops CLI 接受的名 —— MICROPHONE 与
     * RECORD_AUDIO 在记账层同名，此处统一用 MICROPHONE）。
     */
    val QUERY_OPS: List<String> = listOf(
        "CAMERA", "MICROPHONE", "FINE_LOCATION", "COARSE_LOCATION", "READ_CLIPBOARD",
    )

    /**
     * query-op 行形态（宽容两种历史格式）：
     * `Package com.foo: CAMERA (allow)` / `com.foo: CAMERA (allow)`。
     */
    private val QUERY_OP_LINE = Regex("^(?:Package\\s+)?([a-zA-Z0-9._]+):\\s+([A-Z_]+)\\s*\\(([a-z]+)\\)")

    fun queryOp(op: String): String =
        "appops query-op " + ShizukuCommands.requireSafeToken(op, "appop 名")

    /**
     * 逐行匹配，坏行跳过；空 / 全坏 → 空列表（「近期无应用使用该 op」
     * 是合法答案；命令失败由 runner 层区分 —— 输出为 null 走读取失败）。
     */
    fun parseQueryOp(output: String): List<OpUsage> =
        output.lineSequence().mapNotNull { raw ->
            val match = QUERY_OP_LINE.find(raw.trim()) ?: return@mapNotNull null
            OpUsage(pkg = match.groupValues[1], mode = match.groupValues[3])
        }.toList()
}

// ── 隐私与管控：Doze 白名单（dumpsys deviceidle whitelist）────────────────

/**
 * Doze 白名单（隐私域 / 省电域交界）：白名单内应用在 Doze 深睡期保留
 * 网络 / 同步豁免。诚实边界：系统内置条目由系统维护，移除会被服务拒绝
 * —— 原样报错（错误条），不静默吞掉。
 */
object DeviceIdleCommands {

    /** 白名单条目形态：小写包名，至少一个点分段。 */
    private val WHITELIST_TOKEN = Regex("^[a-z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

    fun whitelist(): String = "dumpsys deviceidle whitelist"

    /**
     * 解析：逐行按逗号切分后逐 token 匹配包名形态 —— 段头（system
     * whitelist:）与带过期时间尾注的条目（com.foo: 626s-left）天然被拒。
     * 空 / 无匹配 → 空列表（如实展示「无条目」）。
     */
    fun parseWhitelist(output: String): List<String> {
        val seen = LinkedHashSet<String>()
        output.lineSequence().forEach { raw ->
            raw.split(',').forEach { part ->
                val token = part.trim()
                if (WHITELIST_TOKEN.matches(token)) seen.add(token)
            }
        }
        return seen.toList()
    }

    fun add(pkg: String): String =
        "dumpsys deviceidle whitelist +" + ShizukuCommands.requireSafeToken(pkg, "包名")

    fun remove(pkg: String): String =
        "dumpsys deviceidle whitelist -" + ShizukuCommands.requireSafeToken(pkg, "包名")

    /**
     * UI 输入门禁：看起来像包名（字母开头、至少一个点分段）才放行 ——
     * 构造器的 requireSafeToken 异常若在点击处抛会闪退，先在 UI 层拦。
     */
    fun looksLikePackageName(value: String): Boolean =
        value.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$"))
}

// ── 隐私与管控：流量策略（cmd netpolicy）──────────────────────────────────

/**
 * 流量策略（隐私域）：全局数据节省（restrict-background）与单应用后台
 * 流量管制（uid-policy）。uid-policy 二值通道：1 = 拒绝后台流量
 * （POLICY_REJECT_METERED_BACKGROUND），0 = 清回默认；4（显式允许）只读
 * 汇报，构造器不写 4 —— 0/1 通道还原不回「显式允许」，撤销链诚实弃权。
 */
object NetPolicyCommands {

    /** uid-policy 输出里的整数 token（官方形态 "POLICY_NONE (0)" / "1"）。 */
    private val INT_TOKEN = Regex("\\d+")

    fun getRestrictBackground(): String = "cmd netpolicy get restrict-background"

    /**
     * 宽容解析（官方形态 "restrict-background: enabled/disabled"，历代
     * 亦有 "restricted: true/false"）：含 true/enabled → 开；含 false/
     * disabled/not restricted → 关；其余（空 / 报错文案）→ null —— 绝不猜。
     */
    fun parseRestrictBackground(output: String): Boolean? {
        val text = output.trim().lowercase()
        return when {
            text.isEmpty() -> null
            text.contains("true") || text.contains("enabled") -> true
            text.contains("false") || text.contains("disabled") ||
                text.contains("not restricted") -> false
            else -> null
        }
    }

    fun setRestrictBackground(enable: Boolean): String =
        "cmd netpolicy set restrict-background " + if (enable) "true" else "false"

    fun getUidPolicy(uid: Int): String {
        require(uid >= 0) { "非法 uid（须为非负）：$uid" }
        return "cmd netpolicy get uid-policy $uid"
    }

    /**
     * 取输出首个整数（0 = 默认 / 1 = 拒绝后台流量 / 4 = 显式允许后台
     * 流量）。无整数（纯策略名、空、报错文案）→ null。诚实边界：若个别
     * ROM 回显「uid 10123: …」会把 uid 当策略值 —— 已知形态都不带 uid
     * 回显，遇到未知形态宁 null 不猜。
     */
    fun parseUidPolicy(output: String): Int? {
        val text = output.trim()
        if (text.isEmpty()) return null
        val match = INT_TOKEN.find(text) ?: return null
        return match.value.toIntOrNull()
    }

    /** 单应用策略直改（reject=true 写 1，false 清 0 回默认）。 */
    fun setUidPolicy(uid: Int, reject: Boolean): String {
        require(uid >= 0) { "非法 uid（须为非负）：$uid" }
        return "cmd netpolicy set uid-policy $uid " + if (reject) "1" else "0"
    }
}
