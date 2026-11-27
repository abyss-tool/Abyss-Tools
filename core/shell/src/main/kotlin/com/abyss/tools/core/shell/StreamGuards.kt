package com.abyss.tools.core.shell

import java.io.ByteArrayOutputStream
import java.io.IOException
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
 *
 * B-17 修复：截断后**继续排空**剩余流（丢弃不入库）—— 若无人消费，
 * 子进程写满管道后会阻塞到看门狗 30s 强杀；排空让它自然走到 EOF，
 * 读取线程及时收尾、streamAbandoned 语义可判定。
 */
object StreamGuards {

    /**
     * 有界读取（截断语义见上）。
     *
     * @param closeStream true（默认）：读完后关闭流（既有语义，普通
     * InputStream 调用方继续用）；false：只读不关 —— 供「流只是底层
     * fd 的读取视图、fd 归宿在别处」的调用方使用（如 Shizuku PFD：
     * FIS.close() 会关闭底层 fd，与 ParcelFileDescriptor 的 close
     * 对同一描述符叠加成双重关闭，NB-6）。
     */
    fun readTextCapped(input: InputStream, maxBytes: Int, closeStream: Boolean = true): CappedText {
        require(maxBytes > 0) { "maxBytes 必须为正：$maxBytes" }
        val buffer = ByteArrayOutputStream(min(maxBytes, 64 * 1024))
        val chunk = ByteArray(8 * 1024)
        val truncated = if (closeStream) {
            input.use { stream -> readLoop(stream, chunk, buffer, maxBytes) }
        } else {
            readLoop(input, chunk, buffer, maxBytes)
        }
        return CappedText(buffer.toByteArray().decodeToString(), truncated)
    }

    /** 循环体（两种关闭模式共用）：返回是否发生截断（截断后排空）。 */
    private fun readLoop(
        stream: InputStream,
        chunk: ByteArray,
        buffer: ByteArrayOutputStream,
        maxBytes: Int,
    ): Boolean {
        while (true) {
            val read = stream.read(chunk)
            if (read == -1) return false
            val remaining = maxBytes - buffer.size()
            if (remaining <= 0) {
                drainQuietly(stream)
                return true
            }
            if (read > remaining) {
                buffer.write(chunk, 0, remaining)
                drainQuietly(stream)
                return true
            }
            buffer.write(chunk, 0, read)
        }
    }

    /**
     * 截断后排空（丢弃，不入库）：解除子进程管道写阻塞。流的自然 EOF /
     * 看门狗杀进程后管道断开都会结束排空；IO 异常静默容忍（截断语义
     * 已如实上报，排空失败不影响结论）。
     */
    private fun drainQuietly(stream: InputStream) {
        val chunk = ByteArray(8 * 1024)
        try {
            while (stream.read(chunk) != -1) { /* 丢弃 */ }
        } catch (_: IOException) {
            // 流已断：截断结论不受影响
        }
    }
}
