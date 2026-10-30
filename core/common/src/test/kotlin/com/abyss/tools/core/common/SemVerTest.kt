package com.abyss.tools.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {

    // ── 解析 ──────────────────────────────────────────────────────────

    @Test
    fun `parse 三段版本`() {
        val v = SemVer.parse("0.1.0")!!
        assertEquals(0, v.major)
        assertEquals(1, v.minor)
        assertEquals(0, v.patch)
        assertNull(v.preRelease)
        assertNull(v.build)
    }

    @Test
    fun `parse 预发布与构建元数据`() {
        val v = SemVer.parse("1.2.3-beta.1+001")!!
        assertEquals("beta.1", v.preRelease)
        assertEquals("001", v.build)
        assertEquals("1.2.3-beta.1+001", v.toString())
    }

    @Test
    fun `parse 拒绝非法输入`() {
        assertNull(SemVer.parse("1.2"))
        assertNull(SemVer.parse("01.2.3"))
        assertNull(SemVer.parse("1.2.3.4"))
        assertNull(SemVer.parse(""))
        assertNull(SemVer.parse("v1.2.3"))
        assertNull(SemVer.parse("1.2.3-"))
    }

    @Test
    fun `parseTag 解析 v 前缀`() {
        assertEquals(SemVer(1, 4, 9), SemVer.parseTag("v1.4.9"))
        assertEquals(SemVer(1, 4, 9, "beta"), SemVer.parseTag("v1.4.9-beta"))
    }

    @Test
    fun `toTag 生成发布仓约定形态`() {
        assertEquals("v0.1.0", SemVer(0, 1, 0).toTag())
    }

    // ── 排序语义（SemVer 2.0.0 规范样例）──────────────────────────────

    @Test
    fun `规范排序链`() {
        val ordered = listOf(
            "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
            "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0",
        ).map { SemVer.parse(it)!! }

        for (i in 0 until ordered.size - 1) {
            assertTrue(
                "${ordered[i]} 应小于 ${ordered[i + 1]}",
                ordered[i] < ordered[i + 1]
            )
        }
    }

    @Test
    fun `数值段小于字母段`() {
        assertTrue(SemVer.parse("1.0.0-1")!! < SemVer.parse("1.0.0-alpha")!!)
    }

    @Test
    fun `构建元数据不参与比较`() {
        assertEquals(0, SemVer.parse("1.0.0+build.1")!!.compareTo(SemVer.parse("1.0.0+elsewhere")!!))
    }

    @Test
    fun `主次修订数值比较`() {
        assertTrue(SemVer.parse("0.9.9")!! < SemVer.parse("0.10.0")!!)
        assertTrue(SemVer.parse("0.1.9")!! < SemVer.parse("0.2.0")!!)
        assertTrue(SemVer.parse("2.0.0")!! > SemVer.parse("1.99.99")!!)
    }

    @Test
    fun `无预发布优先于有预发布`() {
        assertTrue(SemVer.parse("0.2.0")!! > SemVer.parse("0.2.0-rc.1")!!)
    }
}
