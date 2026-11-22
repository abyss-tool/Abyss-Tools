package com.abyss.tools.core.update

/**
 * N-UPD-003 · 更新链结构化事件
 *
 * 覆盖从「发起检查」到「安装完成/失败」的全链路关键节点。每个事件都包含：
 * - phase: 当前阶段
 * - mirrorId: 命中的镜像 ID（可选）
 * - latencyMs: 本阶段耗时
 * - bytesDownloaded / bytesTotal: 下载进度
 * - retryCount / maxRetries: 重试计数
 * - error: 失败时的分类原因与消息
 */
data class UpdateEvent(
    val phase: Phase,
    val mirrorId: String? = null,
    val latencyMs: Long = 0L,
    val bytesDownloaded: Long = 0L,
    val bytesTotal: Long = 0L,
    val retryCount: Int = 0,
    val maxRetries: Int = 0,
    val httpStatus: Int? = null,
    val rangeSupport: Boolean? = null,
    val error: UpdateFailure? = null,
    val extra: Map<String, String> = emptyMap(),
    val ts: Long = System.currentTimeMillis()
) {
    enum class Phase {
        CHECK_STARTED,
        INDEX_FETCHED,
        INDEX_SIGNATURE_VERIFIED,
        MIRROR_PROBE_STARTED,
        MIRROR_SELECTED,
        DOWNLOAD_STARTED,
        DOWNLOAD_PROGRESS,
        DOWNLOAD_RESUMED,
        DOWNLOAD_RESTARTED,
        DOWNLOAD_COMPLETED,
        CHUNK_VERIFIED,
        PATCH_APPLIED,
        INSTALL_STARTED,
        INSTALL_COMPLETED,
        FAILED
    }

    fun toJson(): String = buildString {
        append("{\"phase\":\"${phase.name}\"")
        mirrorId?.let { append(",\"mirror\":\"$it\"") }
        if (latencyMs > 0) append(",\"latencyMs\":$latencyMs")
        if (bytesDownloaded > 0) append(",\"bytesDownloaded\":$bytesDownloaded")
        if (bytesTotal > 0) append(",\"bytesTotal\":$bytesTotal")
        if (retryCount > 0) append(",\"retry\":$retryCount")
        httpStatus?.let { append(",\"http\":$it") }
        rangeSupport?.let { append(",\"rangeSupport\":$it") }
        error?.let { append(",\"error\":{\"reason\":\"${it.reason.name}\",\"message\":\"${escape(it.message)}\"}") }
        if (extra.isNotEmpty()) {
            append(",\"extra\":{")
            append(extra.entries.joinToString(",") { (k, v) -> "\"$k\":\"${escape(v)}\"" })
            append("}")
        }
        append("}")
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
