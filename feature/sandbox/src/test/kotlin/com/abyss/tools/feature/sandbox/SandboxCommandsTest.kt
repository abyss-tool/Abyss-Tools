package com.abyss.tools.feature.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 指令编排纯逻辑：命令形态（与 adb 手敲一字不差，可人工对照）/
 * fail-closed 校验（包名白名单 · Owner 兜底 · 名称引用）/
 * pm list users · am get-current-user · pm create-user 三族解析。
 */
class SandboxCommandsTest {

    // ── 命令形态 ─────────────────────────────────────────────────────

    @Test
    fun `读侧命令形态`() {
        assertEquals("pm list users", SandboxCommands.listUsers())
        assertEquals("am get-current-user", SandboxCommands.getCurrentUser())
    }

    @Test
    fun `创建命令引用中文名`() {
        assertEquals("pm create-user '深渊空间'", SandboxCommands.createUser("深渊空间"))
    }

    @Test
    fun `创建命令引用带空格名`() {
        assertEquals("pm create-user 'Second Space'", SandboxCommands.createUser("Second Space"))
    }

    @Test
    fun `创建命令引用含单引号名`() {
        assertEquals("pm create-user 'Meng'\\''s Space'", SandboxCommands.createUser("Meng's Space"))
    }

    @Test
    fun `创建命令 trim 后引用`() {
        // 首尾空白不属于用户敲进的名字语义（SettingsCommands.putSetting 同款纪律）
        assertEquals("pm create-user '深渊空间'", SandboxCommands.createUser("  深渊空间 "))
    }

    @Test
    fun `用户生命周期命令形态`() {
        assertEquals("am start-user 11", SandboxCommands.startUser(11))
        assertEquals("am stop-user -w 11", SandboxCommands.stopUser(11))
        assertEquals("am switch-user 11", SandboxCommands.switchUser(11))
        assertEquals("pm remove-user 11", SandboxCommands.removeUser(11))
    }

    @Test
    fun `双开命令形态`() {
        assertEquals(
            "pm install --user 11 --existing com.example.app",
            SandboxCommands.installExisting("com.example.app", 11),
        )
    }

    @Test
    fun `空间内卸载命令形态`() {
        assertEquals(
            "pm uninstall --user 11 com.example.app",
            SandboxCommands.uninstallForUser("com.example.app", 11),
        )
    }

    // ── 创建名称校验（自由文本 → 引用 + 换行拒绝）──────────────────────

    @Test
    fun `创建名称空白即拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { SandboxCommands.createUser("   ") }
        assertThrows(IllegalArgumentException::class.java) { SandboxCommands.createUser("") }
    }

    @Test
    fun `创建名称含换行即拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { SandboxCommands.createUser("a\nb") }
        assertThrows(IllegalArgumentException::class.java) { SandboxCommands.createUser("a\rb") }
    }

    @Test
    fun `创建名称注入载荷可无损还原（引号守恒）`() {
        // 敌意载荷经 shellQuote 后，把 '\'' 折叠回 ' 应还原原文 —— 引号守恒 =
        // 不存在能逃逸引用面的断点（appmgr 同款验证手法）
        val hostile = "x'; am switch-user 0; '"
        val quoted = SandboxCommands.createUser(hostile)
        assertTrue(quoted.startsWith("pm create-user '") && quoted.endsWith("'"))
        val payload = quoted.removePrefix("pm create-user '").removeSuffix("'")
        assertEquals(hostile, payload.replace("'\\''", "'"))
    }

    // ── pm list users 解析 ────────────────────────────────────────────

    @Test
    fun `解析经典输出含中文名`() {
        val output = "Users:\n\tUserInfo{0:Owner:13} running\n\tUserInfo{11:深渊空间:0}\n"
        assertEquals(
            listOf(
                SandboxCommands.AndroidUser(id = 0, name = "Owner", running = true, isCurrent = true),
                SandboxCommands.AndroidUser(id = 11, name = "深渊空间", running = false, isCurrent = false),
            ),
            SandboxCommands.parseUsers(output, currentUserId = 0),
        )
    }

    @Test
    fun `解析 c 前缀 flags 与带空格名`() {
        val output = "Users:\n\tUserInfo{0:Owner:c13} running\n\tUserInfo{12:Second Space:0} running"
        val users = SandboxCommands.parseUsers(output, currentUserId = 0)
        assertEquals(2, users.size)
        assertEquals("Owner", users[0].name)
        assertTrue(users[0].running)
        assertEquals("Second Space", users[1].name)
        assertTrue(users[1].running)
    }

    @Test
    fun `解析 current 用户为次用户`() {
        val output = "Users:\n\tUserInfo{0:Owner:13} running\n\tUserInfo{11:深渊空间:0}\n"
        val users = SandboxCommands.parseUsers(output, currentUserId = 11)
        assertEquals(false, users[0].isCurrent)
        assertEquals(true, users[1].isCurrent)
    }

    @Test
    fun `解析 current 未知哨兵时不猜`() {
        // am get-current-user 未成功 → UNKNOWN 哨兵：任何行都不标「当前」
        val output = "Users:\n\tUserInfo{0:Owner:13} running\n\tUserInfo{11:深渊空间:0}\n"
        val users = SandboxCommands.parseUsers(output, SandboxCommands.UNKNOWN_USER_ID)
        assertTrue(users.none { it.isCurrent })
    }

    @Test
    fun `解析空名字保留空串`() {
        // 真实形态：名字段空 —— 保留空串交 UI 显示「未知」，绝不替系统编名字
        val users = SandboxCommands.parseUsers("Users:\n\tUserInfo{11::0}\n", 0)
        assertEquals(1, users.size)
        assertEquals("", users[0].name)
    }

    @Test
    fun `解析空输出与坏行`() {
        assertEquals(emptyList<SandboxCommands.AndroidUser>(), SandboxCommands.parseUsers("", 0))
        assertEquals(emptyList<SandboxCommands.AndroidUser>(), SandboxCommands.parseUsers("\n\n  \n", 0))
        // "Users:" 头行 / 垃圾行不含 UserInfo{ → 跳过；CRLF 容忍
        val crlf = "Users:\r\njunk line\r\n\tUserInfo{0:Owner:13} running\r\n"
        assertEquals(1, SandboxCommands.parseUsers(crlf, 0).size)
    }

    @Test
    fun `解析乱序输入按 id 升序稳定排列`() {
        val output = "Users:\n\tUserInfo{11:深渊空间:0} running\n\tUserInfo{0:Owner:13} running\n"
        assertEquals(
            listOf(0, 11),
            SandboxCommands.parseUsers(output, 0).map { it.id },
        )
    }

    // ── am get-current-user 解析 ──────────────────────────────────────

    @Test
    fun `解析当前用户 id`() {
        assertEquals(0, SandboxCommands.parseCurrentUserId("0"))
        assertEquals(0, SandboxCommands.parseCurrentUserId("0\n"))
        assertEquals(10, SandboxCommands.parseCurrentUserId("  10 \n"))
    }

    @Test
    fun `解析当前用户 id 垃圾输入返回 null`() {
        assertNull(SandboxCommands.parseCurrentUserId(""))
        assertNull(SandboxCommands.parseCurrentUserId("Error: no such command"))
        assertNull(SandboxCommands.parseCurrentUserId("unknown"))
        // 多行 / 负数都是坏形态：绝不猜默认 0
        assertNull(SandboxCommands.parseCurrentUserId("0\n10"))
        assertNull(SandboxCommands.parseCurrentUserId("-1"))
    }

    // ── pm create-user 结果解析 ──────────────────────────────────────

    @Test
    fun `解析新空间 id`() {
        assertEquals(12, SandboxCommands.parseCreatedUserId("Success: created user id 12"))
        assertEquals(10, SandboxCommands.parseCreatedUserId("Success: created user id 10\n"))
    }

    @Test
    fun `解析新空间 id 失败文本返回 null`() {
        assertNull(SandboxCommands.parseCreatedUserId("Error: couldn't create user"))
        assertNull(SandboxCommands.parseCreatedUserId(""))
        assertNull(SandboxCommands.parseCreatedUserId("Success: created user id"))
    }

    // ── 包名白名单（fail-closed 注入闸）───────────────────────────────

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
                SandboxCommands.installExisting(pkg, 11)
            }
            assertThrows(IllegalArgumentException::class.java) {
                SandboxCommands.uninstallForUser(pkg, 11)
            }
        }
    }

    // ── 用户 id 收敛与 Owner 兜底 ─────────────────────────────────────

    @Test
    fun `id 收敛到 0__999`() {
        assertEquals("am start-user 0", SandboxCommands.startUser(-1))
        assertEquals("am switch-user 0", SandboxCommands.switchUser(-7))
        assertEquals("am stop-user -w 999", SandboxCommands.stopUser(12345))
        assertEquals(
            "pm install --user 0 --existing com.example.app",
            SandboxCommands.installExisting("com.example.app", -1),
        )
        assertEquals(
            "pm uninstall --user 999 com.example.app",
            SandboxCommands.uninstallForUser("com.example.app", 9999),
        )
    }

    @Test
    fun `移除 Owner 被命令层兜底拒绝`() {
        // UI 永不渲染 Owner 的移除入口；这里验证纵深防御：即便调用也不下发
        assertThrows(IllegalArgumentException::class.java) { SandboxCommands.removeUser(0) }
        // 负数收敛到 0 后同样被 Owner 拒绝
        assertThrows(IllegalArgumentException::class.java) { SandboxCommands.removeUser(-3) }
    }

    @Test
    fun `移除荒谬 id 收敛`() {
        assertEquals("pm remove-user 999", SandboxCommands.removeUser(5000))
    }
}
