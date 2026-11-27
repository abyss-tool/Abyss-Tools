package com.abyss.tools.core.common.virus

/**
 * 用户白名单检查器（N-VIR-006 FR-004，priority=5，最早执行）。
 *
 * 用户在误报面板一键加入白名单后，对应 sha256 进入本表；
 * 后续判定管线命中白名单时直接返回 CLEAN，并在理由链中明确标注
 * "用户已加入白名单"，以便审计追溯（不做静默放行）。
 */
class UserAllowlistChecker : VerdictChecker {
    override val priority: Int = PRIORITY
    override val name: String = "allowlist"

    private val allowlist = java.util.concurrent.ConcurrentHashMap<String, AllowlistEntry>()

    data class AllowlistEntry(
        val addedAtMs: Long,
        val reason: String,
        val addedByUser: Boolean = true,
    )

    fun add(sha256: String, reason: String = "用户手动加入白名单") {
        val normalized = sha256.lowercase().trim()
        require(normalized.length == 64) { "非法 sha256：$sha256" }
        allowlist[normalized] = AllowlistEntry(System.currentTimeMillis(), reason)
    }

    fun remove(sha256: String) = allowlist.remove(sha256.lowercase().trim())

    fun contains(sha256: String): Boolean = allowlist.containsKey(sha256.lowercase().trim())

    fun entries(): Map<String, AllowlistEntry> = allowlist.toMap()

    fun clear() = allowlist.clear()

    override fun check(subject: ScanSubject): List<VerdictReason> {
        val entry = allowlist[subject.sha256.lowercase()] ?: return emptyList()
        return listOf(
            VerdictReason(
                source = "allowlist",
                severity = Verdict.CLEAN,
                message = "该文件已在用户白名单中（${entry.reason}），跳过拦截",
                detail = "addedAt=${entry.addedAtMs}",
            )
        )
    }

    companion object {
        /** 白名单优先级最高（早于黑名单），但理由链仍会记录。 */
        const val PRIORITY = 5
    }
}
