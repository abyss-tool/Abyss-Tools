package com.abyss.tools.core.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * B-04 回归：MirroredTransport.getBytes 必须返回**网络原文字节**。
 *
 * 旧实现未重写 getBytes，走接口缺省 get().toByteArray(UTF_8) —— String
 * decode/re-encode 往返会把 CRLF/BOM/非法 UTF-8（0xFF）归一化（U+FFFD
 * 替换），签名验证对象不再是网络原文，N-SEC-001「原始字节验签」被整链
 * 绕过。本组用例注入含上述字节的 fake transport，锁定字节保真 + 镜像
 * 回退语义。
 */
class MirroredTransportTest {

    private val rawUrl = "https://raw.githubusercontent.com/abyss-tool/Abyss-Tools-Release/main/version.json"

    /** ghfast 反代（目录序首个）改写形态。 */
    private val ghfastUrl = "https://ghfast.top/$rawUrl"

    /** raw host 等价镜像改写形态。 */
    private val rawGitmirrorUrl = "https://raw.gitmirror.com/abyss-tool/Abyss-Tools-Release/main/version.json"

    /** fake transport：按 URL 路由字节/错误，记录请求落点。 */
    private class ByteFakeTransport(
        private val failAt: Set<String> = emptySet(),
        private val bytes: (String) -> ByteArray?,
    ) : HttpTransport {
        val requested = mutableListOf<String>()
        override fun get(url: String): String {
            throw IOException("fake: get() must not be used on the signed-byte path (B-04)")
        }
        override fun getBytes(url: String): ByteArray {
            requested += url
            if (url in failAt) throw IOException("500 fake <- $url")
            return bytes(url) ?: throw IOException("404 fake <- $url")
        }
        override fun download(
            url: String,
            dest: File,
            onProgress: (readBytes: Long, totalBytes: Long) -> Unit,
        ) {
            throw IOException("fake: download not used here")
        }
    }

    private fun mirrored(fake: HttpTransport): MirroredTransport =
        MirroredTransport(fake, MirrorRouter())

    @Test
    fun `getBytes preserves raw network bytes - BOM CRLF and invalid UTF-8`() {
        // 网络原文：UTF-8 BOM + CRLF 行尾 + 非法 UTF-8 字节 0xFF/0xFE
        val raw = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "version\r\n".toByteArray() +
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00)
        val fake = ByteFakeTransport { url -> if (url == ghfastUrl) raw else null }
        val result = mirrored(fake).getBytes(rawUrl)
        assertArrayEquals("原始字节必须逐字节保真（无 decode/re-encode 往返）", raw, result)
        // 首选镜像 = 目录序首个（ghfast），且签名路径绝不调 get()
        assertEquals(listOf(ghfastUrl), fake.requested)
    }

    @Test
    fun `getBytes falls back through the whole mirror chain to DIRECT`() {
        val raw = "hello".toByteArray()
        // 全部反代/镜像 500，仅 DIRECT（原始 URL）可用
        val fake = ByteFakeTransport(
            failAt = setOf(ghfastUrl, rawGitmirrorUrl, "https://ghproxy.net/$rawUrl",
                "https://gh-proxy.com/$rawUrl", "https://ghproxy.cn/$rawUrl"),
            bytes = { url -> if (url == rawUrl) raw else null },
        )
        val result = mirrored(fake).getBytes(rawUrl)
        assertArrayEquals(raw, result)
        // 链尾 DIRECT 兜底可达（诚实降级语义不变）
        assertTrue(fake.requested.last() == rawUrl)
    }

    @Test
    fun `getBytes passes non-GitHub URLs through untouched`() {
        val local = byteArrayOf(0x01, 0x02, 0xFF.toByte())
        val fake = ByteFakeTransport { url -> if (url == "https://example.com/x.bin") local else null }
        val result = mirrored(fake).getBytes("https://example.com/x.bin")
        assertArrayEquals(local, result)
        assertEquals(listOf("https://example.com/x.bin"), fake.requested)
    }

    @Test
    fun `signed manifest verification works through mirrored delegate`() {
        // 端到端：SignedHttpTransport(delegate=MirroredTransport(fake)) ——
        // 验签链路经镜像装饰器仍取网络原文字节并验签通过。
        val helloSig = """
            untrusted comment: signature from abyss release key
            RWRx16ORC4gsd6h6gvEIcMQMu/CJekA15W+OF7wGUHZWYYk/vZZzECHKrw/tfdjVNHZqAJxSr6q6dFBpORzv4UOhpxyv9FiE2gw=
            trusted comment: file:hello.txt
            RWRx16ORC4gsdxu9hpnZrmRmmDjZVnRwJDmq4/QomzQgg+VFHeAOggc5vuNGJnm04F14eoLqRBOp3LGeT/zYW+STbpR/R6Kl8gg=
        """.trimIndent()
        val manifest = "hello".toByteArray()
        val fake = ByteFakeTransport { url ->
            when (url) {
                ghfastUrl -> manifest
                "$ghfastUrl.minisig" -> helloSig.toByteArray()
                else -> null
            }
        }
        val signed = SignedHttpTransport(mirrored(fake), "version.json")
        assertEquals("hello", signed.getSigned(rawUrl))
        // 清单 → 签名，双双命中镜像改写 URL（证据：无原始 URL 直连）
        assertEquals(listOf(ghfastUrl, "$ghfastUrl.minisig"), fake.requested)
    }
}
