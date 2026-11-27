package com.abyss.tools.core.common.virus

/**
 * N-VIR-002 · 信任判定管线（纯 JVM 可测）
 *
 * 判定管线：哈希黑名单 → 本地特征 → 云查询（按优先级短路），输出 verdict + 理由链。
 * 输入为 APK 文件路径 + SHA-256 + 包名 + 签名摘要，管线本身不做 IO。
 *
 * 判定结果四态：CLEAN / SUSPICIOUS / MALICIOUS / UNKNOWN。
 * UNKNOWN 不得当作 CLEAN；云查询不可用时降级 UNKNOWN 而非静默放行。
 */

/** 待扫描主体信息（不持有字节流，管线不直接做 IO） */
data class ScanSubject(
    val apkPath: String,
    val sha256: String,
    val packageName: String,
    val signerSha256: String,
    val installerPackageName: String? = null,
)

/** 判定结果枚举 */
enum class Verdict {
    /** 经规则/签名/白名单明确可信 */
    CLEAN,

    /** 存在疑点（签名异常/特征可疑/云返回多引擎命中数低），需人工确认 */
    SUSPICIOUS,

    /** 明确命中恶意特征/黑名单/云多引擎高命中，建议拦截 */
    MALICIOUS,

    /** 信息不足无法判定，不得当作 CLEAN */
    UNKNOWN,
}

/** 单条判定理由 */
data class VerdictReason(
    /** 规则 ID（如 HASH_BLACKLIST/LOCAL_SIGN/CLOUD_QUERY/WHITELIST/...） */
    val ruleId: String,
    /** 人类可读描述 */
    val description: String,
    /** 严重性权重（0=无害；正值=威胁，越大越恶；负值=可信证据） */
    val severity: Int,
) {
    override fun toString(): String = "[$ruleId] $description"
}

/** 判定报告 */
data class VerdictReport(
    val verdict: Verdict,
    val reasons: List<VerdictReason>,
) {
    /** 合并理由为文本链，用于审计与展示 */
    fun reasonChain(): String = reasons.joinToString(" → ") { it.toString() }

    companion object {
        fun unknown(reason: String) = VerdictReport(
            verdict = Verdict.UNKNOWN,
            reasons = listOf(VerdictReason("UNKNOWN", reason, 0))
        )
    }
}

/** 单条判定规则接口（纯 JVM，无 IO 依赖） */
interface VerdictRule {
    /** 规则 ID */
    val id: String

    /** 优先级（越小越早执行；执行顺序决定短路时机） */
    val priority: Int

    /**
     * 对 subject 进行判定，返回该规则给出的理由列表；
     * 若规则不适用/无命中，返回空列表。
     * 规则不得抛异常；自身不可达时返回原因理由（severity=0）。
     */
    fun evaluate(subject: ScanSubject): List<VerdictReason>
}

/** 云查询结果（仅模型，调用由外部层提供） */
sealed class CloudQueryResult {
    /** 云端明确为干净（0 命中/已在 Google Play/AppGallery 发布者白名单） */
    data class Clean(val note: String) : CloudQueryResult()

    /** 云端返回命中数 */
    data class Detections(val positives: Int, val total: Int) : CloudQueryResult()

    /** 云查询不可达/超时/限流 */
    data class Unavailable(val message: String) : CloudQueryResult()
}

/** 云查询客户端接口（管线不发起 IO，调用方注入实现） */
interface CloudQueryClient {
    /** 对 sha256 发起查询；网络异常/限流返回 CloudQueryResult.Unavailable */
    fun query(sha256: String): CloudQueryResult
}

/**
 * 哈希黑名单规则：命中即 MALICIOUS。
 */
class HashBlacklistRule(
    private val blacklistSha256: Set<String>,
) : VerdictRule {
    override val id = "HASH_BLACKLIST"
    override val priority = 10

    override fun evaluate(subject: ScanSubject): List<VerdictReason> {
        if (subject.sha256 in blacklistSha256) {
            return listOf(
                VerdictReason(
                    ruleId = id,
                    description = "SHA-256 命中本地黑名单：${subject.sha256.take(16)}…",
                    severity = 100,
                )
            )
        }
        return emptyList()
    }
}

/**
 * 发布者签名白名单规则：系统签名或深渊发布者指纹视为 CLEAN。
 */
class SignerWhitelistRule(
    private val trustedSignerSha256: Set<String>,
) : VerdictRule {
    override val id = "SIGNER_WHITELIST"
    override val priority = 20

    override fun evaluate(subject: ScanSubject): List<VerdictReason> {
        if (subject.signerSha256 in trustedSignerSha256) {
            return listOf(
                VerdictReason(
                    ruleId = id,
                    description = "签名指纹在受信发布者白名单内：${subject.signerSha256.take(16)}…",
                    severity = -100,
                )
            )
        }
        return emptyList()
    }
}

/**
 * 本地包名/签名特征规则：识别已知可疑包名模式或签名异常。
 */
class LocalHeuristicRule(
    /** 包名黑名单（如伪装系统包名模式 com.android.* 但签名非系统） */
    private val suspiciousPackagePatterns: List<Regex> = DEFAULT_SUSPICIOUS_PATTERNS,
) : VerdictRule {
    override val id = "LOCAL_HEURISTIC"
    override val priority = 30

    override fun evaluate(subject: ScanSubject): List<VerdictReason> {
        val hits = mutableListOf<VerdictReason>()
        for (pattern in suspiciousPackagePatterns) {
            if (pattern.containsMatchIn(subject.packageName)) {
                hits += VerdictReason(
                    ruleId = id,
                    description = "包名匹配可疑模式：${pattern.pattern}（包名 ${subject.packageName}）",
                    severity = 40,
                )
            }
        }
        // SHA-256 格式合法性校验（管线不做 IO，但要 fail-closed 对非法输入）
        if (!HEX64.matches(subject.sha256)) {
            hits += VerdictReason(
                ruleId = id,
                description = "SHA-256 格式非法：${subject.sha256.take(32)}…",
                severity = 60,
            )
        }
        return hits
    }

    companion object {
        private val HEX64 = Regex("^[0-9a-fA-F]{64}$")
        val DEFAULT_SUSPICIOUS_PATTERNS: List<Regex> = listOf(
            // 伪装系统更新/系统服务
            Regex("^com\\.android\\.(update|service|security)[a-z0-9]*$"),
            // 典型勒索/流氓包命名
            Regex("(?i)(virus|malware|ransom|spy|keylog)"),
        )
    }
}

/**
 * 云查询规则：按 VirusTotal 风格 positives/total 判定。
 * 云不可达时输出 UNKNOWN 理由（不静默放行）。
 */
class CloudQueryRule(
    private val client: CloudQueryClient,
    /** 云查询启用开关（尊重用户「云查毒默认关闭」偏好） */
    private val enabled: Boolean = false,
    /** MALICIOUS 阈值：positives >= maliciousThreshold 视为恶意 */
    private val maliciousThreshold: Int = 8,
    /** SUSPICIOUS 阈值：positives >= suspiciousThreshold 视为可疑 */
    private val suspiciousThreshold: Int = 2,
) : VerdictRule {
    override val id = "CLOUD_QUERY"
    override val priority = 40

    override fun evaluate(subject: ScanSubject): List<VerdictReason> {
        if (!enabled) {
            return listOf(
                VerdictReason(
                    ruleId = id,
                    description = "云查毒未启用（默认关闭），跳过",
                    severity = 0,
                )
            )
        }
        return when (val r = client.query(subject.sha256)) {
            is CloudQueryResult.Clean -> listOf(
                VerdictReason(ruleId = id, description = "云端返回可信：${r.note}", severity = -50)
            )
            is CloudQueryResult.Detections -> {
                val ratio = if (r.total > 0) r.positives.toDouble() / r.total else 0.0
                when {
                    r.positives >= maliciousThreshold -> listOf(
                        VerdictReason(
                            ruleId = id,
                            description = "云端 ${r.positives}/${r.total} 引擎命中（阈值 $maliciousThreshold）",
                            severity = 90,
                        )
                    )
                    r.positives >= suspiciousThreshold -> listOf(
                        VerdictReason(
                            ruleId = id,
                            description = "云端 ${r.positives}/${r.total} 引擎命中（阈值 $suspiciousThreshold）",
                            severity = 30,
                        )
                    )
                    else -> listOf(
                        VerdictReason(
                            ruleId = id,
                            description = "云端 ${r.positives}/${r.total} 引擎命中，无显著风险",
                            severity = -10,
                        )
                    )
                }
            }
            is CloudQueryResult.Unavailable -> listOf(
                VerdictReason(
                    ruleId = id,
                    description = "云查询不可用：${r.message}（降级 UNKNOWN）",
                    severity = 0,
                )
            )
        }
    }
}

/**
 * 信任判定管线：按优先级排序执行规则，累计 severity 分数并短路到最终 verdict。
 *
 * 短路策略（可通过 VerdictPipelineConfig 调整）：
 * - 任意规则命中 MALICIOUS 阈值 → 立即短路 MALICIOUS；
 * - 执行完全部规则后根据累计分数判定 CLEAN/SUSPICIOUS/UNKNOWN；
 * - 若云查询返回 Unavailable 且此前没有任何强信号 → UNKNOWN（不静默放行）。
 */
data class VerdictPipelineConfig(
    /** MALICIOUS 阈值：单条理由 severity >= 此值即短路 */
    val maliciousThreshold: Int = 80,
    /** SUSPICIOUS 阈值：累计 severity >= 此值判为可疑 */
    val suspiciousThreshold: Int = 30,
    /** CLEAN 阈值：累计 severity <= 此值且有明确可信证据判为干净 */
    val cleanThreshold: Int = -20,
)

class VerdictPipeline(
    rules: List<VerdictRule>,
    private val config: VerdictPipelineConfig = VerdictPipelineConfig(),
) {
    private val sortedRules = rules.sortedBy { it.priority }

    fun judge(subject: ScanSubject): VerdictReport {
        val reasons = mutableListOf<VerdictReason>()
        var cloudUnavailable = false

        for (rule in sortedRules) {
            val hit = try {
                rule.evaluate(subject)
            } catch (t: Throwable) {
                listOf(
                    VerdictReason(
                        ruleId = rule.id,
                        description = "规则执行异常：${t.message ?: t.javaClass.simpleName}（fail-closed 为 UNKNOWN）",
                        severity = 0,
                    )
                )
            }
            reasons += hit
            // 云不可达标记
            if (rule.id == "CLOUD_QUERY") {
                cloudUnavailable = hit.any { it.severity == 0 && it.description.contains("不可用") }
            }
            // 短路：命中强恶意信号
            val maxSeverity = hit.maxOfOrNull { it.severity } ?: Int.MIN_VALUE
            if (maxSeverity >= config.maliciousThreshold) {
                return VerdictReport(verdict = Verdict.MALICIOUS, reasons = reasons)
            }
        }

        val totalScore = reasons.sumOf { it.severity }

        // 云不可达且没有任何可信或恶意强信号 → UNKNOWN
        if (cloudUnavailable && totalScore in (config.cleanThreshold + 1) until config.suspiciousThreshold) {
            return VerdictReport(verdict = Verdict.UNKNOWN, reasons = reasons)
        }

        // 签名白名单命中且无恶意信号 → CLEAN
        val hasTrustedSigner = reasons.any { it.ruleId == "SIGNER_WHITELIST" && it.severity < 0 }
        if (hasTrustedSigner && totalScore < config.suspiciousThreshold) {
            return VerdictReport(verdict = Verdict.CLEAN, reasons = reasons)
        }

        return when {
            totalScore >= config.maliciousThreshold -> VerdictReport(Verdict.MALICIOUS, reasons)
            totalScore >= config.suspiciousThreshold -> VerdictReport(Verdict.SUSPICIOUS, reasons)
            totalScore <= config.cleanThreshold -> VerdictReport(Verdict.CLEAN, reasons)
            else -> VerdictReport(Verdict.UNKNOWN, reasons)
        }
    }
}
