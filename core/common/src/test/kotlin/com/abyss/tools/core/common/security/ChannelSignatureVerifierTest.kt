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

    @Test
    fun `rotation public key still verifies during dual-key transition`() {
        // N-SEC-001 FR-003 双公钥过渡期：签名密钥轮换时新公钥加入 TRUSTED_KEYS、
        // 旧公钥保留 —— 任意一把验过即合法。本用例用轮换演练钥（keyId
        // 0aa1b2c3d4e5f607，与首签钥不同）对 "hello" 的固定向量验证。
        val rotationPubkey = """
            untrusted comment: Abyss-Tools rotation-test public key (M1)
            RWQKobLD1OX2BwHGd5ijnrBGLEq0BtJa+rM1TcZj9s19ABor1RmvF5MB
        """.trimIndent()
        val minisig = """
            untrusted comment: signature from abyss rotation test key
            RWQKobLD1OX2B9FRX5JZC9fRjHIfArJEC/XpyVeb4hhMZeaWC3hLY/knd4+9dsOXISEuWCeWWf2k557d20C7Po8/jxdcuF1ZFwg=
            trusted comment: file:hello.txt
            RWQKobLD1OX2B5iwH2CsH12ZsYLIRLS5lp/2L7W1zQGegO1lpdhbUP9cMuw/P8H7f6SJoElP8EFD525vevLBCCj24c/4Q4G24ww=
        """.trimIndent()
        // MinisignVerifier 直验（keyId 0aa1… 与首签钥 969f… 不同也应验过）
        assertTrue(MinisignVerifier.verify(rotationPubkey, minisig, "hello".toByteArray()))
        // 篡改消息 → fail-closed
        assertFalse(MinisignVerifier.verify(rotationPubkey, minisig, "hellO".toByteArray()))
        // 首签钥的签名不应被轮换钥验过（keyId 不匹配即拒）
        val devSig = """
            untrusted comment: signature from abyss dev key
            RWSWn6+V/umBvFVYNDRKQ5H/btabzHuxtROwGrx2+x5WbpnTlJQ1v1T9QhInhI2vtGboPl7qLOBPz0KWreOlrZiFx+TFK3svrQ0=
        """.trimIndent()
        assertFalse(MinisignVerifier.verify(rotationPubkey, devSig, "hello".toByteArray()))
    }
}
