package com.abyss.tools.feature.shizukukit

import com.abyss.tools.core.shell.ShizukuCommands
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Shizuku 工具箱的指令编排（纯 Kotlin，零 Android 依赖 —— 可单测）。
 *
 * 结构对齐 core:shell 的 ShizukuCommands：命令构造 / 输出解析 / 历史序列化
 * 全部收敛为显式纯函数，UI 层只挑命令 + 解析输出，禁止散落字符串拼接。
 *
 * fail-closed 纪律（对齐仓库数据契约铁律）：
 *  - 令牌位（包名 / op 名 / settings key）必须非空、无空白、无 shell 元字符，
 *    非法输入就地 IllegalArgumentException，绝不猜测补全；
 *  - settings namespace 只认 global / system / secure 三个枚举值（大小写敏感）；
 *  - dumpsys 只放行白名单内的只读诊断命令（写操作走伪装四件套专用页），
 *    白名单外一律拒绝 —— 防误伤系统服务。
 */

/** 面板类型（历史条目 / 结果卡 / 类型徽章的共用语义）。 */
enum class CommandType(val label: String) {
    APPOPS("AppOps"),
    SETTINGS("设置"),
    DUMPSYS("Dumpsys"),
}

object KitCommands {

    /** 令牌位黑名单：出现任意 shell 元字符即拒绝（引号 / 换义 / 管道 / 重定向…）。 */
    private val SHELL_METACHARS = setOf(
        '\'', '"', '\\', '`', '$', ';', '&', '|', '<', '>', '(', ')',
        '{', '}', '[', ']', '*', '?', '#', '!', '~', '=',
    )

    /** 令牌校验（fail-closed）：trim 后非空、无空白、无元字符，原样返回。 */
    fun requireSafeToken(raw: String, what: String): String {
        val token = raw.trim()
        require(token.isNotEmpty()) { "$what 不能为空" }
        require(token.none { it.isWhitespace() }) { "$what 不能包含空白字符" }
        require(token.none { it in SHELL_METACHARS }) { "$what 含 shell 元字符，已拒绝（fail-closed）" }
        return token
    }

    /**
     * sh 单引号解引（历史回放预填值用）：'a b' → a b（' 已被安全转义的
     * 形态还原），非引用形态原样返回 —— 与 ShizukuCommands.shellQuote 互逆。
     */
    fun unshellQuote(quoted: String): String {
        val text = quoted.trim()
        if (text.length >= 2 && text.startsWith("'") && text.endsWith("'")) {
            return text.substring(1, text.length - 1).replace("'\\''", "'")
        }
        return text
    }

    /** dumpsys 等大输出命令的展示窗口上限（64KB）。 */
    const val MAX_OUTPUT_CHARS = 64 * 1024

    /** 裁剪后的展示文本（truncated = 原文更长，已截断并附提示行）。 */
    data class OutputWindow(val text: String, val truncated: Boolean)

    fun windowOutput(output: String, maxChars: Int = MAX_OUTPUT_CHARS): OutputWindow =
        if (output.length <= maxChars) {
            OutputWindow(text = output, truncated = false)
        } else {
            OutputWindow(
                text = output.take(maxChars) + "\n…（已截断，原输出超过 64KB）",
                truncated = true,
            )
        }
}

// ── AppOps（免 Root 权限精细管控通道）────────────────────────────────────

/** appops get 解析出的三态（null = 无法判定，UI 显示 WARN 徽章）。 */
enum class AppOpsState { ALLOW, DENY, DEFAULT }

/** 常用 op 预设（下拉数据源：op 名 + 中文说明）。 */
data class AppOpsPreset(
    val op: String,
    val label: String,
    val detail: String,
)

/** 历史回放反解结果（appops 面板预填用）。 */
data class AppOpsTarget(
    val pkg: String,
    val op: String,
)

object AppOpsCommands {

    /**
     * 常用 op 预设清单。op 名以裸名形态给出（Android 11- 官方形态）；
     * Android 12+ 的 op 名带 android: 前缀（如 android:mock_location），
     * 多数 12+ 设备的 appops 命令兼容裸名，若返回 "No such app ops"
     * 请在自定义输入框改用 android: 前缀形态（两种形态均原样透传）。
     */
    val presets: List<AppOpsPreset> = listOf(
        AppOpsPreset("mock_location", "模拟位置", "允许应用成为系统模拟位置源（Fake GPS 同款通道）"),
        AppOpsPreset("RUN_IN_BACKGROUND", "后台运行", "后台运行资格 —— 关掉可压制后台耗电"),
        AppOpsPreset("RUN_ANY_IN_BACKGROUND", "任意后台运行", "含前台服务的完全后台资格（比后台运行更强）"),
        AppOpsPreset("SYSTEM_ALERT_WINDOW", "悬浮窗", "在其它应用上方绘制悬浮窗 / 覆盖层"),
        AppOpsPreset("WRITE_SETTINGS", "改系统设置", "直接读写 Settings 三张表的资格"),
        AppOpsPreset("REQUEST_INSTALL_PACKAGES", "安装未知应用", "侧载安装其它 APK 的资格"),
        AppOpsPreset("LEGACY_STORAGE", "旧版存储视图", "Android 10 兼容的全盘文件访问视图"),
        AppOpsPreset("AUTO_REVOKE_PERMISSIONS_IF_UNUSED", "闲置权限回收", "长期未使用时自动收回运行时权限"),
        AppOpsPreset("WAKE_LOCK", "唤醒锁", "保持 CPU / 屏幕唤醒的资格（防休眠）"),
        AppOpsPreset("VIBRATE", "振动", "振动器使用资格"),
    )

    /** 查询 op 当前状态（op 兼容 android: 前缀与裸名两种形态，原样透传）。 */
    fun query(pkg: String, op: String): String =
        "appops get ${KitCommands.requireSafeToken(pkg, "包名")} " +
            KitCommands.requireSafeToken(op, "op 名")

    fun setAllow(pkg: String, op: String): String = set(pkg, op, "allow")

    fun setDeny(pkg: String, op: String): String = set(pkg, op, "deny")

    fun setDefault(pkg: String, op: String): String = set(pkg, op, "default")

    private fun set(pkg: String, op: String, mode: String): String =
        "appops set ${KitCommands.requireSafeToken(pkg, "包名")} " +
            "${KitCommands.requireSafeToken(op, "op 名")} $mode"

    /**
     * appops get 输出解析：
     *  - 含 "allow" → [AppOpsState.ALLOW]
     *  - 含 "default" → [AppOpsState.DEFAULT]
     *  - 含 "deny" / "reject" → [AppOpsState.DENY]
     *  - "No such app ops" / 空输出 / 无法识别 → null（绝不猜，UI 显示「无法判定」）
     */
    fun parseAppOps(output: String): AppOpsState? {
        val text = output.trim()
        if (text.isEmpty() || text.contains("No such", ignoreCase = true)) return null
        return when {
            text.contains("allow", ignoreCase = true) -> AppOpsState.ALLOW
            text.contains("default", ignoreCase = true) -> AppOpsState.DEFAULT
            text.contains("deny", ignoreCase = true) ||
                text.contains("reject", ignoreCase = true) -> AppOpsState.DENY

            else -> null
        }
    }

    /** 历史回放：从命令文本反解包名 + op（只识别本文件构造的单空格形态）。 */
    fun parseTarget(command: String): AppOpsTarget? {
        val tokens = command.trim().split(' ')
        if (tokens.size < 4 || tokens[0] != "appops") return null
        if (tokens[1] != "get" && tokens[1] != "set") return null
        return AppOpsTarget(pkg = tokens[2], op = tokens[3])
    }
}

// ── 系统设置（settings get / put 可视化）─────────────────────────────────

object SettingsCommands {

    /** namespace 枚举：只认这三个 CLI 值（大小写敏感，非法即 IllegalArgumentException）。 */
    enum class Namespace(val cli: String) {
        GLOBAL("global"),
        SYSTEM("system"),
        SECURE("secure"),
        ;

        companion object {
            fun fromCli(raw: String): Namespace? =
                entries.firstOrNull { it.cli == raw.trim() }
        }
    }

    /** 常用键预设（点填 namespace + key）。 */
    data class SettingPreset(
        val key: String,
        val namespace: Namespace,
        val label: String,
        val detail: String,
    )

    val presets: List<SettingPreset> = listOf(
        SettingPreset("airplane_mode_on", Namespace.GLOBAL, "飞行模式", "1=开 / 0=关（部分系统需另行广播才即时生效）"),
        SettingPreset("wifi_on", Namespace.GLOBAL, "Wi-Fi 开关", "1=开 / 0=关（svc wifi 通道更稳）"),
        SettingPreset("mobile_data", Namespace.GLOBAL, "移动数据", "1=开 / 0=关（svc data 通道更稳）"),
        SettingPreset("bluetooth_on", Namespace.GLOBAL, "蓝牙开关", "1=开 / 0=关"),
        SettingPreset("device_name", Namespace.GLOBAL, "设备名", "快速分享 / 投放 / 关于手机等界面读取"),
        SettingPreset("headset_connect_enabled", Namespace.GLOBAL, "耳机接管", "插入耳机时自动路由音频的开关"),
        SettingPreset("auto_time", Namespace.GLOBAL, "自动时间", "网络时间同步（临时改时间的敲门砖）"),
        SettingPreset("stay_on_while_plugged_in", Namespace.GLOBAL, "充电常亮", "1=充电时屏幕常亮 / 0=默认熄屏"),
        SettingPreset("window_animation_scale", Namespace.GLOBAL, "窗口动画", "0=关闭窗口动画（数字提速感）"),
        SettingPreset("animator_duration_scale", Namespace.GLOBAL, "属性动画", "0=关闭属性动画"),
        SettingPreset("screen_off_timeout", Namespace.SYSTEM, "熄屏超时", "毫秒值（如 30000 = 30 秒）"),
        SettingPreset("default_input_method", Namespace.SECURE, "默认输入法", "输入法组件 id（切换默认输入法）"),
    )

    fun getSetting(namespace: String, key: String): String {
        val ns = requireNamespace(namespace)
        return "settings get ${ns.cli} ${KitCommands.requireSafeToken(key, "设置键名")}"
    }

    fun putSetting(namespace: String, key: String, value: String): String {
        val ns = requireNamespace(namespace)
        val k = KitCommands.requireSafeToken(key, "设置键名")
        val v = value.trim()
        require(v.isNotEmpty()) { "设置值不能为空" }
        require(v.none { it == '\n' || it == '\r' }) { "设置值不能包含换行" }
        return "settings put ${ns.cli} $k ${ShizukuCommands.shellQuote(v)}"
    }

    private fun requireNamespace(raw: String): Namespace =
        Namespace.fromCli(raw)
            ?: throw IllegalArgumentException(
                "非法 settings 命名空间「$raw」—— 只允许 global / system / secure",
            )

    /** 历史回放：从命令文本反解 namespace + key（+ put 的 value，含解引）。 */
    fun parseTarget(command: String): SettingsTarget? {
        val tokens = command.trim().split(' ')
        if (tokens.size < 4 || tokens[0] != "settings") return null
        val ns = Namespace.fromCli(tokens[2]) ?: return null
        return when (tokens[1]) {
            "get" -> SettingsTarget(namespace = ns, key = tokens[3], value = null)
            "put" -> {
                val rawValue = tokens.drop(4).joinToString(" ")
                SettingsTarget(
                    namespace = ns,
                    key = tokens[3],
                    value = rawValue.ifEmpty { null }?.let { KitCommands.unshellQuote(it) },
                )
            }

            else -> null
        }
    }
}

/** 历史回放反解结果（系统设置面板预填用）。 */
data class SettingsTarget(
    val namespace: SettingsCommands.Namespace,
    val key: String,
    val value: String?,
)

// ── Dumpsys（白名单只读诊断）─────────────────────────────────────────────

object DumpsysCommands {

    /** 白名单条目：args 即命令尾参（精确匹配，非白名单一律拒绝）。 */
    data class DumpsysPreset(
        val args: String,
        val title: String,
        val detail: String,
    )

    /**
     * dumpsys 目标白名单（fail-closed）：只提供只读诊断命令 —— 写操作
     * （battery set / unplug 等）走伪装四件套的专用页，自由拼接输入
     * 一律拒绝，防误伤系统服务。
     */
    val whitelist: List<DumpsysPreset> = listOf(
        DumpsysPreset("battery", "电池服务", "电量 / 充电状态 / 健康度 / 伪装状态一览"),
        DumpsysPreset("meminfo", "内存", "整机与应用内存占用总览（可用 / 已用 / 进程 RSS）"),
        DumpsysPreset("cpuinfo", "CPU 占用", "各进程 CPU 占用快照（百分比 + 用户 / 内核时间）"),
        DumpsysPreset("window", "窗口管理", "焦点窗口 / 窗口层级 / 分辨率状态"),
        DumpsysPreset("activity top", "顶层 Activity", "当前前台 Activity 的任务栈与 Fragment 层级"),
        DumpsysPreset("package", "包管理器", "全部已装包与权限授予状态（输出量大，自动截断）"),
        DumpsysPreset("procstats --hours 3", "进程统计", "近 3 小时进程内存 / 运行时长统计"),
        DumpsysPreset("netstats", "网络统计", "各应用流量收发字节数历史"),
        DumpsysPreset("deviceidle", "打盹模式", "Doze 低功耗状态机：空闲 / 深睡 / 白名单"),
    )

    /** 白名单精确匹配才放行（杜绝 "battery; rm" / "battery set level 0" 等变体）。 */
    fun dumpsys(args: String): String {
        val requested = args.trim()
        val matched = whitelist.firstOrNull { it.args == requested }
            ?: throw IllegalArgumentException(
                "dumpsys 目标「$requested」不在白名单内 —— 已拒绝（fail-closed，防误伤系统服务）",
            )
        return "dumpsys ${matched.args}"
    }
}

// ── 历史记录（filesDir/shizuku-kit/history.json 的纯函数序列化层）──────────

/** 单条执行历史（类型徽章 / 命令 / 退出码 / 耗时 / 时间戳）。 */
@Serializable
data class CommandHistoryItem(
    val type: CommandType,
    val command: String,
    val exitCode: Int,
    val elapsedMs: Long,
    val timestamp: Long,
)

/**
 * 纯函数序列化：List[CommandHistoryItem] ↔ JSON 字符串。
 * 坏 JSON → failure（fail-closed：绝不猜测、绝不静默当作空历史）。
 * 存储顺序为最新在前（push 头插），裁剪只保留最近 [MAX_ITEMS] 条。
 */
object HistoryStore {

    /** 只保留最近 50 条。 */
    const val MAX_ITEMS = 50

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(CommandHistoryItem.serializer())

    fun serialize(items: List<CommandHistoryItem>): String =
        json.encodeToString(serializer, items)

    fun deserialize(raw: String): Result<List<CommandHistoryItem>> = runCatching {
        json.decodeFromString(serializer, raw)
    }.recoverCatching { e ->
        throw IllegalStateException("历史数据损坏（无效 JSON）：${e.message}", e)
    }

    /** 裁剪到最近 [MAX_ITEMS] 条（入参即存储序：最新在前，保留头部）。 */
    fun trim(items: List<CommandHistoryItem>): List<CommandHistoryItem> =
        if (items.size <= MAX_ITEMS) items else items.take(MAX_ITEMS)

    /** 头插新记录并裁剪（时间倒序语义由存储顺序维持）。 */
    fun push(items: List<CommandHistoryItem>, item: CommandHistoryItem): List<CommandHistoryItem> =
        trim(listOf(item) + items)
}
