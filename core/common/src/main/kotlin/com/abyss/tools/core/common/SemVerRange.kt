package com.abyss.tools.core.common

/**
 * SemVer 版本区间（ADR-100 D-5 / ARC-102 §3 —— 依赖解析的版本比较语言）。
 *
 * 形态：空格分隔的约束序列（`>=0.2.0 <1.0.0`），每条约束 = 操作符 +
 * SemVer。**统一用 [SemVer] 比较，禁止 `split('.')` 手写数值比较**
 * （DC-100 §3 / FR-003）。
 *
 * 解析失败返回 null —— 调用方负责 fail-closed（坏区间一律视为
 * 「不兼容」，绝不猜测）。
 */
data class SemVerRange(val constraints: List<Constraint>) {

    /** 单条约束：操作符 + 版本锚点。 */
    data class Constraint(val op: Op, val version: SemVer) {
        fun check(candidate: SemVer): Boolean {
            val c = candidate.compareTo(version)
            return when (op) {
                Op.EQ -> c == 0
                Op.GT -> c > 0
                Op.GTE -> c >= 0
                Op.LT -> c < 0
                Op.LTE -> c <= 0
            }
        }
    }

    /** 区间操作符（声明顺序即解析匹配优先级：双字符在前）。 */
    enum class Op(val token: String) {
        GTE(">="),
        LTE("<="),
        EQ("=="),
        GT(">"),
        LT("<"),
    }

    init {
        require(constraints.isNotEmpty()) { "区间至少含一条约束" }
    }

    /** 候选版本是否满足全部约束。 */
    fun matches(candidate: SemVer): Boolean = constraints.all { it.check(candidate) }

    /** 人话形态（诊断与 UI 展示用，与声明形态一致）。 */
    override fun toString(): String = constraints.joinToString(" ") { "${it.op.token}${it.version}" }

    companion object {
        /** 解析 `>=0.2.0 <1.0.0` / `>=1.0.0` / `==2.3.1` 等形态；非法返回 null。 */
        fun parse(raw: String): SemVerRange? {
            val parts = raw.trim().split(Regex("\\s+"))
            if (parts.isEmpty() || parts.any { it.isBlank() }) return null
            val constraints = parts.mapNotNull { part ->
                val op = Op.entries.firstOrNull { part.startsWith(it.token) } ?: return null
                val version = SemVer.parse(part.substring(op.token.length)) ?: return null
                Constraint(op, version)
            }
            if (constraints.size != parts.size) return null
            return SemVerRange(constraints)
        }
    }
}
