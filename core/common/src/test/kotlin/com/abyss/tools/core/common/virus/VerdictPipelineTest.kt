package com.abyss.tools.core.common.virus

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * VerdictPipeline 单测（N-VIR-002 AC-001）。
 *
 * 覆盖四种 verdict 分支 + 云不可达降级分支 + 短路优先级。
 */
class VerdictPipelineTest {

    private val shaClean = "a".repeat(64)
    private val shaMalware = "b".repeat(64)
    private val shaSigned = "c".repeat(64)
    private val shaAllow = "d".repeat(64)
    private val shaUnsignedApk = "e".repeat(64)

    private lateinit var allowlist: UserAllowlistChecker
    private lateinit var blacklist: HashBlacklistChecker
    private lateinit var localRules: LocalRuleChecker
    private lateinit var cloud: CloudUnavailableChecker
    private lateinit var pipeline: VerdictPipeline

    @Before fun setUp() {
        allowlist = UserAllowlistChecker()
        blacklist = HashBlacklistChecker()
        localRules = LocalRuleChecker()
        cloud = CloudUnavailableChecker()
        pipeline = VerdictPipeline(allowlist, blacklist, localRules, cloud)
    }

    private fun subject(sha: String, pkg: String = "com.example.app", signer: String = "f".repeat(64),
                        path: String = "/data/app/x.apk", size: Long = 1024) =
        ScanSubject(apkPath = path, sha256 = sha, packageName = pkg, signerSha256 = signer, fileSizeBytes = size)

    @Test fun `clean subject returns CLEAN when allowlisted`() {
        allowlist.add(shaAllow, "test")
        val r = pipeline.scan(subject(shaAllow))
        assertEquals(Verdict.CLEAN, r.verdict)
        assertTrue(r.reasons.any { it.source == "allowlist" })
    }

    @Test fun `blacklist hit short-circuits to MALICIOUS`() {
        blacklist.add(shaMalware, "Test Trojan")
        val r = pipeline.scan(subject(shaMalware))
        assertEquals(Verdict.MALICIOUS, r.verdict)
        assertTrue(r.isBlacklistHit())
        // 短路：后续 checker 不执行，不会产生 cloud 理由
        assertFalse(r.reasons.any { it.source.startsWith("cloud:") })
    }

    @Test fun `unsigned apk with no name triggers local rule SUSPICIOUS`() {
        // apk 路径 + 空签名 + 空包名 → apk-unsigned 规则命中
        val r = pipeline.scan(subject(shaUnsignedApk, pkg = "", signer = "", path = "/tmp/x.apk"))
        assertEquals(Verdict.SUSPICIOUS, r.verdict)
        assertTrue(r.isLocalRuleHit())
    }

    @Test fun `no signals returns UNKNOWN not CLEAN — FR-001`() {
        // 一个完全干净（不在黑名单、有签名有包名、不触发本地规则、云不可用）的对象，
        // 因 cloud 不可达 + 没有白名单命中 → 应为 UNKNOWN（严格遵循 FR-001 不静默放行）。
        // 注：本地规则 "apk-unsigned" 需要 signer 为空才命中；这里给了 signer → 不命中。
        val r = pipeline.scan(subject(shaClean))
        // 所有 checker 执行后，cloud 返回 UNKNOWN 理由 → 最终 UNKNOWN
        assertEquals(Verdict.UNKNOWN, r.verdict)
        assertTrue(r.cloudFailed())
    }

    @Test fun `cloud unavailable produces UNKNOWN reason — FR-005`() {
        cloud.enabled = false
        cloud.networkAvailable = false
        val r = pipeline.scan(subject(shaClean))
        assertTrue(r.reasons.any { it.source == "cloud:unavailable" })
        assertTrue(r.reasons.any { it.message.contains("云查毒") })
    }

    @Test fun `allowlist overrides blacklist (priority 5 before 10)`() {
        allowlist.add(shaMalware, "用户误报")
        blacklist.add(shaMalware, "Test Trojan")
        val r = pipeline.scan(subject(shaMalware))
        assertEquals(Verdict.CLEAN, r.verdict)
    }

    @Test fun `scan rejects invalid sha256`() {
        assertThrows(IllegalArgumentException::class.java) {
            ScanSubject("/x.apk", "not-a-hash", "com.x", "f".repeat(64))
        }
    }

    @Test fun `pipeline is resilient to checker exceptions`() {
        val bad = object : VerdictChecker {
            override val priority = 15
            override val name = "throwing"
            override fun check(subject: ScanSubject): List<VerdictReason> = throw RuntimeException("boom")
        }
        val p = VerdictPipeline(listOf(allowlist, blacklist, bad, localRules, cloud))
        val r = p.scan(subject(shaClean))
        // 异常 checker 应被捕获并产生一条 UNKNOWN 理由
        assertTrue(r.reasons.any { it.source == "checker-error:throwing" })
    }

    /**
     * 回归用例（严重度聚合遮蔽 bug）：Verdict 枚举实现了 Comparable，其成员
     * compareTo（按声明序）优先级高于任何扩展 operator —— 曾导致 UNKNOWN(ordinal 3)
     * 覆盖 SUSPICIOUS(ordinal 1)。聚合后任何数量的 UNKNOWN 理由都不得压过
     * SUSPICIOUS / MALICIOUS 结论。
     */
    @Test fun `UNKNOWN reasons never downgrade SUSPICIOUS or MALICIOUS aggregation`() {
        // SUSPICIOUS（本地规则）+ UNKNOWN（云不可达）→ 仍为 SUSPICIOUS
        val suspicious = pipeline.scan(subject(shaUnsignedApk, pkg = "", signer = "", path = "/tmp/x.apk"))
        assertEquals(Verdict.SUSPICIOUS, suspicious.verdict)
        // 悬浮聚合：topReason 必须取 severityWeight 最大者（SUSPICIOUS 规则），而非声明序
        val top = suspicious.topReason()
        assertNotNull(top)
        assertEquals(Verdict.SUSPICIOUS, top!!.severity)
        assertTrue(top.source.startsWith("local-rule:"))
    }

    /** 回归用例：severityWeight 全序 —— CLEAN < UNKNOWN < SUSPICIOUS < MALICIOUS。 */
    @Test fun `severityWeight ordering is CLEAN lt UNKNOWN lt SUSPICIOUS lt MALICIOUS`() {
        val w = listOf(Verdict.CLEAN, Verdict.UNKNOWN, Verdict.SUSPICIOUS, Verdict.MALICIOUS)
        assertEquals(listOf(0, 1, 2, 3), w.map { it.severityWeight })
        assertEquals(w, w.sortedBy { it.severityWeight })
    }
}
