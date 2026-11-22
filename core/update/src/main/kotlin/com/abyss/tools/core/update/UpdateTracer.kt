package com.abyss.tools.core.update

import com.abyss.tools.core.common.diagnostics.DiagnosticsLog
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * N-UPD-003 · 更新链埋点器
 *
 * - 每个更新会话一个 UpdateTracer 实例，记录全链路结构化事件
 * - 事件同时写入 DiagnosticsLog（落盘）与内存 listeners（供UI消费）
 * - 失败时自动归类并汇总本次会话统计
 */
class UpdateTracer(
    private val source: String = "app", // "app" | "plugin" | "patch"
    private val kind: DiagnosticsLog.Kind = DiagnosticsLog.Kind.UPDATE_CHECK
) {
    private val events = CopyOnWriteArrayList<UpdateEvent>()
    private val startedAt = AtomicLong(System.currentTimeMillis())
    private val sessionId = nextSessionId()

    private fun logInfo(msg: String) =
        DiagnosticsLog.info(kind, "UpdateTracer", "[sess=$sessionId src=$source] $msg")
    private fun logWarn(msg: String) =
        DiagnosticsLog.warn(kind, "UpdateTracer", "[sess=$sessionId src=$source] $msg")

    fun emit(event: UpdateEvent) {
        events.add(event)
        logInfo(event.toJson())
        listeners.forEach { runCatching { it(event) } }
        if (event.phase == UpdateEvent.Phase.FAILED) {
            val err = event.error
            if (err != null) {
                logWarn("FAILED reason=${err.reason.name} recoverable=${err.reason.recoverable} msg=${err.message.take(200)}")
            }
        }
    }

    fun start() {
        startedAt.set(System.currentTimeMillis())
        events.clear()
        emit(UpdateEvent(phase = UpdateEvent.Phase.CHECK_STARTED,
            extra = mapOf("session" to sessionId)))
    }

    fun mirrorProbeStarted() =
        emit(UpdateEvent(phase = UpdateEvent.Phase.MIRROR_PROBE_STARTED))

    fun mirrorSelected(id: String, latencyMs: Long) =
        emit(UpdateEvent(phase = UpdateEvent.Phase.MIRROR_SELECTED,
            mirrorId = id, latencyMs = latencyMs))

    fun indexFetched(mirrorId: String, latencyMs: Long, httpStatus: Int) =
        emit(UpdateEvent(phase = UpdateEvent.Phase.INDEX_FETCHED,
            mirrorId = mirrorId, latencyMs = latencyMs, httpStatus = httpStatus))

    fun signatureVerified(mirrorId: String) =
        emit(UpdateEvent(phase = UpdateEvent.Phase.INDEX_SIGNATURE_VERIFIED, mirrorId = mirrorId))

    fun downloadStarted(mirrorId: String, bytesTotal: Long) =
        emit(UpdateEvent(phase = UpdateEvent.Phase.DOWNLOAD_STARTED,
            mirrorId = mirrorId, bytesTotal = bytesTotal))

    fun downloadProgress(mirrorId: String, downloaded: Long, total: Long) =
        emit(UpdateEvent(phase = UpdateEvent.Phase.DOWNLOAD_PROGRESS,
            mirrorId = mirrorId, bytesDownloaded = downloaded, bytesTotal = total))

    fun downloadResumed(mirrorId: String, existing: Long, total: Long) =
        emit(UpdateEvent(phase = UpdateEvent.Phase.DOWNLOAD_RESUMED,
            mirrorId = mirrorId, bytesDownloaded = existing, bytesTotal = total,
            extra = mapOf("mode" to "resume")))

    fun downloadRestarted(mirrorId: String, retry: Int, max: Int) =
        emit(UpdateEvent(phase = UpdateEvent.Phase.DOWNLOAD_RESTARTED,
            mirrorId = mirrorId, retryCount = retry, maxRetries = max,
            extra = mapOf("mode" to "restart")))

    fun downloadCompleted(mirrorId: String, bytes: Long, latencyMs: Long, retry: Int) =
        emit(UpdateEvent(phase = UpdateEvent.Phase.DOWNLOAD_COMPLETED,
            mirrorId = mirrorId, bytesDownloaded = bytes, latencyMs = latencyMs, retryCount = retry))

    fun chunkVerified(mirrorId: String, bytes: Long, latencyMs: Long) =
        emit(UpdateEvent(phase = UpdateEvent.Phase.CHUNK_VERIFIED,
            mirrorId = mirrorId, bytesDownloaded = bytes, latencyMs = latencyMs))

    fun installStarted() =
        emit(UpdateEvent(phase = UpdateEvent.Phase.INSTALL_STARTED))

    fun installCompleted(latencyMs: Long) =
        emit(UpdateEvent(phase = UpdateEvent.Phase.INSTALL_COMPLETED, latencyMs = latencyMs))

    fun failed(failure: UpdateFailure, mirrorId: String? = null, retry: Int = 0, max: Int = 0) {
        val total = System.currentTimeMillis() - startedAt.get()
        emit(UpdateEvent(phase = UpdateEvent.Phase.FAILED,
            mirrorId = mirrorId, retryCount = retry, maxRetries = max,
            latencyMs = total, error = failure))
    }

    fun allEvents(): List<UpdateEvent> = events.toList()

    fun summary(): UpdateSessionSummary {
        val totalLatency = events.lastOrNull()?.let { it.ts - startedAt.get() } ?: 0
        val mirrorId = events.firstNotNullOfOrNull { it.mirrorId }
        val totalBytes = events.filter { it.bytesTotal > 0 }.lastOrNull()?.bytesTotal ?: 0
        val retries = events.count { it.phase == UpdateEvent.Phase.DOWNLOAD_RESTARTED }
        val error = events.firstOrNull { it.phase == UpdateEvent.Phase.FAILED }?.error
        return UpdateSessionSummary(
            sessionId = sessionId, source = source,
            success = error == null && events.any { it.phase == UpdateEvent.Phase.INSTALL_COMPLETED },
            mirrorId = mirrorId, totalLatencyMs = totalLatency,
            totalBytes = totalBytes, retries = retries,
            error = error, eventCount = events.size
        )
    }

    private val listeners = CopyOnWriteArrayList<(UpdateEvent) -> Unit>()
    fun addListener(l: (UpdateEvent) -> Unit) { listeners.add(l) }

    companion object {
        private val counter = AtomicLong(0)
        private fun nextSessionId(): String {
            val n = counter.incrementAndGet()
            return "U${System.currentTimeMillis().toString(36).takeLast(6)}-$n"
        }
    }
}

data class UpdateSessionSummary(
    val sessionId: String,
    val source: String,
    val success: Boolean,
    val mirrorId: String?,
    val totalLatencyMs: Long,
    val totalBytes: Long,
    val retries: Int,
    val error: UpdateFailure?,
    val eventCount: Int
)
