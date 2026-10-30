package com.abyss.tools.core.plugin

import com.abyss.tools.core.update.Digests
import com.abyss.tools.core.update.HttpTransport
import com.abyss.tools.core.update.PatchApplier
import com.abyss.tools.core.update.PatchRejectedException
import com.abyss.tools.core.update.PatchReport
import com.abyss.tools.core.update.ZipPatchApplier
import com.abyss.tools.plugin.api.PluginContract
import com.abyss.tools.plugin.api.PluginManifest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * APK 安装网关 —— 交由 Android 侧（:app）实现。
 *
 * 框架期实现为占位：APK 插件的发现与安装（PackageManager +
 * PackageInstaller + 同签名校验）在 v0.2 发布流水线接入。core:plugin
 * 保持纯 JVM（可单测），Android 依赖全部隔离在该网关后面。
 */
interface ApkInstallGateway {
    fun install(apk: File): Result<Unit>
}

/**
 * 插件安装器（框架）：
 *
 *  - APK 插件：downloadApk → verify(sha256) → ApkInstallGateway.install
 *    （资产未发布时 fail-closed 给出人话错误）；
 *  - 数据插件：downloadPatch → applyDataPatch（abyss-patch-v1 引擎，
 *    备份 + 回滚全真实）；
 *  - 已装登记表：pluginDir(id)/plugin.json 的存在性即安装态。
 */
class PluginInstaller(
    private val pluginsRoot: File,
    private val transport: HttpTransport,
    private val apkGateway: ApkInstallGateway,
) {
    private val patchApplier: PatchApplier = ZipPatchApplier()

    fun pluginDir(pluginId: String): File = File(pluginsRoot, pluginId)

    fun isInstalled(pluginId: String): Boolean =
        pluginDir(pluginId).resolve(PluginContract.MANIFEST_FILE).exists()

    /** 已安装插件清单（目录内 plugin.json 反序列化；坏 manifest 跳过并保留目录待修复）。 */
    fun installed(): List<PluginManifest> =
        pluginsRoot.listFiles { f -> f.isDirectory }
            .orEmpty()
            .mapNotNull { dir ->
                runCatching {
                    PluginManifest.parse(
                        dir.resolve(PluginContract.MANIFEST_FILE).readText()
                    )
                }.getOrNull()
            }

    fun uninstall(pluginId: String): Boolean = pluginDir(pluginId).deleteRecursively()

    // ── APK 插件安装流水线（框架期：资产未发布 → 明确报错）────────────

    suspend fun downloadApk(
        entry: PluginIndexEntry,
        onProgress: (readBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        val apk = entry.apk
            ?: return@withContext Result.failure(
                IllegalStateException("「${entry.name}」安装包尚未发布 —— 框架预览期插件仓库仅发布 manifest 与补丁通道。")
            )
        runCatching {
            val dest = File(cacheDirFor(entry.id), apk.url.substringAfterLast('/'))
            transport.download(apk.url, dest, onProgress)
            dest
        }
    }

    fun verifyApk(entry: PluginIndexEntry, file: File): Boolean =
        Digests.verify(file, entry.apk?.sha256)

    fun installApk(file: File): Result<Unit> = apkGateway.install(file)

    // ── 数据插件补丁流水线（全真实：下载 → 校验 → 应用 → 回滚保护）────

    suspend fun downloadPatch(
        pluginId: String,
        patch: PluginPatch,
        onProgress: (readBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dest = File(cacheDirFor(pluginId), patch.url.substringAfterLast('/'))
            transport.download(patch.url, dest, onProgress)
            dest
        }
    }

    fun verifyPatch(patch: PluginPatch, file: File): Boolean = Digests.verify(file, patch.sha256)

    @Throws(PatchRejectedException::class)
    fun applyDataPatch(pluginId: String, patchFile: File): PatchReport {
        val target = pluginDir(pluginId)
        target.mkdirs()
        return patchApplier.apply(patchFile, target)
    }

    fun clearCache() {
        File(pluginsRoot, "cache").deleteRecursively()
    }

    private fun cacheDirFor(pluginId: String): File =
        File(pluginsRoot, "cache/$pluginId").apply { mkdirs() }
}
