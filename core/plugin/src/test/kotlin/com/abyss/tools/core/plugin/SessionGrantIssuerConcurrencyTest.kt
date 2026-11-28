package com.abyss.tools.core.plugin

import com.abyss.tools.plugin.api.PluginContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * SessionGrantIssuer 并发与清扫行为（NB-25，深挖第三轮）。
 *
 * 旧实现三处穿透的回归锚：
 *  - 并发 verify 同一 grant（读-判-写竞态）→ 只允许放行一次；
 *  - 二次 revoke 不再跳过 nonce 收缩（孤儿条目）；
 *  - 长时间滚动签发 / 校验后 usedNonces / activeGrants 按 TTL 清扫（无界增长被抑制）。
 */
class SessionGrantIssuerConcurrencyTest {

    private val publisherSha = "760378214488D6207978BA1E37E6DC82ABC2DE72C9F6C67D9B4495158A99B769"

    @Test
    fun `并发 verify 同一 grant - 恰好一次 Valid 其余全部 E_GRANT_REPLAY`() {
        val issuer = SessionGrantIssuer(clock = { 1_000L }, hostIdentity = "concurrent-host")
        val grant = issuer.issue("com.abyss.plugin.appbuilder", publisherSha, "linux-runtime-v1", "sess-race")
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val validCount = AtomicInteger(0)
        val replayCount = AtomicInteger(0)
        try {
            repeat(threads) {
                pool.submit {
                    ready.countDown()
                    go.await()
                    when (issuer.verify(grant, "sess-race")) {
                        is SessionGrantIssuer.GrantCheck.Valid -> validCount.incrementAndGet()
                        is SessionGrantIssuer.GrantCheck.Invalid -> replayCount.incrementAndGet()
                    }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            go.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, validCount.get())
        assertEquals(threads - 1, replayCount.get())
        assertEquals(1, issuer.usedNonces.size)
    }

    @Test
    fun `撤销后的在途 verify - 消耗与撤销检查闭环不留孤儿`() {
        val issuer = SessionGrantIssuer(clock = { 1_000L }, hostIdentity = "concurrent-host")
        // 会话 A 已消耗过一次（正常路径）
        val grantA = issuer.issue("com.abyss.plugin.appbuilder", publisherSha, "linux-runtime-v1", "sess-a")
        assertTrue(issuer.verify(grantA, "sess-a") is SessionGrantIssuer.GrantCheck.Valid)
        // revoke 无条件收缩 nonce：表里不留 sess-a 的登记
        assertEquals(1, issuer.revoke("sess-a"))
        assertTrue(issuer.usedNonces.values.none { it.sessionId == "sess-a" })
        // 二次 revoke（DeathRecipient 与用户关闭同拍）：返回 0 但同样完成收缩，不抛
        assertEquals(0, issuer.revoke("sess-a"))
        // 撤销后再拿新 grant 重放旧 nonce → 会话已撤销（不是重放语义）
        val check = issuer.verify(grantA, "sess-a")
        assertEquals("E_GRANT_INVALID", (check as SessionGrantIssuer.GrantCheck.Invalid).code)
        // 拒绝路径自身不留登记（孤儿为零）
        assertTrue(issuer.usedNonces.values.none { it.sessionId == "sess-a" })
    }

    @Test
    fun `nonce 表按 TTL 清扫 - 滚动签发校验后无界增长被抑制`() {
        var now = 0L
        val issuer = SessionGrantIssuer(clock = { now }, hostIdentity = "sweep-host")
        // 模拟长驻宿主：每秒一个会话（issue + verify 成功），连跑 3 个 TTL 周期
        val cycles = PluginContract.GRANT_TTL_MAX_SEC * 3
        repeat(cycles) { i ->
            now = i * 1_000L
            val sessionId = "sess-$i"
            val grant = issuer.issue("com.abyss.plugin.appbuilder", publisherSha, "linux-runtime-v1", sessionId)
            assertTrue(issuer.verify(grant, sessionId) is SessionGrantIssuer.GrantCheck.Valid)
        }
        // 全部 grant 已过期：清扫把 nonce 表收缩到零（issue 不登记 nonce，
        // 只有 verify 消耗时登记）、授权表只剩本次触发签发的会话
        now += PluginContract.GRANT_TTL_MAX_SEC * 1000L * 2
        issuer.issue("com.abyss.plugin.appbuilder", publisherSha, "linux-runtime-v1", "sess-trigger")
        assertEquals(0, issuer.usedNonces.size)
        assertEquals(1, issuer.activeGrants.size)
    }

    @Test
    fun `并发 invalidateAll - 代际翻转不丢不乱（AtomicLong）`() {
        val issuer = SessionGrantIssuer(clock = { 1_000L }, hostIdentity = "gen-host")
        val threads = 4
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val flips = AtomicInteger(0)
        try {
            repeat(threads) {
                pool.submit {
                    ready.countDown()
                    go.await()
                    repeat(25) {
                        issuer.invalidateAll()
                        flips.incrementAndGet()
                    }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            go.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        // 翻转次数一个不丢（旧 volatile 读-改-写会 < flips）；代际 = 初值 1 + 翻转数
        val grant = issuer.issue("com.abyss.plugin.appbuilder", publisherSha, "linux-runtime-v1", "sess-gen")
        assertEquals(flips.get() + 1L, issuer.currentGeneration())
        assertTrue(issuer.verify(grant, "sess-gen") is SessionGrantIssuer.GrantCheck.Valid)
    }
}
