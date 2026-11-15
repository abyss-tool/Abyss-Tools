package com.abyss.tools.feature.scriptlab

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ScriptLabShared 纯 JVM 单测（TemporaryFolder 模拟 filesDir/scripts/）：
 * 名字门禁矩阵（路径穿越 / 隐藏文件 / 空白 / 空名 / 超长 fail-closed）、
 * 存储往返 + 原子改名不留半截文件 + 未知文件忽略、版本探测解析、
 * 命令构造纪律（printf 管道形态 / 单引号转义 / 16KB 与 NUL 拒绝）。
 * 零 Android 依赖（构造函数只吃 java.io.File）。
 */
class ScriptLabSharedTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): ScriptStore = ScriptStore(tmp.root)

    // ── safeFileName 门禁矩阵（fail-closed）──────────────────────────

    @Test
    fun `名字门禁拒绝路径穿越`() {
        assertNull(safeFileName("../evil"))
    }

    @Test
    fun `名字门禁拒绝隐藏文件`() {
        assertNull(safeFileName(".hidden"))
    }

    @Test
    fun `名字门禁拒绝空白字符`() {
        assertNull(safeFileName("a b"))
    }

    @Test
    fun `名字门禁拒绝空名`() {
        assertNull(safeFileName(""))
    }

    @Test
    fun `名字门禁拒绝 41 字符超长名`() {
        assertNull(safeFileName("a".repeat(41)))
    }

    @Test
    fun `名字门禁接受常规形态`() {
        assertEquals("my_script-1.py", safeFileName("my_script-1.py"))
        assertEquals("a", safeFileName("a"))
        assertEquals("B2_snapshot.v2", safeFileName("B2_snapshot.v2"))
    }

    @Test
    fun `名字门禁接受 40 字符边界`() {
        val name = "a".repeat(MAX_NAME_LENGTH)
        assertEquals(name, safeFileName(name))
    }

    // ── ScriptStore 保存 / 列表 / 读取 / 删除往返 ─────────────────────

    @Test
    fun `目录不存在时首次保存自动建目录`() {
        val store = ScriptStore(File(tmp.root, SCRIPTS_DIR_NAME))

        assertTrue(store.save(SavedScript("first", ScriptLang.SHELL, "echo first", 0L)).isSuccess)
        assertEquals(listOf("first"), store.list().map { it.name })
    }

    @Test
    fun `保存后列表与读取往返一致`() {
        val store = store()
        val body = "echo hello\nuname -a\ndate"
        store.save(SavedScript("hello", ScriptLang.SHELL, body, 0L)).getOrThrow()

        val listed = store.list()
        assertEquals(1, listed.size)
        assertEquals("hello", listed[0].name)
        assertEquals(ScriptLang.SHELL, listed[0].lang)
        assertEquals(body, listed[0].body)
        // modifiedAt 以文件 mtime 为单一真源（保存方传 0 也不作数）
        assertTrue(listed[0].modifiedAt > 0L)

        val loaded = store.load("hello", ScriptLang.SHELL)
        assertEquals(body, loaded?.body)
        assertEquals("hello", loaded?.name)
    }

    @Test
    fun `同名同语言保存为覆盖且不留 tmp 半截文件`() {
        val store = store()
        store.save(SavedScript("snap", ScriptLang.SHELL, "echo v1", 0L)).getOrThrow()
        store.save(SavedScript("snap", ScriptLang.SHELL, "echo v2", 0L)).getOrThrow()

        assertEquals("echo v2", store.load("snap", ScriptLang.SHELL)?.body)
        // 原子写纪律：成功路径 tmp 已被 rename 消费 —— 目录里只有终稿
        assertEquals(1, tmp.root.listFiles()!!.size)
        assertFalse(tmp.root.listFiles()!![0].name.endsWith(".tmp"))
    }

    @Test
    fun `同名不同语言是两个独立脚本`() {
        val store = store()
        store.save(SavedScript("demo", ScriptLang.SHELL, "echo sh", 0L)).getOrThrow()
        store.save(SavedScript("demo", ScriptLang.PYTHON, "print('py')", 0L)).getOrThrow()

        assertEquals(2, store.list().size)
        assertEquals("echo sh", store.load("demo", ScriptLang.SHELL)?.body)
        assertEquals("print('py')", store.load("demo", ScriptLang.PYTHON)?.body)
    }

    @Test
    fun `删除后列表清空且二次删除返回假`() {
        val store = store()
        store.save(SavedScript("gone", ScriptLang.PYTHON, "print(1)", 0L)).getOrThrow()

        assertTrue(store.delete("gone", ScriptLang.PYTHON))
        assertTrue(store.list().isEmpty())
        assertFalse(store.delete("gone", ScriptLang.PYTHON))
    }

    @Test
    fun `删除拒绝非法名字且不碰磁盘`() {
        val store = store()
        store.save(SavedScript("keep", ScriptLang.SHELL, "echo", 0L)).getOrThrow()

        assertFalse(store.delete("../evil", ScriptLang.SHELL))
        assertEquals(1, store.list().size)
    }

    @Test
    fun `载入不存在与非法名字返回 null`() {
        val store = store()

        assertNull(store.load("missing", ScriptLang.SHELL))
        assertNull(store.load("../evil", ScriptLang.SHELL))
    }

    @Test
    fun `保存拒绝非法名字（fail-closed）`() {
        val result = store().save(SavedScript("../evil", ScriptLang.SHELL, "echo x", 0L))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `未知与非法文件被列表忽略`() {
        val dir = tmp.root
        File(dir, "readme.txt").writeText("not a script")
        File(dir, "notes.md").writeText("notes")
        File(dir, ".secret.sh").writeText("stem 以点开头 —— 门禁拒绝")
        File(dir, "weird.pyx").writeText("未知后缀 —— 忽略")
        File(dir, "subdir").mkdir()

        val store = store()
        store.save(SavedScript("real", ScriptLang.SHELL, "echo real", 0L)).getOrThrow()

        assertEquals(listOf("real"), store.list().map { it.name })
    }

    // ── RunDetectors.parseVersion ─────────────────────────────────────

    @Test
    fun `解析标准版本行`() {
        assertEquals("3.11.4", RunDetectors.parseVersion("Python 3.11.4"))
    }

    @Test
    fun `错误输出解析不出版本`() {
        assertNull(RunDetectors.parseVersion("sh: python3: inaccessible"))
    }

    @Test
    fun `空输出解析不出版本`() {
        assertNull(RunDetectors.parseVersion(""))
    }

    @Test
    fun `多行输出取首条命中`() {
        assertEquals(
            "3.8.10",
            RunDetectors.parseVersion("sh: python3: not found\nPython 3.8.10"),
        )
    }

    // ── buildRunCommand 构造纪律 ─────────────────────────────────────

    @Test
    fun `Shell 命令形态为 printf 管道进 sh`() {
        assertEquals("printf %s 'echo hi' | sh", buildRunCommand(ScriptLang.SHELL, "echo hi"))
    }

    @Test
    fun `Python 主命令走 python3 回退命令走 python`() {
        assertEquals(
            "printf %s 'print(\"hi\")' | python3",
            buildRunCommand(ScriptLang.PYTHON, "print(\"hi\")"),
        )
        assertEquals(
            "printf %s 'print(\"hi\")' | python",
            buildPythonFallbackCommand("print(\"hi\")"),
        )
    }

    @Test
    fun `单引号脚本体按 sh 单引号纪律转义`() {
        // ShizukuCommands.shellQuote：' → '\''（唯一可靠形态，与伪装四件套同源）
        assertEquals("printf %s 'echo '\\''hi'\\''' | sh", buildRunCommand(ScriptLang.SHELL, "echo 'hi'"))
    }

    @Test
    fun `换行体原样保真（多行脚本不丢行）`() {
        assertEquals("printf %s 'a\nb' | sh", buildRunCommand(ScriptLang.SHELL, "a\nb"))
    }

    @Test
    fun `超过 16KB 的脚本体被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            buildRunCommand(ScriptLang.SHELL, "x".repeat(MAX_BODY_BYTES + 1))
        }
    }

    @Test
    fun `多字节字符按字节计数拒绝超限体`() {
        // 汉字 UTF-8 三字节：6000 字 = 18000 字节 > 16KB（字符数 < 16K 但字节超限）
        assertThrows(IllegalArgumentException::class.java) {
            buildRunCommand(ScriptLang.SHELL, "字".repeat(6000))
        }
    }

    @Test
    fun `NUL 字符被拒绝（防命令串静默截断）`() {
        assertThrows(IllegalArgumentException::class.java) {
            buildRunCommand(ScriptLang.SHELL, "a\u0000b")
        }
    }
}
