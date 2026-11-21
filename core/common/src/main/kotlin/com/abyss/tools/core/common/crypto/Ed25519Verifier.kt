package com.abyss.tools.core.common.crypto

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
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
            // 直接用 eddsa 库的公钥对象构造，避免依赖 JCA Provider 注册：
            // net.i2p.crypto.eddsa 的 KeyFactory 只有在 Security.addProvider(
            // EdDSASecurityProvider()) 之后才可按 "EdDSA" 名称查得，客户端一旦
            // 漏注册就会静默失败（验签恒 false → fail-closed 拒收全部清单）。
            val pubKey = EdDSAPublicKey(EdDSAPublicKeySpec(publicKeyBytes, ED25519_SPEC))
            val engine = EdDSAEngine()
            engine.initVerify(pubKey)
            engine.update(message)
            engine.verify(signatureBytes)
        }.getOrDefault(false)
    }

    /** Base64 便捷入口。 */
    fun verifyBase64(publicKeyBase64: String, signatureBase64: String, message: ByteArray): Boolean =
        runCatching {
            val dec = Base64.getDecoder()
            verify(dec.decode(publicKeyBase64.trim()), dec.decode(signatureBase64.trim()), message)
        }.getOrDefault(false)
}
