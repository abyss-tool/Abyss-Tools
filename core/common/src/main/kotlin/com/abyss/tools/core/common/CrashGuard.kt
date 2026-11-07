package com.abyss.tools.core.common

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 崩溃防护网（v0.7.0，P0）—— 秒闪退可诊断化的最后一道防线。
 *
 * 背景：v0.3.0 用户报告「进不去秒闪退」却无堆栈可查。本类把未捕获异常
 * 全量落盘到 files/crash/ 目录（保留最近 [MAX_LOGS] 份），下次启动由
 * 设置页「诊断」入口查看 / 分享 —— 侧载渠道没有 Play 统计，崩溃现场
 * 只能靠本地取证。
 *
 * 设计纪律：
 *  - 处理器自身【绝不抛】（任何 IO 失败静默吞掉 —— 崩溃处理器崩溃是
 *    最讽刺的死循环）；
 *  - 链式回退：装完后把原 default handler 接在链尾，系统原有行为
 *    （进程崩溃 / ANR 上报）不受影响；
 *  - 纯 Kotlin（java.io），:app 与任何宿主环境都能挂载。
 */
object CrashGuard {

    private val TS_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())

    const val CRASH_DIR = "crash"
    const val FILE_PREFIX = "crash-"
    const val FILE_SUFFIX = ".txt"
    const val LAST_CRASH_FILE = "last-crash.txt"

    /** 崩溃日志留存上限（旧文件滚动清理，防磁盘膨胀）。 */
    const val MAX_LOGS = 8

    @Volatile
    private var installed = false

    /** 挂载全局处理器（幂等；Application.onCreate 调一次即可）。 */
    fun install(crashRoot: File) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeReport(crashRoot, thread, throwable) }
            // 链尾回退：保持系统默认崩溃行为（进程终止 + 系统 dialog）
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 写一份崩溃报告（时间 / 线程 / 版本 / 堆栈全量）。 */
    fun writeReport(crashRoot: File, thread: Thread, throwable: Throwable) {
        val dir = File(crashRoot, CRASH_DIR).apply { mkdirs() }
        val timestamp = TS_FORMAT.format(Instant.now())
        val report = buildString {
            appendLine("════════════════════════════════════════════════")
            appendLine("深渊工具 崩溃报告")
            appendLine("时间:   $timestamp")
            appendLine("版本:   v${AppInfo.VERSION_NAME} (code ${AppInfo.VERSION_CODE})")
            appendLine("线程:   ${thread.name} (id ${thread.id})")
            appendLine("设备:   Android ${System.getProperty("os.version") ?: "?"}")
            appendLine("════════════════════════════════════════════════")
            appendLine()
            appendLine(throwable.stackTraceToString())
        }
        // 1) 独立档案（滚动保留）
        val archive = File(dir, "$FILE_PREFIX$timestamp$FILE_SUFFIX")
        archive.writeText(report)
        // 2) last-crash.txt 指针文件（设置页「诊断」直接读这份）
        File(dir, LAST_CRASH_FILE).writeText(report)
        // 3) 滚动清理：只留最近 MAX_LOGS 份档案
        dir.listFiles { f -> f.name.startsWith(FILE_PREFIX) }
            ?.sortedByDescending { it.name }
            ?.drop(MAX_LOGS)
            ?.forEach { runCatching { it.delete() } }
    }

    /** 最近一次崩溃报告（无崩溃 / 读取失败 → null）。 */
    fun lastReport(crashRoot: File): String? = runCatching {
        val file = File(File(crashRoot, CRASH_DIR), LAST_CRASH_FILE)
        if (file.exists()) file.readText() else null
    }.getOrNull()

    /** 全部崩溃档案列表（新的在前；空目录 → 空表）。 */
    fun allReports(crashRoot: File): List<File> =
        File(crashRoot, CRASH_DIR)
            .listFiles { f -> f.name.startsWith(FILE_PREFIX) }
            ?.sortedByDescending { it.name }
            .orEmpty()

    /** 清空全部崩溃档案（设置页「清除诊断记录」）。 */
    fun clear(crashRoot: File): Boolean =
        File(crashRoot, CRASH_DIR).deleteRecursively()
}
