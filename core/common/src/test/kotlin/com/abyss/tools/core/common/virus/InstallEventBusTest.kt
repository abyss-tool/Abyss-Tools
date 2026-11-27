package com.abyss.tools.core.common.virus

import org.junit.Assert.*
import org.junit.Test

/**
 * 安装事件总线单测（N-VIR-001 FR-004 幂等注册）。
 */
class InstallEventBusTest {

    @Test fun `ensureRegistered is idempotent`() {
        InstallEventBus.resetForBoot()
        assertTrue(InstallEventBus.ensureRegistered(InstallMonitorMode.SHIZUKU_SESSION))
        assertFalse(InstallEventBus.ensureRegistered(InstallMonitorMode.BROADCAST_ONLY))
        // NB-2：mode 每次调用如实更新（降级可见 —— 注册幂等 ≠ mode 幂等），
        // 旧注释误述为「断言第一次的 mode 保留」而实际断言的就是第二次
        assertEquals(InstallMonitorMode.BROADCAST_ONLY, InstallEventBus.currentMode())
        InstallEventBus.resetForBoot()
        assertEquals(InstallMonitorMode.DISABLED, InstallEventBus.currentMode())
    }

    @Test fun `mode always reflects the latest call even when registration is idempotent`() {
        // NB-2 锁定：注册幂等（返回 false），mode 仍如实更新 —— 降级链可见
        InstallEventBus.resetForBoot()
        assertTrue(InstallEventBus.ensureRegistered(InstallMonitorMode.SHIZUKU_SESSION))
        assertFalse(InstallEventBus.ensureRegistered(InstallMonitorMode.POLLING_DUMPSYS))
        assertEquals(InstallMonitorMode.POLLING_DUMPSYS, InstallEventBus.currentMode())
    }

    @Test fun `dispatch delivers to all listeners and swallows exceptions`() {
        InstallEventBus.resetForBoot()
        InstallEventBus.ensureRegistered(InstallMonitorMode.BROADCAST_ONLY)
        val received = mutableListOf<InstallEvent>()
        InstallEventBus.register(InstallEventListener { received += it })
        InstallEventBus.register(InstallEventListener { throw RuntimeException("boom") })
        InstallEventBus.register(InstallEventListener { received += it })

        val ev = InstallEvent(
            packageName = "com.x", mode = InstallMonitorMode.BROADCAST_ONLY,
            sha256 = "a".repeat(64),
        )
        InstallEventBus.dispatch(ev)
        assertEquals(2, received.size)
        assertEquals("com.x", received[0].packageName)
    }
}
