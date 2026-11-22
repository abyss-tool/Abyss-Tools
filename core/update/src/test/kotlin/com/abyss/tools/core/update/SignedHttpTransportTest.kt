package com.abyss.tools.core.update

import com.abyss.tools.core.common.security.SignatureVerificationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * N-SEC-001 SignedHttpTransport 行为单测（纯 JVM，fake transport）：
 * 先拉 .minisig → 验签 → 返回原文；缺签名 / 签名错一律 fail-closed，
 * 且缺失场景给出「镜像可能尚未同步」人话提示。
 *
 * 签名向量 = meng4117222 首签钥对 "hello" 的固定 detached 签名（与
 * ChannelSignatureVerifierTest 同源，离线生成）。
 */
class SignedHttpTransportTest {

    private val helloSig = """
        untrusted comment: signature from abyss release key
        RWRx16ORC4gsd6h6gvEIcMQMu/CJekA15W+OF7wGUHZWYYk/vZZzECHKrw/tfdjVNHZqAJxSr6q6dFBpORzv4UOhpxyv9FiE2gw=
        trusted comment: file:hello.txt
        RWRx16ORC4gsdxu9hpnZrmRmmDjZVnRwJDmq4/QomzQgg+VFHeAOggc5vuNGJnm04F14eoLqRBOp3LGeT/zYW+STbpR/R6Kl8gg=
    """.trimIndent()

    private val url = "https://raw.githubusercontent.com/abyss-tool/Abyss-Tools-Release/main/version.json"

    /** fake transport：按 URL 返回文本或抛错，记录全部请求落点。 */
    private class FakeTransport(
        private val responses: Map<String, String>,
        private val errors: Map<String, IOException> = emptyMap(),
    ) : HttpTransport {
        val requested = mutableListOf<String>()
        override fun get(url: String): String {
            requested += url
            errors[url]?.let { throw it }
            return responses[url] ?: throw IOException("404 not found: $url")
        }
        override fun getBytes(url: String): ByteArray {
            requested += url
            errors[url]?.let { throw it }
            return (responses[url] ?: throw IOException("404 not found: $url")).toByteArray(Charsets.UTF_8)
        }
        override fun download(
            url: String,
            dest: java.io.File,
            onProgress: (readBytes: Long, totalBytes: Long) -> Unit,
        ) {
            throw IOException("fake transport: download not used in signed-manifest tests")
        }
    }

    @Test
    fun `returns manifest only after signature verifies`() {
        val t = FakeTransport(mapOf(url to "hello", "$url.minisig" to helloSig))
        val signed = SignedHttpTransport(t, "version.json")
        assertEquals("hello", signed.getSigned(url))
        // 请求序列 = 清单 → 签名（先验签再消费原文）
        assertEquals(listOf(url, "$url.minisig"), t.requested)
    }

    @Test
    fun `missing minisig fails closed with mirror-sync human message`() {
        // 镜像未同步场景：清单 200 而 .minisig 404 —— 绝不降级为无签名放行
        val t = FakeTransport(mapOf(url to "hello"))
        val signed = SignedHttpTransport(t, "version.json")
        try {
            signed.getSigned(url)
            fail("缺 .minisig 必须 fail-closed")
        } catch (e: SignatureVerificationException) {
            assertTrue("人话提示应含镜像同步语义: ${e.message}", e.message!!.contains("镜像可能尚未同步"))
        }
    }

    @Test
    fun `tampered manifest fails closed`() {
        // 清单被篡改（"hello" → "hallO"），签名仍为原文签名 → 拒绝
        val t = FakeTransport(mapOf(url to "hallO", "$url.minisig" to helloSig))
        val signed = SignedHttpTransport(t, "version.json")
        try {
            signed.getSigned(url)
            fail("篡改清单必须 fail-closed")
        } catch (e: SignatureVerificationException) {
            assertTrue(e.message!!.contains("签名校验"))
        }
    }

    @Test
    fun `sigUrlOverride is honored for non-sibling signatures`() {
        // 插件补丁通道类场景：签名不与清单同路径后缀，由 sigUrlOverride 指定
        val override = "https://raw.githubusercontent.com/abyss-tool/Abyss-Tools-Release/main/sigs/version.minisig"
        val t = FakeTransport(mapOf(url to "hello", override to helloSig))
        val signed = SignedHttpTransport(t, "version.json", sigUrlOverride = override)
        assertEquals("hello", signed.getSigned(url))
        assertEquals(listOf(url, override), t.requested)
    }

    @Test(expected = SignatureVerificationException::class)
    fun `blank signature fails closed`() {
        val t = FakeTransport(mapOf(url to "hello", "$url.minisig" to ""))
        SignedHttpTransport(t, "version.json").getSigned(url)
    }
}
