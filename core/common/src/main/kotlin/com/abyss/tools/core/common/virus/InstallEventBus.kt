package com.abyss.tools.core.common.virus

/**
 * 安装事件监听器（N-VIR-001 FR-001）。
 *
 * Android 侧具体实现（Shizuku 会话/BroadcastReceiver/dumpsys 轮询）
 * 通过 [register] 接入；纯 JVM 测试可用 Fake 实现。
 */
fun interface InstallEventListener {
    fun onInstallEvent(event: InstallEvent)
}

/**
 * 安装事件总线（骨架注册中心，N-VIR-001）。
 *
 * 负责：
 *  - 集中注册/分发安装事件（线程安全）。
 *  - 记录当前运行模式 [currentMode]，供 UI 展示"当前监听能力"。
 *  - 开机恢复幂等（[ensureRegistered] 多次调用无副作用）。
 *
 * 不负责：Android Service/Receiver 启动逻辑（由 app 层在 Activity/Service 中触发）。
 */
object InstallEventBus {
    @Volatile private var currentMode: InstallMonitorMode = InstallMonitorMode.DISABLED
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<InstallEventListener>()
    private val registered = java.util.concurrent.atomic.AtomicBoolean(false)

    fun currentMode(): InstallMonitorMode = currentMode

    fun register(listener: InstallEventListener) {
        listeners.add(listener)
    }

    fun unregister(listener: InstallEventListener) {
        listeners.remove(listener)
    }

    /**
     * 标记监听已启动（幂等）。FR-004：重复注册不产生重复事件。
     * @return true 表示本次为首次注册；false 表示此前已注册（幂等跳过）。
     */
    fun ensureRegistered(mode: InstallMonitorMode): Boolean {
        currentMode = mode
        return registered.compareAndSet(false, true)
    }

    /** 开机恢复 / 服务重启时调用，状态重置为 DISABLED 再 re-register。 */
    fun resetForBoot() {
        registered.set(false)
        currentMode = InstallMonitorMode.DISABLED
    }

    /** 分发安装事件给所有监听器（由 Android 层 Receiver/Observer 调用）。 */
    fun dispatch(event: InstallEvent) {
        for (l in listeners) {
            runCatching { l.onInstallEvent(event) }.onFailure { t ->
                com.abyss.tools.core.common.diagnostics.DiagnosticsLog.error(
                    com.abyss.tools.core.common.diagnostics.DiagnosticsLog.Kind.GENERAL,
                    "InstallEventBus",
                    "listener 异常：${t.message}"
                )
            }
        }
    }
}
