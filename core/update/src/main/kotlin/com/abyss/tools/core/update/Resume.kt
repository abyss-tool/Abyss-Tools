package com.abyss.tools.core.update

import java.io.File

/**
 * 下载断点续传决策（v0.7.8）—— 纯 JVM 可单测的决策核心。
 *
 * 背景：整包 APK（10-30 MB）经国内镜像链下载极易半途而废；此前
 * 直接写目标文件，任何一次中断都从零重下。现在统一走 `.part` 暂存
 * 文件 + HTTP Range 续传 + 成功后原子改名，传输健壮性与落盘完整性
 * 一次补齐（语义对齐 UPDATE_CHANNEL.md 的「镜像只承担通路角色」）。
 *
 * 决策表（[ResumePlanner.plan]）：
 *  - 无残留（partLength ≤ 0）        → RESTART（全量写）
 *  - 有残留 + 服务端命中 Range（206）→ APPEND（从 partLength 续写）
 *  - 有残留 + 服务端回全量（200）    → RESTART（镜像不支持 Range，截断重下）
 *  - 有残留 + 416 等异常码           → RESTART（part 已越界 / 损坏，重下）
 *
 * 纪律：只认 206 命中才续传 —— 任何歧义一律重下，绝不把错位字节拼进
 * APK（拼错的下场是 SHA-256 校验失败，重下是唯一诚实路径）。
 */
data class ResumePlan(
    val mode: Mode,
    val resumeFrom: Long,
) {
    enum class Mode { APPEND, RESTART }
}

object ResumePlanner {

    const val PART_SUFFIX = ".part"

    /** HTTP Range 命中（部分内容）。 */
    const val HTTP_PARTIAL_CONTENT = 206

    /**
     * @param partLength    已存在的暂存文件长度（0 = 无残留）
     * @param httpCode      本次响应码（200 全量 / 206 Range 命中）
     */
    fun plan(partLength: Long, httpCode: Int): ResumePlan = when {
        partLength <= 0L -> ResumePlan(ResumePlan.Mode.RESTART, 0L)
        httpCode == HTTP_PARTIAL_CONTENT -> ResumePlan(ResumePlan.Mode.APPEND, partLength)
        else -> ResumePlan(ResumePlan.Mode.RESTART, 0L)
    }

    /** 暂存文件与最终文件同目录同分区 —— renameTo 原子收尾才有保证。 */
    fun partFileOf(dest: File): File = File(dest.parentFile, dest.name + PART_SUFFIX)
}
