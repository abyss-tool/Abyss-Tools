package com.abyss.tools.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端纯逻辑单测：ConsoleBuffer 有界窗口语义（追加 / 裁剪 / 清空）、
 * CommandHistory 的去重与裁剪（HistoryStore 注入内存假件 —— SharedPreferences
 * 零出场，对齐 core:shell 的纯 JVM 单测纪律）。
 */
class TerminalSharedTest {

    // ── ConsoleBuffer：有界窗口 ───────────────────────────────────────

    @Test
    fun `append 与 lines 保序往返`() {
        val buffer = ConsoleBuffer(maxLines = 8)
        buffer.append(ConsoleLine("first", LineKind.INPUT))
        buffer.append(ConsoleLine("second", LineKind.OUTPUT))
        buffer.append(ConsoleLine("third", LineKind.ERROR))
        val lines = buffer.lines()
        assertEquals(3, lines.size)
        assertEquals(ConsoleLine("first", LineKind.INPUT), lines[0])
        assertEquals(ConsoleLine("second", LineKind.OUTPUT), lines[1])
        assertEquals(ConsoleLine("third", LineKind.ERROR), lines[2])
    }

    @Test
    fun `超出上限丢弃最旧行（窗口大小恒定）`() {
        val buffer = ConsoleBuffer(maxLines = 3)
        repeat(10) { n -> buffer.append(ConsoleLine("line$n", LineKind.OUTPUT)) }
        val lines = buffer.lines()
        assertEquals(3, lines.size) // 窗口恒定：不会无限增长
        assertEquals("line7", lines[0].text) // 最旧的 line0..6 被裁掉
        assertEquals("line9", lines.last().text) // 末行永远是最新的
    }

    @Test
    fun `clear 清空且清空后可继续使用`() {
        val buffer = ConsoleBuffer(maxLines = 5)
        buffer.append(ConsoleLine("a", LineKind.INPUT))
        buffer.append(ConsoleLine("b", LineKind.OUTPUT))
        buffer.clear()
        assertTrue(buffer.lines().isEmpty())
        buffer.append(ConsoleLine("c", LineKind.SYSTEM))
        assertEquals(listOf(ConsoleLine("c", LineKind.SYSTEM)), buffer.lines())
    }

    @Test
    fun `默认上限 400 行`() {
        val buffer = ConsoleBuffer()
        repeat(1000) { n -> buffer.append(ConsoleLine("l$n", LineKind.OUTPUT)) }
        val lines = buffer.lines()
        assertEquals(400, lines.size)
        assertEquals("l999", lines.last().text)
        assertEquals("l600", lines[0].text)
    }

    @Test
    fun `非法上限就地拒绝（fail-closed）`() {
        assertThrows(IllegalArgumentException::class.java) { ConsoleBuffer(maxLines = 0) }
        assertThrows(IllegalArgumentException::class.java) { ConsoleBuffer(maxLines = -1) }
    }

    @Test
    fun `lines 快照与后续 append 隔离`() {
        val buffer = ConsoleBuffer(maxLines = 10)
        buffer.append(ConsoleLine("held", LineKind.OUTPUT))
        val snapshot = buffer.lines()
        buffer.append(ConsoleLine("extra", LineKind.OUTPUT))
        assertEquals(listOf(ConsoleLine("held", LineKind.OUTPUT)), snapshot)
    }

    // ── CommandHistory：去重 / 裁剪 / 恢复 ────────────────────────────

    /** 内存假件：load/save 全量往返，save 记快照供断言落盘时序。 */
    private class FakeStore(initial: List<String> = emptyList()) : HistoryStore {
        private var items: List<String> = initial
        val savedSnapshots = mutableListOf<List<String>>()

        override fun load(): List<String> = items

        override fun save(items: List<String>) {
            this.items = items
            savedSnapshots.add(items.toList())
        }
    }

    @Test
    fun `record 最新在前并落盘`() {
        val store = FakeStore()
        val history = CommandHistory(store, cap = 3)
        history.record("dumpsys battery")
        history.record("wm size")
        assertEquals(listOf("wm size", "dumpsys battery"), history.all())
        assertEquals(listOf("wm size", "dumpsys battery"), store.load()) // 内存与持久一致
    }

    @Test
    fun `连续重复命令去重`() {
        val history = CommandHistory(FakeStore(), cap = 5)
        history.record("ls")
        history.record("ls") // 连续重复：不记
        history.record("ls") // 连续重复：同上
        history.record("pwd")
        history.record("ls") // 非连续（中间夹了 pwd）：正常记
        assertEquals(listOf("ls", "pwd", "ls"), history.all())
    }

    @Test
    fun `超出上限裁最旧`() {
        val history = CommandHistory(FakeStore(), cap = 3)
        listOf("a", "b", "c", "d", "e").forEach(history::record)
        assertEquals(listOf("e", "d", "c"), history.all())
    }

    @Test
    fun `recent 取最近 N 条且不动全量`() {
        val history = CommandHistory(FakeStore(), cap = 10)
        listOf("one", "two", "three", "four").forEach(history::record)
        assertEquals(listOf("four", "three"), history.recent(2))
        assertEquals(4, history.all().size) // 全量不受影响
        assertEquals(emptyList<String>(), history.recent(0))
        assertEquals(4, history.recent(99).size) // 越界按全量处理，不炸
    }

    @Test
    fun `空白命令不记录`() {
        val history = CommandHistory(FakeStore(), cap = 5)
        history.record("   ")
        history.record("")
        assertTrue(history.all().isEmpty())
    }

    @Test
    fun `含换行的命令单行化（防存储格式撕裂）`() {
        val store = FakeStore()
        val history = CommandHistory(store, cap = 5)
        history.record("echo a\necho b")
        assertEquals(listOf("echo a echo b"), history.all())
        assertEquals(listOf("echo a echo b"), store.load()) // 换行分隔的存储格式未被撕裂
    }

    @Test
    fun `clear 清空内存与存储`() {
        val store = FakeStore(listOf("old1", "old2"))
        val history = CommandHistory(store, cap = 5)
        assertEquals(listOf("old1", "old2"), history.all()) // 构造时从 store 恢复
        history.clear()
        assertTrue(history.all().isEmpty())
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `构造时从 store 恢复（重启往返）`() {
        val store = FakeStore()
        val first = CommandHistory(store, cap = 5)
        first.record("getprop")
        first.record("wm size")
        val reloaded = CommandHistory(store, cap = 5)
        assertEquals(listOf("wm size", "getprop"), reloaded.all())
    }

    @Test
    fun `历史非法上限就地拒绝（fail-closed）`() {
        assertThrows(IllegalArgumentException::class.java) { CommandHistory(FakeStore(), cap = 0) }
        assertThrows(IllegalArgumentException::class.java) { CommandHistory(FakeStore(), cap = -3) }
    }
}
