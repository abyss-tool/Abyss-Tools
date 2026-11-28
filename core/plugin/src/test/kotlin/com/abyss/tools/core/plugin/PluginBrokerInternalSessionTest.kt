package com.abyss.tools.core.plugin

import com.abyss.tools.core.common.SemVer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ARC-104 §4 / AC-002 —— 宿主自消费通道 `PluginBroker.getInternalSession()` 契约测试。
 *
 * 覆盖：正向签发（INTERNAL 作用域、TTL ≤60s、requesterId 审计分账）；
 * 签名自校验失败 / 包名不符；能力越权（未注册 / 底座未提供）；底座未安装（一键引导）；
 * 底座不在索引；requesterId 非法；内部 grant 同样单次消耗、可撤销。
 */
class PluginBrokerInternalSessionTest {

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes().decodeToString()

    private fun fixtureIndex(): PluginIndex = PluginParsers.parseIndex(resource("plugins-index.json"))

    /** 宿主签名（与 PluginBrokerTest 同一发布者指纹，冒号分隔形态 → 归一化）。 */
    private val hostSignerColons: String =
        "76:03:78:21:44:88:D6:20:79:78:BA:1E:37:E6:DC:82:AB:C2:DE:72:C9:F6:C6:7D:9B:44:95:15:8A:99:B7:69"
    private val hostSignerHex: String = hostSignerColons.replace(":", "")

    private val installedBase: Map<String, SemVer> = mapOf("abyss-plugin-linux" to SemVer.parse("0.2.0")!!)

    private fun request(
        requesterId: String = "internal:terminal",
        capability: String = "linux-runtime-v1",
        callerPackage: String = "com.abyss.tools",
        callerSigner: String = hostSignerHex,
    ) = PluginBroker.Request(requesterId, capability, callerPackage, callerSigner)

    private fun broker(
        audit: MutableList<PluginBroker.AuditEvent> = mutableListOf(),
        hostSigner: String = hostSignerColons,
    ) = PluginBroker(
        resolver = DependencyResolver(hostAppVersion = SemVer.parse("0.10.0")!!),
        grantIssuer = SessionGrantIssuer(clock = { 1_000L }, hostIdentity = "test-host"),
        hostPackage = "com.abyss.tools",
        hostSignerSha256 = hostSigner,
        onAudit = { audit += it },
    )

    @Test
    fun `正向 - 主壳内部模块拿到 INTERNAL 令牌（TTL 60s + 审计分账）`() {
        val audit = mutableListOf<PluginBroker.AuditEvent>()
        val binding = broker(audit).getInternalSession(request(), fixtureIndex(), installedBase, "sess-int-1")
        val ok = binding as PluginBroker.Binding.Authorized
        assertEquals("abyss-plugin-linux", ok.basePluginId)
        assertEquals(SemVer.parse("0.2.0"), ok.baseVersion)
        assertEquals(GrantScope.INTERNAL, ok.grant.scope)
        assertEquals("internal:terminal", ok.grant.requesterId)
        assertEquals("com.abyss.tools", ok.grant.callerPackage)
        assertEquals(SessionGrantIssuer.INTERNAL_GRANT_TTL_SEC * 1000L, ok.grant.expiryEpochMs - ok.grant.issuedAtEpochMs)
        assertEquals(1, audit.size)
        assertEquals("AUTHORIZED", audit.single().outcome)
        assertEquals("internal:terminal", audit.single().requesterId)
    }

    @Test
    fun `内部令牌同样单次消耗且可撤销（DeathRecipient 回收路径）`() {
        val issuer = SessionGrantIssuer(clock = { 1_000L }, hostIdentity = "test-host")
        val broker = PluginBroker(DependencyResolver(hostAppVersion = SemVer.parse("0.10.0")!!), issuer, "com.abyss.tools", hostSignerColons)
        val ok = broker.getInternalSession(request(), fixtureIndex(), installedBase, "sess-int-2") as PluginBroker.Binding.Authorized
        assertTrue(issuer.verify(ok.grant, "sess-int-2") is SessionGrantIssuer.GrantCheck.Valid)
        val replay = issuer.verify(ok.grant, "sess-int-2")
        assertEquals("E_GRANT_REPLAY", (replay as SessionGrantIssuer.GrantCheck.Invalid).code)
        assertEquals(1, issuer.revoke("sess-int-2"))
    }

    @Test
    fun `签名不符 - E_SIGNATURE_MISMATCH（不给自己发免检通行证）`() {
        val audit = mutableListOf<PluginBroker.AuditEvent>()
        val binding = broker(audit).getInternalSession(
            request(callerSigner = "aa" + "bb".repeat(31)),
            fixtureIndex(),
            installedBase,
            "sess-x",
        )
        val denied = binding as PluginBroker.Binding.Denied
        assertEquals("E_SIGNATURE_MISMATCH", denied.code)
        assertTrue(denied.humanReason.contains("宿主自校验"))
        assertNull(denied.remedy)
        assertTrue(audit.single().outcome.startsWith("DENIED:E_SIGNATURE_MISMATCH"))
    }

    @Test
    fun `冒充其他包名 - E_SIGNATURE_MISMATCH`() {
        val binding = broker().getInternalSession(request(callerPackage = "com.evil.thirdparty"), fixtureIndex(), installedBase, "sess-x")
        assertEquals("E_SIGNATURE_MISMATCH", (binding as PluginBroker.Binding.Denied).code)
    }

    @Test
    fun `宿主指纹常量配错 - 构造即拒绝（fail-closed）`() {
        var thrown = false
        try {
            broker(hostSigner = "not-a-sha")
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `能力越权 - 未注册能力 E_CAPABILITY_MISMATCH`() {
        val binding = broker().getInternalSession(request(capability = "linux-runtime-v2"), fixtureIndex(), installedBase, "sess-x")
        assertEquals("E_CAPABILITY_MISMATCH", (binding as PluginBroker.Binding.Denied).code)
    }

    @Test
    fun `能力越权 - 底座未提供该能力 E_CAPABILITY_MISMATCH`() {
        val index = fixtureIndex().let { idx ->
            idx.copy(
                plugins = idx.plugins.map { e ->
                    if (e.id == "abyss-plugin-linux") e.copy(provides = emptyList()) else e
                },
            )
        }
        val binding = broker().getInternalSession(request(), index, installedBase, "sess-x")
        assertEquals("E_CAPABILITY_MISMATCH", (binding as PluginBroker.Binding.Denied).code)
    }

    @Test
    fun `底座未安装 - E_RUNTIME_UNAVAILABLE + 一键安装引导（绝不谎报可用）`() {
        val binding = broker().getInternalSession(request(), fixtureIndex(), installed = emptyMap(), "sess-x")
        val denied = binding as PluginBroker.Binding.Denied
        assertEquals("E_RUNTIME_UNAVAILABLE", denied.code)
        assertEquals(PluginBroker.Remedy.INSTALL_BASE_PLUGIN, denied.remedy)
        assertTrue(denied.humanReason.contains("请先安装"))
    }

    @Test
    fun `底座不在索引 - E_RUNTIME_UNAVAILABLE + 安装引导`() {
        val index = fixtureIndex().let { idx -> idx.copy(plugins = idx.plugins.filter { it.id != "abyss-plugin-linux" }) }
        val binding = broker().getInternalSession(request(), index, installedBase, "sess-x")
        val denied = binding as PluginBroker.Binding.Denied
        assertEquals("E_RUNTIME_UNAVAILABLE", denied.code)
        assertEquals(PluginBroker.Remedy.INSTALL_BASE_PLUGIN, denied.remedy)
    }

    @Test
    fun `requesterId 非法 - E_INTERNAL_REQUESTER_INVALID（审计分账前提）`() {
        listOf("terminal", "internal:", "internal:Terminal", "internal:has space").forEach { bad ->
            val binding = broker().getInternalSession(request(requesterId = bad), fixtureIndex(), installedBase, "sess-x")
            assertEquals("E_INTERNAL_REQUESTER_INVALID", (binding as PluginBroker.Binding.Denied).code)
        }
    }

    @Test
    fun `会话标识为空 - E_GRANT_INVALID 不抛异常（NB-26 穿透锚）`() {
        // 旧实现：issueInternal 的 require 会以 IllegalArgumentException 穿透
        // Binder / UI 调用栈（违反 FR-002）；现在预拦为人话错误码。
        val binding = broker().getInternalSession(request(), fixtureIndex(), installedBase, sessionId = "")
        val denied = binding as PluginBroker.Binding.Denied
        assertEquals("E_GRANT_INVALID", denied.code)
        assertTrue(denied.humanReason.contains("会话标识"))
    }

    @Test
    fun `能力 since 高于已装版本 - E_DEP_VERSION 引导升级（NB-27 假可用收口）`() {
        // 索引升级到 0.3.0 且该能力 since=0.3.0，本地仍装 0.2.0：
        // 「看似提供」但本地没有 —— 必须拒签并引导升级，绝不带病签发。
        val index = fixtureIndex().let { idx ->
            idx.copy(
                plugins = idx.plugins.map { e ->
                    if (e.id == "abyss-plugin-linux") {
                        e.copy(
                            version = "0.3.0",
                            provides = e.provides.map { it.copy(since = "0.3.0") },
                        )
                    } else {
                        e
                    }
                },
            )
        }
        val binding = broker().getInternalSession(request(), index, installedBase, "sess-x")
        val denied = binding as PluginBroker.Binding.Denied
        assertEquals("E_DEP_VERSION", denied.code)
        assertEquals(PluginBroker.Remedy.INSTALL_BASE_PLUGIN, denied.remedy)
        assertTrue(denied.humanReason.contains("升级"))
    }

    @Test
    fun `能力 since 非法形态 - fail-closed 拒签不猜（NB-27）`() {
        val index = fixtureIndex().let { idx ->
            idx.copy(
                plugins = idx.plugins.map { e ->
                    if (e.id == "abyss-plugin-linux") {
                        e.copy(provides = e.provides.map { it.copy(since = "0.x") })
                    } else {
                        e
                    }
                },
            )
        }
        val binding = broker().getInternalSession(request(), index, installedBase, "sess-x")
        assertEquals("E_CAPABILITY_MISMATCH", (binding as PluginBroker.Binding.Denied).code)
    }

    @Test
    fun `minHost 高于宿主 - 复用解析器 fail-closed（E_HOST_API_TOO_LOW）`() {
        val resolver = DependencyResolver(hostApiVersion = 1, hostAppVersion = SemVer.parse("0.10.0")!!)
        val broker = PluginBroker(resolver, SessionGrantIssuer(clock = { 1L }), "com.abyss.tools", hostSignerColons)
        val binding = broker.getInternalSession(request(), fixtureIndex(), installedBase, "sess-x")
        assertEquals("E_HOST_API_TOO_LOW", (binding as PluginBroker.Binding.Denied).code)
    }

    @Test
    fun `指纹归一化 - 冒号与空白形态等价，非法为 null`() {
        assertEquals(hostSignerHex, PluginBroker.normalizeSha256(hostSignerColons))
        assertEquals(hostSignerHex, PluginBroker.normalizeSha256(hostSignerHex.lowercase()))
        assertNotNull(PluginBroker.normalizeSha256(hostSignerHex))
        assertNull(PluginBroker.normalizeSha256("zz"))
        assertNull(PluginBroker.normalizeSha256(null))
    }
}
