package com.abyss.tools.core.common.virus

/**
 * 哈希黑名单检查器（N-VIR-002 FR-003，priority=10 最先执行）。
 *
 * 维护一份 sha256→描述 的黑名单集合，命中立即返回 MALICIOUS。
 * 支持运行时增量更新（从远端签名拉取后由调用方调用 [addAll]），
 * 线程安全：CopyOnWrite 语义由 AtomicReference 保证。
 */
class HashBlacklistChecker : VerdictChecker {
    override val priority: Int = PRIORITY
    override val name: String = "blacklist"

    private val blacklist = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun add(sha256: String, description: String) {
        val normalized = sha256.lowercase().trim()
        require(normalized.length == 64) { "非法 sha256：$sha256" }
        blacklist[normalized] = description
    }

    fun addAll(entries: Map<String, String>) {
        entries.forEach { (k, v) -> add(k, v) }
    }

    fun remove(sha256: String) {
        blacklist.remove(sha256.lowercase().trim())
    }

    fun clear() = blacklist.clear()

    fun size(): Int = blacklist.size

    fun contains(sha256: String): Boolean = blacklist.containsKey(sha256.lowercase().trim())

    override fun check(subject: ScanSubject): List<VerdictReason> {
        val desc = blacklist[subject.sha256.lowercase()] ?: return emptyList()
        return listOf(
            VerdictReason(
                source = "blacklist",
                severity = Verdict.MALICIOUS,
                message = "命中本地恶意软件哈希黑名单：$desc",
                detail = "sha256=${subject.sha256}; desc=$desc",
            )
        )
    }

    companion object {
        const val PRIORITY = 10
    }
}
