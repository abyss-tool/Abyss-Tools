package com.abyss.tools.core.common.diagnostics

import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 本地诊断日志中心（N-EXP-003，M1）。
 *
 * 设计原则：
 *  - **零外传**：日志只写本地沙箱，绝不上传；导出由用户在诊断中心主动触发。
 *  - **内存环形缓冲**：进程内保留最近 N 条事件，供"诊断中心"页面直接展示。
 *  - **磁盘滚动文件**：可选追加到磁盘文件（`diag/abyss-diag.log`），
 *    文件超上限自动轮转保留最近 2 份，不爆存储。
 *  - **结构化事件**：每条事件含时间戳、类别、严重度、人话语义、可诊断
 *    上下文（URL / statusCode / manifest 名），不写 token/密钥/隐私字段。
 *
 * 典型使用：
 * ```
 * DiagnosticsLog.record(EventKind.SIGNATURE_FAILURE, "version.json", "签名不匹配")
 * ```
 */
object DiagnosticsLog {

    enum class Level { INFO, WARN, ERROR }

    enum class Kind(val label: String) {
        UPDATE_CHECK("更新检查"),
        UPDATE_DOWNLOAD("下载"),
        PATCH_APPLY("补丁"),
        SIGNATURE_FAILURE("清单签名校验"),
        PLUGIN_INDEX("插件索引"),
        PLUGIN_INSTALL("插件安装"),
        SHIZUKU("Shizuku 通道"),
        NETWORK("网络"),
        BACKUP_RESTORE("备份恢复"),
        CRASH("崩溃防护"),
        VIRUS_SCAN("病毒拦截"),
        PLUGIN_SESSION("插件会话"),
        GENERAL("通用"),
    }

    data class Event(
        val timestampMs: Long,
        val level: Level,
        val kind: Kind,
        val subject: String,
        val message: String,
    ) {
        fun humanTime(): String = FORMAT.get().format(Date(timestampMs))
    }

    private const val MAX_MEMORY_EVENTS = 200
    private const val MAX_FILE_BYTES = 256L * 1024 // 256 KB
    private const val MAX_FILE_ROTATIONS = 2

    private val buffer = ConcurrentLinkedQueue<Event>()

    @Volatile
    private var logDir: File? = null

    private val FORMAT = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    /**
     * 绑定磁盘日志目录（一般为 `filesDir/diag`）。未绑定则只走内存缓冲。
     * 必须在 App 启动 ServiceRegistry 阶段调用一次。
     */
    fun attachLogDir(dir: File) {
        dir.mkdirs()
        logDir = dir
    }

    /** 记录一条诊断事件（线程安全，不阻塞调用方）。 */
    fun record(kind: Kind, subject: String, message: String, level: Level = Level.ERROR) {
        val ev = Event(System.currentTimeMillis(), level, kind, subject, message)
        buffer.offer(ev)
        // NB-7：队列自身水位为准 —— offer/poll + 独立计数器非原子（同
        // VirusAuditLog NB-3a），clear() 与 record() 并发时计数与队列漂移，
        // 计数永久失真后 200 条上限失效；while 修剪恒收敛。
        while (buffer.size > MAX_MEMORY_EVENTS) {
            buffer.poll()
        }
        appendToFile(ev)
    }

    fun info(kind: Kind, subject: String, message: String) =
        record(kind, subject, message, Level.INFO)
    fun warn(kind: Kind, subject: String, message: String) =
        record(kind, subject, message, Level.WARN)
    fun error(kind: Kind, subject: String, message: String) =
        record(kind, subject, message, Level.ERROR)

    /** 获取最近 N 条事件（按时间升序）。 */
    fun recent(limit: Int = 50): List<Event> =
        buffer.toList().takeLast(limit.coerceAtMost(MAX_MEMORY_EVENTS))

    /**
     * 导出全部日志到目标文件（合并内存缓冲 + 磁盘滚动文件）。
     * @return 导出的目标文件路径，失败返回 null
     */
    fun exportTo(dest: File): File? = runCatching {
        dest.parentFile?.mkdirs()
        dest.bufferedWriter().use { w ->
            w.write("# Abyss Tools 诊断日志导出\n")
            w.write("# 导出时间：${FORMAT.get().format(Date())}\n")
            w.write("# 级别：I=INFO W=WARN E=ERROR\n\n")
            w.write("## 进程内缓冲（最近 $MAX_MEMORY_EVENTS 条）\n")
            for (ev in buffer.toList()) w.write(ev.toLine() + "\n")
            w.write("\n## 磁盘日志\n")
            val main = logFile()
            if (main?.exists() == true) {
                main.forEachLine { w.write(it + "\n") }
            } else {
                w.write("(无磁盘日志)\n")
            }
        }
        dest
    }.getOrNull()

    /**
     * 清空内存缓冲 + 磁盘日志（用户主动操作后）。
     */
    fun clear() {
        buffer.clear()
        runCatching { logFile()?.delete() }
        runCatching { rotatedFile()?.delete() }
    }

    /** 按 Kind 统计 ERROR 数，供诊断页总览。 */
    fun errorCounts(): Map<Kind, Int> {
        val map = mutableMapOf<Kind, Int>()
        for (ev in buffer) {
            if (ev.level == Level.ERROR) {
                map[ev.kind] = (map[ev.kind] ?: 0) + 1
            }
        }
        return map
    }

    private fun appendToFile(ev: Event) {
        val dir = logDir ?: return
        runCatching {
            val file = logFile() ?: return@runCatching
            if (file.exists() && file.length() > MAX_FILE_BYTES) rotate(file)
            file.appendText(ev.toLine() + "\n")
        }
    }

    private fun logFile(): File? = logDir?.let { File(it, "abyss-diag.log") }
    private fun rotatedFile(): File? = logDir?.let { File(it, "abyss-diag.log.1") }

    private fun rotate(main: File) {
        runCatching {
            val rot = rotatedFile() ?: return
            if (rot.exists()) rot.delete()
            main.renameTo(rot)
        }
    }

    private fun Event.toLine(): String {
        val l = when (level) {
            Level.INFO -> "I"
            Level.WARN -> "W"
            Level.ERROR -> "E"
        }
        return "${humanTime()} [$l] [${kind.label}] $subject :: $message"
    }
}
