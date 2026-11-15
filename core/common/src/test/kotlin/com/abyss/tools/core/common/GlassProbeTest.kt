package com.abyss.tools.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * GlassProbe 管线探针语义测试（纯 JVM，无 Android 依赖）。
 *
 * 纪律锚点：探针自身绝不抛（fail-open），未挂载时 mark 为无操作，
 * 写穿文件行数有界（MAX_LINES），同阶段连续去重。
 */
class GlassProbeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val file: File get() = File(tmp.root, GlassProbe.FILE_NAME)

    private val t0 = 1_000_000_000L

    @Before
    fun install() {
        GlassProbe.install(file)
    }

    private fun mark(stage: String, at: Long = t0) = GlassProbe.mark(stage, at)

    // ── 里程碑落盘与去重 ──────────────────────────────────────────────

    @Test
    fun `里程碑按顺序落盘 —— 管线推进可读`() {
        mark(GlassProbe.STAGE_SESSION_START, t0)
        mark(GlassProbe.STAGE_VIBRANCY, t0 + 10)
        mark(GlassProbe.STAGE_BLUR, t0 + 20)
        mark(GlassProbe.STAGE_LENS, t0 + 30)
        mark(GlassProbe.STAGE_FIRST_FRAME, t0 + 40)
        assertEquals(
            listOf(
                "$t0|${GlassProbe.STAGE_SESSION_START}",
                "${t0 + 10}|${GlassProbe.STAGE_VIBRANCY}",
                "${t0 + 20}|${GlassProbe.STAGE_BLUR}",
                "${t0 + 30}|${GlassProbe.STAGE_LENS}",
                "${t0 + 40}|${GlassProbe.STAGE_FIRST_FRAME}",
            ),
            GlassProbe.readAll(),
        )
    }

    @Test
    fun `同阶段连续去重 —— 逐帧 mark 不刷屏`() {
        mark(GlassProbe.STAGE_FIRST_FRAME, t0)
        mark(GlassProbe.STAGE_FIRST_FRAME, t0 + 16)
        mark(GlassProbe.STAGE_FIRST_FRAME, t0 + 32)
        assertEquals(1, GlassProbe.readAll().size)
    }

    @Test
    fun `跨阶段后重复照常落盘 —— 保留真实时序`() {
        mark(GlassProbe.STAGE_SESSION_START, t0)
        mark(GlassProbe.STAGE_FIRST_FRAME, t0 + 10)
        // 下一会话（探针不清盘，跨会话累积）—— session-start 再次出现
        mark(GlassProbe.STAGE_SESSION_START, t0 + 60_000)
        assertEquals(3, GlassProbe.readAll().size)
        assertEquals("${t0 + 60_000}|${GlassProbe.STAGE_SESSION_START}", GlassProbe.readAll().last())
    }

    @Test
    fun `行数上限滚动 —— 只留最近 MAX_LINES 行`() {
        repeat(GlassProbe.MAX_LINES + 10) { i ->
            mark("stage-$i", t0 + i)
        }
        val lines = GlassProbe.readAll()
        assertEquals(GlassProbe.MAX_LINES, lines.size)
        // 最早的 10 条被丢弃，最新一条保留
        assertEquals("stage-${GlassProbe.MAX_LINES + 9}", lines.last().substringAfter('|'))
        assertEquals("stage-10", lines.first().substringAfter('|'))
    }

    // ── fail-open：绝不成为新的崩溃源 ─────────────────────────────────

    @Test
    fun `IO 失败静默兜底 —— 父目录不存在不抛不写`() {
        val badFile = File(tmp.root.resolve("missing-dir/nested"), GlassProbe.FILE_NAME)
        GlassProbe.install(badFile)
        GlassProbe.mark(GlassProbe.STAGE_SESSION_START, t0) // 不抛（runCatching 兜底）
        assertTrue(GlassProbe.readAll().isEmpty())
    }

    @Test
    fun `损坏文件 fail-open —— 读现场为空表不抛`() {
        file.writeText("garbage-line-no-pipe\n\n")
        assertEquals(listOf("garbage-line-no-pipe"), GlassProbe.readAll())
    }

    @Test
    fun `无文件读现场 —— 空表`() {
        assertTrue(GlassProbe.readAll().isEmpty())
    }

    @Test
    fun `清除现场 —— 文件删除且读为空`() {
        mark(GlassProbe.STAGE_SESSION_START, t0)
        assertTrue(GlassProbe.clear())
        assertTrue(GlassProbe.readAll().isEmpty())
    }
}
