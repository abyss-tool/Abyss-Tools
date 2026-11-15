package com.abyss.tools.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * RenderSafeMode 断路器语义测试（纯 JVM，无 Android 依赖）。
 *
 * 基准时刻 t0 任意，各用例以相对偏移推进时间轴：
 *  - 快速窗口 = 20s；markStable 时点 = 会话起始 + 45s；
 *  - 慢性循环形态 = 每次会话存活 8 分钟后崩溃（v2 覆盖的真实场景）。
 */
class RenderSafeModeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val file: File get() = File(tmp.root, RenderSafeMode.FILE_NAME)

    private val t0 = 1_000_000_000L

    private fun coldStart(at: Long) = RenderSafeMode.onColdStart(file, at)

    // ── v1 语义回归：快速闪退循环 ─────────────────────────────────────

    @Test
    fun `快速闪退循环 —— 三次 20s 内冷启动触发安全模式`() {
        assertFalse(coldStart(t0).engage)
        assertFalse(coldStart(t0 + 5_000).engage)
        val d = coldStart(t0 + 10_000)
        assertTrue(d.engage)
        assertEquals(3, d.rapidRestarts)
    }

    @Test
    fun `间隔超窗口重新计数 —— 正常使用节奏不触发`() {
        assertFalse(coldStart(t0).engage)
        assertFalse(coldStart(t0 + 5_000).engage)
        assertFalse(coldStart(t0 + 86_400_000).engage)
    }

    @Test
    fun `engaged 粘滞 —— 触发后保持到 markStable`() {
        coldStart(t0)
        coldStart(t0 + 5_000)
        assertTrue(coldStart(t0 + 10_000).engage)
        // 干净退出 + 重启：engaged 仍保持（粘滞，直到 markStable）
        RenderSafeMode.markCleanExit(file)
        assertTrue(coldStart(t0 + 20_000).engage)
        // 会话存活 45s → markStable → 下次冷启动恢复正常档
        RenderSafeMode.markStable(file, t0 + 65_000)
        assertFalse(coldStart(t0 + 70_000).engage)
    }

    @Test
    fun `温重建只读判定 —— 不改状态文件`() {
        coldStart(t0)
        val before = RenderSafeMode.readState(file)
        assertFalse(RenderSafeMode.currentDecision(file).engage)
        assertEquals(before, RenderSafeMode.readState(file))
    }

    // ── v2 新语义：慢性闪退循环（PLE110 2026-11-15 教训）──────────────

    @Test
    fun `慢性闪退循环 —— 每次存活数分钟后崩溃，间隔远超快速窗口仍触发`() {
        // markStable 在每个会话 45s 时清掉快速计数 —— v1 对此形态永久失明；
        // v2 连崩计数与间隔无关：3 次异常终止即触发。
        assertFalse(coldStart(t0).engage)
        RenderSafeMode.markStable(file, t0 + 45_000)
        assertFalse(coldStart(t0 + 8 * 60_000).engage)
        RenderSafeMode.markStable(file, t0 + 8 * 60_000 + 45_000)
        assertFalse(coldStart(t0 + 16 * 60_000).engage)
        RenderSafeMode.markStable(file, t0 + 16 * 60_000 + 45_000)
        assertTrue(coldStart(t0 + 24 * 60_000).engage)
    }

    @Test
    fun `markStable 只清快速计数 —— 连崩计数与退出标记保留`() {
        coldStart(t0)
        coldStart(t0 + 60_000) // streak = 1
        RenderSafeMode.markStable(file, t0 + 105_000)
        val s = RenderSafeMode.readState(file)
        assertEquals(0, s.rapidRestarts)
        assertFalse(s.engaged)
        assertEquals(1, s.crashStreak)
        assertFalse(s.lastCleanExit)
    }

    @Test
    fun `干净退出归零连崩 —— 正常使用自愈，不惩罚普通节奏`() {
        coldStart(t0)
        coldStart(t0 + 60_000) // 连崩 ×2
        RenderSafeMode.markCleanExit(file) // 用户主动退出
        assertFalse(coldStart(t0 + 120_000).engage) // streak 归零
        assertFalse(coldStart(t0 + 180_000).engage) // streak = 1
        assertFalse(coldStart(t0 + 240_000).engage) // streak = 2，差一次
    }

    @Test
    fun `markCleanExit 只补退出标记 —— 不动其它计数`() {
        coldStart(t0)
        coldStart(t0 + 60_000)
        RenderSafeMode.markCleanExit(file)
        val s = RenderSafeMode.readState(file)
        assertTrue(s.lastCleanExit)
        assertEquals(1, s.crashStreak)
        assertEquals(t0 + 60_000, s.lastLaunchAt)
    }

    // ── fail-open：绝不成为新的崩溃源 ─────────────────────────────────

    @Test
    fun `v0.7.3 三行旧格式 —— 迁移不误伤`() {
        file.writeText("$t0\n1\n0\n")
        val d = RenderSafeMode.onColdStart(file, t0 + 5_000)
        assertFalse(d.engage)
        assertEquals(2, d.rapidRestarts)
        // 行数不足视为已干净退出：连崩从 0 起算，升级用户不被惩罚
        assertEquals(0, RenderSafeMode.readState(file).crashStreak)
    }

    @Test
    fun `损坏文件 fail-open —— 全零默认不抛不误触发`() {
        file.writeText("garbage\n\nnot-a-number\nx\ny\n")
        val d = RenderSafeMode.onColdStart(file, t0)
        assertFalse(d.engage)
        assertEquals(1, d.rapidRestarts)
    }

    @Test
    fun `无文件冷启动 —— 全零默认`() {
        val d = coldStart(t0)
        assertFalse(d.engage)
        assertEquals(1, d.rapidRestarts)
    }
}
