package com.abyss.tools.core.common.virus

/**
 * 判定结果枚举（N-VIR-002 FR-001）。
 *
 *  - CLEAN：所有启用的检查器均未发现威胁。
 *  - SUSPICIOUS：至少一个 SUSPICIOUS 级规则命中，未命中 MALICIOUS。
 *  - MALICIOUS：命中 MALICIOUS 级规则（哈希黑名单/本地高权特征/云返回恶意）。
 *  - UNKNOWN：管线无有效信号（如云查询不可达且本地规则无命中），**绝不静默放行**。
 *
 * 短路策略：MALICIOUS 命中后立即停止后续检查。
 */
enum class Verdict {
    CLEAN,
    SUSPICIOUS,
    MALICIOUS,
    UNKNOWN,
}

/**
 * 单条判定理由（N-VIR-002 FR-002）。
 *
 * 每一条命中规则/签名/云查询结果都产生一个 VerdictReason，
 * 供 VerdictReport.reasons 形成可审计的理由链。
 *
 * @property source 判定来源标识（"blacklist"/"local-rule:rule_id"/"cloud:virustotal"/"allowlist"/...）
 * @property severity 本条理由的严重度（对应 Verdict 等级）
 * @property message 人话说明（展示给用户、写入审计）
 * @property detail 机器可读细节（规则ID/匹配串/云结果数等），不向用户展示但进入审计日志。
 */
data class VerdictReason(
    val source: String,
    val severity: Verdict,
    val message: String,
    val detail: String = "",
)
