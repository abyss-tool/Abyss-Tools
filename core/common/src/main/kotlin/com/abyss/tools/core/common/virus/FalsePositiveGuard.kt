package com.abyss.tools.core.common.virus

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * N-VIR-006 · 误杀防护与审计日志（纯 JVM 可测）。
 *
 * 设计原则：
 *  - **默认只提示不自动卸载**：任何 MALICIOUS 判定必须经用户确认后才处置。
 *  - **白名单双轨**：用户可把样本加入白名单（按 sha256+包名），并一键撤销处置（若可逆）。
 *  - **审计留痕**：每次判定与处置都写入本地审计日志（时间/包名/verdict/理由链/操作者/结果），
 *    仅写本地沙箱文件，**绝不上传云端**（N-VIR-006 FR-005）。
 *  - **脱敏导出**：导出前默认脱敏路径与 APK 路径中的私有目录前缀。
 */

/** 处置动作 */
enum class DispositionAction {
    /** 仅提示，未拦截 */
    NOTIFY_ONLY,

    /** 用户确认后阻断安装（已安装包则停止启动监听） */
    BLOCK,

    /** 用户确认后卸载（高权限场景，默认关闭） */
    UNINSTALL,

    /** 用户标记为误报，加入白名单并放行 */
    WHITELIST,

    /** 用户撤销之前的处置（恢复放行） */
    REVERT,
}

/** 处置操作者 */
enum class DispositionActor {
    /** 用户在确认对话框中手动选择 */
    USER,

    /** 系统自动处置（默认配置下仅 NOTIFY_ONLY 允许自动；其他必须 USER） */
    AUTOMATIC,
}

/** 处置结果 */
enum class DispositionResult {
    PENDING,     // 等待用户确认
    APPLIED,     // 处置已执行
    FAILED,      // 处置执行失败
    SKIPPED,     // 用户跳过/取消
    REVERTED,    // 已被后续 REVERT 撤销
}

/** 一条审计记录 */
data class AuditEntry(
    val id: Long,
    val timestampMs: Long,
    val packageName: String,
    val versionCode: Long?,
    val sha256: String,
    val signerSha256: String,
    val verdict: Verdict,
    val reasonChain: String,
    val action: DispositionAction,
    val actor: DispositionActor,
    val result: DispositionResult,
    /** 用户自由文本备注（误报说明等），可空 */
    val note: String? = null,
) {
    fun humanTime(): String = FORMAT.get().format(Date(timestampMs))

    /** 脱敏后文本（移除 apkPath 中私有目录前缀等） */
    fun toLogLine(): String = buildString {
        append(humanTime())
        append(" #").append(id)
        append(" [").append(verdict.name).append("]")
        append(" pkg=").append(packageName)
        if (versionCode != null) append("(vc=").append(versionCode).append(")")
        append(" sha256=").append(sha256.take(16)).append("…")
        append(" action=").append(action.name)
        append(" actor=").append(actor.name)
        append(" result=").append(result.name)
        if (!note.isNullOrBlank()) append(" note=").append(note)
        append(" reasons=").append(reasonChain)
    }

    companion object {
        internal val FORMAT = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        }
    }
}

/**
 * 白名单存储：按 sha256 + 包名双重键，避免跨包名误判。
 * 纯内存 + 可选磁盘持久化（一行一条记录）；不加密，因仅本地沙箱。
 */
class WhitelistStore(
    private val storeFile: File? = null,
) {
    /** sha256 → setOf(packageName) */
    private val entries = ConcurrentLinkedQueue<Pair<String, String>>()
    private val snapshot = HashMap<String, MutableSet<String>>()

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        val f = storeFile ?: return
        if (!f.exists()) return
        synchronized(snapshot) {
            snapshot.clear()
            f.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine
                val (sha, pkg) = trimmed.split("\t", limit = 2).let {
                    it.getOrElse(0) { "" } to it.getOrElse(1) { "" }
                }
                if (sha.length == 64 && pkg.isNotEmpty()) {
                    snapshot.getOrPut(sha) { mutableSetOf() }.add(pkg)
                    entries.add(sha to pkg)
                }
            }
        }
    }

    fun isWhitelisted(sha256: String, packageName: String): Boolean = synchronized(snapshot) {
        snapshot[sha256]?.contains(packageName) == true
    }

    fun add(sha256: String, packageName: String) {
        require(sha256.length == 64) { "sha256 非法" }
        require(packageName.isNotEmpty()) { "packageName 不能为空" }
        synchronized(snapshot) {
            if (snapshot.getOrPut(sha256) { mutableSetOf() }.add(packageName)) {
                entries.add(sha256 to packageName)
                appendToDisk(sha256, packageName)
            }
        }
    }

    fun remove(sha256: String, packageName: String) {
        synchronized(snapshot) {
            snapshot[sha256]?.remove(packageName)
            // 磁盘重写
            persistAll()
        }
    }

    fun allWhitelisted(): List<Pair<String, String>> = synchronized(snapshot) {
        snapshot.flatMap { (sha, pkgs) -> pkgs.map { sha to it } }
    }

    private fun appendToDisk(sha: String, pkg: String) {
        val f = storeFile ?: return
        f.parentFile?.mkdirs()
        f.appendText("$sha\t$pkg\n", Charsets.UTF_8)
    }

    private fun persistAll() {
        val f = storeFile ?: return
        f.writeText(buildString {
            append("# Abyss VirusGuard whitelist\n")
            append("# sha256<TAB>packageName\n")
            snapshot.forEach { (sha, pkgs) ->
                pkgs.forEach { pkg -> append(sha).append('\t').append(pkg).append('\n') }
            }
        }, Charsets.UTF_8)
    }
}

/**
 * 审计日志：环形内存缓冲 + 可选磁盘滚动文件。
 * 不做任何网络 IO；上传由用户在诊断中心主动触发时显式操作（不属于本类职责）。
 */
class VirusAuditLog(
    private val logFile: File? = null,
    private val maxMemoryEntries: Int = 500,
    private val maxFileBytes: Long = 512L * 1024,
    private val maxRotations: Int = 2,
) {
    private val buffer = ConcurrentLinkedQueue<AuditEntry>()
    private val idSeq = AtomicLong(0L)

    init {
        logFile?.parentFile?.mkdirs()
    }

    /**
     * 记录一条审计项并返回其 ID。
     * N-VIR-006 FR-001：默认配置下任何 MALICIOUS 判定都不得自动卸载——
     * 调用方必须在传入 action/actor 前遵守此约束，本类不强制业务策略。
     */
    fun record(
        subject: ScanSubject,
        verdict: Verdict,
        reasons: List<VerdictReason>,
        action: DispositionAction,
        actor: DispositionActor,
        result: DispositionResult,
        note: String? = null,
        versionCode: Long? = null,
    ): AuditEntry {
        val id = idSeq.incrementAndGet()
        val entry = AuditEntry(
            id = id,
            timestampMs = System.currentTimeMillis(),
            packageName = subject.packageName,
            versionCode = versionCode,
            sha256 = subject.sha256,
            signerSha256 = subject.signerSha256,
            verdict = verdict,
            reasonChain = reasons.joinToString(" → ") { it.toString() },
            action = action,
            actor = actor,
            result = result,
            note = note,
        )
        buffer.add(entry)
        // 内存环形裁剪
        while (buffer.size > maxMemoryEntries) buffer.poll()
        // 写磁盘
        appendToDisk(entry)
        return entry
    }

    fun recent(n: Int = 100): List<AuditEntry> =
        buffer.toList().takeLast(n.coerceAtMost(maxMemoryEntries))

    fun findById(id: Long): AuditEntry? = buffer.firstOrNull { it.id == id }

    fun count(): Int = buffer.size

    private fun appendToDisk(entry: AuditEntry) {
        val f = logFile ?: return
        rotateIfNeeded(f)
        f.appendText(entry.toLogLine() + "\n", Charsets.UTF_8)
    }

    private fun rotateIfNeeded(f: File) {
        if (!f.exists()) return
        if (f.length() < maxFileBytes) return
        // 滚动：.1 ← .0 ← current
        for (i in (maxRotations - 1) downTo 1) {
            val src = File("${f.absolutePath}.$i")
            val dst = File("${f.absolutePath}.${i + 1}")
            if (src.exists()) src.renameTo(dst)
        }
        val first = File("${f.absolutePath}.1")
        if (first.exists()) first.delete()
        f.renameTo(first)
    }
}

/**
 * 误杀防护门面：编排白名单查询 + 审计记录 + 自动处置策略。
 *
 * 默认安全策略（N-VIR-006 FR-001）：
 *  - CLEAN → 自动放行（NOTIFY 视 UI 层是否提示）
 *  - UNKNOWN/SUSPICIOUS → 仅提示（NOTIFY_ONLY），等待用户决定
 *  - MALICIOUS → 仅提示（NOTIFY_ONLY），等待用户决定；**绝不自动卸载/阻断**
 *  - 白名单命中 → 直接 CLEAN 放行
 */
enum class AutoPolicy {
    /** 默认：任何可疑/恶意都只提示，必须用户确认才处置 */
    NOTIFY_ONLY,

    /** 用户主动开启后：SUSPICIOUS 自动阻断，MALICIOUS 仍需用户确认 */
    AUTO_BLOCK_SUSPICIOUS,
}

class FalsePositiveGuard(
    private val whitelist: WhitelistStore,
    private val audit: VirusAuditLog,
    private val autoPolicy: AutoPolicy = AutoPolicy.NOTIFY_ONLY,
) {
    /**
     * 对已判定的报告应用白名单与自动处置策略。
     * 返回 (finalVerdict, autoAction) 二元组：
     *  - finalVerdict：考虑白名单后的最终 verdict（白名单命中→CLEAN）
     *  - suggestedAction：系统建议的自动处置动作；
     *    MALICIOUS/SUSPICIOUS/UNKNOWN 在 NOTIFY_ONLY 策略下均返回 NOTIFY_ONLY（仅提示）。
     */
    fun applyPolicy(subject: ScanSubject, report: VerdictReport): Pair<Verdict, DispositionAction> {
        // 白名单优先
        if (whitelist.isWhitelisted(subject.sha256, subject.packageName)) {
            val reasons = report.reasons + VerdictReason(
                ruleId = "WHITELIST",
                description = "用户已将此样本加入白名单，放行",
                severity = -200,
            )
            audit.record(
                subject = subject,
                verdict = Verdict.CLEAN,
                reasons = reasons,
                action = DispositionAction.WHITELIST,
                actor = DispositionActor.USER,
                result = DispositionResult.APPLIED,
                note = "白名单命中，自动放行",
            )
            return Verdict.CLEAN to DispositionAction.NOTIFY_ONLY
        }

        return when (report.verdict) {
            Verdict.CLEAN -> {
                audit.record(
                    subject, Verdict.CLEAN, report.reasons,
                    action = DispositionAction.NOTIFY_ONLY,
                    actor = DispositionActor.AUTOMATIC,
                    result = DispositionResult.SKIPPED,
                    note = "判定干净，无需处置",
                )
                Verdict.CLEAN to DispositionAction.NOTIFY_ONLY
            }
            Verdict.UNKNOWN -> {
                audit.record(
                    subject, Verdict.UNKNOWN, report.reasons,
                    action = DispositionAction.NOTIFY_ONLY,
                    actor = DispositionActor.AUTOMATIC,
                    result = DispositionResult.PENDING,
                    note = "信息不足，仅提示",
                )
                Verdict.UNKNOWN to DispositionAction.NOTIFY_ONLY
            }
            Verdict.SUSPICIOUS -> {
                val action = if (autoPolicy == AutoPolicy.AUTO_BLOCK_SUSPICIOUS)
                    DispositionAction.BLOCK else DispositionAction.NOTIFY_ONLY
                audit.record(
                    subject, Verdict.SUSPICIOUS, report.reasons,
                    action = action,
                    actor = if (action == DispositionAction.BLOCK) DispositionActor.AUTOMATIC else DispositionActor.AUTOMATIC,
                    result = DispositionResult.PENDING,
                    note = if (action == DispositionAction.BLOCK) "自动阻断" else "仅提示，等待用户决定",
                )
                Verdict.SUSPICIOUS to action
            }
            Verdict.MALICIOUS -> {
                // N-VIR-006 FR-001：无论策略如何，MALICIOUS 默认不得自动卸载/阻断
                audit.record(
                    subject, Verdict.MALICIOUS, report.reasons,
                    action = DispositionAction.NOTIFY_ONLY,
                    actor = DispositionActor.AUTOMATIC,
                    result = DispositionResult.PENDING,
                    note = "默认仅提示，不自动处置（N-VIR-006 FR-001）",
                )
                Verdict.MALICIOUS to DispositionAction.NOTIFY_ONLY
            }
        }
    }

    /**
     * 用户手动确认处置（BLOCK/UNINSTALL）或标记误报（WHITELIST）。
     * 记录审计 + 执行白名单变更（WHITELIST 时）。
     */
    fun userDecision(
        subject: ScanSubject,
        report: VerdictReport,
        action: DispositionAction,
        note: String? = null,
    ): AuditEntry {
        require(action in setOf(DispositionAction.BLOCK, DispositionAction.UNINSTALL,
            DispositionAction.WHITELIST, DispositionAction.REVERT, DispositionAction.NOTIFY_ONLY)) {
            "userDecision 只接受 BLOCK/UNINSTALL/WHITELIST/REVERT/NOTIFY_ONLY"
        }
        if (action == DispositionAction.WHITELIST) {
            whitelist.add(subject.sha256, subject.packageName)
        }
        return audit.record(
            subject = subject,
            verdict = if (action == DispositionAction.WHITELIST) Verdict.CLEAN else report.verdict,
            reasons = report.reasons,
            action = action,
            actor = DispositionActor.USER,
            result = DispositionResult.APPLIED,
            note = note,
        )
    }
}
