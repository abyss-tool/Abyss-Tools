package com.abyss.tools.core.common.crypto

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.security.KeyFactory
import java.security.Signature
import java.util.Base64

/**
 * Ed25519 验签器（仅 verify，不负责签名生成）。
 *
 * 实现：使用 [net.i2p.crypto.eddsa]（纯 Java、零原生依赖、Android API 21+
 * 兼容、长期稳定），曲线采用 RFC 8032 标准 `Ed25519`（对应
 * [net.i2p.crypto.eddsa.EdDSANamedCurveTable.ED_25519_CURVE_SPEC]），
 * 与 minisign/libsodium 严格互验。
 *
 * 不引入签名生成能力：私钥仅存在于 GitHub Actions secrets，客户端永不持有。
 */
object Ed25519Verifier {

    private val ED25519_SPEC: EdDSANamedCurveSpec =
        EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
            ?: error("Ed25519 curve spec missing in eddsa library")

    /**
     * 校验 detached 原始签名。
     *
     * @param publicKeyBytes 32 字节原始 Ed25519 公钥（RFC 8032 A 编码）
     * @param signatureBytes 64 字节原始签名（R || S）
     * @param message        被签名消息
     * @return true 表示签名有效；格式错误/算法异常/验签失败均返回 false
     */
    fun verify(publicKeyBytes: ByteArray, signatureBytes: ByteArray, message: ByteArray): Boolean {
        if (publicKeyBytes.size != 32) return false
        if (signatureBytes.size != 64) return false
        return runCatching {
            val keyFactory = KeyFactory.getInstance("EdDSA", "EdDSA")
            val pubSpec = EdDSAPublicKeySpec(publicKeyBytes, ED25519_SPEC)
            val pubKey = keyFactory.generatePublic(pubSpec)
            val sig: Signature = EdDSAEngine()
            sig.initVerify(pubKey)
            sig.update(message)
            sig.verify(signatureBytes)
        }.getOrDefault(false)
    }

    /** Base64 便捷入口。 */
    fun verifyBase64(publicKeyBase64: String, signatureBase64: String, message: ByteArray): Boolean =
        runCatching {
            val dec = Base64.getDecoder()
            verify(dec.decode(publicKeyBase64.trim()), dec.decode(signatureBase64.trim()), message)
        }.getOrDefault(false)
}
