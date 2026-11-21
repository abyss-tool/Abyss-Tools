package com.abyss.tools.core.update

import com.abyss.tools.core.common.SemVer
import java.io.File
import java.io.IOException
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
    versionSigned: SignedHttpTransport? = null,
    patchesSigned: SignedHttpTransport? = null,
    changelogSigned: SignedHttpTransport? = null,
) {
    private val checker = UpdateChecker(versionSigned ?: transport, versionJsonUrl)
    private val patchesTransport: HttpTransport = patchesSigned ?: transport
    private val changelogTransport: HttpTransport = changelogSigned ?: transport
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
        runCatching { UpdateParsers.parseChangelogManifest(changelogTransport.get(url)) }
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
     * 覆盖更新专用管线（v0.7.8）：下载 → SHA-256 校验 → 失败即弃 →
     * 返回已验证的 APK。与裸 [downloadApk] 的区别：
     *  - 校验内聚 —— 校验失败就地删除坏文件与 `.part` 残留（此前坏文件
     *    会留在缓存里，下次「检查更新」前一直占着 30 MB）；
     *  - 缓存命中 —— 同一资产（同名 + SHA-256 通过）已下载过则秒回，
     *    「安装失败后重试」不重付 30 MB 流量；
     *  - 断点续传由传输层承担（OkHttpTransport：.part + Range + 原子改名）。
     */
    suspend fun downloadVerifiedApk(
        info: DownloadInfo,
        onProgress: (readBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            cacheDir.mkdirs()
            val dest = File(cacheDir, fileNameOf(info.url))
            // 缓存命中：完整文件已存在且校验通过 —— 不重付流量
            if (dest.exists() && verify(dest, info.sha256)) {
                return@runCatching dest
            }
            transport.download(info.url, dest, onProgress)
            if (!verify(dest, info.sha256)) {
                runCatching { dest.delete() }
                runCatching { ResumePlanner.partFileOf(dest).delete() }
                throw IOException("SHA-256 校验失败 —— 已丢弃下载缓存（fail-closed）")
            }
            dest
        }
    }

    /**
     * 提交 APK 给系统安装器（经注入的网关）。
     * 阻塞调用（等待安装结果广播）—— 调用方负责 withContext(Dispatchers.IO)。
     *
     * v0.7.8：自更新传 [expectation]（通道清单 versionCode）—— 网关在
     * 进入系统安装器前核对清单与 APK 一致；安装成功即删除缓存 APK
     * （覆盖完成，30 MB 不再占坑；失败保留 —— 重试时缓存命中秒回）。
     */
    fun installApk(file: File, expectation: InstallExpectation? = null): Result<Unit> {
        val result = if (expectation == null) {
            apkGateway?.install(file)
        } else {
            apkGateway?.install(file, expectation)
        } ?: Result.failure(
            IllegalStateException("APK 安装网关未装配（core:update 保持纯 JVM，由 :app 注入 Android PackageInstaller 实现）"),
        )
        if (result.isSuccess) runCatching { file.delete() }
        return result
    }

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
