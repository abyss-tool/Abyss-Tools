package com.abyss.tools.feature.syspower

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 隐私与管控指令编排纯逻辑（权限审计 / 敏感操作记录 / Doze 白名单 /
 * 流量策略）：命令形态（与 adb 手敲一字不差，可人工对照）/ 夹具解析
 * （Android 9 / 13 / 14 历代 dumpsys 形态）/ fail-closed（缺段 → null、
 * 坏输出 → null / 空表，绝不猜）/ 注入门禁（敌意 token 就地拦截）。
 */
class SysPrivacyCommandsTest {

    // ── 权限审计：命令形态与门禁 ──────────────────────────────────────

    @Test
    fun `dumpPackage 命令形态与敌意包名拦截`() {
        assertEquals("dumpsys package com.foo.bar", PermissionAuditCommands.dumpPackage("com.foo.bar"))
        assertThrows(IllegalArgumentException::class.java) {
            PermissionAuditCommands.dumpPackage("com.foo;rm -rf /")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PermissionAuditCommands.dumpPackage("../evil")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PermissionAuditCommands.dumpPackage("com.foo bar")
        }
    }

    @Test
    fun `危险权限白名单 16 项且顺序稳定（顺序即展示序）`() {
        assertEquals(16, PermissionAuditCommands.DANGEROUS_PERMS.size)
        assertEquals("android.permission.CAMERA", PermissionAuditCommands.DANGEROUS_PERMS.first())
        assertEquals(
            "android.permission.ACTIVITY_RECOGNITION",
            PermissionAuditCommands.DANGEROUS_PERMS.last(),
        )
        // 全员带 android.permission. 前缀（pm grant / revoke 接受的完整形态）
        assertTrue(PermissionAuditCommands.DANGEROUS_PERMS.all { it.startsWith("android.permission.") })
    }

    // ── 权限审计：runtime permissions 段解析 ─────────────────────────

    /** Android 13 形态：顶层 runtime 段 + User 0 段重复同名权限 + Queries 段尾随。 */
    private val dumpA13 = """
        Package [com.example.app] (/data/app/~~aQ==/com.example.app-Qw==/base.apk):
          userId=10178
          pkg=Package{com.example.app}
          requested permissions:
            android.permission.ACCESS_COARSE_LOCATION
            android.permission.ACCESS_FINE_LOCATION
            android.permission.CAMERA
            android.permission.INTERNET
            android.permission.READ_EXTERNAL_STORAGE
            android.permission.RECORD_AUDIO
          install permissions:
            android.permission.INTERNET: granted=true
          runtime permissions:
            android.permission.ACCESS_COARSE_LOCATION: granted=true, flags=[ GRANTED_BY_DEFAULT ]
            android.permission.CAMERA: granted=false, flags=[ USER_SET|USER_FIXED ]
            android.permission.READ_EXTERNAL_STORAGE: granted=true, flags=[ USER_SET ]
          User 0: ceDataInode=291424832 installed=true hidden=false suspended=false stopped=true notLaunched=false enabled=0
            runtime permissions:
              android.permission.ACCESS_COARSE_LOCATION: granted=true, flags=[ GRANTED_BY_DEFAULT ]
              android.permission.CAMERA: granted=false, flags=[ USER_SET|USER_FIXED ]
              android.permission.READ_EXTERNAL_STORAGE: granted=true, flags=[ USER_SET ]
              install permissions:
                android.permission.INTERNET: granted=true, flags=[0x0]
        Queries:
          via package:
            com.android.shell
    """.trimIndent()

    /** Android 9 老形态：权限只在 User 0 段下，flags 为 0x0 数字形态。 */
    private val dumpA9 = """
        Package [com.example.legacy] (/data/app/com.example.legacy-1/base.apk):
          userId=10098
          requested permissions:
            android.permission.CAMERA
            android.permission.RECORD_AUDIO
          User 0: ceDataInode=999 installed=true hidden=false suspended=false stopped=false notLaunched=false enabled=0
            runtime permissions:
              android.permission.CAMERA: granted=true, flags=[0x0]
              android.permission.RECORD_AUDIO: granted=false, flags=[0x0]
            install permissions:
              android.permission.INTERNET: granted=true
    """.trimIndent()

    /** Android 14 形态：权限只在 User 0 段（含 API 33+ 的 POST_NOTIFICATIONS）。 */
    private val dumpA14 = """
        Package [com.example.modern] (/data/app/~~x==/com.example.modern-y==/base.apk):
          userId=10255
          requested permissions:
            android.permission.CAMERA
            android.permission.POST_NOTIFICATIONS
          User 0: ceDataInode=112233 installed=true hidden=false suspended=false stopped=false notLaunched=false enabled=0
            runtime permissions:
              android.permission.CAMERA: granted=true, flags=[ USER_SET ]
              android.permission.POST_NOTIFICATIONS: granted=false, flags=[ USER_SENSITIVE_WHEN_UNLOCKED|USER_SET ]
            install permissions:
              android.permission.INTERNET: granted=true, flags=[0x0]
    """.trimIndent()

    @Test
    fun `parseRuntimePermissions 解析 Android 13 形态（去重 + install 段不误收）`() {
        // 顶层段与 User 0 段同名权限只收一次（首见为准）；install 段的
        // INTERNET granted=true（段前段后各一处）绝不混入
        assertEquals(
            listOf(
                RuntimePermissionState("android.permission.ACCESS_COARSE_LOCATION", true),
                RuntimePermissionState("android.permission.CAMERA", false),
                RuntimePermissionState("android.permission.READ_EXTERNAL_STORAGE", true),
            ),
            PermissionAuditCommands.parseRuntimePermissions(dumpA13),
        )
    }

    @Test
    fun `parseRuntimePermissions 解析 Android 9 老形态（0x0 flags）`() {
        assertEquals(
            listOf(
                RuntimePermissionState("android.permission.CAMERA", true),
                RuntimePermissionState("android.permission.RECORD_AUDIO", false),
            ),
            PermissionAuditCommands.parseRuntimePermissions(dumpA9),
        )
    }

    @Test
    fun `parseRuntimePermissions 解析 Android 14 形态（权限仅在 User 段）`() {
        assertEquals(
            listOf(
                RuntimePermissionState("android.permission.CAMERA", true),
                RuntimePermissionState("android.permission.POST_NOTIFICATIONS", false),
            ),
            PermissionAuditCommands.parseRuntimePermissions(dumpA14),
        )
    }

    @Test
    fun `parseRuntimePermissions 缺段与坏输出一律 null（fail-closed）`() {
        // 只有 requested / install 段：runtime 段缺失 → null，install 的
        // granted=true 行不能顶替（那不是运行时权限真值）
        val noRuntime = """
            Package [com.example.permless]:
              requested permissions:
                android.permission.INTERNET
              install permissions:
                android.permission.INTERNET: granted=true
        """.trimIndent()
        assertNull(PermissionAuditCommands.parseRuntimePermissions(noRuntime))
        assertNull(PermissionAuditCommands.parseRuntimePermissions(""))
        assertNull(PermissionAuditCommands.parseRuntimePermissions("totally garbage\nnot a dump at all"))
    }

    // ── 权限审计：requested permissions 段解析 ────────────────────────

    @Test
    fun `parseRequestedPermissions 解析 Android 13 形态（保序 + install 段不误收）`() {
        assertEquals(
            listOf(
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.CAMERA",
                "android.permission.INTERNET",
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.RECORD_AUDIO",
            ),
            PermissionAuditCommands.parseRequestedPermissions(dumpA13),
        )
    }

    @Test
    fun `parseRequestedPermissions 段尾边界（陌生形态终止，不吞其它段的包名）`() {
        // 「User 0: installed=true」不是小写段头也不是裸名单行 → 段尾；
        // 其后的包名行（com.android.shell）绝不误收成权限
        val tricky = """
            requested permissions:
              android.permission.CAMERA
            User 0: installed=true
              com.android.shell
        """.trimIndent()
        assertEquals(
            listOf("android.permission.CAMERA"),
            PermissionAuditCommands.parseRequestedPermissions(tricky),
        )
        // 段内空行容忍：跨空行继续收名单
        val withBlank = """
            requested permissions:
              android.permission.CAMERA

              android.permission.RECORD_AUDIO
            install permissions:
        """.trimIndent()
        assertEquals(
            listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"),
            PermissionAuditCommands.parseRequestedPermissions(withBlank),
        )
    }

    @Test
    fun `parseRequestedPermissions 缺段与坏输出一律 null`() {
        assertNull(PermissionAuditCommands.parseRequestedPermissions(""))
        assertNull(PermissionAuditCommands.parseRequestedPermissions("Packages:\n  nothing here"))
        assertNull(PermissionAuditCommands.parseRequestedPermissions("runtime permissions:\n  android.permission.CAMERA: granted=true"))
    }

    @Test
    fun `filterDangerous 白名单序交集与 null 透传`() {
        assertNull(PermissionAuditCommands.filterDangerous(null))
        assertEquals(emptyList<String>(), PermissionAuditCommands.filterDangerous(emptyList()))
        // 交集后按白名单序（CAMERA 在 READ_SMS 前），非白名单项（INTERNET）剔除
        assertEquals(
            listOf("android.permission.CAMERA", "android.permission.READ_SMS"),
            PermissionAuditCommands.filterDangerous(
                listOf("android.permission.READ_SMS", "android.permission.INTERNET", "android.permission.CAMERA"),
            ),
        )
    }

    // ── 敏感操作记录 ──────────────────────────────────────────────────

    @Test
    fun `queryOp 命令形态与 op 集合`() {
        assertEquals("appops query-op CAMERA", SensitiveOpsCommands.queryOp("CAMERA"))
        assertEquals("appops query-op READ_CLIPBOARD", SensitiveOpsCommands.queryOp("READ_CLIPBOARD"))
        assertEquals(
            listOf("CAMERA", "MICROPHONE", "FINE_LOCATION", "COARSE_LOCATION", "READ_CLIPBOARD"),
            SensitiveOpsCommands.QUERY_OPS,
        )
        assertThrows(IllegalArgumentException::class.java) {
            SensitiveOpsCommands.queryOp("CAMERA && pm hide com.x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SensitiveOpsCommands.queryOp("camera;reboot")
        }
    }

    @Test
    fun `parseQueryOp 宽容两种历史行形态（Package 前缀可选）`() {
        val output = """
            com.whatsapp: CAMERA (allow)
            Package com.android.camera: CAMERA (foreground)
            com.foo.bar: CAMERA (ignore)
            坏行直接跳过
        """.trimIndent()
        assertEquals(
            listOf(
                OpUsage("com.whatsapp", "allow"),
                OpUsage("com.android.camera", "foreground"),
                OpUsage("com.foo.bar", "ignore"),
            ),
            SensitiveOpsCommands.parseQueryOp(output),
        )
    }

    @Test
    fun `parseQueryOp 空 / 坏输出 → 空表（无使用记录是合法答案）`() {
        assertEquals(emptyList<OpUsage>(), SensitiveOpsCommands.parseQueryOp(""))
        assertEquals(emptyList<OpUsage>(), SensitiveOpsCommands.parseQueryOp("Error: unknown op"))
        assertEquals(emptyList<OpUsage>(), SensitiveOpsCommands.parseQueryOp("No such app ops: CAMERA for 10123"))
    }

    // ── Doze 白名单 ──────────────────────────────────────────────────

    @Test
    fun `deviceidle 命令形态与敌意包名拦截`() {
        assertEquals("dumpsys deviceidle whitelist", DeviceIdleCommands.whitelist())
        assertEquals("dumpsys deviceidle whitelist +com.foo", DeviceIdleCommands.add("com.foo"))
        assertEquals("dumpsys deviceidle whitelist -com.foo", DeviceIdleCommands.remove("com.foo"))
        assertThrows(IllegalArgumentException::class.java) {
            DeviceIdleCommands.add("com.foo;rm")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceIdleCommands.remove("com.foo bar")
        }
    }

    @Test
    fun `parseWhitelist 提取包名 token（段头 / 时效尾注 / 重复全拒）`() {
        val dump = """
            system whitelist:
              com.android.providers.downloads
              com.android.providers.downloads.ui
              com.android.vending: 626s-left
            except-idle whitelist:
              com.android.shell
            user whitelist:
            com.example.alpha, com.example.beta
            com.example.alpha
        """.trimIndent()
        // 带过期时间尾注的条目（com.android.vending: 626s-left）按契约被拒 ——
        // 宁可少展示，不把非包名形态当包名
        assertEquals(
            listOf(
                "com.android.providers.downloads",
                "com.android.providers.downloads.ui",
                "com.android.shell",
                "com.example.alpha",
                "com.example.beta",
            ),
            DeviceIdleCommands.parseWhitelist(dump),
        )
    }

    @Test
    fun `parseWhitelist 空 / 坏输出 → 空表`() {
        assertEquals(emptyList<String>(), DeviceIdleCommands.parseWhitelist(""))
        assertEquals(emptyList<String>(), DeviceIdleCommands.parseWhitelist("Error: service not found"))
        assertEquals(emptyList<String>(), DeviceIdleCommands.parseWhitelist("can't find service: deviceidle"))
    }

    @Test
    fun `looksLikePackageName 输入门禁（UI 层先拦，防点击处闪退）`() {
        assertTrue(DeviceIdleCommands.looksLikePackageName("com.foo"))
        assertTrue(DeviceIdleCommands.looksLikePackageName("com.foo.bar"))
        assertFalse(DeviceIdleCommands.looksLikePackageName("foo")) // 无点分段
        assertFalse(DeviceIdleCommands.looksLikePackageName(".com")) // 点开头
        assertFalse(DeviceIdleCommands.looksLikePackageName("com"))
        assertFalse(DeviceIdleCommands.looksLikePackageName("com.foo;rm"))
        assertFalse(DeviceIdleCommands.looksLikePackageName(""))
    }

    // ── 流量策略 ─────────────────────────────────────────────────────

    @Test
    fun `netpolicy 全局命令形态与宽容解析`() {
        assertEquals("cmd netpolicy get restrict-background", NetPolicyCommands.getRestrictBackground())
        assertEquals(
            "cmd netpolicy set restrict-background true",
            NetPolicyCommands.setRestrictBackground(true),
        )
        assertEquals(
            "cmd netpolicy set restrict-background false",
            NetPolicyCommands.setRestrictBackground(false),
        )
        // 官方形态 "restrict-background: enabled/disabled" 与历代 "restricted: true/false"
        assertEquals(true, NetPolicyCommands.parseRestrictBackground("restrict-background: enabled"))
        assertEquals(false, NetPolicyCommands.parseRestrictBackground("restrict-background: disabled"))
        assertEquals(true, NetPolicyCommands.parseRestrictBackground("restricted: true"))
        assertEquals(false, NetPolicyCommands.parseRestrictBackground("restricted: false"))
        assertEquals(false, NetPolicyCommands.parseRestrictBackground("Not restricted"))
        // 空 / 陌生文案 → null（绝不猜）
        assertNull(NetPolicyCommands.parseRestrictBackground(""))
        assertNull(NetPolicyCommands.parseRestrictBackground("Unknown command"))
    }

    @Test
    fun `uid-policy 命令形态与非负门禁`() {
        assertEquals("cmd netpolicy get uid-policy 10123", NetPolicyCommands.getUidPolicy(10123))
        assertEquals("cmd netpolicy set uid-policy 10123 1", NetPolicyCommands.setUidPolicy(10123, true))
        assertEquals("cmd netpolicy set uid-policy 10123 0", NetPolicyCommands.setUidPolicy(10123, false))
        assertThrows(IllegalArgumentException::class.java) { NetPolicyCommands.getUidPolicy(-1) }
        assertThrows(IllegalArgumentException::class.java) { NetPolicyCommands.setUidPolicy(-5, true) }
    }

    @Test
    fun `parseUidPolicy 取首个整数（POLICY_XXX (N) 与裸数字形态）`() {
        assertEquals(0, NetPolicyCommands.parseUidPolicy("0"))
        assertEquals(0, NetPolicyCommands.parseUidPolicy("POLICY_NONE (0)"))
        assertEquals(1, NetPolicyCommands.parseUidPolicy("POLICY_REJECT_METERED_BACKGROUND (1)"))
        assertEquals(4, NetPolicyCommands.parseUidPolicy("POLICY_ALLOW_METERED_BACKGROUND (4)"))
        assertEquals(4, NetPolicyCommands.parseUidPolicy("4"))
        // 无整数（纯策略名 / 空 / 陌生文案）→ null
        assertNull(NetPolicyCommands.parseUidPolicy("POLICY_NONE"))
        assertNull(NetPolicyCommands.parseUidPolicy(""))
        assertNull(NetPolicyCommands.parseUidPolicy("unknown uid"))
    }
}
