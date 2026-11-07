package com.abyss.tools.core.update

import com.abyss.tools.core.common.SemVer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 更新编排器：检查 → 下载 → 校验 → （应用 / 安装）。
 *
 * v0.1.1 起的闭环边界（诚实声明）：
 *  - checkForUpdate / fetchChangelog / downloadPatch / downloadApk / verify /
 *    installApk 已是真实实现（真实 HTTP、真实流式下载、真实 SHA-256 校验、
 *    经 [ApkInstallGateway] 的系统级 APK 安装 —— 由 :app 注入
 *    PackageInstallerApkGateway，含同签名门禁与用户确认页转发）；
 *  - APK 二进制增量的合成（vcdiff）仍属发布流水线的一部分，v0.2 接入 ——
 *    当前 applyDataPatch 只服务数据层资产（abyss-patch-v1）。
 */
class UpdateManager(
    private val transport: HttpTransport,
    private val versionJsonUrl: String,
    private val cacheDir: File,
    private val changelogJsonUrl: String? = null,
    private val apkGateway: ApkInstallGateway? = null,
) {
    private val checker = UpdateChecker(transport, versionJsonUrl)
    private val applier: PatchApplier = ZipPatchApplier()

    suspend fun checkForUpdate(
        current: SemVer,
        currentTag: String = current.toTag(),
    ): UpdateCheckResult = checker.check(current, currentTag)

    /** 更新日志（changelog.json）。未配置通道地址 → 人话失败。 */
    suspend fun fetchChangelog(): Result<ChangelogManifest> = withContext(Dispatchers.IO) {
        val url = changelogJsonUrl
            ?: return@withContext Result.failure(
                IllegalStateException("changelog.json 通道地址未配置（ServiceRegistry 装配时注入 AbyssChannels.CHANGELOG_JSON）")
            )
        runCatching { UpdateParsers.parseChangelogManifest(transport.get(url)) }
    }

    suspend fun downloadPatch(
        patch: PatchInfo,
        onProgress: (readBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<File> = download(patch.url, fileNameOf(patch.url), onProgress)

    suspend fun downloadApk(
        info: DownloadInfo,
        onProgress: (readBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<File> = download(info.url, fileNameOf(info.url), onProgress)

    fun verify(file: File, expectedSha256: String?): Boolean = Digests.verify(file, expectedSha256)

    /**
     * 提交 APK 给系统安装器（经注入的网关）。
     * 阻塞调用（等待安装结果广播）—— 调用方负责 withContext(Dispatchers.IO)。
     */
    fun installApk(file: File): Result<Unit> =
        apkGateway?.install(file)
            ?: Result.failure(
                IllegalStateException("APK 安装网关未装配（core:update 保持纯 JVM，由 :app 注入 Android PackageInstaller 实现）")
            )

    /** 数据层补丁应用（插件 manifest / 技能目录等）。 */
    fun applyDataPatch(patchFile: File, targetDir: File): PatchReport =
        applier.apply(patchFile, targetDir)

    fun clearCache() {
        cacheDir.takeIf { it.exists() }?.deleteRecursively()
        cacheDir.mkdirs()
    }

    private suspend fun download(
        url: String,
        fileName: String,
        onProgress: (Long, Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            cacheDir.mkdirs()
            val dest = File(cacheDir, fileName)
            transport.download(url, dest, onProgress)
            dest
        }
    }

    /** 文件名规范化：剥 query、只留安全字符，空则时间戳兑底（评审 #20）。 */
    private fun fileNameOf(url: String): String {
        val raw = url.substringBefore('?').substringAfterLast('/')
        val sanitized = raw.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
        return sanitized.ifBlank { "abyss-download-${System.currentTimeMillis()}" }
    }
}
