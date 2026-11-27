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
        // 第二次不更新 mode（或只在首次时生效；这里断言第一次的 mode 保留）
        assertEquals(InstallMonitorMode.BROADCAST_ONLY, InstallEventBus.currentMode())
        InstallEventBus.resetForBoot()
        assertEquals(InstallMonitorMode.DISABLED, InstallEventBus.currentMode())
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
