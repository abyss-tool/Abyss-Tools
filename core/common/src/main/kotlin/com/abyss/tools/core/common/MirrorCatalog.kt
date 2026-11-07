package com.abyss.tools.core.common

/**
 * ═══════════════════════════════════════════════════════════════════════
 * 下载镜像目录（v0.7.0：国内镜像 + 测延迟）
 * ═══════════════════════════════════════════════════════════════════════
 *
 * 背景：GitHub Release 资产 / raw.githubusercontent.com 在中国大陆裸连
 * 极不稳定（超时 / 半途断流），插件 APK 下载几乎必然失败。本目录定义
 * 一组「GitHub 反代镜像」，插件中心按延迟实测自动择优，也可手动锁定。
 *
 * 镜像形态（两种，全部 https）：
 *  - [Strategy.PREFIX]：最终 URL = prefix + 原始 URL（ghproxy 系反代，
 *    同时支持 github.com 资产与 raw.githubusercontent.com 文本）；
 *  - [Strategy.HOST_SWAP]：host 等价替换（raw.gitmirror.com 与
 *    raw.githubusercontent.com 同路径同内容）。
 *
 * 信任锚（不因引入镜像而松动）：
 *  1. 镜像域白名单收敛在 [MIRROR_HOSTS]（FetchHostPolicy 联动校验，
 *     白名单外拒绝）；
 *  2. 一切经镜像下载的资产仍强制 SHA-256 校验（下载管线 fail-closed，
 *     镜像被劫持最多造成「下载失败」，不可能植入被篡改的载荷）。
 */
enum class MirrorStrategy { DIRECT, PREFIX, HOST_SWAP }

/**
 * 单个镜像定义。
 *
 * @param id        持久化标识（设置存储 / 索引用它，改动即失效回退）
 * @param label     UI 展示名
 * @param strategy  改写策略
 * @param prefix    PREFIX 形态的镜像前缀（DIRECT / HOST_SWAP 为 null）
 * @param swapHost  HOST_SWAP 形态的目标 host（其余为 null）
 * @param appliesTo 该镜像可服务的资产形态（Release 资产 / raw 文本）
 */
data class DownloadMirror(
    val id: String,
    val label: String,
    val strategy: MirrorStrategy,
    val prefix: String? = null,
    val swapHost: String? = null,
    val appliesTo: Set<MirrorAssetKind> = MirrorAssetKind.ALL,
) {
    /** 该镜像的请求 host（延迟探测与白名单校验用）。 */
    val host: String = when (strategy) {
        MirrorStrategy.DIRECT -> ""
        MirrorStrategy.PREFIX -> prefix!!.removePrefix("https://").removeSuffix("/")
        MirrorStrategy.HOST_SWAP -> swapHost!!
    }
}

/** 资产形态：Release 下载页（github.com）与 raw 文本（raw.githubusercontent.com）。 */
enum class MirrorAssetKind { RELEASE, RAW;

    companion object {
        val ALL: Set<MirrorAssetKind> = setOf(RELEASE, RAW)
    }
}

/** 手动选择时的「自动」哨兵（存储层的 magic string 收敛在此）。 */
const val MIRROR_AUTO = "auto"

object MirrorCatalog {

    /** GitHub 直连（无改写；延迟最差时的兜底，永不下架）。 */
    val DIRECT = DownloadMirror(
        id = "direct",
        label = "GitHub 直连",
        strategy = MirrorStrategy.DIRECT,
    )

    /**
     * ghproxy 系反代（PREFIX 形态，github.com + raw 双支持）。
     *
     * 清单纪律：只收录 2026 年社区公认存活且无需注册的公共反代；排序即
     * 「自动择优时的探测顺序偏好」。任何镜像长期不可用直接从表里删 ——
     * 镜像死亡是常态，目录必须可演进。
     */
    private val ghProxies = listOf(
        DownloadMirror(
            id = "ghfast",
            label = "ghfast.top",
            strategy = MirrorStrategy.PREFIX,
            prefix = "https://ghfast.top/",
        ),
        DownloadMirror(
            id = "ghproxy-net",
            label = "ghproxy.net",
            strategy = MirrorStrategy.PREFIX,
            prefix = "https://ghproxy.net/",
        ),
        DownloadMirror(
            id = "gh-proxy-com",
            label = "gh-proxy.com",
            strategy = MirrorStrategy.PREFIX,
            prefix = "https://gh-proxy.com/",
        ),
        DownloadMirror(
            id = "ghproxy-cn",
            label = "ghproxy.cn",
            strategy = MirrorStrategy.PREFIX,
            prefix = "https://ghproxy.cn/",
        ),
    )

    /** raw 文本的 host 等价镜像（raw.gitmirror.com ← raw.githubusercontent.com）。 */
    private val rawSwaps = listOf(
        DownloadMirror(
            id = "raw-gitmirror",
            label = "raw.gitmirror.com",
            strategy = MirrorStrategy.HOST_SWAP,
            swapHost = "raw.gitmirror.com",
            appliesTo = setOf(MirrorAssetKind.RAW),
        ),
    )

    /** 全量目录（UI 列表与白名单的单一事实源）。 */
    val all: List<DownloadMirror> = listOf(DIRECT) + ghProxies + rawSwaps

    /** 镜像域白名单（FetchHostPolicy 消费：白名单外 host 一律拒绝）。 */
    val MIRROR_HOSTS: Set<String> = all
        .filter { it.strategy != MirrorStrategy.DIRECT }
        .map { it.host }
        .toSet()

    /** 按 id 找镜像（未知 id / DIRECT → null，调用方回退直连）。 */
    fun byId(id: String?): DownloadMirror? =
        if (id == null || id == MIRROR_AUTO) null else all.firstOrNull { it.id == id }

    /** 适用于某种资产形态的全部镜像（DIRECT 恒在列）。 */
    fun forAsset(kind: MirrorAssetKind): List<DownloadMirror> {
        val candidates = all.filter { kind in it.appliesTo }
        // 缺省序（无探测缓存时）：反代在前（主受众为大陆网络，直连几乎
        // 必败且要挂 15s 连接超时），DIRECT 殿底兜底（非大陆用户最终可达）。
        // 探测缓存存在时由 MirrorRouter 按延迟重排，本序仅为冷启动缺省。
        return candidates.sortedBy { it.strategy == MirrorStrategy.DIRECT }
    }

    /**
     * 把原始 GitHub URL 改写为该镜像下的实际请求 URL。
     *
     * 资产形态自动判定：host 是 github.com → RELEASE；是
     * raw.githubusercontent.com → RAW。镜像不服务该形态（或 URL 本就不是
     * GitHub 域）→ 原样返回（调用方按直连语义处理，绝不猜测改写）。
     */
    fun rewrite(mirror: DownloadMirror, url: String): String {
        val kind = assetKindOf(url) ?: return url
        if (kind !in mirror.appliesTo) return url
        return when (mirror.strategy) {
            MirrorStrategy.DIRECT -> url
            MirrorStrategy.PREFIX -> mirror.prefix!! + url
            MirrorStrategy.HOST_SWAP ->
                if (kind == MirrorAssetKind.RAW) {
                    url.replaceFirst(
                        "https://raw.githubusercontent.com/",
                        "https://${mirror.swapHost}/",
                    )
                } else {
                    url
                }
        }
    }

    /** 判定 URL 的资产形态（非 GitHub 域 → null）。 */
    fun assetKindOf(url: String): MirrorAssetKind? {
        val lower = url.lowercase()
        return when {
            lower.startsWith("https://github.com/") -> MirrorAssetKind.RELEASE
            lower.startsWith("https://raw.githubusercontent.com/") -> MirrorAssetKind.RAW
            else -> null
        }
    }

    /**
     * 从镜像 URL 还原原始 GitHub URL（PREFIX 形态的内嵌目标）。
     * 非镜像 URL / 结构不符 → null。FetchHostPolicy 联动校验用。
     */
    fun originalUrlOf(mirrorUrl: String): String? {
        val mirror = all.firstOrNull {
            it.strategy != MirrorStrategy.DIRECT && mirrorUrl.startsWith("https://${it.host}/")
        } ?: return null
        return when (mirror.strategy) {
            MirrorStrategy.PREFIX -> mirrorUrl.removePrefix("https://${mirror.host}/")
            MirrorStrategy.HOST_SWAP ->
                if (mirrorUrl.startsWith("https://${mirror.swapHost}/")) {
                    "https://raw.githubusercontent.com/" +
                        mirrorUrl.removePrefix("https://${mirror.swapHost}/")
                } else {
                    null
                }
            MirrorStrategy.DIRECT -> null
        }
    }
}
