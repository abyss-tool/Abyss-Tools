package com.abyss.tools.core.common.crypto

import java.util.Base64

/**
 * Minisign 兼容 detached 签名验签器。
 *
 * 签名文件格式（minisign 0.11 约定）：
 * ```
 * untrusted comment: ...
 * base64( signature blob = 0x45 64 02 || keynum(8) || sig(64) )
 * trusted comment: ...
 * base64( global signature = 0x45 64 02 || keynum(8) || sig(64)  over "sig blob || trusted comment" )
 * ```
 *
 * 我们只使用第一行签名（对被签消息的 detached 签名）；global signature 仅在
 * 需验证 trusted comment 完整性时使用，本客户端不展示 trusted comment，
 * 故可选校验。
 *
 * 公钥文件格式：
 * ```
 * untrusted comment: ...
 * base64( 0x45 64 02 || keynum(8) || pubkey(32) )
 * ```
 *
 * 对应规格：`N-SEC-001`，FR-001 / FR-002。
 */
object MinisignVerifier {

    private const val MAGIC_LEN = 2
    private const val KEYNUM_LEN = 8
    private const val PUBKEY_LEN = 32
    private const val SIG_LEN = 64
    private const val SIG_BLOB_LEN = MAGIC_LEN + KEYNUM_LEN + SIG_LEN // 74
    private const val KEY_BLOB_LEN = MAGIC_LEN + KEYNUM_LEN + PUBKEY_LEN // 42
    private val MAGIC = byteArrayOf(0x45, 0x64) // "Ed"
    private val MAGIC_SECRET = byteArrayOf(0x45, 0x64.toByte()) // 同
    private const val OLD_SIGALG = byteArrayOf(0x45, 0x64, 0x53, 0x65) // EdSe
    private const val NEW_SIGALG = byteArrayOf(0x45, 0x64, 0x02.toByte(), 0x00) // Ed2+padding

    data class MinisignPublicKey(
        val keyId: ByteArray,
        val publicKey: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean = other is MinisignPublicKey &&
            keyId.contentEquals(other.keyId) && publicKey.contentEquals(other.publicKey)
        override fun hashCode(): Int = 31 * keyId.contentHashCode() + publicKey.contentHashCode()
    }

    /**
     * 解析 minisign 公钥文本。
     * @return MinisignPublicKey；格式非法返回 null
     */
    fun parsePublicKey(publicKeyText: String): MinisignPublicKey? {
        val line = publicKeyText.lines()
            .map { it.trim() }
            .firstOrNull { !it.startsWith("untrusted comment:") && it.isNotEmpty() }
            ?: return null
        val blob = runCatching { Base64.getDecoder().decode(line) }.getOrNull() ?: return null
        if (blob.size != KEY_BLOB_LEN) return null
        if (!blob.sliceArray(0 until MAGIC_LEN).contentEquals(MAGIC)) return null
        val keyId = blob.sliceArray(MAGIC_LEN until MAGIC_LEN + KEYNUM_LEN)
        val pubKey = blob.sliceArray(MAGIC_LEN + KEYNUM_LEN until KEY_BLOB_LEN)
        return MinisignPublicKey(keyId, pubKey)
    }

    /**
     * 解析 minisign 签名文件，抽取对消息的 64 字节 detached 签名与 keyId。
     * @return Pair(keyId, signature)；格式非法返回 null
     */
    fun parseSignature(signatureText: String): Pair<ByteArray, ByteArray>? {
        val lines = signatureText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val sigLine = lines.firstOrNull { !it.startsWith("untrusted comment:") && !it.startsWith("trusted comment:") }
            ?: return null
        val blob = runCatching { Base64.getDecoder().decode(sigLine) }.getOrNull() ?: return null
        if (blob.size != SIG_BLOB_LEN) return null
        if (!blob.sliceArray(0 until MAGIC_LEN).contentEquals(MAGIC)) return null
        val keyId = blob.sliceArray(MAGIC_LEN until MAGIC_LEN + KEYNUM_LEN)
        val sig = blob.sliceArray(MAGIC_LEN + KEYNUM_LEN until SIG_BLOB_LEN)
        return keyId to sig
    }

    /**
     * 一次性验签入口：给定 minisign 公钥文本、minisign 签名文本、原始消息，
     * 返回签名是否合法。
     *
     * fail-closed：任何解析/算法异常均返回 false，不抛异常到上层。
     */
    fun verify(publicKeyText: String, signatureText: String, message: ByteArray): Boolean {
        val pk = parsePublicKey(publicKeyText) ?: return false
        val (sigKeyId, sig) = parseSignature(signatureText) ?: return false
        if (!pk.keyId.contentEquals(sigKeyId)) return false
        return Ed25519Verifier.verify(pk.publicKey, sig, message)
    }
}
