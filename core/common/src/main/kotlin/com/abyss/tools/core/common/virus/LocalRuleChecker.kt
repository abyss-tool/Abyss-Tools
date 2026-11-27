package com.abyss.tools.core.common.virus

/**
 * 本地启发式特征规则（N-VIR-002，priority=20）。
 *
 * 纯 JVM：基于包名模式/签名指纹/大小等静态信号给出 SUSPICIOUS 判定。
 * 这里不做任何 APK 解析，所有输入都由调用方在 ScanSubject 中准备好。
 *
 * 规则集通过 [addRule] 动态注册（支持后续规则热更新），内置一组常见高风险特征。
 */
class LocalRuleChecker : VerdictChecker {
    override val priority: Int = PRIORITY
    override val name: String = "local-rules"

    /** 一条本地规则：id + 人话语义 + 严重度 + 判定函数。 */
    data class Rule(
        val id: String,
        val label: String,
        val severity: Verdict,
        val match: (ScanSubject) -> Boolean,
    )

    private val rules = java.util.concurrent.CopyOnWriteArrayList<Rule>()

    init {
        // 内置基础规则：检测空包名 + 过大 APK + 空签名（对应"未知来源未签名包"启发式）
        addRule(Rule(
            id = "pkg-empty",
            label = "包名为空或非标准 APK 结构，可能为伪装安装包",
            severity = Verdict.SUSPICIOUS,
        ) { it.packageName.isBlank() && it.apkPath.endsWith(".apk", ignoreCase = true) })

        addRule(Rule(
            id = "apk-unsigned",
            label = "APK 无有效签名（可能为篡改重打包）",
            severity = Verdict.SUSPICIOUS,
        ) { it.signerSha256.isBlank() && it.apkPath.endsWith(".apk", ignoreCase = true) })

        addRule(Rule(
            id = "apk-over-200mb-unnamed",
            label = "200MB 以上无包名 APK，异常特征组合",
            severity = Verdict.SUSPICIOUS,
        ) { it.fileSizeBytes > 200L * 1024 * 1024 && it.packageName.isBlank() })
    }

    fun addRule(rule: Rule) {
        rules.add(rule)
    }

    fun clearRules() = rules.clear()

    fun ruleCount(): Int = rules.size

    override fun check(subject: ScanSubject): List<VerdictReason> {
        val hits = mutableListOf<VerdictReason>()
        for (rule in rules) {
            val matched = runCatching { rule.match(subject) }.getOrDefault(false)
            if (matched) {
                hits += VerdictReason(
                    source = "local-rule:${rule.id}",
                    severity = rule.severity,
                    message = rule.label,
                    detail = "rule=${rule.id}",
                )
                // MALICIOUS 级本地规则命中即短路
                if (rule.severity == Verdict.MALICIOUS) break
            }
        }
        return hits
    }

    companion object {
        const val PRIORITY = 20
    }
}
