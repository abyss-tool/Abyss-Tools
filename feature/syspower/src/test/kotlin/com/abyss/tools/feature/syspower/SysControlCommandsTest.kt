package com.abyss.tools.feature.syspower

import com.abyss.tools.core.shell.ShizukuCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统控制中心指令编排纯逻辑：命令形态（与 adb 手敲一字不差，可人工
 * 对照）/ 解析 fail-closed / 注入门禁 / 会话日志撤销语义。
 */
class SysControlCommandsTest {

    // ── 显示与外观 ───────────────────────────────────────────────────

    @Test
    fun `深色模式命令形态与三态解析`() {
        assertEquals("cmd uimode night yes", DisplayCommands.setDarkMode(true))
        assertEquals("cmd uimode night no", DisplayCommands.setDarkMode(false))
        assertEquals("settings get secure ui_night_mode", DisplayCommands.readDarkMode())
        assertEquals(false, DisplayCommands.parseNightMode("0"))
        assertEquals(true, DisplayCommands.parseNightMode("1"))
        // "2" = 自定义时刻表（日落/定时）—— 开关双态不可判定；"null" 缺省同理
        assertNull(DisplayCommands.parseNightMode("2"))
        assertNull(DisplayCommands.parseNightMode("null"))
        assertNull(DisplayCommands.parseNightMode(""))
    }

    @Test
    fun `亮度夹取 1-255 且读取解析 fail-closed`() {
        assertEquals("settings put system screen_brightness 128", DisplayCommands.setBrightness(128))
        assertEquals("settings put system screen_brightness 1", DisplayCommands.setBrightness(-5))
        assertEquals("settings put system screen_brightness 255", DisplayCommands.setBrightness(300))
        assertEquals("settings get system screen_brightness", DisplayCommands.readBrightness())
        assertEquals(128, DisplayCommands.parseBrightness("128"))
        assertNull(DisplayCommands.parseBrightness("null"))
        assertNull(DisplayCommands.parseBrightness("garbage"))
    }

    @Test
    fun `动画三表链式命令与刻度形态`() {
        assertEquals(
            "settings put global window_animation_scale 0.5" +
                " && settings put global transition_animation_scale 0.5" +
                " && settings put global animator_duration_scale 0.5",
            DisplayCommands.setAnimationScale(0.5f),
        )
        // 整数刻度保留一位小数（官方 CLI 形态），0 为「关动画」特例
        assertTrue(DisplayCommands.setAnimationScale(1f).endsWith("animator_duration_scale 1.0"))
        assertTrue(DisplayCommands.setAnimationScale(0f).endsWith("animator_duration_scale 0"))
        assertEquals(0.5f, DisplayCommands.parseAnimationScale("0.5"))
        assertEquals(1f, DisplayCommands.parseAnimationScale("1.0"))
        assertNull(DisplayCommands.parseAnimationScale("null"))
    }

    @Test
    fun `熄屏超时秒转毫秒与正数门禁`() {
        assertEquals("settings put system screen_off_timeout 30000", DisplayCommands.setScreenTimeout(30))
        assertEquals(60, DisplayCommands.parseTimeoutSeconds("60000"))
        assertNull(DisplayCommands.parseTimeoutSeconds("null"))
        assertThrows(IllegalArgumentException::class.java) {
            DisplayCommands.setScreenTimeout(0)
        }
    }

    @Test
    fun `自动亮度与 0 1 解析`() {
        assertEquals(
            "settings put system screen_brightness_mode 1",
            DisplayCommands.setAutoBrightness(true),
        )
        assertEquals(
            "settings put system screen_brightness_mode 0",
            DisplayCommands.setAutoBrightness(false),
        )
        assertEquals(true, DisplayCommands.parseAutoBrightness("1"))
        assertEquals(false, DisplayCommands.parseAutoBrightness("0"))
        assertNull(DisplayCommands.parseAutoBrightness("null"))
    }

    // ── wm 尺寸与密度（Override 优先）────────────────────────────────

    @Test
    fun `parseSize 偏向 Override（活跃覆写值优先）`() {
        val both = "Physical size: 1080x2400\nOverride size: 1080x2340"
        assertEquals(1080 to 2340, DisplayCommands.parseSize(both))
        // 无 Override 时回落 Physical（无覆写即物理值活跃）
        assertEquals(1080 to 2400, DisplayCommands.parseSize("Physical size: 1080x2400"))
        assertNull(DisplayCommands.parseSize("Physical size: ???"))
        assertNull(DisplayCommands.parseSize(""))
    }

    @Test
    fun `parseDensity 偏向 Override 且容忍 dpi 尾缀`() {
        assertEquals(560, DisplayCommands.parseDensity("Physical density: 420\nOverride density: 560"))
        assertEquals(420, DisplayCommands.parseDensity("Physical density: 420"))
        assertEquals(460, DisplayCommands.parseDensity("Physical density: 460dpi"))
        assertNull(DisplayCommands.parseDensity("Physical density: abc"))
    }

    @Test
    fun `wm 命令形态与正数门禁、密度夹取`() {
        assertEquals("wm size 1080x2340", DisplayCommands.setSize(1080, 2340))
        assertThrows(IllegalArgumentException::class.java) { DisplayCommands.setSize(0, 2400) }
        assertThrows(IllegalArgumentException::class.java) { DisplayCommands.setSize(1080, -1) }
        assertEquals("wm size reset", DisplayCommands.resetSize())
        assertEquals("wm size", DisplayCommands.readSize())
        assertEquals("wm density 400", DisplayCommands.setDensity(400))
        assertEquals("wm density 120", DisplayCommands.setDensity(60))
        assertEquals("wm density 640", DisplayCommands.setDensity(900))
        assertEquals("wm density reset", DisplayCommands.resetDensity())
        assertEquals("wm density", DisplayCommands.readDensity())
    }

    // ── 电源与性能 ────────────────────────────────────────────────────

    @Test
    fun `电源命令形态`() {
        assertEquals("settings put global stay_on_while_plugged_in 7", PowerCommands.setStayOnWhilePlugged(true))
        assertEquals("settings put global stay_on_while_plugged_in 0", PowerCommands.setStayOnWhilePlugged(false))
        assertEquals("dumpsys deviceidle force-idle", PowerCommands.forceDoze())
        assertEquals("dumpsys deviceidle unforce", PowerCommands.unforceDoze())
        assertEquals("settings put global low_power 1", PowerCommands.setLowPower(true))
        assertEquals("settings put global low_power 0", PowerCommands.setLowPower(false))
        assertEquals("settings get global low_power", PowerCommands.readLowPower())
    }

    @Test
    fun `常亮位掩码解析（任一电源组合都算开）`() {
        assertEquals(true, PowerCommands.parseStayOnFlag("7"))
        assertEquals(true, PowerCommands.parseStayOnFlag("2"))
        assertEquals(true, PowerCommands.parseStayOnFlag("3")) // AC|USB 组合
        assertEquals(false, PowerCommands.parseStayOnFlag("0"))
        assertNull(PowerCommands.parseStayOnFlag("null"))
        assertNull(PowerCommands.parseStayOnFlag("junk"))
        assertEquals(true, PowerCommands.parseLowPowerFlag("1"))
        assertEquals(false, PowerCommands.parseLowPowerFlag("0"))
        assertNull(PowerCommands.parseLowPowerFlag("null"))
    }

    // ── AppOps ───────────────────────────────────────────────────────

    @Test
    fun `appops 命令形态与敏感清单`() {
        assertEquals(
            "appops set com.foo.bar CAMERA ignore",
            AppOpsCommands.state("com.foo.bar", "CAMERA", AppOpsMode.IGNORE),
        )
        assertEquals(
            "appops set com.foo.bar SYSTEM_ALERT_WINDOW default",
            AppOpsCommands.state("com.foo.bar", "SYSTEM_ALERT_WINDOW", AppOpsMode.DEFAULT),
        )
        assertEquals(
            "appops get com.foo.bar SYSTEM_ALERT_WINDOW",
            AppOpsCommands.query("com.foo.bar", "SYSTEM_ALERT_WINDOW"),
        )
        // 清单顺序即授权面板展示序（12 项）
        assertEquals(12, AppOpsCommands.INTERESTING_OPS.size)
        assertEquals("CAMERA", AppOpsCommands.INTERESTING_OPS.first())
        assertEquals("POST_NOTIFICATION", AppOpsCommands.INTERESTING_OPS.last())
    }

    @Test
    fun `parseMode 各输出形态`() {
        assertEquals("allow", AppOpsCommands.parseMode("CAMERA: allow"))
        assertEquals("ignore", AppOpsCommands.parseMode("RUN_IN_BACKGROUND: ignore"))
        assertEquals("default", AppOpsCommands.parseMode("MICROPHONE: default"))
        assertEquals("foreground", AppOpsCommands.parseMode("FINE_LOCATION: foreground"))
        assertEquals("deny", AppOpsCommands.parseMode("WAKE_LOCK: deny"))
        // 大小写、行首空白、附加括注与时间片段容忍
        assertEquals("allow", AppOpsCommands.parseMode("Camera: allow (implied)"))
        assertEquals("allow", AppOpsCommands.parseMode("  CAMERA: allow; time=..."))
        assertNull(AppOpsCommands.parseMode("No such app ops: CAMERA for 10123"))
        assertNull(AppOpsCommands.parseMode(""))
        assertNull(AppOpsCommands.parseMode("Random dump text without mode"))
    }

    @Test
    fun `敌意包名与 op 名在 appops 构造层被拦截`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppOpsCommands.state("../evil", "CAMERA", AppOpsMode.ALLOW)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppOpsCommands.query("com.evil; svc data disable", "CAMERA")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppOpsCommands.query("com.foo.bar", "CAMERA && pm hide com.x")
        }
    }

    // ── 运行时权限 ────────────────────────────────────────────────────

    @Test
    fun `pm 权限命令形态（check 参数序：权限在前）`() {
        assertEquals(
            "pm grant com.foo.bar android.permission.CAMERA",
            RuntimePermCommands.grant("com.foo.bar", "android.permission.CAMERA"),
        )
        assertEquals(
            "pm revoke com.foo.bar android.permission.CAMERA",
            RuntimePermCommands.revoke("com.foo.bar", "android.permission.CAMERA"),
        )
        assertEquals(
            "pm check-permission android.permission.CAMERA com.foo.bar",
            RuntimePermCommands.check("com.foo.bar", "android.permission.CAMERA"),
        )
    }

    @Test
    fun `parseGranted 宽容形态`() {
        assertEquals(true, RuntimePermCommands.parseGranted("granted=true"))
        assertEquals(true, RuntimePermCommands.parseGranted("granted=true,"))
        assertEquals(true, RuntimePermCommands.parseGranted("granted"))
        assertEquals(true, RuntimePermCommands.parseGranted("true"))
        assertEquals(false, RuntimePermCommands.parseGranted("granted=false"))
        assertEquals(false, RuntimePermCommands.parseGranted("granted=false."))
        assertEquals(false, RuntimePermCommands.parseGranted("denied"))
        assertEquals(false, RuntimePermCommands.parseGranted("false"))
        assertNull(RuntimePermCommands.parseGranted(""))
        assertNull(RuntimePermCommands.parseGranted("package:com.foo.bar"))
    }

    @Test
    fun `敌意输入在 pm 构造层被拦截`() {
        assertThrows(IllegalArgumentException::class.java) {
            RuntimePermCommands.grant("com.evil && pm hide com.x", "android.permission.CAMERA")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimePermCommands.check("com.foo.bar", "../evil perm")
        }
    }

    // ── 门禁（命令注入收敛的单一入口）────────────────────────────────

    @Test
    fun `requireSafeToken 拒绝路径穿越`() {
        // ../（路径穿越）不在 [A-Za-z0-9._:-] 白名单内 —— 就地 IllegalArgumentException
        assertThrows(IllegalArgumentException::class.java) {
            ShizukuCommands.requireSafeToken("../evil", "包名")
        }
    }

    // ── 会话日志（撤销语义）───────────────────────────────────────────

    @Test
    fun `会话日志：记录顺序与撤销最新可撤销动作`() {
        val journal = SessionJournal()
        val darkOn = SysAction("开启深色模式", "cmd uimode night yes")
        val darkOff = SysAction("关闭深色模式", "cmd uimode night no")
        val doze = SysAction("强制 Doze", "dumpsys deviceidle force-idle")
        val unDoze = SysAction("退出 Doze", "dumpsys deviceidle unforce")

        journal.record(darkOn, darkOff)
        journal.record(doze, unDoze)
        assertEquals(listOf(darkOn, doze), journal.entries())
        assertEquals(listOf(darkOn to true, doze to true), journal.entriesWithUndo())

        assertEquals(unDoze, journal.undoLast())
        assertEquals(listOf(darkOn), journal.entries())
        assertEquals(darkOff, journal.undoLast())
        assertEquals(emptyList<SysAction>(), journal.entries())
        assertNull(journal.undoLast())
    }

    @Test
    fun `会话日志：无逆动作条目弹出即弃，单条撤销定位`() {
        val journal = SessionJournal()
        val a = SysAction("A", "cmd-a")
        journal.record(a, SysAction("撤销 A", "undo-cmd-a"))
        journal.record(SysAction("B", "cmd-b"), null) // 无逆动作（如退出 Doze）
        assertEquals(
            listOf(a to true, SysAction("B", "cmd-b") to false),
            journal.entriesWithUndo(),
        )

        // 单条撤销：按动作相等性定位，未命中 → null
        assertEquals("undo-cmd-a", journal.undoOf(a)?.command)
        assertEquals(listOf(SysAction("B", "cmd-b")), journal.entries())
        assertNull(journal.undoOf(a))

        // 无逆动作的条目：undoLast 越过它仍无撤销可用
        assertNull(journal.undoLast())
        assertEquals(emptyList<SysAction>(), journal.entries())
    }
}
