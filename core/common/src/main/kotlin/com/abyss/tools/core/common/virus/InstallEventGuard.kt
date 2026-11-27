package com.abyss.tools.core.common.virus

/**
 * N-VIR-001 · 安装事件拦截骨架（核心状态机，纯 JVM 可测）。
 *
 * 监听能力分级：
 *  - SESSION_BLOCK：Shizuku 可用，挂接 `pm install-session` 会话监听，可**阻断**安装；
 *  - SESSION_OBSERVE：能拿到会话生命周期，但无权限调用 abandon/commit 拦截，仅记录；
 *  - BROADCAST_ONLY：仅通过 targetSdk 28 的隐式 PACKAGE_ADDED/REMOVED 广播感知（已装完才收到），仅提示；
 *  - POLLING_FALLBACK：dumpsys package 轮询降级，仅观测；
 *  - DISABLED：未授权/Shizuku 未激活，监听降级为 DISABLED。
 *
 * FR-001：Shizuku 可用时挂接 install-session 监听可拦截；不可用时仅提示并明确降级口径。
 * FR-003：不同 ROM 的会话广播差异支持 dumpsys 轮询降级。
 * FR-004：开机恢复监听幂等（状态机保证重复 start 不重复注册）。
 * FR-005：前台服务/通知渠道在 Android 层实现，本层只定义状态。
 *
 * Android 实际的 BroadcastReceiver / Session 监听 / dumpsys 轮询 / 前台服务由
 * 宿主 App 的 `feature/virusguard` 模块实现，本类只提供纯逻辑决策与状态。
 */

/** 监听模式（能力等级） */
enum class InstallListenMode {
    /** Shizuku 可用，可实时拦截安装会话（最高级） */
    SESSION_BLOCK,
    /** 可观察会话但无法拦截（中等级） */
    SESSION_OBSERVE,
    /** 仅 PACKAGE_ADDED/REMOVED 广播事后感知（仅提示） */
    BROADCAST_ONLY,
    /** dumpsys package 轮询降级（事后观测，周期≥30s） */
    POLLING_FALLBACK,
    /** 未授权/不可用：仅提示状态，不拦截也不主动轮询 */
    DISABLED,
}

/** 降级原因（人话） */
enum class DowngradeReason(val human: String) {
    SHIZUKU_AVAILABLE("Shizuku 激活，使用会话拦截模式"),
    SHIZUKU_NOT_RUNNING("Shizuku 服务未运行，降级为广播监听（仅提示）"),
    SHIZUKU_PERMISSION_DENIED("Shizuku 权限未授予，降级为广播监听（仅提示）"),
    SESSION_UNSUPPORTED_ROM("当前 ROM 会话广播不可用，降级为 dumpsys 轮询"),
    BROADCAST_BLOCKED("当前 ROM 隐式广播受限，降级为轮询"),
    USER_DISABLED("用户已关闭实时保护"),
}

/** 单次安装事件（从广播/会话/轮询中采集） */
data class InstallEvent(
    /** 会话 ID（install-session id，广播模式为 -1） */
    val sessionId: Int,
    val packageName: String,
    /** 事件类型 */
    val kind: Kind,
    /** 采集时间戳 */
    val timestampMs: Long,
    /** 安装来源包名（installerPackageName，可空） */
    val installerPackageName: String? = null,
) {
    enum class Kind {
        SESSION_CREATED,     // 新安装会话创建
        SESSION_COMMITTED,   // 会话已 commit（APK 安装开始）
        PACKAGE_ADDED,       // 安装完成（广播）
        PACKAGE_REMOVED,     // 卸载完成（广播）
        SESSION_ABANDONED,   // 会话被放弃（取消/失败）
    }
}

/** 拦截状态机状态 */
enum class GuardState {
    /** 未启动 */
    STOPPED,
    /** 启动中（正在探测能力） */
    STARTING,
    /** 运行中 */
    RUNNING,
    /** 降级模式运行（弱能力） */
    RUNNING_DEGRADED,
    /** 出错停止 */
    ERROR,
}

/**
 * 能力探测结果（由 Android 层填充）。
 */
data class CapabilitySnapshot(
    val shizukuRunning: Boolean,
    val shizukuPermissionGranted: Boolean,
    val sessionCallbackSupported: Boolean,
    val implicitBroadcastWorks: Boolean,
    val userEnabled: Boolean,
    val bootCompleted: Boolean = false,
)

/**
 * 安装监听器骨架（纯 JVM 可测状态机）。
 *
 * 本类不持有 Android Context、不注册真实 Receiver/Service；
 * Android 宿主模块根据 resolveMode() 返回的模式选择对应的监听器实现，
 * 并通过 onEvent() 将事件喂入判定管线。
 */
class InstallEventGuard(
    /** 事件回调：由宿主注入（调用 VerdictPipeline + FalsePositiveGuard） */
    private val onEvent: (InstallEvent) -> Unit,
) {
    @Volatile private var state: GuardState = GuardState.STOPPED
    @Volatile private var mode: InstallListenMode = InstallListenMode.DISABLED
    @Volatile private var downgradeReason: DowngradeReason = DowngradeReason.USER_DISABLED

    // 用于开机幂等：记录已经处理过的 sessionId，避免重启重复响应
    private val seenSessions = HashSet<Int>()
    private val seenPackages = HashSet<String>() // 用于广播重复事件去抖

    fun currentState(): GuardState = state
    fun currentMode(): InstallListenMode = mode
    fun currentReason(): DowngradeReason = downgradeReason

    /**
     * 探测能力并选择监听模式（N-VIR-001 FR-001/FR-003）。
     * 可重复调用（FR-004 开机恢复幂等），不会重复触发启动。
     * @return 选定的模式 + 降级原因
     */
    fun resolveMode(cap: CapabilitySnapshot): Pair<InstallListenMode, DowngradeReason> {
        if (!cap.userEnabled) {
            return InstallListenMode.DISABLED to DowngradeReason.USER_DISABLED
        }
        val (m, r) = when {
            cap.shizukuRunning && cap.shizukuPermissionGranted && cap.sessionCallbackSupported ->
                InstallListenMode.SESSION_BLOCK to DowngradeReason.SHIZUKU_AVAILABLE
            cap.shizukuRunning && cap.shizukuPermissionGranted && !cap.sessionCallbackSupported ->
                InstallListenMode.SESSION_OBSERVE to DowngradeReason.SESSION_UNSUPPORTED_ROM
            cap.shizukuRunning && !cap.shizukuPermissionGranted ->
                InstallListenMode.BROADCAST_ONLY to DowngradeReason.SHIZUKU_PERMISSION_DENIED
            !cap.shizukuRunning ->
                InstallListenMode.BROADCAST_ONLY to DowngradeReason.SHIZUKU_NOT_RUNNING
            !cap.implicitBroadcastWorks ->
                InstallListenMode.POLLING_FALLBACK to DowngradeReason.BROADCAST_BLOCKED
            else ->
                InstallListenMode.BROADCAST_ONLY to DowngradeReason.SHIZUKU_NOT_RUNNING
        }
        mode = m
        downgradeReason = r
        state = if (m == InstallListenMode.DISABLED) GuardState.STOPPED
        else if (m == InstallListenMode.SESSION_BLOCK) GuardState.RUNNING
        else GuardState.RUNNING_DEGRADED
        return m to r
    }

    /** 启动（首次或开机恢复）。幂等。 */
    fun start(cap: CapabilitySnapshot) {
        if (state == GuardState.RUNNING || state == GuardState.RUNNING_DEGRADED) {
            // FR-004：重复注册需幂等，已经在运行时不重复启动
            return
        }
        state = GuardState.STARTING
        resolveMode(cap)
    }

    /** 停止监听（用户关闭/异常） */
    fun stop() {
        state = GuardState.STOPPED
        mode = InstallListenMode.DISABLED
        seenSessions.clear()
        seenPackages.clear()
    }

    /**
     * 处理一个事件：幂等去抖 + 回调给上层。
     * 只有 STOPPED/ERROR 状态下丢弃事件（不应发生）。
     */
    fun onEvent(event: InstallEvent) {
        if (state == GuardState.STOPPED || state == GuardState.ERROR) return
        // 去重：同一 sessionId 的 SESSION_COMMITTED 只处理一次；同一包名 PACKAGE_ADDED 60s 内不重复
        when (event.kind) {
            InstallEvent.Kind.SESSION_COMMITTED,
            InstallEvent.Kind.SESSION_CREATED,
            InstallEvent.Kind.SESSION_ABANDONED -> {
                synchronized(seenSessions) {
                    if (!seenSessions.add(event.sessionId)) return
                }
            }
            InstallEvent.Kind.PACKAGE_ADDED,
            InstallEvent.Kind.PACKAGE_REMOVED -> {
                synchronized(seenPackages) {
                    // 简单去重：同包名 60s 内只处理一次
                    // 这里用包名做简单幂等；实际实现由 Android 层按时间戳去抖
                    if (!seenPackages.add(event.packageName)) return
                }
            }
        }
        onEvent.invoke(event)
    }

    /** 宿主检测到 ROM 不支持会话广播时，主动降级到轮询（FR-003） */
    fun degradeToPolling(reason: DowngradeReason = DowngradeReason.SESSION_UNSUPPORTED_ROM) {
        mode = InstallListenMode.POLLING_FALLBACK
        downgradeReason = reason
        state = GuardState.RUNNING_DEGRADED
    }

    /**
     * 是否当前有能力拦截安装（决定是否在 UI 上显示「实时拦截」标签）。
     * 仅 SESSION_BLOCK 模式可真正阻断安装；其余仅提示/事后审计。
     */
    fun canBlock(): Boolean = mode == InstallListenMode.SESSION_BLOCK
}
