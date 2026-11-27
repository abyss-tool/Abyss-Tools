package com.abyss.tools.core.common.virus

/**
 * 安装监听模式（N-VIR-001 FR-001/FR-002/FR-003）。
 *
 *  - SHIZUKU_SESSION：Shizuku 可用时挂接 pm install-session 监听，可主动拦截。
 *  - BROADCAST_ONLY：无 Shizuku 时降级为 PACKAGE_ADDED 广播监听，**仅提示**不拦截。
 *  - POLLING_DUMPSYS：ROM 不发广播/会话回调时，dumpsys package 轮询降级（兜底）。
 *  - DISABLED：用户未授权或服务未启动。
 */
enum class InstallMonitorMode {
    SHIZUKU_SESSION,
    BROADCAST_ONLY,
    POLLING_DUMPSYS,
    DISABLED,
}

/**
 * 安装事件（被管线消费的最小数据单元）。
 */
data class InstallEvent(
    val packageName: String,
    val installerPackageName: String? = null,
    val sessionId: Int? = null,
    val apkPath: String? = null,
    val sha256: String? = null,
    val signerSha256: String? = null,
    val fileSizeBytes: Long = 0L,
    val mode: InstallMonitorMode,
    val receivedAtMs: Long = System.currentTimeMillis(),
)
