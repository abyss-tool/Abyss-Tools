package com.abyss.tools.core.common.virus

/**
 * 信任判定管线（N-VIR-002，纯 JVM 可单测）。
 *
 * 管线按 checker.priority 升序执行：allowlist → blacklist → local-rules → cloud。
 * 短路规则：
 *  1. 白名单命中：直接返回 CLEAN（理由链中仍保留 allowlist 记录以便审计）。
 *  2. MALICIOUS 命中：立即停止后续 checker，返回 MALICIOUS。
 *  3. 所有 checker 无命中：返回 UNKNOWN（FR-001 明确 UNKNOWN 不得当 CLEAN）。
 *  4. SUSPICIOUS 命中但无 MALICIOUS：继续跑完所有 checker，最终 SUSPICIOUS。
 *
 * 管线不做 IO：黑名单/规则/云查询的数据由调用方预先注入 checker。
 *
 * 线程安全：checkers 列表用 CopyOnWrite 语义；scan 过程只读不改。
 */
class VerdictPipeline(
    val checkers: List<VerdictChecker>,
) {
    /** 用默认 4 个 checker 构造（allowlist → blacklist → local → cloud-占位）。 */
    constructor(
        allowlist: UserAllowlistChecker = UserAllowlistChecker(),
        blacklist: HashBlacklistChecker = HashBlacklistChecker(),
        localRules: LocalRuleChecker = LocalRuleChecker(),
        cloud: CloudChecker = CloudUnavailableChecker(),
    ) : this(listOf(allowlist, blacklist, localRules, cloud))

    private val sorted = checkers.sortedBy { it.priority }

    /**
     * 对 [subject] 执行完整判定管线，返回 [VerdictReport]。
     *
     * 不会抛异常；单个 checker 抛异常时捕获并记录为一条 UNKNOWN 理由。
     */
    fun scan(subject: ScanSubject): VerdictReport {
        val reasons = mutableListOf<VerdictReason>()
        var top: Verdict = Verdict.CLEAN
        var shortCircuited = false

        for (checker in sorted) {
            if (shortCircuited) break
            val hits = runCatching { checker.check(subject) }
                .getOrElse { t ->
                    listOf(VerdictReason(
                        source = "checker-error:${checker.name}",
                        severity = Verdict.UNKNOWN,
                        message = "检查器 ${checker.name} 异常：${t.message ?: t.javaClass.simpleName}",
                        detail = t.javaClass.name,
                    ))
                }

            for (hit in hits) {
                reasons += hit
                when (hit.severity) {
                    Verdict.MALICIOUS -> {
                        top = Verdict.MALICIOUS
                        shortCircuited = true // 黑名单/MALICIOUS 立即短路
                        break
                    }
                    // 严重度聚合只比 severityWeight（枚举 ordinal 声序 ≠ 严重度，
                    // 且成员 compareTo 会遮蔽任何扩展 operator —— 详见 Verdict KDoc）
                    Verdict.SUSPICIOUS -> if (top.severityWeight < hit.severity.severityWeight) top = Verdict.SUSPICIOUS
                    Verdict.UNKNOWN -> if (top.severityWeight < hit.severity.severityWeight) top = Verdict.UNKNOWN
                    Verdict.CLEAN -> {
                        // 白名单命中（source=allowlist）：直接 CLEAN 短路
                        if (hit.source == "allowlist") {
                            top = Verdict.CLEAN
                            shortCircuited = true
                            break
                        }
                    }
                }
            }
        }

        // FR-001：完全无命中（所有 checker 都返回空）→ UNKNOWN
        if (top == Verdict.CLEAN && reasons.none { it.severity == Verdict.CLEAN }) {
            top = Verdict.UNKNOWN
            reasons += VerdictReason(
                source = "pipeline",
                severity = Verdict.UNKNOWN,
                message = "无匹配规则，判定为未知（未静默放行）",
                detail = "checkers=${sorted.map { it.name }}",
            )
        }

        return VerdictReport(
            verdict = top,
            reasons = reasons.toList(),
            subjectSha256 = subject.sha256,
        )
    }

}
