package com.abyss.tools.core.update

import com.abyss.tools.core.common.DownloadMirror
import com.abyss.tools.core.common.MIRROR_AUTO
import com.abyss.tools.core.common.MirrorCatalog
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 镜像路由器（v0.7.0）：统一决策「这个 URL 走哪个镜像」。
 *
 * 决策输入：
 *  - 用户偏好（[preference]，id 或 [MIRROR_AUTO]；由宿主设置页注入）；
 *  - 探测缓存（[probeCache]：UI「测速」按钮刷新；MirroredTransport 首
 *    次自动择优时懒探测一次，[CACHE_TTL_MS] 内复用）。
 *
 * 决策输出：**有序镜像列表**（首元素 = 首选，其余为回退链）。
 *  - 手动锁定：[锁定镜像, DIRECT]（锁定镜像挂了回退直连，绝不死磕）；
 *  - 自动：可达镜像按延迟升序 + 不可达镜像殿后（DIRECT 恒在列殿底 ——
 *    探测全部失败时至少直连一试，诚实降级）。
 */
class MirrorRouter {

    /**
     * 用户偏好镜像 id（或 "auto"）的实时读取口（宿主注入闭包指向设置存
     * 储 —— 设置页改动即刻生效，无需重启 / 手动同步）。
     */
    @Volatile
    var preferenceProvider: () -> String = { MIRROR_AUTO }

    /** 探测缓存：mirror.id → 延迟毫秒（null 值记录「不可达」）。 */
    @Volatile
    var probeCache: Map<String, Long?> = emptyMap()
        set(value) {
            field = value
            cacheUpdatedAt = System.currentTimeMillis()
        }

    @Volatile
    private var cacheUpdatedAt: Long = 0L

    /** 首选镜像（手动锁定 / 自动无缓存时的目录序首个）。 */
    fun preferredMirror(url: String): DownloadMirror {
        val kind = MirrorCatalog.assetKindOf(url) ?: return MirrorCatalog.DIRECT
        val candidates = MirrorCatalog.forAsset(kind)
        val manual = MirrorCatalog.byId(preferenceProvider())
        if (manual != null && kind in manual.appliesTo) return manual
        return candidates.firstOrNull() ?: MirrorCatalog.DIRECT
    }

    /**
     * 有序镜像列表（首选在前，回退链在后；DIRECT 恒在列）。
     *
     * 自动模式且缓存过期时同步探测一次（调用方已在 IO 线程；探测并发
     * 全镜像，总耗时 ≤ 单镜像超时）。
     */
    fun orderedMirrors(url: String, lazyProbe: Boolean = true): List<DownloadMirror> {
        val kind = MirrorCatalog.assetKindOf(url) ?: return listOf(MirrorCatalog.DIRECT)
        val candidates = MirrorCatalog.forAsset(kind)

        val manual = MirrorCatalog.byId(preferenceProvider())
        if (manual != null && kind in manual.appliesTo) {
            return if (manual.strategy == com.abyss.tools.core.common.MirrorStrategy.DIRECT) {
                listOf(MirrorCatalog.DIRECT)
            } else {
                listOf(manual, MirrorCatalog.DIRECT)
            }
        }

        // 自动模式：缓存新鲜 → 按延迟排序；过期 → 懒探测（IO 语境安全）
        if (System.currentTimeMillis() - cacheUpdatedAt > CACHE_TTL_MS) {
            if (lazyProbe) refreshProbeCache(url)
        }
        val cache = probeCache
        if (cache.isEmpty()) return candidates // 探测失败：目录序直试
        return candidates.sortedWith(
            compareBy(
                { (cache[it.id] == null) },        // 不可达殿后
                { cache[it.id] ?: Long.MAX_VALUE }, // 延迟升序
            ),
        )
    }

    /** 并发探测全部候选镜像并写缓存（探测对象 = url 的真实资产路径）。 */
    fun refreshProbeCache(url: String) {
        val kind = MirrorCatalog.assetKindOf(url) ?: return
        val candidates = MirrorCatalog.forAsset(kind)
        val results = ConcurrentHashMap<String, Long?>()
        val threads = candidates.map { mirror ->
            Thread {
                results[mirror.id] = probeSync(mirror, url)
            }.apply { isDaemon = true; start() }
        }
        threads.forEach { runCatching { it.join(PROBE_JOIN_MS) } }
        probeCache = results.toMap()
    }

    /** 同步单镜像探测（TTFB 毫秒；失败 → null）。HEAD + Range 兜底。 */
    private fun probeSync(mirror: DownloadMirror, assetUrl: String): Long? = runCatching {
        val url = MirrorCatalog.rewrite(mirror, assetUrl)
        val started = System.nanoTime()
        val request = Request.Builder()
            .url(url)
            .head()
            .header("Range", "bytes=0-0")
            .build()
        probeClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            (System.nanoTime() - started) / 1_000_000
        }
    }.getOrNull()

    private companion object {
        /** 探测缓存有效期（自动模式复用；UI 手动「测速」可随时强刷）。 */
        const val CACHE_TTL_MS = 10 * 60 * 1000L

        /** 单探测线程 join 上界（防单个镜像拖死整批排序）。 */
        const val PROBE_JOIN_MS = 5_000L

        val probeClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}

/**
 * 镜像感知传输装饰器（v0.7.0）：包住任意 [HttpTransport]，使其获得
 * 「按路由顺序逐镜像尝试 + 失败自动回退」能力。
 *
 * 接入方式（:app 装配处一行）：所有既有消费方（UpdateManager /
 * PluginRepository / PluginInstaller —— 它们只依赖 HttpTransport 接口）
 * 注入 `MirroredTransport(OkHttpTransport(), router)` 即全链路生效，
 * 零调用点改造。
 *
 * 语义：
 *  - 逐镜像尝试（路由器序），首个成功即返回；
 *  - 单镜像失败（网络 / 4xx-5xx）→ 下一镜像；全部失败 → 抛出**最后一次**
 *    的异常（如实上报，不吞错）；
 *  - 下载中断（半途 IOException）→ 换下一镜像**从零重下**（dest 覆写；
 *    SHA-256 门禁在管线后置校验，不可能落盘半截污染）。
 */
class MirroredTransport(
    private val base: HttpTransport,
    private val router: MirrorRouter,
) : HttpTransport {

    /** 短时失败记忆（mirror.id → 失败时刻）：冷却期内跳过，防全链重复超时。 */
    private val recentFailures = java.util.concurrent.ConcurrentHashMap<String, Long>()

    override fun get(url: String): String {
        // 非 GitHub 域（本地 / 未来其它通道）不改写，直通
        if (MirrorCatalog.assetKindOf(url) == null) return base.get(url)
        var lastError: Exception? = null
        var chain = router.orderedMirrors(url, lazyProbe = false) // 热路径不隐式探测（UI 负责填缓存）
        // 冷却期内的失败镜像跳过；但至少保留链尾一个（防全跳过成空链）
        val cooled = chain.filter { cooledOut(it.id) }
        if (cooled.isNotEmpty()) chain = cooled
        for (mirror in chain) {
            try {
                val result = base.get(MirrorCatalog.rewrite(mirror, url))
                recentFailures.remove(mirror.id)
                return result
            } catch (e: Exception) {
                lastError = e
                recentFailures[mirror.id] = System.currentTimeMillis()
            }
        }
        throw lastError ?: IllegalStateException("镜像链为空：$url")
    }

    override fun download(
        url: String,
        dest: File,
        onProgress: (readBytes: Long, totalBytes: Long) -> Unit,
    ) {
        if (MirrorCatalog.assetKindOf(url) == null) {
            return base.download(url, dest, onProgress)
        }
        var lastError: Exception? = null
        var chain = router.orderedMirrors(url, lazyProbe = false)
        val cooled = chain.filter { cooledOut(it.id) }
        if (cooled.isNotEmpty()) chain = cooled
        for (mirror in chain) {
            try {
                base.download(MirrorCatalog.rewrite(mirror, url), dest, onProgress)
                recentFailures.remove(mirror.id)
                return
            } catch (e: Exception) {
                lastError = e
                recentFailures[mirror.id] = System.currentTimeMillis()
            }
        }
        throw lastError ?: IllegalStateException("镜像链为空：$url")
    }

    /** 该镜像是否仍在失败冷却期内（2 分钟）。 */
    private fun cooledOut(id: String): Boolean {
        val failedAt = recentFailures[id] ?: return true
        return System.currentTimeMillis() - failedAt > FAILURE_COOLDOWN_MS
    }

    private companion object {
        /** 单镜像失败冷却期：期间不再重试（防每次下载都挂同一批超时）。 */
        const val FAILURE_COOLDOWN_MS = 2 * 60 * 1000L
    }
}
