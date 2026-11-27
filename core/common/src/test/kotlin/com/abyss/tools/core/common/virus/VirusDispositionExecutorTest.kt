package com.abyss.tools.core.common.virus

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 处置执行器 + 审计日志 + 误报申诉单测（N-VIR-006 AC-001）。
 */
class VirusDispositionExecutorTest {

    private val shaClean = "1".repeat(64)
    private val shaMalware = "2".repeat(64)
    private val shaFp = "3".repeat(64)

    private lateinit var allowlist: UserAllowlistChecker
    private lateinit var blacklist: HashBlacklistChecker
    private lateinit var pipeline: VerdictPipeline
    private lateinit var executor: VirusDispositionExecutor

    @Before fun setUp() {
        allowlist = UserAllowlistChecker()
        blacklist = HashBlacklistChecker()
        pipeline = VerdictPipeline(allowlist, blacklist, LocalRuleChecker(), CloudUnavailableChecker())
        executor = VirusDispositionExecutor(pipeline, allowlist, VirusDispositionPolicy())
        VirusAuditLog.clear()
    }

    private fun subject(sha: String, pkg: String = "com.demo.app", signer: String = "a".repeat(64)) =
        ScanSubject("/tmp/x.apk", sha, pkg, signer, fileSizeBytes = 5000)

    @Test fun `default policy blocks MALICIOUS without auto uninstall — FR-001`() {
        blacklist.add(shaMalware, "Trojan")
        val (report, disp) = executor.evaluate(subject(shaMalware))
        assertEquals(Verdict.MALICIOUS, report.verdict)
        assertTrue(disp is Disposition.BlockInstall)
        assertFalse(disp is Disposition.Uninstall)
    }

    @Test fun `false positive adds to allowlist and subsequent scan passes — AC-001`() {
        blacklist.add(shaFp, "Misidentified")
        val subj = subject(shaFp)

        // 第一次：被拦截
        val (r1, d1) = executor.evaluate(subj)
        assertEquals(Verdict.MALICIOUS, r1.verdict)
        assertTrue(d1 is Disposition.BlockInstall)

        // 用户申诉误报
        executor.reportFalsePositive(subj, "这是我自己编译的 App", canReverse = true)
        assertTrue(allowlist.contains(shaFp))

        // 再次扫描：应被白名单放行
        val (r2, d2) = executor.evaluate(subj)
        assertEquals(Verdict.CLEAN, r2.verdict)
        assertTrue(d2 is Disposition.Pass)
    }

    @Test fun `audit log records the false positive event`() {
        val subj = subject(shaFp)
        executor.reportFalsePositive(subj, "误报")
        val recent = VirusAuditLog.recent(10)
        assertTrue(recent.any { it.action == VirusAuditLog.Action.FALSE_POSITIVE_REPORT })
    }

    @Test fun `audit log export with masking`() {
        blacklist.add(shaMalware, "Trj")
        executor.evaluate(subject(shaMalware))
        val tmp = File(System.getProperty("java.io.tmpdir"), "virus-audit-${System.nanoTime()}.log")
        tmp.deleteOnExit()
        assertNotNull(VirusAuditLog.exportTo(tmp, maskSha = true))
        val content = tmp.readText()
        // 脱敏后不包含完整 sha256
        assertFalse(content.contains(shaMalware))
        assertTrue(content.contains("..."))
        assertTrue(content.contains("病毒拦截审计日志"))
        assertTrue(content.contains("未随云查询外传"))
    }

    @Test fun `suspicious subject prompts user without blocking`() {
        // 无签名 + 空包名 apk → SUSPICIOUS
        val subj = ScanSubject("/data/x.apk", shaClean, "", "", fileSizeBytes = 5000)
        val (r, d) = executor.evaluate(subj)
        assertEquals(Verdict.SUSPICIOUS, r.verdict)
        assertTrue(d is Disposition.PromptUser)
    }

    @Test fun `user decision is audited`() {
        val subj = subject(shaClean)
        val (report, _) = executor.evaluate(subj)
        executor.onUserDecision(subj, report, allowed = true, reason = "trust developer")
        val events = VirusAuditLog.recent(20)
        assertTrue(events.any { it.action == VirusAuditLog.Action.USER_OVERRIDE && it.result.startsWith("user-allowed") })
    }

    @Test fun `removeFromAllowlist clears entry and logs`() {
        val subj = subject(shaFp)
        executor.reportFalsePositive(subj)
        assertTrue(allowlist.contains(shaFp))
        executor.removeFromAllowlist(subj)
        assertFalse(allowlist.contains(shaFp))
        assertTrue(VirusAuditLog.recent().any { it.action == VirusAuditLog.Action.ALLOWLIST_REMOVE })
    }

    @Test fun `audit export includes rotated history file`() {
        // NB-3b 回归：导出必须包含轮转件 .log.1（旧实现静默丢弃较早历史）
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "virus-audit-rot-${System.nanoTime()}")
        dir.mkdirs()
        dir.resolve("abyss-virus-audit.log").writeText("L1 current-line")
        dir.resolve("abyss-virus-audit.log.1").writeText("L0 rotated-line")
        VirusAuditLog.attachLogDir(dir)
        val out = java.io.File(dir, "export.txt")
        assertNotNull(VirusAuditLog.exportTo(out, maskSha = false))
        val text = out.readText()
        assertTrue(text.contains("current-line"))
        assertTrue("导出必须含轮转历史", text.contains("rotated-line"))
        assertTrue(text.contains("轮转 .1"))
        VirusAuditLog.clear()
    }
}
