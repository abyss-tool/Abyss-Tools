package com.abyss.tools.core.shell

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.min

/** 截断语义的读取结果（超限部分丢弃，调用方如实上报 truncated）。 */
data class CappedText(val text: String, val truncated: Boolean)

/**
 * 流读取守护（纯 JVM，评审 #7）：给 stdout/stderr 读取加字节上限 ——
 * zip bomb 级输出 / 失控 dumpsys 不再把读取端拖进无界内存。
 *
 * 截断策略：保留前 [maxBytes] 字节，置 truncated = true（UI 层提示
 * 「输出已截断」），绝不静默假装读完了。
 */
object StreamGuards {

    fun readTextCapped(input: InputStream, maxBytes: Int): CappedText {
        require(maxBytes > 0) { "maxBytes 必须为正：$maxBytes" }
        val buffer = ByteArrayOutputStream(min(maxBytes, 64 * 1024))
        val chunk = ByteArray(8 * 1024)
        var truncated = false
        input.use { stream ->
            while (true) {
                val read = stream.read(chunk)
                if (read == -1) break
                val remaining = maxBytes - buffer.size()
                if (remaining <= 0) {
                    truncated = true
                    break
                }
                if (read > remaining) {
                    buffer.write(chunk, 0, remaining)
                    truncated = true
                    break
                }
                buffer.write(chunk, 0, read)
            }
        }
        return CappedText(buffer.toByteArray().decodeToString(), truncated)
    }
}
