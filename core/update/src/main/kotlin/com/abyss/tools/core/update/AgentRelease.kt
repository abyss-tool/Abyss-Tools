package com.abyss.tools.core.update

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

    /**
     * fail-closed 解析：坏 JSON / 缺关键字段（versionName 或 download 资产）
     * 一律抛给人话错误，绝不把畸形清单猜成合法态。
     */
    fun parseAgentReleaseManifest(raw: String): AgentReleaseManifest {
        val manifest = json.decodeFromString<AgentReleaseManifest>(raw)
        require(manifest.versionName.isNotBlank()) { "通道清单缺 versionName" }
        require(manifest.download.isNotEmpty()) { "通道清单缺 download 资产（arm64/universal 均未发布）" }
        manifest.download.values.forEach { asset ->
            require(asset.url.isNotBlank()) { "通道清单 download 资产缺 url" }
            require(!asset.sha256.isNullOrBlank()) { "通道清单 download 资产缺 sha256（拒绝无校验下载）" }
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
     */
    fun downloadVerified(
        asset: DownloadInfo,
        onProgress: (readBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File {
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

    /** 提交系统安装器（阻塞；等待安装结果广播）。 */
    fun install(file: File): Result<Unit> = apkGateway.install(file)

    /** 从 URL 提取安全文件名（与 UpdateManager.fileNameOf 同构，防路径穿越）。 */
    private fun fileNameOf(url: String): String {
        val raw = url.substringBefore('?').substringAfterLast('/')
        val sanitized = raw.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
        return sanitized.ifBlank { "abyss-agent-download-${System.currentTimeMillis()}.apk" }
    }
}
