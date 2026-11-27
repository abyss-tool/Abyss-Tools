package com.abyss.tools.core.common.virus

/**
 * 云查询检查器（N-VIR-002，priority=30，N-VIR-004 的抽象占位接口）。
 *
 * 设计：
 *  - 本文件只定义**接口 + 默认不可达实现**（CloudUnavailableChecker），
 *    确保管线在云查询未配置/用户关闭/网络失败时，严格遵循 FR-005
 *    "云不可用时降级为 UNKNOWN 并提示，不静默放行"。
 *  - 真正的 VirusTotal 调度（N-VIR-004）在后续里程碑接入
 *    `CloudChecker.lookup()`，由上层注入。
 */
interface CloudChecker : VerdictChecker {
    /** 用户是否启用云查询（UI 开关控制，默认 false）。 */
    var enabled: Boolean

    /** 本次调用的网络状态，由调用方在 scan 前设置。 */
    var networkAvailable: Boolean
}

/**
 * 默认云查询占位：始终返回一条 "cloud:unavailable" 的 UNKNOWN 理由，
 * 保证管线不会把"未配置云查询"误判为 CLEAN。
 */
class CloudUnavailableChecker : CloudChecker {
    override val priority: Int = PRIORITY
    override val name: String = "cloud"

    override var enabled: Boolean = false
    override var networkAvailable: Boolean = false

    override fun check(subject: ScanSubject): List<VerdictReason> {
        val msg = when {
            !enabled -> "云查毒未开启（用户未授权外传哈希）"
            !networkAvailable -> "云查毒失败：网络不可达"
            else -> "云查毒未配置查询实现"
        }
        return listOf(
            VerdictReason(
                source = "cloud:unavailable",
                severity = Verdict.UNKNOWN,
                message = msg,
                detail = "enabled=$enabled network=$networkAvailable",
            )
        )
    }

    companion object {
        const val PRIORITY = 30
    }
}
