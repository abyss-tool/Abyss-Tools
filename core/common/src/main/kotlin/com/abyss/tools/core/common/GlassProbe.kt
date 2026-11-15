package com.abyss.tools.core.common

import java.io.File

/**
 * 玻璃管线探针（v0.7.8）—— RenderThread 原生崩溃的进程内取证。
 *
 * 背景：v0.7.4 三道防线（自嵌套 / 成环断路器 / 深度预算）落地后，玻璃档
 * 仍连崩触发降级 —— 而 [CrashGuard] 只能捕获 Java 层异常，RenderThread
 * SIGSEGV 不走 UncaughtExceptionHandler，诊断页必然空白，崩溃现场永远
 * 拿不到。本探针在玻璃管线的每个咽喉处落里程碑（Glass.kt 效果链挂载处）：
 *
 * ```
 * session-start → vibrancy-applied → blur-applied → lens-applied
 *               → first-frame-drawn
 * ```
 *
 * 崩溃后，下一次启动在诊断页读探针文件的**最后一行**即知进程死在管线
 * 的哪一步：
 *  - 无记录 / 停在 session-start → 玻璃管线尚未跑起来（非玻璃效果类崩溃）；
 *  - 停在 lens-applied 且无 first-frame-drawn → 效果链已挂上、GPU 首帧
 *    执行期死亡 —— AGSL RuntimeShader（lens）是各 OEM GPU 驱动最脆弱的
 *    一环，首要嫌疑；
 *  - 停在 first-frame-drawn 之后 → 会话中期崩溃（慢性增长 / 驱动不稳类），
 *    时间戳差值即存活时长，配合 RenderSafeMode 连崩计数定位。
 *
 * 纪律（对齐 CrashGuard / RenderSafeMode）：
 *  - 纯 Kotlin（java.io），core:common 保持纯 JVM 可单测；
 *  - 一切 IO runCatching 静默兜底 —— 探针自身【绝不抛】、绝不成为新的
 *    崩溃源（fail-open：未 install / 读不到 = 无操作）；
 *  - 单文件写穿（≤ [MAX_LINES] 行，每会话数条），无后台线程、无锁竞争面。
 */
object GlassProbe {

    const val FILE_NAME = "glass-probe.txt"

    /** 留存行数上限（滚动丢弃最旧）—— 跨会话累积有界，防磁盘膨胀。 */
    const val MAX_LINES = 64

    // ── 里程碑常量（文件内的人类可读阶段名）─────────────────────────
    const val STAGE_SESSION_START = "session-start"
    const val STAGE_VIBRANCY = "vibrancy-applied"
    const val STAGE_BLUR = "blur-applied"
    const val STAGE_LENS = "lens-applied"

    /** NO_LENS 档（RenderSafeMode.GlassMode 分级降级）跳过 lens 的标记。 */
    const val STAGE_LENS_SKIPPED = "lens-skipped"
    const val STAGE_FIRST_FRAME = "first-frame-drawn"

    @Volatile
    private var probeFile: File? = null

    /** 挂载（幂等；Activity 早期调用一次，早于任何玻璃组合）。 */
    fun install(file: File) {
        probeFile = file
    }

    /**
     * 落一个里程碑。**同阶段连续重复自动去重** —— 逐帧绘制路径
     * （first-frame-drawn 每帧执行）不刷屏；跨过其它阶段后再次出现则
     * 照常落（保留真实时序）。
     */
    fun mark(stage: String, now: Long = System.currentTimeMillis()) {
        val file = probeFile ?: return
        runCatching {
            synchronized(this@GlassProbe) {
                val lines = if (file.exists()) {
                    file.readLines().filter { it.isNotBlank() }
                } else {
                    emptyList()
                }
                if (lines.lastOrNull()?.substringAfter('|') == stage) return
                val next = lines + "$now|$stage"
                val bounded = if (next.size > MAX_LINES) next.drop(next.size - MAX_LINES) else next
                file.writeText(bounded.joinToString(separator = "\n", postfix = "\n"))
            }
        }
    }

    /** 读现场（全部行，旧的在前；无文件 / 读取失败 → 空表，fail-open）。 */
    fun readAll(): List<String> = runCatching {
        probeFile?.takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }
    }.getOrNull().orEmpty()

    /** 诊断页「清除探针现场」：与崩溃记录同款清空语义。 */
    fun clear(): Boolean {
        val file = probeFile ?: return false
        return runCatching { file.delete() }.getOrDefault(false)
    }
}
