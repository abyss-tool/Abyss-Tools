package com.abyss.tools.feature.shizukukit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具箱指令编排纯逻辑：命令形态 / 引用安全 / 输出解析 / 白名单与
 * namespace 校验（fail-closed 断言）/ 历史序列化往返与裁剪。
 */
class KitCommandsTest {

    // ── 令牌校验（fail-closed）───────────────────────────────────────

    @Test
    fun `令牌校验透传合法形态`() {
        assertEquals("com.abyss.tools", KitCommands.requireSafeToken(" com.abyss.tools ", "包名"))
        assertEquals("android:mock_location", KitCommands.requireSafeToken("android:mock_location", "op 名"))
    }

    @Test
    fun `令牌校验拒绝注入形态`() {
        assertThrows(IllegalArgumentException::class.java) {
            KitCommands.requireSafeToken("", "包名")
        }
        assertThrows(IllegalArgumentException::class.java) {
            KitCommands.requireSafeToken("   ", "包名")
        }
        assertThrows(IllegalArgumentException::class.java) {
            KitCommands.requireSafeToken("com foo", "包名")
        }
        assertThrows(IllegalArgumentException::class.java) {
            KitCommands.requireSafeToken("a;reboot", "包名")
        }
        assertThrows(IllegalArgumentException::class.java) {
            KitCommands.requireSafeToken("a\$b", "包名")
        }
    }

    // ── appops 命令形态 ───────────────────────────────────────────────

    @Test
    fun `appops 查询与设置命令形态（裸名与 android 前缀均透传）`() {
        assertEquals(
            "appops get com.abyss.tools android:mock_location",
            AppOpsCommands.query("com.abyss.tools", "android:mock_location"),
        )
        assertEquals(
            "appops get com.abyss.tools SYSTEM_ALERT_WINDOW",
            AppOpsCommands.query("com.abyss.tools", "SYSTEM_ALERT_WINDOW"),
        )
        assertEquals(
            "appops set com.abyss.tools RUN_IN_BACKGROUND allow",
            AppOpsCommands.setAllow("com.abyss.tools", "RUN_IN_BACKGROUND"),
        )
        assertEquals(
            "appops set com.abyss.tools RUN_IN_BACKGROUND deny",
            AppOpsCommands.setDeny("com.abyss.tools", "RUN_IN_BACKGROUND"),
        )
        assertEquals(
            "appops set com.abyss.tools RUN_IN_BACKGROUND default",
            AppOpsCommands.setDefault("com.abyss.tools", "RUN_IN_BACKGROUND"),
        )
    }

    @Test
    fun `appops 非法令牌就地拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppOpsCommands.query("", "mock_location")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppOpsCommands.query("com.abyss.tools", "op; rm -rf /")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppOpsCommands.setAllow("com foo", "mock_location")
        }
    }

    @Test
    fun `parseAppOps 三态与无法判定`() {
        assertEquals(AppOpsState.ALLOW, AppOpsCommands.parseAppOps("android:mock_location: allow"))
        assertEquals(AppOpsState.ALLOW, AppOpsCommands.parseAppOps("Mock location: Allow"))
        assertEquals(AppOpsState.DEFAULT, AppOpsCommands.parseAppOps("RUN_IN_BACKGROUND: default"))
        assertEquals(AppOpsState.DENY, AppOpsCommands.parseAppOps("SYSTEM_ALERT_WINDOW: deny"))
        assertEquals(AppOpsState.DENY, AppOpsCommands.parseAppOps("WAKE_LOCK: Reject"))
        // "No such app ops" / 空输出 / 无法识别 → null（绝不猜）
        assertNull(AppOpsCommands.parseAppOps("No such app ops: android:mock_location"))
        assertNull(AppOpsCommands.parseAppOps(""))
        assertNull(AppOpsCommands.parseAppOps("Foreground only: ignore"))
    }

    @Test
    fun `appops 历史回放反解`() {
        val target = AppOpsCommands.parseTarget(
            "appops set com.foo android:mock_location allow",
        )
        assertEquals("com.foo", target?.pkg)
        assertEquals("android:mock_location", target?.op)

        val queried = AppOpsCommands.parseTarget("appops get com.foo RUN_IN_BACKGROUND")
        assertEquals("com.foo", queried?.pkg)
        assertEquals("RUN_IN_BACKGROUND", queried?.op)

        // 非 appops 命令 → null（fail-closed，不猜）
        assertNull(AppOpsCommands.parseTarget("dumpsys battery"))
        assertNull(AppOpsCommands.parseTarget("appops"))
    }

    // ── settings 命令形态与校验 ───────────────────────────────────────

    @Test
    fun `settings 读取写入命令形态`() {
        assertEquals(
            "settings get global airplane_mode_on",
            SettingsCommands.getSetting("global", "airplane_mode_on"),
        )
        assertEquals(
            "settings put global device_name 'Pixel 9 Pro'",
            SettingsCommands.putSetting("global", "device_name", "Pixel 9 Pro"),
        )
        // 值一律引用（数字亦同）：sh 会剖去引号，语义等价且杜绝注入面
        assertEquals(
            "settings put system screen_off_timeout '30000'",
            SettingsCommands.putSetting("system", "screen_off_timeout", "30000"),
        )
    }

    @Test
    fun `settings 写入值含单引号安全转义`() {
        val hostile = "X'; reboot; '"
        val cmd = SettingsCommands.putSetting("global", "device_name", hostile)
        // 标准 POSIX sh 引用（shlex.quote 同款）：'X' + \' + '; reboot; ' + \' + ''
        // —— 分号全部落在引号内，注入面归零；末尾 '\''' = 替换产物 '\'' + 闭合 '
        assertEquals("settings put global device_name 'X'\\''; reboot; '\\'''", cmd)
    }

    @Test
    fun `settings 命名空间枚举校验非法即拒`() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsCommands.getSetting("bogus", "wifi_on")
        }
        // 大小写敏感（fail-closed：不静默归一化）
        assertThrows(IllegalArgumentException::class.java) {
            SettingsCommands.getSetting("GLOBAL", "wifi_on")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsCommands.putSetting("secure2", "key", "1")
        }
        // trim 容忍（UI 侧枚举透传，不受影响）
        assertEquals(
            "settings get global wifi_on",
            SettingsCommands.getSetting(" global ", "wifi_on"),
        )
    }

    @Test
    fun `settings 空值换行与非法键名拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsCommands.putSetting("global", "wifi_on", "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsCommands.putSetting("global", "wifi_on", "a\nb")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsCommands.getSetting("global", "bad key")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsCommands.putSetting("global", "key;reboot", "1")
        }
    }

    @Test
    fun `settings 历史回放反解（含值解引）`() {
        val put = SettingsCommands.parseTarget(
            "settings put global device_name 'Pixel 9 Pro'",
        )
        assertEquals(SettingsCommands.Namespace.GLOBAL, put?.namespace)
        assertEquals("device_name", put?.key)
        assertEquals("Pixel 9 Pro", put?.value)

        val get = SettingsCommands.parseTarget("settings get secure default_input_method")
        assertEquals(SettingsCommands.Namespace.SECURE, get?.namespace)
        assertEquals("default_input_method", get?.key)
        assertNull(get?.value)

        assertNull(SettingsCommands.parseTarget("settings delete global wifi_on"))
    }

    @Test
    fun `sh 引号解引与引用互逆`() {
        assertEquals("Pixel 9 Pro", KitCommands.unshellQuote("'Pixel 9 Pro'"))
        assertEquals("Meng's Phone", KitCommands.unshellQuote("'Meng'\\''s Phone'"))
        assertEquals("raw", KitCommands.unshellQuote("raw"))
    }

    // ── dumpsys 白名单 ────────────────────────────────────────────────

    @Test
    fun `dumpsys 白名单全量放行（构造器与白名单自洽）`() {
        assertEquals("dumpsys battery", DumpsysCommands.dumpsys("battery"))
        assertEquals("dumpsys activity top", DumpsysCommands.dumpsys("activity top"))
        assertEquals(
            "dumpsys procstats --hours 3",
            DumpsysCommands.dumpsys("procstats --hours 3"),
        )
        DumpsysCommands.whitelist.forEach { preset ->
            assertEquals("dumpsys ${preset.args}", DumpsysCommands.dumpsys(preset.args))
        }
    }

    @Test
    fun `dumpsys 白名单外一律拒绝`() {
        // 写操作变体 / 注入拼接 / 未收录目标 / 空串 —— fail-closed
        assertThrows(IllegalArgumentException::class.java) {
            DumpsysCommands.dumpsys("battery set level 0")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DumpsysCommands.dumpsys("battery; reboot")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DumpsysCommands.dumpsys("activity")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DumpsysCommands.dumpsys("surfaceflinger")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DumpsysCommands.dumpsys("")
        }
    }

    // ── 64KB 输出窗口 ─────────────────────────────────────────────────

    @Test
    fun `大输出按 64KB 窗口截断`() {
        val small = "x".repeat(1024)
        val kept = KitCommands.windowOutput(small)
        assertFalse(kept.truncated)
        assertEquals(small, kept.text)

        val exact = "z".repeat(KitCommands.MAX_OUTPUT_CHARS)
        assertFalse(KitCommands.windowOutput(exact).truncated)

        val huge = "y".repeat(KitCommands.MAX_OUTPUT_CHARS + 1000)
        val window = KitCommands.windowOutput(huge)
        assertTrue(window.truncated)
        assertTrue(window.text.startsWith("y"))
        // 截断后 = 64KB 原文 + 一行截断提示，远小于原文
        assertTrue(window.text.length in KitCommands.MAX_OUTPUT_CHARS..(KitCommands.MAX_OUTPUT_CHARS + 64))
        assertTrue(window.text.length < huge.length)
        assertTrue(window.text.contains("截断"))
    }

    // ── 历史序列化 ────────────────────────────────────────────────────

    @Test
    fun `历史序列化往返`() {
        val items = listOf(
            CommandHistoryItem(CommandType.APPOPS, "appops get com.a android:m", 0, 12, 1000),
            CommandHistoryItem(CommandType.SETTINGS, "settings get global wifi_on", 1, 5, 2000),
            CommandHistoryItem(CommandType.DUMPSYS, "dumpsys battery", 0, 88, 3000),
            CommandHistoryItem(CommandType.APPOPS, "appops set com.a op allow", -1, 3, 4000),
        )
        val json = HistoryStore.serialize(items)
        assertEquals(items, HistoryStore.deserialize(json).getOrNull())
    }

    @Test
    fun `坏 JSON 拒绝不猜测`() {
        assertTrue(HistoryStore.deserialize("not json").isFailure)
        assertTrue(HistoryStore.deserialize("{\"type\":").isFailure)
    }

    @Test
    fun `历史裁剪到最近 50 条（最新在前语义）`() {
        // 存储序 = 最新在前：index 0 最新
        val items = (1..60)
            .map { CommandHistoryItem(CommandType.APPOPS, "cmd $it", 0, it.toLong(), it.toLong()) }
            .asReversed() // cmd 60 在头部（最新）
        val trimmed = HistoryStore.trim(items)
        assertEquals(50, trimmed.size)
        assertEquals("cmd 60", trimmed.first().command)
        assertEquals("cmd 11", trimmed.last().command)
        // trim 幂等
        assertEquals(trimmed, HistoryStore.trim(trimmed))
    }

    @Test
    fun `push 头插并裁剪`() {
        val items = (1..50).map {
            CommandHistoryItem(CommandType.SETTINGS, "cmd $it", 0, 1, it.toLong())
        }
        val fresh = CommandHistoryItem(CommandType.DUMPSYS, "fresh", 0, 2, 99)
        val pushed = HistoryStore.push(items, fresh)
        assertEquals(50, pushed.size)
        assertEquals("fresh", pushed.first().command)
        // 头插 51 条 → take(50)：队尾的 cmd 50 被挤掉，cmd 1 保留
        assertFalse(pushed.any { it.command == "cmd 50" })
        assertTrue(pushed.any { it.command == "cmd 1" })
    }
}
