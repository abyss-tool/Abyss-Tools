package com.abyss.tools.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AbyssIndicatorStyle fail-closed 解析测试（纯 JVM，FEAT-033 AC-011 六例）。
 */
class AbyssIndicatorStyleTest {

    @Test
    fun `from null 回退 FROSTED（键缺失即默认）`() {
        assertEquals(AbyssIndicatorStyle.FROSTED, AbyssIndicatorStyle.from(null))
    }

    @Test
    fun `from 空串 回退 FROSTED`() {
        assertEquals(AbyssIndicatorStyle.FROSTED, AbyssIndicatorStyle.from(""))
    }

    @Test
    fun `from 大写 CLEAR 回退 FROSTED（存储值精确匹配，不猜）`() {
        assertEquals(AbyssIndicatorStyle.FROSTED, AbyssIndicatorStyle.from("CLEAR"))
    }

    @Test
    fun `from 未知值 回退 FROSTED（fail-closed 不抛）`() {
        assertEquals(AbyssIndicatorStyle.FROSTED, AbyssIndicatorStyle.from("bogus"))
    }

    @Test
    fun `from frosted 命中 FROSTED`() {
        assertEquals(AbyssIndicatorStyle.FROSTED, AbyssIndicatorStyle.from("frosted"))
    }

    @Test
    fun `from clear 命中 CLEAR`() {
        assertEquals(AbyssIndicatorStyle.CLEAR, AbyssIndicatorStyle.from("clear"))
    }
}
