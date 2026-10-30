package com.abyss.tools.core.update

import com.abyss.tools.core.common.SemVer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 更新编排器：检查 → 下载 → 校验 → （应用）。
 *
 * 框架期边界（诚实声明）：
 *  - checkForUpdate / downloadPatch / downloadApk / verify 已是真实实现
 *    （真实 HTTP、真实流式下载、真实 SHA-256 校验）；
 *  - APK 二进制增量的合成与覆盖安装（vcdiff + PackageInstaller）属发布
 *    流水线的一部分，v0.2 接入 —— 当前 applyDataPatch 只服务数据层资产。
 */
class UpdateManager(
    private val transport: HttpTransport,
    private val versionJsonUrl: String,
    private val cacheDir: File,
) {
    private val checker = UpdateChecker(transport, versionJsonUrl)
    private val applier: PatchApplier = ZipPatchApplier()

    suspend fun checkForUpdate(
        current: SemVer,
        currentTag: String = current.toTag(),
    ): UpdateCheckResult = checker.check(current, currentTag)

    suspend fun downloadPatch(
        patch: PatchInfo,
        onProgress: (readBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<File> = download(patch.url, fileNameOf(patch.url), onProgress)

    suspend fun downloadApk(
        info: DownloadInfo,
        onProgress: (readBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<File> = download(info.url, fileNameOf(info.url), onProgress)

    fun verify(file: File, expectedSha256: String?): Boolean = Digests.verify(file, expectedSha256)

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

    private fun fileNameOf(url: String): String =
        url.substringAfterLast('/').ifBlank { "download-${System.currentTimeMillis()}" }
}
