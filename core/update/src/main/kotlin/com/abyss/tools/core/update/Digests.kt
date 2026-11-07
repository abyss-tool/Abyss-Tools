package com.abyss.tools.core.update

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** SHA-256 摘要工具（更新资产完整性校验的单一实现）。 */
object Digests {

    private val HEX = "0123456789abcdef".toCharArray()

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return toHex(digest.digest())
    }

    fun sha256(bytes: ByteArray): String =
        toHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /**
     * 摘要缺失即拒绝（fail-closed，v0.6.0 起）：expected 为空视作
     * 「通道拒绝背书」—— 下载资产（APK / 补丁）没有声明校验值就绝不消费。
     * 旧版「框架期兼容」放行分支已删除（评审 #2：空摘要放行 = 校验链
     * 整体失效面）。
     */
    fun verify(file: File, expected: String?): Boolean =
        !expected.isNullOrBlank() && sha256(file).equals(expected.trim(), ignoreCase = true)

    private fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }
}
