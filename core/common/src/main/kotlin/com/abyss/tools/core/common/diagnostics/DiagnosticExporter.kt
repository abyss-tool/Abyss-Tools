package com.abyss.tools.core.common.diagnostics

import java.io.File

/**
 * 诊断日志导出适配器（N-EXP-003，M1）。
 *
 * 把 [DiagnosticsLog] 的读写能力收敛到一个供 UI 层直接依赖的门面上：
 * 「诊断中心」页面通过本对象读取最近事件、触发导出、清空日志，无需直接
 * 触碰日志实现；导出目标路径的组装也在这一层完成。
 *
 * 零外传原则不变：导出只写本地文件，是否对外分享由用户在诊断中心显式发起。
 */
object DiagnosticExporter {

    @Volatile
    private var source: DiagnosticsLog? = null

    /** 绑定日志来源。App 启动装配阶段（ServiceRegistry）调用一次。 */
    fun bind(log: DiagnosticsLog) {
        source = log
    }

    /** 是否已绑定日志来源。 */
    val isBound: Boolean get() = source != null

    /** 最近 N 条事件（时间升序）。未绑定返回空列表。 */
    fun recent(limit: Int = 50): List<DiagnosticsLog.Event> =
        source?.recent(limit) ?: emptyList()

    /** 按类别统计 ERROR 数，供诊断页总览。 */
    fun errorCounts(): Map<DiagnosticsLog.Kind, Int> =
        source?.errorCounts() ?: emptyMap()

    /**
     * 导出全部日志到 [dest]。
     * @return 导出的目标文件；未绑定或导出失败返回 null
     */
    fun export(dest: File): File? = source?.exportTo(dest)

    /** 便捷导出：写入 [dir] 下带时间戳的文件名。 */
    fun exportToDir(dir: File, timestampMs: Long = System.currentTimeMillis()): File? =
        export(File(dir, "abyss-diag-$timestampMs.log"))

    /** 清空内存缓冲 + 磁盘日志（用户主动操作后）。 */
    fun clear() {
        source?.clear()
    }
}
