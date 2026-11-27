package com.abyss.tools.core.common.virus

/**
 * 处置策略配置（N-VIR-006 FR-001）。
 *
 * 默认配置下任何 MALICIOUS 判定都**只提示不自动卸载**，
 * 由用户手动确认后才执行处置（Block/Uninstall/AddAllowlist）。
 */
data class VirusDispositionPolicy(
    /** CLEAN 自动放行。 */
    val autoPassClean: Boolean = true,
    /** SUSPICIOUS：默认提示，不阻断但弹确认。 */
    val autoPassSuspicious: Boolean = false,
    /** MALICIOUS：默认拦截但不自动卸载，必须用户确认。 */
    val blockMalicious: Boolean = true,
    val autoUninstallMalicious: Boolean = false, // FR-001：默认 false
    /** UNKNOWN：提示用户云查询不可达，由用户决定是否继续。 */
    val blockUnknown: Boolean = false,
    /** 用户白名单命中时直接放行。 */
    val trustAllowlist: Boolean = true,
) {
    init {
        // FR-001：autoUninstallMalicious 默认 false 是安全约束，此处硬约束不允许构造为 true 的默认版本
        require(!autoUninstallMalicious) {
            "默认策略禁止 autoUninstallMalicious=true；如需自动卸载必须显式构造且经用户设置开关。"
        }
    }
}

/**
 * 处置结果（由 DispositionExecutor 产出；纯 JVM 侧的"决定"，真正卸载/放行操作由 Android 侧完成）。
 */
sealed class Disposition {
    /** 放行：通过管线无阻断。 */
    data class Pass(val reason: String) : Disposition()
    /** 提示用户确认（SUSPICIOUS/UNKNOWN 场景）。 */
    data class PromptUser(val message: String, val report: VerdictReport) : Disposition()
    /** 拦截安装（不卸载已安装的包）。 */
    data class BlockInstall(val message: String, val report: VerdictReport) : Disposition()
    /** 卸载（仅当用户手动确认 + 策略显式开启）。 */
    data class Uninstall(val message: String, val report: VerdictReport) : Disposition()
}
