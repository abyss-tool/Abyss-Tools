package com.abyss.tools.core.common.virus

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 病毒拦截审计日志（N-VIR-006 FR-002/003/005）。
 *
 * 纪律：
 *  - 每次判定与处置（扫描命中 / 拦截 / 用户放行 / 自动放行 / 加白名单）都留痕。
 *  - 日志默认仅写本地沙箱，**绝不随云查询外传**（FR-005）。
 *  - 文件上限 512KB，轮转保留最近 2 份，可导出（脱敏选项默认开启）。
 *  - 仅记录包名/哈希/verdict/理由链/操作者/结果，不写文件路径、不写 token、不写隐私。
 */
object VirusAuditLog {

    enum class Action(val label: String) {
        SCAN_HIT("扫描命中"),
        BLOCKED("拦截安装"),
        USER_OVERRIDE("用户放行"),
        AUTO_PASS("自动放行"),
        ALLOWLIST_ADD("加入白名单"),
        ALLOWLIST_REMOVE("移出白名单"),
        DISPOSITION_REVERSED("撤销处置"),
        FALSE_POSITIVE_REPORT("误报申诉"),
    }

    enum class Operator { USER, SYSTEM, PIPELINE }

    data class Entry(
        val timestampMs: Long,
        val action: Action,
        val verdict: Verdict?,
        val packageName: String,
        val sha256: String,
        val operator: Operator,
        val result: String,
        val reasons: List<String> = emptyList(),
    ) {
        fun humanTime(): String = FMT.get().format(Date(timestampMs))
    }

    private const val MAX_MEMORY = 200
    private const val MAX_FILE_BYTES = 512L * 1024
    private const val MAX_ROTATIONS = 2

    private val buffer = ConcurrentLinkedQueue<Entry>()
    private val count = AtomicInteger(0)

    @Volatile
    private var logDir: File? = null

    private val FMT = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    fun attachLogDir(dir: File) {
        dir.mkdirs()
        logDir = dir
    }

    fun record(
        action: Action,
        verdict: Verdict?,
        packageName: String,
        sha256: String,
        operator: Operator,
        result: String,
        reasons: List<String> = emptyList(),
    ) {
        val normalizedSha = sha256.lowercase()
        val ev = Entry(
            timestampMs = System.currentTimeMillis(),
            action = action, verdict = verdict,
            packageName = packageName, sha256 = normalizedSha,
            operator = operator, result = result,
            reasons = reasons.toList(),
        )
        buffer.offer(ev)
        if (count.incrementAndGet() > MAX_MEMORY) {
            buffer.poll(); count.decrementAndGet()
        }
        appendToFile(ev)
    }

    fun recent(limit: Int = 50): List<Entry> =
        buffer.toList().takeLast(limit.coerceAtMost(MAX_MEMORY))

    fun clear() {
        buffer.clear(); count.set(0)
        runCatching { logFile()?.delete() }
        runCatching { rotatedFile()?.delete() }
    }

    /**
     * 导出审计日志到 dest。
     * @param maskSha 脱敏：sha256 保留前8位+...+后8位，默认 true。
     */
    fun exportTo(dest: File, maskSha: Boolean = true): File? = runCatching {
        dest.parentFile?.mkdirs()
        dest.bufferedWriter().use { w ->
            w.write("# Abyss Tools 病毒拦截审计日志\n")
            w.write("# 导出时间：${FMT.get().format(Date())}\n")
            w.write("# 本日志仅记录在本地，未随云查询外传\n")
            w.write("# 操作者：S=SYSTEM P=PIPELINE U=USER\n\n")
            for (ev in buffer.toList()) w.write(ev.toLine(maskSha) + "\n")
            val main = logFile()
            if (main?.exists() == true) {
                w.write("\n## 磁盘日志\n")
                main.forEachLine { w.write(it + "\n") }
            }
        }
        dest
    }.getOrNull()

    private fun appendToFile(ev: Entry) {
        val dir = logDir ?: return
        runCatching {
            val f = logFile() ?: return@runCatching
            if (f.exists() && f.length() > MAX_FILE_BYTES) rotate(f)
            f.appendText(ev.toLine(maskSha = false) + "\n")
        }
    }

    private fun logFile(): File? = logDir?.let { File(it, "abyss-virus-audit.log") }
    private fun rotatedFile(): File? = logDir?.let { File(it, "abyss-virus-audit.log.1") }

    private fun rotate(f: File) {
        runCatching {
            val rot = rotatedFile() ?: return@runCatching
            if (rot.exists()) rot.delete()
            f.renameTo(rot)
        }
    }

    private fun Entry.toLine(maskSha: Boolean): String {
        val op = when (operator) { Operator.SYSTEM -> "S"; Operator.PIPELINE -> "P"; Operator.USER -> "U" }
        val v = verdict?.name ?: "-"
        val sha = if (maskSha && sha256.length >= 64) "${sha256.take(8)}...${sha256.takeLast(8)}" else sha256
        val reasonsStr = if (reasons.isEmpty()) "" else " | reasons=${reasons.joinToString(";")}"
        return "${humanTime()} [$op] [${action.label}] verdict=$v pkg=$packageName sha=$sha result=$result$reasonsStr"
    }
}
