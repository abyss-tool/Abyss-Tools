package com.abyss.tools.feature.netaudit

import java.util.Locale

/**
 * `dumpsys netstats` 输出的尽力而为解析器（纯 Kotlin，零 Android 依赖 —— 可单测，
 * 对齐 ShizukuCommands / AppManagerCommands 的编排纪律）。
 *
 * 为什么是「尽力而为」而不是精确文法解析：
 *  - netstats 的 dump 格式随 Android 版本 / OEM 定制漂移（Dev/Xt 分区形态、
 *    周期桶行、ident 形态都出现过不止一种写法），追一套完整文法只会跟着
 *    ROM 一起碎掉；
 *  - 因此采用**行级 token 归因**：谁（uid=NNN）名下出现 rxBytes=/txBytes=
 *    token，就把字节记到谁头上 —— 对 Android 8~13 实测形态全部命中，
 *    对没见过的形态至少不抛、不猜（fail-closed，坏行直接不吃）；
 *  - 诚实口径：快照聚合的是开机以来各周期桶（bucket）的合计，同一 uid
 *    的重复周期桶会被如实累加 —— 存在重复记账风险时按「上限估计」呈现
 *    （页面文案同步声明），绝不静默去重：去重需要理解周期语义，猜错比
 *    多记更糟。
 *
 * 分区闸（Dev/Xt 双记账的止血带）：真实 dump 里 Dev stats（设备口径）与
 * Xt stats（扩展口径）各自包含一整棵 uid 桶树，两棵都吃必然翻倍。处理：
 * 若先见到「Dev stats:」再到「Xt stats:」，则 Xt 起整段停收；只有 Xt（部分
 * ROM / 新版本形态）时照样收 —— 宁可单口径也不双份。「Active interfaces /
 * Active UID interfaces」等头部区不含字节 token，天然不参与累计。
 */
object NetStatsParser {

    /**
     * 未知归属哨兵 uid：token 行先于任何 uid= 行出现（或 uid 值非数字 /
     * 负数，如设备级 uid=-1）时，字节落进这个桶 —— 绝不张冠李戴到上一个应用。
     */
    const val UNKNOWN_UID: Int = -1

    /** 单个 uid 的流量画像（下载 / 上传字节）。[total] 供榜单排序与条形图分母。 */
    data class UidTraffic(
        val uid: Int,
        val rxBytes: Long,
        val txBytes: Long,
    ) {
        val total: Long get() = rxBytes + txBytes
    }

    /**
     * 一次 dump 的聚合快照。
     *
     * 诚实口径（与页面文案一致）：
     *  - [totalRx]/[totalTx] 是**全部累计桶**之和 —— 含落进 [UNKNOWN_UID]
     *    的无法归属流量：总量如实，绝不为了「榜表对齐」而丢弃或乱挂；
     *  - [perUid] 只保留 uid ≥ 0 的可归因应用（总量降序，同总量按 uid 升序
     *    保证确定性）—— 总量与榜单之和的差值 = 未知归属流量，是能力边界
     *    而不是 bug；
     *  - [parsedLines] = 实际吃到字节 token 的行数（覆盖率信号）：0 行意味
     *    着这台 ROM 的 dump 形态没被认出来，页面据此如实提示。
     */
    data class NetStatsSnapshot(
        val totalRx: Long,
        val totalTx: Long,
        val perUid: List<UidTraffic>,
        val parsedLines: Int,
    )

    /** 分区头（trim 后前缀匹配，容忍 OEM 的缩进差异）。 */
    private const val DEV_STATS_HEADER = "Dev stats:"

    /** 同上；出现顺序语义见 [parse] 的分区闸说明。 */
    private const val XT_STATS_HEADER = "Xt stats:"

    /**
     * uid= 的值（吃到空白/逗号/分号/右括号为止；负号等非数字形态也一并捕获，
     * 交给 [toIntOrNull] 判定归属是否成立）。为什么不用 `uid=(\d+)` 直取：
     * 设备级桶常见 `uid=-1`，若值非数字就放行匹配失败、沿用上一个 lastUid，
     * 设备级流量会被错误挂到上一个应用头上 —— 比落进未知桶更糟。
     */
    private val UID_VALUE = Regex("uid=([^\\s,;\\]}]+)")

    /** 字节 token：等号后必须是纯数字（负数 / 非数字一律不认，fail-closed）。 */
    private val RX_TOKEN = Regex("rxBytes=(\\d+)")

    /** 同 [RX_TOKEN]。 */
    private val TX_TOKEN = Regex("txBytes=(\\d+)")

    /**
     * 解析 dump 输出。规则（为什么这样归因）：
     *  1. 行内先认 uid= 再认字节 token —— ident 行与桶行分离的常规形态、
     *     以及「uid=5 … rxBytes=…」同行形态都能正确归因；
     *  2. uid 值非负整数 → 记为当前归属；负数 / 非数字 → 清空归属（后续
     *     字节落进未知桶，见 [UID_VALUE] 的说明）；
     *  3. 一行可含多个 rxBytes=/txBytes= token（周期桶并排），全部累加到
     *     当前归属；Long 装不下的值丢弃（fail-closed，不猜数量级）；
     *  4. [NetStatsSnapshot.parsedLines] 只统计吃到 token 的行（覆盖率信号）。
     */
    fun parse(dump: String): NetStatsSnapshot {
        var sawDevStats = false
        var counting = true
        var lastUid: Int? = null
        var parsedLines = 0
        val rxByUid = HashMap<Int, Long>()
        val txByUid = HashMap<Int, Long>()

        dump.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach

            // ── 分区闸 ──────────────────────────────────────────────
            when {
                line.startsWith(DEV_STATS_HEADER) -> {
                    sawDevStats = true
                    return@forEach
                }

                line.startsWith(XT_STATS_HEADER) -> {
                    // Dev 在前 → Xt 起停收（防双记账）；Dev 从未出现（只有
                    // Xt 的 ROM 形态）→ 照收，宁可单口径也不翻倍。
                    if (sawDevStats) counting = false
                    return@forEach
                }
            }
            if (!counting) return@forEach

            // ── uid 归属（先认 uid 再认字节）────────────────────────
            UID_VALUE.find(line)?.let { match ->
                val value = match.groupValues[1].toIntOrNull()
                lastUid = if (value != null && value >= 0) value else null
            }

            var tokenOnLine = false
            RX_TOKEN.findAll(line).forEach { match ->
                // 只认非负数字且装得进 Long 的 token；溢出值丢弃（不猜数量级）。
                match.groupValues[1].toLongOrNull()?.let { bytes ->
                    rxByUid.merge(lastUid ?: UNKNOWN_UID, bytes) { acc, add -> acc + add }
                    tokenOnLine = true
                }
            }
            TX_TOKEN.findAll(line).forEach { match ->
                match.groupValues[1].toLongOrNull()?.let { bytes ->
                    txByUid.merge(lastUid ?: UNKNOWN_UID, bytes) { acc, add -> acc + add }
                    tokenOnLine = true
                }
            }
            if (tokenOnLine) parsedLines++
        }

        val perUid = (rxByUid.keys + txByUid.keys)
            .filter { uid -> uid >= 0 } // 未知归属桶不进榜（总量里如实保留）
            .map { uid ->
                UidTraffic(
                    uid = uid,
                    rxBytes = rxByUid[uid] ?: 0L,
                    txBytes = txByUid[uid] ?: 0L,
                )
            }
            .sortedWith(compareByDescending<UidTraffic> { it.total }.thenBy { it.uid })

        return NetStatsSnapshot(
            totalRx = rxByUid.values.sum(),
            totalTx = txByUid.values.sum(),
            perUid = perUid,
            parsedLines = parsedLines,
        )
    }

    /**
     * 流量字节的人话格式化（纯函数，总览卡 / 榜单徽章共用，测试钉死边界）。
     *
     * 口径：1000 进制（与系统 `Formatter.formatFileSize` / 设置页流量数据同
     * 口径，方便用户对照系统 UI）；B 档出整数（512 B），KB/MB/GB 一位小数
     * （1.5 KB），封顶 GB（流量画像量级足够，不再上 TB）；负值防御性按 0
     * 呈现（解析产物只可能是非负数，出现负值说明调用方出错 —— 不猜不补）。
     */
    object Bytes {

        /**
         * 进位阈值：小数四舍五入后达到 1000.0 的值直接升一位单位
         * （避免 "1000.0 KB" 这类丑值，999,999 B → 1.0 MB）。
         */
        private const val BUMP_THRESHOLD = 999.95

        fun format(bytes: Long): String {
            if (bytes <= 0L) return "0 B"
            if (bytes < 1_000L) return "$bytes B"
            val kb = bytes / 1_000.0
            if (kb < BUMP_THRESHOLD) return oneDecimal(kb, "KB")
            val mb = kb / 1_000.0
            if (mb < BUMP_THRESHOLD) return oneDecimal(mb, "MB")
            return oneDecimal(mb / 1_000.0, "GB")
        }

        private fun oneDecimal(value: Double, unit: String): String =
            String.format(Locale.ROOT, "%.1f %s", value, unit)
    }
}
