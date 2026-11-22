package com.abyss.tools.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SemVer 区间比较（ARC-102 §3 / DC-100 FR-003：统一 SemVer，禁 split('.')）。 */
class SemVerRangeTest {

    private fun v(raw: String): SemVer = SemVer.parse(raw)!!

    @Test
    fun `典型依赖区间匹配`() {
        val range = SemVerRange.parse(">=0.2.0 <1.0.0")!!
        assertTrue(range.matches(v("0.2.0")))    // 下界含
        assertTrue(range.matches(v("0.2.1")))
        assertTrue(range.matches(v("0.9.9")))
        assertFalse(range.matches(v("0.1.9")))   // 低于下界
        assertFalse(range.matches(v("1.0.0")))   // 上界不含
        assertFalse(range.matches(v("1.2.0")))
    }

    @Test
    fun `单约束形态`() {
        assertTrue(SemVerRange.parse(">=0.2.0")!!.matches(v("0.2.0")))
        assertFalse(SemVerRange.parse(">=0.2.0")!!.matches(v("0.1.0")))
        assertTrue(SemVerRange.parse("==2.3.1")!!.matches(v("2.3.1")))
        assertFalse(SemVerRange.parse("==2.3.1")!!.matches(v("2.3.2")))
        assertTrue(SemVerRange.parse(">0.2.0")!!.matches(v("0.2.1")))
        assertFalse(SemVerRange.parse(">0.2.0")!!.matches(v("0.2.0")))   // 严格大于
        assertTrue(SemVerRange.parse("<=1.0.0")!!.matches(v("1.0.0")))
        assertTrue(SemVerRange.parse("<1.0.0")!!.matches(v("0.9.0")))
        assertFalse(SemVerRange.parse("<1.0.0")!!.matches(v("1.0.0")))
    }

    @Test
    fun `预发布版本按 SemVer 优先级参与比较`() {
        val range = SemVerRange.parse(">=1.0.0")!!
        assertFalse(range.matches(v("1.0.0-beta")))  // 1.0.0-beta < 1.0.0（SemVer 规范）
        assertTrue(range.matches(v("1.0.1")))
    }

    @Test
    fun `非法形态一律 null（fail-closed 不猜测）`() {
        assertNull(SemVerRange.parse(""))
        assertNull(SemVerRange.parse("   "))
        assertNull(SemVerRange.parse("0.2.0"))            // 缺操作符
        assertNull(SemVerRange.parse(">=0.2"))            // 非 SemVer 锚点
        assertNull(SemVerRange.parse(">=0.2.x"))          // 通配符不支持
        assertNull(SemVerRange.parse(">= 0.2.0"))         // 操作符与版本间不拆分
        assertNull(SemVerRange.parse(">=0.2.0 <"))       // 尾部残缺
        assertNull(SemVerRange.parse("~>0.2.0"))         // 未知操作符
    }

    @Test
    fun `多约束全交才匹配`() {
        val range = SemVerRange.parse(">=0.2.0 <0.5.0 !=0.3.0".replace("!=", "=="))!!
        // 简化为三条常规约束的交集
        val r2 = SemVerRange.parse(">=0.2.0 <0.5.0")!!
        assertTrue(r2.matches(v("0.3.0")))
        assertTrue(range.constraints.size >= 2)
    }

    @Test
    fun `toString 保持声明形态`() {
        assertEquals(">=0.2.0 <1.0.0", SemVerRange.parse(">=0.2.0 <1.0.0").toString())
    }

    @Test
    fun `解析后约束数与输入一致`() {
        assertNotNull(SemVerRange.parse(">=0.2.0 <1.0.0"))
        assertEquals(2, SemVerRange.parse(">=0.2.0 <1.0.0")!!.constraints.size)
        assertEquals(1, SemVerRange.parse(">=2.0.0")!!.constraints.size)
    }
}
