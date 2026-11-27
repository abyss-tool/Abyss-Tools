package com.abyss.tools.core.update

import com.abyss.tools.core.common.AbyssChannels
import kotlinx.serialization.Serializable
import java.io.File

/**
 * 深渊agent（Ultra-Guru/Android-Guru-Agent）发布通道数据契约（FEAT-037）。
 *
 * 姊妹项目发布仓 version.json 与本仓 schema 同族（v0.6.0 起有意对齐，
 * 见 UpdateModels.kt 头注），`download` 为 ABI 变体 map
 * （`arm64` / `universal` → {url, sizeBytes, sha256}）。
 * 解析一律 ignoreUnknownKeys —— 通道加字段不炸旧客户端。
 */
@Serializable
data class AgentReleaseManifest(
    val versionName: String,
    val versionCode: Int = 0,
    val tag: String? = null,
    val publishedAt: String? = null,
    val releasePage: String? = null,
    val download: Map<String, DownloadInfo> = emptyMap(),
    val signingCertSha256: String? = null,
) {
    val resolvedTag: String get() = tag ?: "v$versionName"

    /** 变体选择：arm64 优先（主流设备体积最小），其次 universal，其余取首键。 */
    fun defaultVariantKey(): String? = when {
        download.containsKey("arm64") -> "arm64"
        download.containsKey("universal") -> "universal"
        else -> download.keys.firstOrNull()
    }

    /** 显式变体键命中即返回（未知键 = null，不猜）；null = 取默认变体。 */
    fun assetOf(variantKey: String?): DownloadInfo? =
        if (variantKey != null) {
            download[variantKey]
        } else {
            defaultVariantKey()?.let { download[it] }
        }
}

object AgentReleaseParsers {
    // 与 UpdateParsers 同纪律：严格 JSON（坏清单直接失败，绝不猜），
    // ignoreUnknownKeys 保留前向兼容。
    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
    }

    /** SHA-256 摘要形态（64 位小写十六进制）。 */
    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

    /**
     * fail-closed 解析：坏 JSON / 缺关键字段（versionName / download 资产 /
     * 签名证书指纹）/ 资产 URL 仓库归属不符，一律抛人话错误，绝不把畸形
     * 清单猜成合法态。
     *
     * B-06 ②（资产 URL 仓库归属）：download.url 必须命中
     * [AbyssChannels.AGENT_ASSET_URL_PREFIX]（指定发布仓 releases/download/
     * 路径）—— 旧语义仅域级白名单，任意 github.com 仓库路径均放行。
     *
     * B-18（signingCertSha256 实校验前置）：指纹必填且形态合法 ——
     * 无指纹锚点的清单不再提供安装通路（fail-closed，而非降级展示）。
     */
    fun parseAgentReleaseManifest(raw: String): AgentReleaseManifest {
        val manifest = json.decodeFromString<AgentReleaseManifest>(raw)
        require(manifest.versionName.isNotBlank()) { "通道清单缺 versionName" }
        require(manifest.download.isNotEmpty()) { "通道清单缺 download 资产（arm64/universal 均未发布）" }
        require(!manifest.signingCertSha256.isNullOrBlank()) {
            "通道清单缺 signingCertSha256 —— 无签名证书指纹锚点，拒绝该清单（fail-closed）"
        }
        require(SHA256_HEX.matches(manifest.signingCertSha256!!)) {
            "通道清单 signingCertSha256 形态非法（需 64 位小写十六进制）：${manifest.signingCertSha256}"
        }
        manifest.download.values.forEach { asset ->
            require(asset.url.isNotBlank()) { "通道清单 download 资产缺 url" }
            require(!asset.sha256.isNullOrBlank()) { "通道清单 download 资产缺 sha256（拒绝无校验下载）" }
            require(asset.url.startsWith(AbyssChannels.AGENT_ASSET_URL_PREFIX)) {
                "download 资产 URL 不在指定发布仓 releases/download/ 路径内" +
                    "（fail-closed 防清单劫持指向任意仓库）：${asset.url}"
            }
        }
        return manifest
    }
}

/**
 * 深渊agent 直达下载管理器（FEAT-037）：清单拉取解析 → 多镜像下载
 * （[MirroredTransport] 自动择优回退）→ SHA-256 强校验 → 系统安装器。
 *
 * 纯 JVM（Android 依赖隔离在 [ApkInstallGateway] 后），全部方法为阻塞
 * IO —— 调用方负责切 IO 线程。非宿主自更新：走 [ApkInstallGateway.install]
 * 默认转发（无发布者指纹门禁，与插件 APK 同路径）；完整性锚 = 通道清单
 * 内置 sha256（fail-closed，不符拒装）。
 */
class AgentReleaseManager(
    private val transport: HttpTransport,
    private val versionJsonUrl: String,
    private val cacheDir: File,
    private val apkGateway: ApkInstallGateway,
) {

    /** 拉取并解析通道清单（阻塞）。 */
    fun fetchManifest(): AgentReleaseManifest =
        AgentReleaseParsers.parseAgentReleaseManifest(transport.get(versionJsonUrl))

    /**
     * 下载指定变体 APK 并校验 sha256（阻塞）。校验不符删除缓存文件
     * （不留坏文件占坑），抛人话错误。
     *
     * B-06 ② 防御深度：资产 URL 仓库归属在解析层已拦；此处对直接构造的
     * [DownloadInfo]（调用方绕过解析器的形态）再做同门禁 —— 下载发起前
     * 拒绝，绝不向非指定发布仓发起请求。
     */
    fun downloadVerified(
        asset: DownloadInfo,
        onProgress: (readBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File {
        require(asset.url.startsWith(AbyssChannels.AGENT_ASSET_URL_PREFIX)) {
            "agent 资产 URL 不在指定发布仓 releases/download/ 路径内（fail-closed）：" +
                "${asset.url} —— 拒绝向非深渊agent 发布仓发起下载"
        }
        cacheDir.mkdirs()
        val dest = File(cacheDir, fileNameOf(asset.url))
        try {
            transport.download(asset.url, dest, onProgress)
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
        if (!Digests.verify(dest, asset.sha256)) {
            dest.delete()
            throw IllegalStateException("SHA-256 校验失败 —— 拒绝安装（fail-closed）：深渊agent APK 与通道清单不符")
        }
        return dest
    }

    /**
     * 提交系统安装器（阻塞；等待安装结果广播）。
     *
     * B-06 ③/B-18：携带通道清单期望（versionCode + signingCertSha256）——
     * Android 侧网关在会话提交前对 APK 实际 versionCode 与签名证书指纹
     * 硬校验，不符即拒（fail-closed），不再无锚点直装。
     */
    fun install(file: File, manifest: AgentReleaseManifest): Result<Unit> =
        apkGateway.install(
            file,
            InstallExpectation(
                expectedVersionCode = manifest.versionCode.toLong(),
                expectedSigningCertSha256 = manifest.signingCertSha256,
            ),
        )

    /** 从 URL 提取安全文件名（与 UpdateManager.fileNameOf 同构，防路径穿越）。 */
    private fun fileNameOf(url: String): String {
        val raw = url.substringBefore('?').substringAfterLast('/')
        val sanitized = raw.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
        return sanitized.ifBlank { "abyss-agent-download-${System.currentTimeMillis()}.apk" }
    }
}
