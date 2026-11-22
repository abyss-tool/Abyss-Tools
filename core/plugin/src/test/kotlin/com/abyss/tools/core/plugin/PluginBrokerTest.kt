package com.abyss.tools.core.plugin

import com.abyss.tools.core.common.SemVer
import com.abyss.tools.plugin.api.DependSpec
import com.abyss.tools.plugin.api.PluginContract
import com.abyss.tools.plugin.api.ProvideSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PluginBroker 三件套（DependencyResolver / CapabilityBinder /
 * SessionGrantIssuer）—— ARC-102 M1 门禁单测。
 *
 * 覆盖（AC-001 / AC-002 / AC-003 / AC-005 的纯 JVM 面）：
 *  - 依赖解析：缺失 / 版本过低 / 环 / 归档 id / 伪造签名 / 拓扑序；
 *  - v1 宿主加载 minHost=2 → E_HOST_API_TOO_LOW fail-closed；v2 宿主
 *    加载 v1 插件（minHost=1）正常；
 *  - grant：过期 / 重放 / 跨会话复用三类全部拒绝；宿主重启代际失效。
 */
class PluginBrokerTest {

    // ── 夹具：以真实索引夹具为底 + 内联构造变体 ────────────────────────

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes().decodeToString()

    private fun fixtureIndex(): PluginIndex = PluginParsers.parseIndex(resource("plugins-index.json"))

    /** 可变测试索引（clone 夹具条目）。 */
    private fun testIndex(mutate: (MutableList<PluginIndexEntry>) -> Unit = {}): PluginIndex {
        val base = fixtureIndex()
        val entries = base.plugins.toMutableList()
        mutate(entries)
        return base.copy(plugins = entries)
    }

    private val hostAppVersion: SemVer = SemVer.parse("0.10.0")!!

    private fun resolverOf(hostApi: Int = PluginContract.HOST_API_VERSION) =
        DependencyResolver(hostApiVersion = hostApi, hostAppVersion = hostAppVersion)

    // ── DependencyResolver ─────────────────────────────────────────────

    @Test
    fun `拓扑序 - Linux 先于应用构建器（一并安装序）`() {
        val result = resolverOf().resolve("abyss-plugin-app-builder", fixtureIndex(), installed = emptyMap())
        assertTrue(result is DependencyResolver.Resolution.InstallPlan)
        val plan = result as DependencyResolver.Resolution.InstallPlan
        assertEquals(listOf("abyss-plugin-linux", "abyss-plugin-app-builder"), plan.installOrder.map { it.id })
        assertEquals(listOf("abyss-plugin-linux", "abyss-plugin-app-builder"), plan.missingDeps.map { it.id })
    }

    @Test
    fun `依赖已装且版本满足 - 计划不含 missing`() {
        val installed = mapOf("abyss-plugin-linux" to SemVer.parse("0.2.0")!!)
        val result = resolverOf().resolve("abyss-plugin-app-builder", fixtureIndex(), installed)
        val plan = result as DependencyResolver.Resolution.InstallPlan
        assertTrue(plan.missingDeps.isEmpty())
        assertEquals(SemVer.parse("0.2.0"), plan.depVersions["abyss-plugin-linux"])
    }

    @Test
    fun `依赖缺失（归档 id 不在索引）- E_DEP_MISSING 人话引导`() {
        // 构造：索引里没有 linux 条目（模拟归档移除后的旧索引 / 未聚合态）
        val index = testIndex { it.removeAll { e -> e.id == "abyss-plugin-linux" } }
        val result = resolverOf().resolve("abyss-plugin-app-builder", index, installed = emptyMap())
        val rejection = result as DependencyResolver.Resolution.Rejection
        assertEquals("E_DEP_MISSING", rejection.code)
        assertTrue(rejection.humanReason.contains("请先安装"))
    }

    @Test
    fun `依赖版本过低 - E_DEP_VERSION 引导升级`() {
        val installed = mapOf("abyss-plugin-linux" to SemVer.parse("0.1.0")!!)
        val result = resolverOf().resolve("abyss-plugin-app-builder", fixtureIndex(), installed)
        val rejection = result as DependencyResolver.Resolution.Rejection
        assertEquals("E_DEP_VERSION", rejection.code)
        assertTrue(rejection.humanReason.contains("升级"))
        assertTrue(rejection.humanReason.contains(">=0.2.0"))
    }

    @Test
    fun `循环依赖 - E_DEPENDENCY_CYCLE（运行时兜底）`() {
        // 构造：linux ↔ app-builder 互指（索引 CI 会拦；运行时仍需兜底）
        val index = testIndex { entries ->
            val linux = entries.first { it.id == "abyss-plugin-linux" }
            entries[entries.indexOf(linux)] = linux.copy(
                depends = listOf(
                    DependSpec(
                        pluginId = "abyss-plugin-app-builder",
                        capability = "linux-runtime-v1",
                        versionRange = ">=0.2.0",
                        reason = "测试环：linux 反向依赖构建器",
                    ),
                ),
            )
        }
        val result = resolverOf().resolve("abyss-plugin-app-builder", index, installed = emptyMap())
        val rejection = result as DependencyResolver.Resolution.Rejection
        assertEquals("E_DEPENDENCY_CYCLE", rejection.code)
        assertTrue(rejection.humanReason.contains("成环"))
    }

    @Test
    fun `capability 不匹配 - E_CAPABILITY_MISMATCH`() {
        val index = testIndex { entries ->
            val linux = entries.first { it.id == "abyss-plugin-linux" }
            entries[entries.indexOf(linux)] = linux.copy(provides = emptyList())
        }
        val result = resolverOf().resolve("abyss-plugin-app-builder", index, installed = emptyMap())
        val rejection = result as DependencyResolver.Resolution.Rejection
        assertEquals("E_CAPABILITY_MISMATCH", rejection.code)
    }

    @Test
    fun `v1 宿主加载 minHost=2 插件 - fail-closed 升级提示（AC-005）`() {
        val v1Host = resolverOf(hostApi = 1)
        val result = v1Host.resolve("abyss-plugin-app-builder", fixtureIndex(), installed = emptyMap())
        val rejection = result as DependencyResolver.Resolution.Rejection
        assertEquals("E_HOST_API_TOO_LOW", rejection.code)
        assertTrue(rejection.humanReason.contains("升级深渊工具"))
    }

    @Test
    fun `v2 宿主加载 v1 插件（病毒拦截 minHost=1）- 正常（AC-005）`() {
        val installed = emptyMap<String, SemVer>()
        val result = resolverOf().resolve("abyss-plugin-virus-guard", fixtureIndex(), installed)
        assertTrue(result is DependencyResolver.Resolution.InstallPlan)
        val plan = result as DependencyResolver.Resolution.InstallPlan
        assertEquals(listOf("abyss-plugin-virus-guard"), plan.installOrder.map { it.id })
    }

    @Test
    fun `非法版本区间 - fail-closed 不猜测`() {
        val index = testIndex { entries ->
            val builder = entries.first { it.id == "abyss-plugin-app-builder" }
            entries[entries.indexOf(builder)] = builder.copy(
                depends = builder.depends.map { it.copy(versionRange = "0.2.0") }, // 缺操作符 → 非法
            )
        }
        val result = resolverOf().resolve("abyss-plugin-app-builder", index, installed = emptyMap())
        val rejection = result as DependencyResolver.Resolution.Rejection
        assertEquals("E_CAPABILITY_MISMATCH", rejection.code)
        assertTrue(rejection.humanReason.contains("区间非法"))
    }

    @Test
    fun `minApp 高于宿主版本 - 升级引导`() {
        val oldHost = DependencyResolver(
            hostApiVersion = PluginContract.HOST_API_VERSION,
            hostAppVersion = SemVer.parse("0.9.5")!!,
        )
        val result = oldHost.resolve("abyss-plugin-app-builder", fixtureIndex(), installed = emptyMap())
        val rejection = result as DependencyResolver.Resolution.Rejection
        assertEquals("E_HOST_API_TOO_LOW", rejection.code)
        assertTrue(rejection.humanReason.contains("0.10.0"))
    }

    // ── SessionGrantIssuer ────────────────────────────────────────────

    private fun fixedIssuer(now: Long) = SessionGrantIssuer(clock = { now }, hostIdentity = "test-host")

    @Test
    fun `grant TTL 上限 300s 且签发即有效`() {
        val issuer = fixedIssuer(now = 1_000L)
        val grant = issuer.issue("com.abyss.plugin.appbuilder", PUBLISHER_SHA, "linux-runtime-v1", "sess-1")
        assertEquals(1_000L, grant.issuedAtEpochMs)
        assertEquals(1_000L + PluginContract.GRANT_TTL_MAX_SEC * 1000L, grant.expiryEpochMs)
        assertTrue(issuer.verify(grant, "sess-1") is SessionGrantIssuer.GrantCheck.Valid)
    }

    @Test
    fun `grant 过期 - E_GRANT_EXPIRED（AC-003 ①）`() {
        var now = 1_000L
        val issuer = SessionGrantIssuer(clock = { now }, hostIdentity = "test-host")
        val grant = issuer.issue("com.abyss.plugin.appbuilder", PUBLISHER_SHA, "linux-runtime-v1", "sess-1")
        now += PluginContract.GRANT_TTL_MAX_SEC * 1000L // 恰好到达 expiry
        val check = issuer.verify(grant, "sess-1")
        val invalid = check as SessionGrantIssuer.GrantCheck.Invalid
        assertEquals("E_GRANT_EXPIRED", invalid.code)
    }

    @Test
    fun `grant 重放 - E_GRANT_REPLAY（AC-003 ②）`() {
        val issuer = fixedIssuer(now = 1_000L)
        val grant = issuer.issue("com.abyss.plugin.appbuilder", PUBLISHER_SHA, "linux-runtime-v1", "sess-1")
        assertTrue(issuer.verify(grant, "sess-1") is SessionGrantIssuer.GrantCheck.Valid)
        val replay = issuer.verify(grant, "sess-1")
        val invalid = replay as SessionGrantIssuer.GrantCheck.Invalid
        assertEquals("E_GRANT_REPLAY", invalid.code)
        assertTrue(invalid.reason.contains("重放"))
    }

    @Test
    fun `grant 跨会话复用 - 拒绝（AC-003 ③）`() {
        val issuer = fixedIssuer(now = 1_000L)
        val grant = issuer.issue("com.abyss.plugin.appbuilder", PUBLISHER_SHA, "linux-runtime-v1", "sess-1")
        val check = issuer.verify(grant, "sess-OTHER")
        val invalid = check as SessionGrantIssuer.GrantCheck.Invalid
        assertEquals("E_GRANT_REPLAY", invalid.code)
        assertTrue(invalid.reason.contains("跨会话复用"))
    }

    @Test
    fun `宿主重启代际 - 旧 grant 全部失效（ARC-102 FR-007）`() {
        val issuer = fixedIssuer(now = 1_000L)
        val grant = issuer.issue("com.abyss.plugin.appbuilder", PUBLISHER_SHA, "linux-runtime-v1", "sess-1")
        assertEquals(1, issuer.invalidateAll())
        val check = issuer.verify(grant, "sess-1")
        val invalid = check as SessionGrantIssuer.GrantCheck.Invalid
        assertEquals("E_GRANT_INVALID", invalid.code)
        assertTrue(invalid.reason.contains("宿主"))
    }

    @Test
    fun `会话撤销 - 令牌立即失效（DeathRecipient 回收路径）`() {
        val issuer = fixedIssuer(now = 1_000L)
        val grant = issuer.issue("com.abyss.plugin.appbuilder", PUBLISHER_SHA, "linux-runtime-v1", "sess-1")
        assertEquals(1, issuer.revoke("sess-1"))
        val check = issuer.verify(grant, "sess-1")
        assertEquals("E_GRANT_INVALID", (check as SessionGrantIssuer.GrantCheck.Invalid).code)
    }

    // ── CapabilityBinder（四重校验）───────────────────────────────────

    /** 伪造调用方（未签名第三方 APK 冒充构建器 —— AC-002）。 */
    private fun binderOf(
        whitelisted: Boolean = true,
        fingerprint: String? = PUBLISHER_SHA,
    ): CapabilityBinder {
        val trust = object : CapabilityBinder.CallerTrust {
            override fun isConsumerPlugin(packageName: String) = whitelisted && packageName == "com.abyss.plugin.appbuilder"
            override fun publisherFingerprint(packageName: String) = fingerprint
            override fun pluginIdOf(packageName: String) =
                if (whitelisted && packageName == "com.abyss.plugin.appbuilder") "abyss-plugin-app-builder" else null
        }
        return CapabilityBinder(
            resolver = resolverOf(),
            grantIssuer = fixedIssuer(now = 1_000L),
            trust = trust,
        )
    }

    @Test
    fun `四重校验通过 - 签发受限代理令牌（AC-001 正向路径）`() {
        val installed = mapOf("abyss-plugin-linux" to SemVer.parse("0.2.0")!!)
        val binding = binderOf().getCapability(
            caller = CapabilityBinder.CallerIdentity("com.abyss.plugin.appbuilder", PUBLISHER_SHA.lowercase()),
            capability = "linux-runtime-v1",
            index = fixtureIndex(),
            installed = installed,
            sessionId = "sess-1",
        )
        val authorized = binding as CapabilityBinder.Binding.Authorized
        assertEquals("linux-runtime-v1", authorized.capability)
        assertEquals("com.abyss.plugin.appbuilder", authorized.grant.callerPackage)
        assertEquals("sess-1", authorized.grant.sessionId)
    }

    @Test
    fun `未签名第三方冒充 - E_SIGNATURE_MISMATCH 且宿主无状态变化（AC-002）`() {
        val installed = mapOf("abyss-plugin-linux" to SemVer.parse("0.2.0")!!)
        val binding = binderOf().getCapability(
            caller = CapabilityBinder.CallerIdentity("com.abyss.plugin.appbuilder", "aa" + "bb".repeat(31)),
            capability = "linux-runtime-v1",
            index = fixtureIndex(),
            installed = installed,
            sessionId = "sess-1",
        )
        val denied = binding as CapabilityBinder.Binding.Denied
        assertEquals("E_SIGNATURE_MISMATCH", denied.code)
        assertTrue(denied.humanReason.contains("不可信"))
    }

    @Test
    fun `非白名单包直连 - E_DIRECT_BIND_DENIED（插件间永不直连）`() {
        val binding = binderOf().getCapability(
            caller = CapabilityBinder.CallerIdentity("com.evil.thirdparty", PUBLISHER_SHA),
            capability = "linux-runtime-v1",
            index = fixtureIndex(),
            installed = emptyMap(),
            sessionId = "sess-1",
        )
        val denied = binding as CapabilityBinder.Binding.Denied
        assertEquals("E_DIRECT_BIND_DENIED", denied.code)
    }

    @Test
    fun `依赖插件未安装 - E_DEP_MISSING 引导一并安装`() {
        val binding = binderOf().getCapability(
            caller = CapabilityBinder.CallerIdentity("com.abyss.plugin.appbuilder", PUBLISHER_SHA),
            capability = "linux-runtime-v1",
            index = fixtureIndex(),
            installed = emptyMap(),
            sessionId = "sess-1",
        )
        val denied = binding as CapabilityBinder.Binding.Denied
        assertEquals("E_DEP_MISSING", denied.code)
        assertTrue(denied.humanReason.contains("请先安装"))
    }

    @Test
    fun `未注册能力 - E_CAPABILITY_MISMATCH`() {
        val binding = binderOf().getCapability(
            caller = CapabilityBinder.CallerIdentity("com.abyss.plugin.appbuilder", PUBLISHER_SHA),
            capability = "linux-runtime-v2",
            index = fixtureIndex(),
            installed = emptyMap(),
            sessionId = "sess-1",
        )
        val denied = binding as CapabilityBinder.Binding.Denied
        assertEquals("E_CAPABILITY_MISMATCH", denied.code)
    }

    companion object {
        /** 主仓 PublisherSignatures.PINNED_SHA256（32 字节十六进制形态）。 */
        private const val PUBLISHER_SHA: String =
            "760378214488D6207978BA1E37E6DC82ABC2DE72C9F6C67D9B4495158A99B769"
    }
}
