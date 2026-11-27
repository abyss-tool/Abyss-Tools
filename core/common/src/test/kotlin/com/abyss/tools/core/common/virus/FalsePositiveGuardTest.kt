package com.abyss.tools.core.common.virus

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * N-VIR-006 验收 AC-001：模拟误报后可通过白名单恢复且日志留痕完整。
 */
class FalsePositiveGuardTest {

    private val goodHash = "g".repeat(64)
    private val badHash = "h".repeat(64)
    private val suspHash = "i".repeat(64)
    private val signer = "s".repeat(64)

    private fun subject(sha256: String, pkg: String = "com.test.app") = ScanSubject(
        apkPath = "/data/local/tmp/x.apk",
        sha256 = sha256,
        packageName = pkg,
        signerSha256 = signer,
    )

    @Test
    fun `default policy NEVER auto-uninstalls MALICIOUS (FR-001)`() {
        val wl = WhitelistStore()
        val audit = VirusAuditLog()
        val guard = FalsePositiveGuard(wl, audit)

        val report = VerdictReport(
            verdict = Verdict.MALICIOUS,
            reasons = listOf(VerdictReason("TEST", "模拟恶意样本", severity = 90)),
        )
        val (v, action) = guard.applyPolicy(subject(badHash), report)
        assertEquals(Verdict.MALICIOUS, v)
        assertEquals(
            "默认策略下 MALICIOUS 必须返回 NOTIFY_ONLY，禁止自动卸载/阻断",
            DispositionAction.NOTIFY_ONLY,
            action,
        )
    }

    @Test
    fun `default policy treats SUSPICIOUS as notify-only (no auto block)`() {
        val wl = WhitelistStore()
        val audit = VirusAuditLog()
        val guard = FalsePositiveGuard(wl, audit, autoPolicy = AutoPolicy.NOTIFY_ONLY)

        val report = VerdictReport(
            verdict = Verdict.SUSPICIOUS,
            reasons = listOf(VerdictReason("TEST", "可疑样本", severity = 40)),
        )
        val (v, action) = guard.applyPolicy(subject(suspHash), report)
        assertEquals(Verdict.SUSPICIOUS, v)
        assertEquals(DispositionAction.NOTIFY_ONLY, action)
    }

    @Test
    fun `AUTO_BLOCK_SUSPICIOUS allows auto-block for SUSPICIOUS but NOT for MALICIOUS`() {
        val wl = WhitelistStore()
        val audit = VirusAuditLog()
        val guard = FalsePositiveGuard(wl, audit, autoPolicy = AutoPolicy.AUTO_BLOCK_SUSPICIOUS)

        val suspReport = VerdictReport(Verdict.SUSPICIOUS, listOf(VerdictReason("T", "susp", 40)))
        val (v1, a1) = guard.applyPolicy(subject(suspHash, "com.susp"), suspReport)
        assertEquals(Verdict.SUSPICIOUS, v1)
        assertEquals(DispositionAction.BLOCK, a1)

        val malReport = VerdictReport(Verdict.MALICIOUS, listOf(VerdictReason("T", "mal", 90)))
        val (v2, a2) = guard.applyPolicy(subject(badHash, "com.mal"), malReport)
        assertEquals(Verdict.MALICIOUS, v2)
        assertEquals("即使开启自动阻断策略，MALICIOUS 仍必须 NOTIFY_ONLY",
            DispositionAction.NOTIFY_ONLY, a2)
    }

    @Test
    fun `whitelisted sample is immediately CLEAN and audit logged (false positive recovery AC-001)`() {
        val tmp = File.createTempFile("wl", ".txt").also { it.delete() }
        try {
            val wl = WhitelistStore(tmp)
            val audit = VirusAuditLog()
            val guard = FalsePositiveGuard(wl, audit)

            val subj = subject(badHash, "com.falsepositive")
            val report = VerdictReport(
                verdict = Verdict.MALICIOUS,
                reasons = listOf(VerdictReason("CLOUD", "23/70 命中", severity = 90)),
            )

            // 用户标记为误报 → 加入白名单
            guard.userDecision(
                subj, report, DispositionAction.WHITELIST,
                note = "这是我常用的 Xposed 模块，误报",
            )
            assertTrue("白名单应包含该样本", wl.isWhitelisted(badHash, "com.falsepositive"))

            // 再判一次 → 应直接 CLEAN
            val (v, _) = guard.applyPolicy(subj, report)
            assertEquals(Verdict.CLEAN, v)

            // 审计记录留痕：应有 WHITELIST 一条 + CLEAN 一条
            val recent = audit.recent(50)
            assertTrue("应有 WHITELIST 审计记录",
                recent.any { it.action == DispositionAction.WHITELIST && it.actor == DispositionActor.USER })
            assertTrue("误报备注应留痕",
                recent.any { it.note?.contains("误报") == true })
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `audit log records all required fields (FR-002)`() {
        val audit = VirusAuditLog()
        val subj = subject(badHash, "com.ransom")
        audit.record(
            subject = subj,
            verdict = Verdict.MALICIOUS,
            reasons = listOf(VerdictReason("HASH", "黑名单命中", 100)),
            action = DispositionAction.NOTIFY_ONLY,
            actor = DispositionActor.AUTOMATIC,
            result = DispositionResult.PENDING,
        )
        val e = audit.recent(1).first()
        assertEquals("com.ransom", e.packageName)
        assertEquals(Verdict.MALICIOUS, e.verdict)
        assertEquals(DispositionAction.NOTIFY_ONLY, e.action)
        assertEquals(DispositionActor.AUTOMATIC, e.actor)
        assertEquals(DispositionResult.PENDING, e.result)
        assertTrue("reasonChain 应包含命中规则", e.reasonChain.contains("HASH"))
        assertTrue("toLogLine 输出可读", e.toLogLine().contains("MALICIOUS"))
    }

    @Test
    fun `audit ring buffer trims to max size`() {
        val audit = VirusAuditLog(maxMemoryEntries = 5)
        repeat(20) { i ->
            audit.record(
                subject = subject(badHash, "com.x$i"),
                verdict = Verdict.UNKNOWN,
                reasons = emptyList(),
                action = DispositionAction.NOTIFY_ONLY,
                actor = DispositionActor.AUTOMATIC,
                result = DispositionResult.PENDING,
            )
        }
        assertTrue("环形缓冲应裁剪", audit.count() <= 5)
    }

    @Test
    fun `whitelist is persisted to disk and reloaded`() {
        val tmp = File.createTempFile("wl", ".txt").also { it.delete() }
        try {
            val wl1 = WhitelistStore(tmp)
            wl1.add(goodHash, "com.good1")
            wl1.add(goodHash, "com.good2")
            // 用新实例重载
            val wl2 = WhitelistStore(tmp)
            assertTrue(wl2.isWhitelisted(goodHash, "com.good1"))
            assertTrue(wl2.isWhitelisted(goodHash, "com.good2"))
            assertFalse(wl2.isWhitelisted(goodHash, "com.other"))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `whitelist keyed by sha AND package (prevents cross-package bypass)`() {
        val wl = WhitelistStore()
        wl.add(goodHash, "com.good")
        assertTrue(wl.isWhitelisted(goodHash, "com.good"))
        assertFalse("同 sha 不同包名不应放行", wl.isWhitelisted(goodHash, "com.evil"))
        assertFalse("不同 sha 同包名不应放行", wl.isWhitelisted(badHash, "com.good"))
    }

    @Test
    fun `CLEAN verdict is automatically skipped with audit`() {
        val wl = WhitelistStore()
        val audit = VirusAuditLog()
        val guard = FalsePositiveGuard(wl, audit)
        val report = VerdictReport(Verdict.CLEAN,
            listOf(VerdictReason("SIGN", "可信发布者", -50)))
        val (v, action) = guard.applyPolicy(subject(goodHash, "com.clean"), report)
        assertEquals(Verdict.CLEAN, v)
        assertEquals(DispositionAction.NOTIFY_ONLY, action)
        assertTrue(audit.recent(1).any { it.verdict == Verdict.CLEAN })
    }
}
