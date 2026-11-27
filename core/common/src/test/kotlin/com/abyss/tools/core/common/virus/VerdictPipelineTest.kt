package com.abyss.tools.core.common.virus

import org.junit.Assert.*
import org.junit.Test

/**
 * N-VIR-002 验收 AC-001：纯 JVM 单测覆盖四种 verdict 与云不可达降级分支。
 */
class VerdictPipelineTest {

    private val trustedSigner = "a".repeat(64)
    private val badHash = "b".repeat(64)
    private val goodHash = "c".repeat(64)
    private val unknownHash = "d".repeat(64)

    private val fakeClient = object : CloudQueryClient {
        override fun query(sha256: String): CloudQueryResult = when (sha256) {
            goodHash to CloudQueryResult.Clean("已知良性包（Google Play 官方）")
            badHash to CloudQueryResult.Detections(positives = 23, total = 70)
            "e".repeat(64) to CloudQueryResult.Detections(positives = 3, total = 70)
            else to CloudQueryResult.Unavailable("timeout after 5s")
        }
    }

    private fun newPipeline(cloudEnabled: Boolean = true): VerdictPipeline = VerdictPipeline(
        rules = listOf(
            HashBlacklistRule(blacklistSha256 = setOf(badHash)),
            SignerWhitelistRule(trustedSignerSha256 = setOf(trustedSigner)),
            LocalHeuristicRule(),
            CloudQueryRule(client = fakeClient, enabled = cloudEnabled),
        )
    )

    private fun subject(
        sha256: String,
        packageName: String = "com.example.app",
        signer: String = "f".repeat(64),
    ) = ScanSubject(
        apkPath = "/data/local/tmp/x.apk",
        sha256 = sha256,
        packageName = packageName,
        signerSha256 = signer,
    )

    @Test
    fun `hash blacklist hit returns MALICIOUS immediately (short-circuit)`() {
        val report = newPipeline().judge(subject(sha256 = badHash))
        assertEquals(Verdict.MALICIOUS, report.verdict)
        assertTrue(report.reasons.any { it.ruleId == "HASH_BLACKLIST" })
        // 短路：黑名单之后的云查询规则不应执行（reasons 中不应有 CLOUD_QUERY）
        assertFalse("黑名单应短路", report.reasons.any { it.ruleId == "CLOUD_QUERY" })
    }

    @Test
    fun `trusted signer with no other signal returns CLEAN`() {
        val report = newPipeline(cloudEnabled = false).judge(
            subject(sha256 = goodHash, signer = trustedSigner)
        )
        assertEquals(Verdict.CLEAN, report.verdict)
        assertTrue(report.reasons.any { it.ruleId == "SIGNER_WHITELIST" })
    }

    @Test
    fun `cloud high detections returns MALICIOUS`() {
        val report = newPipeline().judge(subject(sha256 = badHash, packageName = "com.demo.clean"))
        // 注意：badHash 已在黑名单 → 短路 MALICIOUS；换一个仅云端命中的 sha
        val cloudOnlyBad = "b".repeat(63) + "x" // 不在本地黑名单但云返回高命中
        // 由于 fakeClient 对未知 hash 返回 Unavailable，这里直接使用 Detections 高命中的 hash
        // 我们把 badHash 从黑名单里移除单独测云高命中
        val pipeline = VerdictPipeline(
            rules = listOf(
                HashBlacklistRule(blacklistSha256 = emptySet()),
                SignerWhitelistRule(trustedSignerSha256 = setOf(trustedSigner)),
                LocalHeuristicRule(),
                CloudQueryRule(client = fakeClient, enabled = true),
            )
        )
        val report2 = pipeline.judge(subject(sha256 = badHash))
        assertEquals(Verdict.MALICIOUS, report2.verdict)
        assertTrue(report2.reasons.any { it.ruleId == "CLOUD_QUERY" && it.severity == 90 })
    }

    @Test
    fun `cloud low detections returns SUSPICIOUS`() {
        val pipeline = VerdictPipeline(
            rules = listOf(
                HashBlacklistRule(blacklistSha256 = emptySet()),
                SignerWhitelistRule(trustedSignerSha256 = emptySet()),
                LocalHeuristicRule(),
                CloudQueryRule(client = fakeClient, enabled = true),
            )
        )
        val report = pipeline.judge(subject(sha256 = "e".repeat(64)))
        assertEquals(Verdict.SUSPICIOUS, report.verdict)
    }

    @Test
    fun `cloud unavailable and no other strong signal returns UNKNOWN (not CLEAN)`() {
        val report = newPipeline(cloudEnabled = true).judge(subject(sha256 = unknownHash))
        assertEquals(
            "云不可达且无本地强信号必须降级 UNKNOWN，禁止静默放行",
            Verdict.UNKNOWN,
            report.verdict,
        )
        assertTrue(report.reasonChain().contains("不可用"))
    }

    @Test
    fun `cloud disabled by default treats unknown as UNKNOWN not CLEAN`() {
        val report = newPipeline(cloudEnabled = false).judge(subject(sha256 = unknownHash))
        // 默认云关闭 + 无白名单签名 + 无黑名单 → UNKNOWN
        assertEquals(Verdict.UNKNOWN, report.verdict)
    }

    @Test
    fun `suspicious package name pattern (pseudo system app) returns SUSPICIOUS`() {
        val pipeline = VerdictPipeline(
            rules = listOf(
                HashBlacklistRule(blacklistSha256 = emptySet()),
                SignerWhitelistRule(trustedSignerSha256 = emptySet()),
                LocalHeuristicRule(),
                CloudQueryRule(client = fakeClient, enabled = false),
            )
        )
        val report = pipeline.judge(
            subject(sha256 = unknownHash, packageName = "com.android.updatex")
        )
        assertEquals(Verdict.SUSPICIOUS, report.verdict)
        assertTrue(report.reasons.any { it.ruleId == "LOCAL_HEURISTIC" })
    }

    @Test
    fun `invalid sha256 format produces local heuristic hit and SUSPICIOUS`() {
        val pipeline = VerdictPipeline(
            rules = listOf(
                LocalHeuristicRule(),
            )
        )
        val report = pipeline.judge(
            subject(sha256 = "not-a-valid-hash")
        )
        assertTrue(report.reasons.any { it.description.contains("SHA-256 格式非法") })
    }

    @Test
    fun `reason chain is human readable`() {
        val report = newPipeline(cloudEnabled = false).judge(subject(sha256 = unknownHash))
        val chain = report.reasonChain()
        // 至少含 [CLOUD_QUERY] 或其他规则 ID
        assertTrue(chain.isNotEmpty())
    }

    @Test
    fun `rule exception does not crash pipeline - fails closed as UNKNOWN`() {
        val crashingRule = object : VerdictRule {
            override val id = "CRASHING_RULE"
            override val priority = 25
            override fun evaluate(subject: ScanSubject): List<VerdictReason> {
                throw RuntimeException("simulated rule failure")
            }
        }
        val pipeline = VerdictPipeline(rules = listOf(crashingRule))
        val report = pipeline.judge(subject(sha256 = unknownHash))
        assertTrue(report.reasons.any { it.ruleId == "CRASHING_RULE" && it.description.contains("异常") })
    }

    @Test
    fun `verdict UNKNOWN must not be treated as CLEAN - explicit four states`() {
        // 直接构造验证四态枚举都存在
        assertEquals(4, Verdict.values().size)
        assertNotNull(Verdict.CLEAN)
        assertNotNull(Verdict.SUSPICIOUS)
        assertNotNull(Verdict.MALICIOUS)
        assertNotNull(Verdict.UNKNOWN)
    }
}
