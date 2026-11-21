package com.abyss.tools.core.common.security

import com.abyss.tools.core.common.crypto.MinisignVerifier
import org.junit.Assert.*
import org.junit.Test

class ChannelSignatureVerifierTest {

    @Test
    fun `dev public key parses successfully`() {
        val keyText = ChannelSignatureVerifier.TRUSTED_KEYS.first()
        val pk = MinisignVerifier.parsePublicKey(keyText)
        assertNotNull("内置公钥必须可被解析", pk)
        assertEquals(32, pk!!.publicKey.size)
        assertEquals(8, pk.keyId.size)
    }

    @Test
    fun `verify passes for known-good signature over hello`() {
        // 使用离线生成的固定测试向量（python cryptography + 开发期公钥对应私钥签出）：
        // message = "hello"
        // keyId   = 969faf95fee981bc
        // sig     = 55583434...ad0d（见 /tmp/hello.minisig）
        val minisig = """
            untrusted comment: signature from abyss dev key
            RWSWn6+V/umBvFVYNDRKQ5H/btabzHuxtROwGrx2+x5WbpnTlJQ1v1T9QhInhI2vtGboPl7qLOBPz0KWreOlrZiFx+TFK3svrQ0=
            trusted comment: file:hello.txt
            RWSWn6+V/umBvFVYNDRKQ5H/btabzHuxtROwGrx2+x5WbpnTlJQ1v1T9QhInhI2vtGboPl7qLOBPz0KWreOlrZiFx+TFK3svrQ0=
        """.trimIndent()
        val msg = "hello".toByteArray()
        ChannelSignatureVerifier.verifyOrThrow(msg, minisig)
    }

    @Test(expected = SignatureVerificationException::class)
    fun `verify fails if manifest byte is tampered`() {
        val minisig = """
            untrusted comment: signature from abyss dev key
            RWSWn6+V/umBvFVYNDRKQ5H/btabzHuxtROwGrx2+x5WbpnTlJQ1v1T9QhInhI2vtGboPl7qLOBPz0KWreOlrZiFx+TFK3svrQ0=
        """.trimIndent()
        // 消息被篡改为 "hellX"，必须 fail-closed
        ChannelSignatureVerifier.verifyOrThrow("hellX".toByteArray(), minisig)
    }

    @Test(expected = SignatureVerificationException::class)
    fun `verify fails when minisig is empty`() {
        ChannelSignatureVerifier.verifyOrThrow("{}".toByteArray(), "")
    }

    @Test(expected = SignatureVerificationException::class)
    fun `verify fails with malformed base64 signature`() {
        ChannelSignatureVerifier.verifyOrThrow("{}".toByteArray(), "untrusted comment: x\nnot-base64!!!\n")
    }

    @Test
    fun `verifyDiagnostic returns structured result`() {
        val minisig = """
            untrusted comment: signature from abyss dev key
            RWSWn6+V/umBvFVYNDRKQ5H/btabzHuxtROwGrx2+x5WbpnTlJQ1v1T9QhInhI2vtGboPl7qLOBPz0KWreOlrZiFx+TFK3svrQ0=
        """.trimIndent()
        val ok = ChannelSignatureVerifier.verifyDiagnostic("version.json", "hello".toByteArray(), minisig)
        assertTrue(ok.verified)
        assertNull(ok.error)
        val bad = ChannelSignatureVerifier.verifyDiagnostic(
            "version.json", "tampered".toByteArray(), minisig
        )
        assertFalse(bad.verified)
        assertNotNull(bad.error)
        assertTrue(bad.error!!.contains("签名校验"))
    }
}
