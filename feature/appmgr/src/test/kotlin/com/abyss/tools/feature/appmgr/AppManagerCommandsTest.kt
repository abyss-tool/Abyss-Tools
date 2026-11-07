package com.abyss.tools.feature.appmgr

import com.abyss.tools.core.shell.ShizukuCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 指令编排纯逻辑：命令形态 / 包名白名单（fail-closed）/ 输出解析 /
 * shellQuote 注入安全（自由文本防护的仓库级复用）。
 */
class AppManagerCommandsTest {

    // ── 命令形态（与 adb 手敲形态一字不差，可人工对照）────────────────

    @Test
    fun `冻结命令形态`() {
        assertEquals(
            "pm disable-user --user 0 com.example.app",
            AppManagerCommands.freeze("com.example.app"),
        )
    }

    @Test
    fun `解冻命令形态`() {
        assertEquals(
            "pm enable com.example.app",
            AppManagerCommands.unfreeze("com.example.app"),
        )
    }

    @Test
    fun `强制停止命令形态`() {
        assertEquals(
            "am force-stop com.example.app",
            AppManagerCommands.forceStop("com.example.app"),
        )
    }

    @Test
    fun `隐藏与取消隐藏命令形态`() {
        assertEquals("pm hide com.example.app", AppManagerCommands.hide("com.example.app"))
        assertEquals("pm unhide com.example.app", AppManagerCommands.unhide("com.example.app"))
    }

    @Test
    fun `列表读取命令形态`() {
        assertEquals("pm list packages -d", AppManagerCommands.listDisabled())
        assertEquals("pm list packages -u -h", AppManagerCommands.listHidden())
    }

    // ── 包名白名单（fail-closed 第一道闸）────────────────────────────

    @Test
    fun `合法包名边界`() {
        assertTrue(AppManagerCommands.isValidPackageName("com.example.app"))
        assertTrue(AppManagerCommands.isValidPackageName("a.b"))
        assertTrue(AppManagerCommands.isValidPackageName("com.a_b.c1"))
        assertTrue(AppManagerCommands.isValidPackageName("com.abyss.tools"))

        // 至少一个点：单词段不是合法 Android 包名
        assertFalse(AppManagerCommands.isValidPackageName("com"))
        assertFalse(AppManagerCommands.isValidPackageName(""))
        // 连字符不在 Android 包名字符集内
        assertFalse(AppManagerCommands.isValidPackageName("com.example.app-x"))
    }

    @Test
    fun `注入载荷在命令构造前被拒绝`() {
        val hostile = listOf(
            "com.foo; rm -rf /",
            "com.foo && pm hide com.other",
            "com.foo | reboot",
            "\$(id)",
            "com.foo`id`",
            "com.foo\nrm",
            "com 'foo'",
            "com \"foo\"",
        )
        hostile.forEach { pkg ->
            assertThrows(IllegalArgumentException::class.java) {
                AppManagerCommands.freeze(pkg)
            }
            assertThrows(IllegalArgumentException::class.java) {
                AppManagerCommands.unfreeze(pkg)
            }
            assertThrows(IllegalArgumentException::class.java) {
                AppManagerCommands.forceStop(pkg)
            }
            assertThrows(IllegalArgumentException::class.java) {
                AppManagerCommands.hide(pkg)
            }
            assertThrows(IllegalArgumentException::class.java) {
                AppManagerCommands.unhide(pkg)
            }
        }
    }

    @Test
    fun `命令产物只含白名单字符`() {
        // 白名单校验严于 shell 引用：合法包名构造出的命令整条可被此正则复现
        assertTrue(
            AppManagerCommands.freeze("com.example.app")
                .matches(Regex("pm disable-user --user 0 [A-Za-z0-9_.]+")),
        )
        assertTrue(
            AppManagerCommands.hide("com.example.app")
                .matches(Regex("pm hide [A-Za-z0-9_.]+")),
        )
    }

    // ── shellQuote 注入安全（自由文本防护，全仓共用）──────────────────

    @Test
    fun `shellQuote 注入安全（自由文本防护复用）`() {
        assertEquals("'正常文本'", ShizukuCommands.shellQuote("正常文本"))
        assertEquals("'Meng'\\''s Phone'", ShizukuCommands.shellQuote("Meng's Phone"))

        // 敌意载荷经 shellQuote 后可无损还原：把 '\'' 折叠回 ' 得到原文
        // —— 引号守恒 = 不存在能逃逸引用面的断点
        val hostile = "x'; pm hide com.abyss.tools; '"
        val quoted = ShizukuCommands.shellQuote(hostile)
        assertTrue(quoted.startsWith("'") && quoted.endsWith("'"))
        val roundTrip = quoted
            .removePrefix("'")
            .removeSuffix("'")
            .replace("'\\''", "'")
        assertEquals(hostile, roundTrip)
    }

    // ── 输出解析 ─────────────────────────────────────────────────────

    @Test
    fun `解析禁用列表正常输出`() {
        val output = """
            package:com.foo.bar
            package:com.baz
            package:com.qux.quux
        """.trimIndent()
        assertEquals(
            setOf("com.foo.bar", "com.baz", "com.qux.quux"),
            AppManagerCommands.parseDisabledPackages(output),
        )
    }

    @Test
    fun `解析空输出`() {
        assertEquals(emptySet<String>(), AppManagerCommands.parseDisabledPackages(""))
        assertEquals(emptySet<String>(), AppManagerCommands.parseDisabledPackages("\n\n   \n"))
    }

    @Test
    fun `解析跳过坏行`() {
        val output = listOf(
            "package:com.ok",          // 正常行
            "garbage line",            // 无 package: 前缀 → 跳过
            "package:",                // 空包名 → 跳过
            "package:com.a b",         // 含空白 → 跳过
            "package:com.bad;rm",      // 含 shell 元字符 → 跳过
            "package:nodotname",       // 无点（非 Android 包名形态）→ 跳过
            "  package:com.padded  ",  // 首尾空白容忍 → 收下
            "",                        // 空行 → 跳过
        ).joinToString(separator = "\n")
        assertEquals(
            setOf("com.ok", "com.padded"),
            AppManagerCommands.parseDisabledPackages(output),
        )
    }

    @Test
    fun `解析容忍 CRLF 并去重`() {
        val output = "package:com.dup\r\npackage:com.dup\r\npackage:com.once\r\n"
        assertEquals(
            setOf("com.dup", "com.once"),
            AppManagerCommands.parseDisabledPackages(output),
        )
    }

    @Test
    fun `隐藏列表解析同源`() {
        val output = "package:com.hidden.one\npackage:com.hidden.two\nnot-a-package-line\n"
        assertEquals(
            setOf("com.hidden.one", "com.hidden.two"),
            AppManagerCommands.parseHiddenPackages(output),
        )
    }
}
