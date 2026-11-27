package com.abyss.tools.core.common.virus

/**
 * 判定检查器统一接口（N-VIR-002 FR-004）。
 *
 * 管线按 [priority] 升序执行：blacklist(10) → local-rules(20) → cloud(30)，
 * 一旦有 MALICIOUS 命中即短路；每个检查器可返回 0..N 个 [VerdictReason]。
 *
 * 实现类必须是纯 JVM / 无 Android 依赖：IO 由调用方在管线外完成，
 * 检查器仅根据传入的 [ScanSubject] 和自身持有的内存数据给出结论。
 */
interface VerdictChecker {
    /** 执行优先级，越小越先跑。MALICIOUS 命中后后续优先级不会执行。 */
    val priority: Int

    /** 检查器可读名，用于审计。 */
    val name: String

    /**
     * 对 [subject] 执行检查，返回命中的理由列表（空表示无命中）。
     *
     * 不应抛异常；若自身数据源不可用（如云连接失败），应返回一条
     * severity=UNKNOWN 且 source 带 "unavailable" 标识的 VerdictReason，
     * 让上层管线可识别"云不可达"并对用户提示。
     */
    fun check(subject: ScanSubject): List<VerdictReason>
}
