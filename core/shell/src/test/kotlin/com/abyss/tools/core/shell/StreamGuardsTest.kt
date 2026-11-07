package com.abyss.tools.core.shell

import java.io.ByteArrayInputStream
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
}
