package com.abyss.tools.core.common.virus

/**
 * 处置决策执行器（纯 JVM，N-VIR-006）。
 *
 * 责任：
 *  1. 根据 [VirusDispositionPolicy] + [VerdictReport] 决定 [Disposition]。
 *  2. 决策结果写入 [VirusAuditLog]。
 *  3. 提供误报申诉入口 [reportFalsePositive]（加白名单 + 记录审计）。
 *
 * 真正的系统级动作（拦截 Session / 卸载 APK）由 Android 侧在调用本执行器
 * 拿到 [Disposition] 后执行，本类保持纯 JVM 可测。
 */
class VirusDispositionExecutor(
    val pipeline: VerdictPipeline,
    val allowlist: UserAllowlistChecker,
    val policy: VirusDispositionPolicy = VirusDispositionPolicy(),
) {
    /** 扫描并产出处置决定（自动写审计）。 */
    fun evaluate(subject: ScanSubject): Pair<VerdictReport, Disposition> {
        val report = pipeline.scan(subject)

        // 白名单命中：直接放行
        val allowHit = report.reasons.any { it.source == "allowlist" }
        if (allowHit && policy.trustAllowlist) {
            VirusAuditLog.record(
                action = VirusAuditLog.Action.AUTO_PASS,
                verdict = report.verdict,
                packageName = subject.packageName,
                sha256 = subject.sha256,
                operator = VirusAuditLog.Operator.PIPELINE,
                result = "allowlist-hit",
                reasons = report.reasons.map { it.message },
            )
            return report to Disposition.Pass("用户白名单命中")
        }

        val disposition = when (report.verdict) {
            Verdict.CLEAN -> {
                if (policy.autoPassClean) {
                    VirusAuditLog.record(
                        VirusAuditLog.Action.AUTO_PASS, Verdict.CLEAN, subject.packageName, subject.sha256,
                        VirusAuditLog.Operator.PIPELINE, "clean-pass", report.reasons.map { it.message })
                    Disposition.Pass("扫描通过")
                } else {
                    VirusAuditLog.record(
                        VirusAuditLog.Action.SCAN_HIT, Verdict.CLEAN, subject.packageName, subject.sha256,
                        VirusAuditLog.Operator.PIPELINE, "clean-prompt")
                    Disposition.PromptUser("无风险，是否继续安装？", report)
                }
            }

            Verdict.SUSPICIOUS -> {
                VirusAuditLog.record(
                    VirusAuditLog.Action.SCAN_HIT, Verdict.SUSPICIOUS, subject.packageName, subject.sha256,
                    VirusAuditLog.Operator.PIPELINE, "suspicious-prompt", report.reasons.map { it.message })
                if (policy.autoPassSuspicious) {
                    Disposition.Pass("可疑但已配置自动放行")
                } else {
                    Disposition.PromptUser(
                        "发现可疑特征（${report.topReason()?.message ?: "未知"}），是否继续安装？",
                        report,
                    )
                }
            }

            Verdict.MALICIOUS -> {
                VirusAuditLog.record(
                    VirusAuditLog.Action.SCAN_HIT, Verdict.MALICIOUS, subject.packageName, subject.sha256,
                    VirusAuditLog.Operator.PIPELINE, "malicious-block", report.reasons.map { it.message })
                if (policy.blockMalicious) {
                    Disposition.BlockInstall(
                        "已拦截疑似恶意软件安装：${report.topReason()?.message ?: "命中黑名单"}",
                        report,
                    )
                } else {
                    Disposition.PromptUser("检测到恶意软件，是否仍要安装？", report)
                }
            }

            Verdict.UNKNOWN -> {
                VirusAuditLog.record(
                    VirusAuditLog.Action.SCAN_HIT, Verdict.UNKNOWN, subject.packageName, subject.sha256,
                    VirusAuditLog.Operator.PIPELINE, "unknown-prompt", report.reasons.map { it.message })
                if (policy.blockUnknown) {
                    Disposition.BlockInstall("无法判定安全性，已按策略拦截", report)
                } else {
                    val cloudMsg = if (report.cloudFailed()) "（云查毒不可用）" else ""
                    Disposition.PromptUser("未查到明确风险$cloudMsg，是否继续安装？", report)
                }
            }
        }

        // 若策略要求自动卸载（仅限用户显式开启，默认 false），替换为 Uninstall
        if (disposition is Disposition.BlockInstall &&
            policy.autoUninstallMalicious && report.verdict == Verdict.MALICIOUS) {
            VirusAuditLog.record(
                VirusAuditLog.Action.BLOCKED, Verdict.MALICIOUS, subject.packageName, subject.sha256,
                VirusAuditLog.Operator.SYSTEM, "auto-uninstall", report.reasons.map { it.message })
            return report to Disposition.Uninstall(disposition.message, report)
        }

        return report to disposition
    }

    /**
     * 用户对弹窗做出选择后调用，记录用户操作。
     * @param allowed true=用户确认继续安装/放行；false=用户取消。
     */
    fun onUserDecision(subject: ScanSubject, report: VerdictReport, allowed: Boolean, reason: String = "") {
        val action = if (allowed) VirusAuditLog.Action.USER_OVERRIDE else VirusAuditLog.Action.BLOCKED
        val result = if (allowed) "user-allowed" else "user-blocked"
        VirusAuditLog.record(
            action = action,
            verdict = report.verdict,
            packageName = subject.packageName,
            sha256 = subject.sha256,
            operator = VirusAuditLog.Operator.USER,
            result = result + if (reason.isNotBlank()) ":$reason" else "",
            reasons = report.reasons.map { it.message },
        )
    }

    /**
     * 误报申诉入口（N-VIR-006 FR-004）：把 sha256 加入白名单，
     * 并记录 FALSE_POSITIVE_REPORT 审计；对已阻断的安装提供"撤销处置"能力。
     */
    fun reportFalsePositive(
        subject: ScanSubject,
        reason: String = "用户标记为误报",
        canReverse: Boolean = false,
    ) {
        allowlist.add(subject.sha256, reason)
        VirusAuditLog.record(
            action = VirusAuditLog.Action.FALSE_POSITIVE_REPORT,
            verdict = null,
            packageName = subject.packageName,
            sha256 = subject.sha256,
            operator = VirusAuditLog.Operator.USER,
            result = if (canReverse) "allowlist-added-reversible" else "allowlist-added",
            reasons = listOf(reason),
        )
        if (canReverse) {
            VirusAuditLog.record(
                action = VirusAuditLog.Action.DISPOSITION_REVERSED,
                verdict = null,
                packageName = subject.packageName,
                sha256 = subject.sha256,
                operator = VirusAuditLog.Operator.USER,
                result = "reversed",
            )
        }
    }

    /** 把 sha256 移出白名单。 */
    fun removeFromAllowlist(subject: ScanSubject, reason: String = "用户移出白名单") {
        allowlist.remove(subject.sha256)
        VirusAuditLog.record(
            action = VirusAuditLog.Action.ALLOWLIST_REMOVE,
            verdict = null,
            packageName = subject.packageName,
            sha256 = subject.sha256,
            operator = VirusAuditLog.Operator.USER,
            result = reason,
        )
    }
}
