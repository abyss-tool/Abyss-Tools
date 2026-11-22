package com.abyss.tools.core.common.diagnostics

/**
 * 一键诊断包（N-EXP-003 / N-UPD-003 收口 —— T4 剩余缺口）。
 *
 * 组成（全部本地合成，零外传 —— 导出只写文件，分享由用户显式发起）：
 *  - 设备快照：ROM/SDK/ABI/权限层（Shizuku 可用性）/ 能力探测结果 ——
 *    由调用方注入字符串快照（Android 侧读取 Build.* / ShizukuMonitor /
 *    能力探测存储），本对象只做**汇总与脱敏**（纯 JVM 可单测）；
 *  - 诊断日志尾部（DiagnosticsLog recent）；
 *  - 最近错误码分布（errorCounts 人话映射）。
 *
 * **脱敏纪律**：除 [allowedPackages] 白名单内的包名外，任何
 * `com.xxx.yyy` 形态的包名一律替换为 `«pkg-redacted»` —— 诊断包可能在
 * 用户求助时被贴到公开渠道，未授权包名（含用户已装应用清单）不属于
 * 可披露范围（SEC-009 隐私最小化）。
 */
object DiagnosticBundle {

    const val REDACTED = "«pkg-redacted»"

    /** 包名形态（宽松：2 段以上小写字母数字点分）。 */
    private val PACKAGE_LIKE = Regex("""(?i)\b([a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+)\b""")

    /**
     * 生成诊断包文本（唯一出口）。
     * @param sections 设备快照分节（标题 → 内容），由 Android 侧注入
     * @param logTail 诊断日志尾部（每行一条，已含时间戳）
     * @param errorSummary 错误分布摘要（Kind.label → 次数）
     * @param allowedPackages 脱敏白名单（本包名 + 宿主/系统组件等）
     */
    fun build(
        sections: List<Pair<String, String>>,
        logTail: List<String>,
        errorSummary: Map<String, Int>,
        allowedPackages: Set<String>,
    ): String = buildString {
        appendLine("abyss-diag-bundle-v1")
        appendLine("generated: ${System.currentTimeMillis()}")
        appendLine()
        for ((title, body) in sections) {
            appendLine("## $title")
            appendLine(redact(body, allowedPackages))
            appendLine()
        }
        appendLine("## 最近错误分布")
        if (errorSummary.isEmpty()) {
            appendLine("(无错误记录)")
        } else {
            for ((k, v) in errorSummary.entries.sortedByDescending { it.value }) {
                appendLine("- $k × $v")
            }
        }
        appendLine()
        appendLine("## 诊断日志尾部（${logTail.size} 条）")
        for (line in logTail) {
            appendLine(redact(line, allowedPackages))
        }
    }

    /**
     * 包名脱敏：白名单外 `com.xxx.yyy` 形态 → [REDACTED]。
     * 白名单匹配大小写不敏感；URL 的 host 部分形态与包名同形 —— 本包
     * 语义下 host（raw.githubusercontent.com 等）也属可脱敏项（保守方向
     * 多脱不漏脱，诊断包读者可据上下文识别域名）。
     */
    fun redact(text: String, allowedPackages: Set<String>): String {
        if (text.isEmpty()) return text
        val allowed = allowedPackages.map { it.lowercase() }.toSet()
        return PACKAGE_LIKE.replace(text) { m ->
            val pkg = m.groupValues[1]
            if (pkg.lowercase() in allowed) pkg else REDACTED
        }
    }
}
