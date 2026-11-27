package com.abyss.tools.core.common.virus

/**
 * 判定管线最终报告（N-VIR-002 §4 契约）。
 *
 * @property verdict 最终结论
 * @property reasons 可审计的理由链（按命中顺序排列，MALICIOUS 命中短路后只包含短路点及之前的理由）
 * @property scannedAtMs 判定完成时间戳
 * @property subjectSha256 对应 ScanSubject.sha256，方便审计关联
 */
data class VerdictReport(
    val verdict: Verdict,
    val reasons: List<VerdictReason>,
    val scannedAtMs: Long = System.currentTimeMillis(),
    val subjectSha256: String,
) {
    /** 是否由黑名单命中导致（便利判断，UI 高亮用）。 */
    fun isBlacklistHit(): Boolean = reasons.any { it.source == "blacklist" && it.severity == Verdict.MALICIOUS }

    /** 是否命中本地规则（非云、非黑名单）。 */
    fun isLocalRuleHit(): Boolean = reasons.any { it.source.startsWith("local-rule:") }

    /** 是否云查询返回结果。 */
    fun isCloudHit(): Boolean = reasons.any { it.source.startsWith("cloud:") }

    /** 是否有云查询失败记录（UNKNOWN 下用于提示用户"云查询不可用"）。 */
    fun cloudFailed(): Boolean = reasons.any { it.source == "cloud:unavailable" }

    /** 最高严重度原因（用于弹窗标题）。 */
    fun topReason(): VerdictReason? = reasons.maxByOrNull { it.severity.ordinal }
}

/** Verdict 严重度排序序号：CLEAN=0 < UNKNOWN=1 < SUSPICIOUS=2 < MALICIOUS=3。 */
private val Verdict.ordinal: Int
    get() = when (this) {
        Verdict.CLEAN -> 0
        Verdict.UNKNOWN -> 1
        Verdict.SUSPICIOUS -> 2
        Verdict.MALICIOUS -> 3
    }
