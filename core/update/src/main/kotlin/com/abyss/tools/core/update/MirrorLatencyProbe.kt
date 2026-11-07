package com.abyss.tools.core.update

import com.abyss.tools.core.common.DownloadMirror
import com.abyss.tools.core.common.MirrorCatalog
import com.abyss.tools.core.common.MirrorStrategy
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 镜像延迟探测（v0.7.0：国内镜像 + 测延迟）。
 *
 * 方法论（诚实测速，不测「域名能不能 ping」这种假指标）：
 *  - 对**真实资产路径**发 HEAD 请求（Range: bytes=0-0 兜底 —— 部分反代
 *    不响应裸 HEAD），度量 TTFB（连接 + 首字节往返）；
 *  - 全镜像**并发**探测（总耗时 = 最慢一个的超时，而非串行累加）；
 *  - 探测结果 [MirrorProbeResult]：可达镜像带毫秒延迟，不可达 / 超时 /
 *    4xx-5xx 如实标记失败 —— UI 直接消费排序展示。
 *
 * 纯 JVM（OkHttp），单测注入 FakeTransport 零网络验证。
 */
data class MirrorProbeResult(
    val mirror: DownloadMirror,
    /** 可达性：null = 不可达 / 超时 / 非 2xx-3xx。 */
    val latencyMs: Long?,
) {
    val reachable: Boolean get() = latencyMs != null
}

class MirrorLatencyProbe(
    private val probeTimeoutMs: Long = 4_000,
) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(probeTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(probeTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(probeTimeoutMs * 2, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    /**
     * 并发探测一组镜像对同一资产的延迟。
     *
     * @param assetUrl 真实资产 URL（GitHub 原始形态；镜像各自改写）
     * @param mirrors  待探测镜像（缺省 = 目录中服务该资产形态的全量）
     */
    suspend fun probeAll(
        assetUrl: String,
        mirrors: List<DownloadMirror>? = null,
    ): List<MirrorProbeResult> = coroutineScope {
        val kind = MirrorCatalog.assetKindOf(assetUrl) ?: return@coroutineScope emptyList()
        val candidates = mirrors ?: MirrorCatalog.forAsset(kind)
        candidates
            .map { mirror -> async { MirrorProbeResult(mirror, probe(mirror, assetUrl)) } }
            .awaitAll()
            .sortedWith(compareBy({ it.latencyMs == null }, { it.latencyMs ?: Long.MAX_VALUE }))
    }

    /** 单镜像探测：TTFB（毫秒）；不可达 / 超时 / 非 2xx-3xx → null。绝不抛。 */
    suspend fun probe(mirror: DownloadMirror, assetUrl: String): Long? {
        val kind = MirrorCatalog.assetKindOf(assetUrl) ?: return null
        if (kind !in mirror.appliesTo) return null // 镜像不服务该资产形态：如实不可用
        val url = MirrorCatalog.rewrite(mirror, assetUrl)
        return withTimeoutOrNull(probeTimeoutMs) {
            runCatching {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val started = System.nanoTime()
                    val request = Request.Builder()
                        .url(url)
                        .head()
                        .header("Range", "bytes=0-0")
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        (System.nanoTime() - started) / 1_000_000
                    }
                }
            }.getOrNull()
        }
    }
}
