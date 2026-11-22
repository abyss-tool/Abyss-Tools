package com.abyss.tools.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LauncherIcon fail-closed 解析测试（纯 JVM，FEAT-036 AC-003）。
 */
class LauncherIconTest {

    @Test
    fun `from null 回退 CYAN（键缺失即默认）`() {
        assertEquals(LauncherIcon.CYAN, LauncherIcon.from(null))
    }

    @Test
    fun `from 空串 回退 CYAN`() {
        assertEquals(LauncherIcon.CYAN, LauncherIcon.from(""))
    }

    @Test
    fun `from 未知值 回退 CYAN（fail-closed 不抛）`() {
        assertEquals(LauncherIcon.CYAN, LauncherIcon.from("bogus"))
        assertEquals(LauncherIcon.CYAN, LauncherIcon.from("RED"))
    }

    @Test
    fun `from cyan red blue 精确命中`() {
        assertEquals(LauncherIcon.CYAN, LauncherIcon.from("cyan"))
        assertEquals(LauncherIcon.RED, LauncherIcon.from("red"))
        assertEquals(LauncherIcon.BLUE, LauncherIcon.from("blue"))
    }
}
