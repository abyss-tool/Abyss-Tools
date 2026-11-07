package com.abyss.tools.core.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 指令编排纯逻辑：命令形态 / 引用安全 / 输出解析（fail-closed 断言）。 */
class ShizukuCommandsTest {

    // ── sh 引用安全 ───────────────────────────────────────────────────

    @Test
    fun `单引号内容原样透传`() {
        assertEquals("'Pixel 9 Pro'", ShizukuCommands.shellQuote("Pixel 9 Pro"))
    }

    @Test
    fun `含单引号的设备名安全转义`() {
        // ' → '\'' ：shell 语义下等价于字面单引号，注入面封死
        assertEquals("'Meng'\\''s Phone'", ShizukuCommands.shellQuote("Meng's Phone"))
    }

    // ── 电量伪装 ──────────────────────────────────────────────────────

    @Test
    fun `电量等级命令夹取 0-100`() {
        assertEquals("dumpsys battery set level 50", BatteryCommands.setLevel(50))
        assertEquals("dumpsys battery set level 0", BatteryCommands.setLevel(-3))
        assertEquals("dumpsys battery set level 100", BatteryCommands.setLevel(120))
    }

    @Test
    fun `解析新版 dumpsys battery 输出`() {
        val dump = """
            Current Battery Service state:
              AC powered: false
              USB powered: true
              Wireless powered: false
              Max charging current: 1500000
              status: 3
              health: 2
              present: true
              level: 87
              scale: 100
              voltage: 4350
              temperature: 280
        """.trimIndent()
        val snapshot = BatteryCommands.parseSnapshot(dump)
        assertEquals(87, snapshot.level)
        assertEquals(BatteryStatus.DISCHARGING, snapshot.status)
        // 新版无统一 powered 字段：USB powered: true → 供电中
        assertEquals(true, snapshot.powered)
    }

    @Test
    fun `解析老版 powered 字段`() {
        val dump = """
            Current Battery Service state:
              powered: false
              status: 2
              level: 45
        """.trimIndent()
        val snapshot = BatteryCommands.parseSnapshot(dump)
        assertEquals(45, snapshot.level)
        assertEquals(BatteryStatus.CHARGING, snapshot.status)
        assertEquals(false, snapshot.powered)
    }

    @Test
    fun `字段缺失时为 null 绝不猜测`() {
        val snapshot = BatteryCommands.parseSnapshot("Current Battery Service state:\n  garbage")
        assertNull(snapshot.level)
        assertNull(snapshot.status)
        assertNull(snapshot.powered)
    }

    // ── 信号伪装 ──────────────────────────────────────────────────────

    @Test
    fun `连接性开关命令形态`() {
        assertEquals("cmd connectivity airplane-mode enable", SignalCommands.setAirplaneMode(true))
        assertEquals("cmd connectivity airplane-mode disable", SignalCommands.setAirplaneMode(false))
        assertEquals("svc data disable", SignalCommands.setMobileData(false))
        assertEquals("svc wifi enable", SignalCommands.setWifi(true))
    }

    @Test
    fun `旧系统飞行模式回退命令带广播`() {
        val cmd = SignalCommands.setAirplaneModeLegacy(true)
        assertTrue(cmd.startsWith("settings put global airplane_mode_on 1"))
        assertTrue(cmd.contains("android.intent.action.AIRPLANE_MODE"))
        assertTrue(cmd.contains("state true"))
    }

    @Test
    fun `settings get 语义解析`() {
        assertEquals(true, SignalCommands.parseSettingFlag("1"))
        assertEquals(false, SignalCommands.parseSettingFlag("0"))
        assertEquals(true, SignalCommands.parseSettingFlag("true"))
        // "null" = 设置项缺省 → 未知（fail-closed）
        assertNull(SignalCommands.parseSettingFlag("null"))
        assertNull(SignalCommands.parseSettingFlag(""))
    }

    // ── 机型伪装 ──────────────────────────────────────────────────────

    @Test
    fun `设备名写入命令带安全引用`() {
        assertEquals(
            "settings put global device_name 'Xiaomi 14 Ultra'",
            DeviceIdentityCommands.setDeviceName("Xiaomi 14 Ultra"),
        )
        val hostile = "X'; dumpsys battery set level 0; '"
        val cmd = DeviceIdentityCommands.setDeviceName(hostile)
        // 单引号被转义 → 注入串不再是独立命令（人肉可读断言：闭合引号数量守恒）
        assertFalse(cmd.contains("; 'X"))
        assertTrue(cmd.startsWith("settings put global device_name '"))
    }

    @Test
    fun `蓝牙名写入命令带安全引用`() {
        assertEquals(
            "settings put secure bluetooth_name 'Meng Phone'",
            DeviceIdentityCommands.setBluetoothName("Meng Phone"),
        )
        // 敌意输入：转义后注入面封死
        val hostile = "X'; settings put global airplane_mode_on 1; '"
        val cmd = DeviceIdentityCommands.setBluetoothName(hostile)
        assertFalse(cmd.contains("; 'X"))
        assertTrue(cmd.startsWith("settings put secure bluetooth_name '"))
    }

    @Test
    fun `机型伪装恢复命令删除覆写项`() {
        assertEquals(
            "settings delete global device_name",
            DeviceIdentityCommands.resetDeviceName(),
        )
        assertEquals(
            "settings delete secure bluetooth_name",
            DeviceIdentityCommands.resetBluetoothName(),
        )
        assertEquals(
            "settings get secure bluetooth_name",
            DeviceIdentityCommands.readBluetoothName(),
        )
    }

    // ── 定位伪装 ──────────────────────────────────────────────────────

    @Test
    fun `mock_location 授权命令双形态`() {
        assertEquals(
            "appops set com.abyss.tools android:mock_location allow",
            MockLocationCommands.allow("com.abyss.tools"),
        )
        assertEquals(
            "appops set com.abyss.tools mock_location allow",
            MockLocationCommands.allowLegacy("com.abyss.tools"),
        )
        assertEquals(
            "appops set com.abyss.tools android:mock_location default",
            MockLocationCommands.reset("com.abyss.tools"),
        )
    }

    @Test
    fun `appops get 输出解析`() {
        assertEquals(true, MockLocationCommands.parseAllowed("android:mock_location: allow"))
        assertEquals(true, MockLocationCommands.parseAllowed("Mock location: Allow"))
        assertEquals(false, MockLocationCommands.parseAllowed("android:mock_location: default"))
        assertEquals(false, MockLocationCommands.parseAllowed("android:mock_location: Reject"))
        assertNull(MockLocationCommands.parseAllowed("No such app ops: android:mock_location"))
        assertNull(MockLocationCommands.parseAllowed(""))
    }

    // ── 安全 token 门禁（命令注入收敛的单一入口）─────────────────────

    @Test
    fun `合法 token 原样放行`() {
        assertEquals(
            "com.abyss.tools",
            ShizukuCommands.requireSafeToken("com.abyss.tools", "包名"),
        )
        // appop 名的 android: 冒号前缀合法（shell 参数位置普通字符）
        assertEquals(
            "android:mock_location",
            ShizukuCommands.requireSafeToken("android:mock_location", "appop 名"),
        )
    }

    @Test
    fun `敌意 token 就地拒绝（元字符 / 空格 / 穿越全拦截）`() {
        val hostile = listOf(
            "com.abyss.tools; dumpsys battery set level 0",
            "com.abyss.tools && svc wifi disable",
            "pkg\$(id)",
            "pkg\`id\`",
            "../..",
            "a b",
            "",
        )
        hostile.forEach { input ->
            try {
                ShizukuCommands.requireSafeToken(input, "包名")
                throw AssertionError("敌意输入未被拒绝：$input")
            } catch (expected: IllegalArgumentException) {
                // fail-closed：就地拒绝
            }
        }
    }

    @Test
    fun `敌意包名在命令构造层被拦截`() {
        val hostile = "com.evil; svc data disable"
        try {
            MockLocationCommands.allow(hostile)
            throw AssertionError("敌意包名未在命令构造层拦截")
        } catch (expected: IllegalArgumentException) {
        }
    }

    // ── 覆盖层授权（SYSTEM_ALERT_WINDOW appops）──────────────────────

    @Test
    fun `覆盖层授权命令形态`() {
        assertEquals(
            "appops set com.abyss.tools SYSTEM_ALERT_WINDOW allow",
            OverlayCommands.grant("com.abyss.tools"),
        )
        assertEquals(
            "appops set com.abyss.tools SYSTEM_ALERT_WINDOW default",
            OverlayCommands.revoke("com.abyss.tools"),
        )
        assertEquals(
            "appops get com.abyss.tools SYSTEM_ALERT_WINDOW",
            OverlayCommands.query("com.abyss.tools"),
        )
    }

    @Test
    fun `覆盖层授权状态解析`() {
        assertEquals(true, OverlayCommands.parseGranted("SYSTEM_ALERT_WINDOW: allow"))
        assertEquals(false, OverlayCommands.parseGranted("SYSTEM_ALERT_WINDOW: default"))
        assertEquals(false, OverlayCommands.parseGranted("SYSTEM_ALERT_WINDOW: deny"))
        assertEquals(false, OverlayCommands.parseGranted("SYSTEM_ALERT_WINDOW: ignore"))
        assertNull(OverlayCommands.parseGranted("No such app ops: SYSTEM_ALERT_WINDOW"))
        assertNull(OverlayCommands.parseGranted(""))
    }

    @Test
    fun `敌意包名在覆盖层命令构造层被拦截`() {
        try {
            OverlayCommands.grant("com.evil; appops set x y allow")
            throw AssertionError("敌意包名未在覆盖层命令构造层拦截")
        } catch (expected: IllegalArgumentException) {
        }
    }
}
