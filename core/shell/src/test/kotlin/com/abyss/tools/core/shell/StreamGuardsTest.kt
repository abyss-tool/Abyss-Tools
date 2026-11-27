package com.abyss.tools.core.shell

import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 流读取守护：字节上限 / 截断语义 / 空流与超限全路径（评审 #7）。 */
class StreamGuardsTest {

    @Test
    fun `小输出全量读取不截断`() {
        val result = StreamGuards.readTextCapped(ByteArrayInputStream("hello".toByteArray()), 1024)
        assertEquals("hello", result.text)
        assertFalse(result.truncated)
    }

    @Test
    fun `空流返回空文本`() {
        val result = StreamGuards.readTextCapped(ByteArrayInputStream(ByteArray(0)), 1024)
        assertEquals("", result.text)
        assertFalse(result.truncated)
    }

    @Test
    fun `超过上限截断并如实标注`() {
        val payload = ByteArray(10 * 1024) { 'x'.code.toByte() }
        val result = StreamGuards.readTextCapped(ByteArrayInputStream(payload), 4096)
        assertEquals(4096, result.text.length)
        assertTrue(result.truncated)
        assertTrue(result.text.all { it == 'x' })
    }

    @Test
    fun `恰好等于上限不截断`() {
        val payload = ByteArray(4096) { 'y'.code.toByte() }
        val result = StreamGuards.readTextCapped(ByteArrayInputStream(payload), 4096)
        assertEquals(4096, result.text.length)
        assertFalse(result.truncated)
    }

    @Test
    fun `多字节字符在字节边界截断不崩溃（decodeToString 容错）`() {
        // 中文 3 字节/字符：1024 字节上限会切在字符中间 → 替换字符兜底
        val text = "深".repeat(2048)
        val result = StreamGuards.readTextCapped(ByteArrayInputStream(text.toByteArray()), 1024)
        assertTrue(result.truncated)
        assertTrue(result.text.length < text.length)
        assertTrue(result.text.contains("深"))
    }

    @Test
    fun `非法上限就地拒绝`() {
        try {
            StreamGuards.readTextCapped(ByteArrayInputStream(ByteArray(0)), 0)
            throw AssertionError("非法上限未被拒绝")
        } catch (expected: IllegalArgumentException) {
        }
    }

    /**
     * B-17 回归：截断后必须继续排空剩余流 —— 写端（子进程管道模拟）
     * 写满管道后不被永久阻塞，能自然写完退出；旧实现读取端停在 1MB，
     * 写端死等到看门狗强杀。
     */
    @Test(timeout = 10_000)
    fun `truncation drains the remaining stream so the writer completes`() {
        val pipeOut = PipedOutputStream()
        val pipeIn = PipedInputStream(pipeOut, 64 * 1024) // 小管道缓冲，放大写阻塞
        val total = 512 * 1024 // 8x 上限：必截断且远超管道缓冲
        val writerDone = CountDownLatch(1)
        @Volatile var writtenBytes = 0

        val writer = Thread {
            try {
                val chunk = ByteArray(8 * 1024)
                var written = 0
                while (written < total) {
                    pipeOut.write(chunk)
                    pipeOut.flush()
                    written += chunk.size
                    writtenBytes = written
                }
            } finally {
                runCatching { pipeOut.close() } // EOF 通知读取端收尾
                writerDone.countDown()
            }
        }.apply { isDaemon = true; start() }

        val result = StreamGuards.readTextCapped(pipeIn, 64 * 1024)

        // 排空语义：写端写完全部 512KB（读取端持续丢弃消费）。
        // 旧实现（不排空）：读取端停读并关流 → 写端在 ~管道缓冲处被
        // IOException 打断，writtenBytes << total —— 以此捕获回归。
        assertTrue("写端未在限时内收尾（B-17 排空回归）", writerDone.await(8, TimeUnit.SECONDS))
        assertEquals("写端被管道写阻塞截胡，未写完全部载荷（B-17 排空回归）",
            total, writtenBytes)
        assertTrue(result.truncated)
        assertEquals(64 * 1024, result.text.toByteArray(Charsets.UTF_8).size)
    }
}
