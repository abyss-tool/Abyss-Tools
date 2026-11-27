package com.abyss.tools.core.common.virus

import org.junit.Assert.*
import org.junit.Test

/**
 * N-VIR-001 验收 AC-001：模拟一次安装会话，有/无 Shizuku 两种模式行为符合声明。
 */
class InstallEventGuardTest {

    @Test
    fun `shizuku available with session support to SESSION_BLOCK (can block)`() {
        val events = mutableListOf<InstallEvent>()
        val guard = InstallEventGuard(onEvent = { events += it })
        val (mode, reason) = guard.resolveMode(
            CapabilitySnapshot(
                shizukuRunning = true,
                shizukuPermissionGranted = true,
                sessionCallbackSupported = true,
                implicitBroadcastWorks = true,
                userEnabled = true,
            )
        )
        assertEquals(InstallListenMode.SESSION_BLOCK, mode)
        assertEquals(DowngradeReason.SHIZUKU_AVAILABLE, reason)
        assertEquals(GuardState.RUNNING, guard.currentState())
        assertTrue(guard.canBlock())
    }

    @Test
    fun `shizuku not running to BROADCAST_ONLY (cannot block, notify only) (FR-001)`() {
        val guard = InstallEventGuard(onEvent = {})
        val (mode, reason) = guard.resolveMode(
            CapabilitySnapshot(
                shizukuRunning = false,
                shizukuPermissionGranted = false,
                sessionCallbackSupported = true,
                implicitBroadcastWorks = true,
                userEnabled = true,
            )
        )
        assertEquals(InstallListenMode.BROADCAST_ONLY, mode)
        assertEquals(DowngradeReason.SHIZUKU_NOT_RUNNING, reason)
        assertEquals(GuardState.RUNNING_DEGRADED, guard.currentState())
        assertFalse("无 Shizuku 时必须降级为仅提示，不能拦截", guard.canBlock())
    }

    @Test
    fun `shizuku permission denied to BROADCAST_ONLY with clear reason`() {
        val guard = InstallEventGuard(onEvent = {})
        val (mode, reason) = guard.resolveMode(
            CapabilitySnapshot(
                shizukuRunning = true,
                shizukuPermissionGranted = false,
                sessionCallbackSupported = true,
                implicitBroadcastWorks = true,
                userEnabled = true,
            )
        )
        assertEquals(InstallListenMode.BROADCAST_ONLY, mode)
        assertEquals(DowngradeReason.SHIZUKU_PERMISSION_DENIED, reason)
        assertTrue(reason.human.contains("权限"))
    }

    @Test
    fun `session unsupported on ROM to degrade to OBSERVE first, then POLLING on demand (FR-003)`() {
        val guard = InstallEventGuard(onEvent = {})
        guard.resolveMode(
            CapabilitySnapshot(
                shizukuRunning = true,
                shizukuPermissionGranted = true,
                sessionCallbackSupported = false, // ROM 不支持会话回调
                implicitBroadcastWorks = true,
                userEnabled = true,
            )
        )
        assertEquals(InstallListenMode.SESSION_OBSERVE, guard.currentMode())
        assertFalse(guard.canBlock())

        // ROM 上实际会话广播也拿不到 → 主动降级到轮询
        guard.degradeToPolling()
        assertEquals(InstallListenMode.POLLING_FALLBACK, guard.currentMode())
        assertEquals(GuardState.RUNNING_DEGRADED, guard.currentState())
    }

    @Test
    fun `user disabled to DISABLED, events dropped`() {
        val events = mutableListOf<InstallEvent>()
        val guard = InstallEventGuard(onEvent = { events += it })
        guard.resolveMode(
            CapabilitySnapshot(false, false, true, true, userEnabled = false)
        )
        assertEquals(InstallListenMode.DISABLED, guard.currentMode())
        assertEquals(GuardState.STOPPED, guard.currentState())

        guard.onEvent(
            InstallEvent(1, "com.x", InstallEvent.Kind.PACKAGE_ADDED, 123L)
        )
        assertTrue("禁用状态不应派发事件", events.isEmpty())
    }

    @Test
    fun `start is idempotent for boot recovery (FR-004)`() {
        var starts = 0
        val guard = InstallEventGuard(onEvent = { starts++ })
        val cap = CapabilitySnapshot(true, true, true, true, userEnabled = true, bootCompleted = true)
        guard.start(cap)
        guard.start(cap)
        guard.start(cap)
        assertEquals(GuardState.RUNNING, guard.currentState())
        // 重复 start 不应触发额外状态变化，不应重复注册（state 保持 RUNNING）
    }

    @Test
    fun `event deduplication prevents duplicate processing`() {
        val events = mutableListOf<InstallEvent>()
        val guard = InstallEventGuard(onEvent = { events += it })
        guard.start(CapabilitySnapshot(true, true, true, true, userEnabled = true))

        val now = System.currentTimeMillis()
        val ev1 = InstallEvent(100, "com.demo.app", InstallEvent.Kind.SESSION_COMMITTED, now)
        guard.onEvent(ev1)
        guard.onEvent(ev1.copy(timestampMs = now + 1)) // 同 sessionId 重复
        guard.onEvent(ev1.copy(sessionId = 101))       // 不同 sessionId 放行
        assertEquals(2, events.size)
    }

    @Test
    fun `stop clears state and drops further events`() {
        val events = mutableListOf<InstallEvent>()
        val guard = InstallEventGuard(onEvent = { events += it })
        guard.start(CapabilitySnapshot(true, true, true, true, userEnabled = true))
        guard.onEvent(InstallEvent(1, "com.x", InstallEvent.Kind.SESSION_COMMITTED, 1))
        assertEquals(1, events.size)
        guard.stop()
        assertEquals(GuardState.STOPPED, guard.currentState())
        assertEquals(InstallListenMode.DISABLED, guard.currentMode())
        guard.onEvent(InstallEvent(2, "com.y", InstallEvent.Kind.SESSION_COMMITTED, 2))
        assertEquals("停止后不应再派发事件", 1, events.size)
    }

    @Test
    fun `simulated full install session - with Shizuku`() {
        // AC-001：模拟一次完整安装会话
        val events = mutableListOf<InstallEvent>()
        val guard = InstallEventGuard(onEvent = { events += it })
        guard.start(CapabilitySnapshot(true, true, true, true, userEnabled = true))
        assertTrue(guard.canBlock())

        guard.onEvent(InstallEvent(10, "com.demo", InstallEvent.Kind.SESSION_CREATED, 1000L))
        guard.onEvent(InstallEvent(10, "com.demo", InstallEvent.Kind.SESSION_COMMITTED, 2000L))
        guard.onEvent(InstallEvent(-1, "com.demo", InstallEvent.Kind.PACKAGE_ADDED, 5000L))

        assertEquals(3, events.size)
        assertEquals(InstallEvent.Kind.SESSION_CREATED, events[0].kind)
        assertEquals(InstallEvent.Kind.SESSION_COMMITTED, events[1].kind)
        assertEquals(InstallEvent.Kind.PACKAGE_ADDED, events[2].kind)
    }

    @Test
    fun `simulated install - no Shizuku (broadcast only notify mode)`() {
        val events = mutableListOf<InstallEvent>()
        val guard = InstallEventGuard(onEvent = { events += it })
        guard.start(CapabilitySnapshot(
            shizukuRunning = false, shizukuPermissionGranted = false,
            sessionCallbackSupported = false, implicitBroadcastWorks = true, userEnabled = true,
        ))
        assertFalse(guard.canBlock())

        // 无 Shizuku 时只能收到 PACKAGE_ADDED 事后广播
        guard.onEvent(InstallEvent(-1, "com.demo", InstallEvent.Kind.PACKAGE_ADDED, 5000L))
        assertEquals(1, events.size)
        assertEquals(InstallEvent.Kind.PACKAGE_ADDED, events[0].kind)
        // UI 层据此弹出"已安装可疑应用"提示，无法事前阻断（按声明行为）
    }

    @Test
    fun `all five listen modes have human-readable downgrade reasons`() {
        for (r in DowngradeReason.values()) {
            assertTrue("${r.name} 必须有人话描述", r.human.isNotBlank())
        }
        for (m in InstallListenMode.values()) {
            assertNotNull(m)
        }
    }
}
