package com.abyss.tools.core.plugin

import com.abyss.tools.core.common.AppInfo
import com.abyss.tools.core.common.SemVer
import com.abyss.tools.core.update.Digests
import com.abyss.tools.core.update.HttpTransport
import com.abyss.tools.core.update.PatchApplier
import com.abyss.tools.core.update.PatchRejectedException
import com.abyss.tools.core.update.PatchReport
import com.abyss.tools.core.update.ResumePlanner
import com.abyss.tools.core.update.ZipPatchApplier
import com.abyss.tools.plugin.api.PluginContract
import com.abyss.tools.plugin.api.PluginManifest
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * APK 安装网关 —— 交由 Android 侧（:app）实现。
 *
 * v0.1.1 起 :app 注入真实 PackageInstaller 实现（同签名门禁 + 用户确认页
 * 转发 + 结果广播等待）；core:plugin 依旧保持纯 JVM（可单测），Android
 * 依赖全部隔离在该网关后面。接口本体收敛在 core:update（宿主自更新与
 * 插件安装共用同一条系统安装通道）。
 */
typealias ApkInstallGateway = com.abyss.tools.core.update.ApkInstallGateway

/**
 * 插件安装器：
 *
 *  - APK 插件：downloadApk → verify(sha256) → ApkInstallGateway.install；
 *  - 数据插件：installDataPlugin（索引项 manifest 落盘登记，staging 原子
 *    落位）+ applyDataPatch（abyss-patch-v1 引擎，备份 + 回滚全真实）；
 *  - 已装登记表：pluginDir(id)/plugin.json 的存在性即安装态；
 *  - 版本查询：installedManifest / installedVersion / updatablePatch
 *    （补丁通道与本机版本比对 → UI 直接消费）。
 */
class PluginInstaller(
    private val pluginsRoot: File,
    private val transport: HttpTransport,
    private val apkGateway: ApkInstallGateway,
) {
    private val patchApplier: PatchApplier = ZipPatchApplier()

    /**
     * 插件目录（带 canonical 路径断言，评审 #6）：id 先过 ID_PATTERN，
     * 目录 canonical 必须落在 pluginsRoot 内 —— 穿越 / 越界就地拒绝，
     * uninstall / applyDataPatch / installDataPlugin 全部经此门禁。
     */
    fun pluginDir(pluginId: String): File {
        require(PluginContract.ID_PATTERN.matches(pluginId)) {
            "插件 id 非法（拒绝对其做任何文件操作）：$pluginId"
        }
        val root = pluginsRoot.canonicalFile
        val dir = File(pluginsRoot, pluginId).canonicalFile
        check(dir.path.startsWith(root.path + File.separator)) {
            "插件目录越界（疑似路径穿越）：$pluginId → ${dir.path}"
        }
        return dir
    }

    fun isInstalled(pluginId: String): Boolean =
        pluginDir(pluginId).resolve(PluginContract.MANIFEST_FILE).exists()

    /** 已安装插件清单（目录内 plugin.json 反序列化；坏 manifest 跳过并保留目录待修复）。 */
    fun installed(): List<PluginManifest> =
        pluginsRoot.listFiles { f ->
            // v0.7.0 修复（存量缺陷）：只认「合法插件 id」形态的目录 ——
            // 补丁引擎的 .abyss-backup-* 备份区恰好也建在 pluginsRoot 内
            // （targetDir.parentFile），旧实现把它一并枚举成「已装插件」，
            // 备份里的旧 plugin.json 会以幽灵插件身份出现在插件中心
            // （文件系统目录序不同则时隐时现 —— CI 绿 / 本地红的根因）。
            f.isDirectory && PluginContract.ID_PATTERN.matches(f.name)
        }
            .orEmpty()
            .mapNotNull { dir ->
                runCatching {
                    PluginManifest.parse(
                        dir.resolve(PluginContract.MANIFEST_FILE).readText()
                    )
                }.getOrNull()
            }

    fun uninstall(pluginId: String): Boolean = pluginDir(pluginId).deleteRecursively()

    // ── 数据插件登记（真实安装：manifest 原子落盘）──────────────────────

    /**
     * 从插件中心索引项安装「数据层插件框架」：
     * 校验（id 形态 / 版本号 / 宿主下限）→ manifest 组装 → staging 落盘 →
     * 原子改名。APK 资产未发布的插件由此获得真实可用的本地安装态与补丁
     * 通道；APK 插件发布后本登记依旧是宿主侧的版本账本。
     */
    fun installDataPlugin(entry: PluginIndexEntry): Result<PluginManifest> {
        if (!PluginContract.ID_PATTERN.matches(entry.id)) {
            return Result.failure(IllegalArgumentException("插件 id 非法：${entry.id}"))
        }
        val version = SemVer.parse(entry.version)
            ?: return Result.failure(IllegalArgumentException("插件版本号非法：${entry.version}"))
        val minApp = SemVer.parse(entry.minApp)
            // NB-5（fail-closed 收口）：minApp 解析失败必须拒绝 —— 此前静默
            // 跳过宿主版本门槛，坏值（如带 v 前缀）反而变成「永过低门槛」
            ?: return Result.failure(
                IllegalArgumentException("插件 minApp 版本号非法：${entry.minApp}（需 x.y.z 形态）")
            )
        if (minApp > AppInfo.version) {
            return Result.failure(
                IllegalStateException("宿主版本过低：需 ≥ v${entry.minApp}，当前 v${AppInfo.VERSION_NAME}")
            )
        }
        if (version <= SemVer(0, 0, 0)) {
            return Result.failure(IllegalArgumentException("插件版本号必须 > 0.0.0：${entry.version}"))
        }
        return runCatching {
            val manifest = entry.toManifest()
            val dir = pluginDir(entry.id).apply { mkdirs() }
            val target = dir.resolve(PluginContract.MANIFEST_FILE)
            val staging = dir.resolve("${PluginContract.MANIFEST_FILE}.staging")
            val backup = dir.resolve("${PluginContract.MANIFEST_FILE}.bak")
            staging.writeText(PluginManifest.serialize(manifest))
            // B-12 原子落盘（备份 → 改名 → 回滚）：旧实现先 delete 再
            // renameTo —— 两者之间 manifest 缺位，且 rename 失败无回滚，
            // 已装插件的登记直接丢失（与注释「原子落盘」承诺相悖）。
            backup.delete() // 清理上一轮失败残留（同 POSIX rename 覆盖语义对齐）
            val hadOld = target.exists()
            if (hadOld && !target.renameTo(backup)) {
                throw IOException("旧 manifest 备份失败：${entry.id}")
            }
            if (!staging.renameTo(target)) {
                // 回滚：恢复备份，不留「登记丢失」窗口；回滚本身失败如实上报
                if (hadOld && !backup.renameTo(target)) {
                    throw IOException(
                        "manifest 落盘失败且回滚失败（staging 与备份均受阻）：${entry.id}"
                    )
                }
                throw IOException("manifest 落盘失败（staging 改名受阻，已回滚）：${entry.id}")
            }
            if (hadOld) backup.delete()
            manifest
        }
    }

    /** 本机安装的插件 manifest（未安装 / 坏 manifest → null）。 */
    fun installedManifest(pluginId: String): PluginManifest? = runCatching {
        val file = pluginDir(pluginId).resolve(PluginContract.MANIFEST_FILE)
        if (file.exists()) PluginManifest.parse(file.readText()) else null
    }.getOrNull()

    /** 本机安装的插件版本（未安装 / 版本号非法 → null）。 */
    fun installedVersion(pluginId: String): SemVer? =
        installedManifest(pluginId)?.let { SemVer.parse(it.version) }

    /**
     * 补丁通道中适用于当前已装版本的最新补丁（from 命中，to 取最大）。
     * 未安装 / 无命中 → null（UI 据此隐藏「应用补丁」动作）。
     */
    fun updatablePatch(pluginId: String, channel: PluginPatchChannel): PluginPatch? {
        val installed = installedVersion(pluginId) ?: return null
        return channel.patches
            .filter { it.from == installed.toString() }
            .maxByOrNull { SemVer.parse(it.to) ?: SemVer(0, 0, 0) }
    }

    // ── APK 插件安装流水线 ─────────────────────────────────────────────

    suspend fun downloadApk(
        entry: PluginIndexEntry,
        onProgress: (readBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        val apk = entry.apk
            ?: return@withContext Result.failure(
                IllegalStateException("「${entry.name}」安装包尚未发布 —— 框架预览期插件仓库仅发布 manifest 与补丁通道。")
            )
        runCatching {
            val dest = File(cacheDirFor(entry.id), safeFileNameOf(apk.url))
            transport.download(apk.url, dest, onProgress)
            dest
        }
    }

    fun verifyApk(entry: PluginIndexEntry, file: File): Boolean =
        Digests.verify(file, entry.apk?.sha256)

    /** 阻塞调用（等待系统安装结果广播）—— 调用方负责切 IO 线程。 */
    fun installApk(file: File): Result<Unit> = apkGateway.install(file)

    /**
     * APK 一条龙安装的阶段回调（UI 进度反馈用）。
     */
    enum class ApkPipelinePhase { DOWNLOADING, VERIFYING, INSTALLING, REGISTERING }

    /**
     * 从 URL 提取安全文件名：剥 query、过滤危险字符（路径分隔符等），空则时间戳兜底。
     * 与 UpdateManager.fileNameOf 同构，防路径穿越写入任意位置。
     */
    private fun safeFileNameOf(url: String): String {
        val raw = url.substringBefore('?').substringAfterLast('/')
        val sanitized = raw.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
        return sanitized.ifBlank { "abyss-plugin-download-${System.currentTimeMillis()}" }
    }

    /**
     * APK 插件一条龙安装（v0.3.0 收口）：
     * 下载 → SHA-256 校验 → 系统安装器 → **版本账本同步**。
     *
     * 此前 APK 安装与数据层登记是两条独立线 —— 装完 APK 后本机 plugin.json
     * 仍停留在旧版本（或未登记），补丁通道的 from 比对会算错、插件中心
     * 的「已安装」徽章也不反映 APK 安装态。本方法把「APK 安装成功」与
     * 「登记簿对齐索引版本」收敛到单一入口：系统安装器返回成功后立即
     * 复用 [installDataPlugin] 的校验 + 原子落盘，任一环节失败都如实上报。
     *
     * @param onPhase 阶段回调（DOWNLOADING 阶段的进度另走 [onProgress]）。
     */
    suspend fun installApkPlugin(
        entry: PluginIndexEntry,
        onProgress: (readBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onPhase: (ApkPipelinePhase) -> Unit = { _ -> },
    ): Result<PluginManifest> {
        onPhase(ApkPipelinePhase.DOWNLOADING)
        val file = downloadApk(entry, onProgress).getOrThrow()
        onPhase(ApkPipelinePhase.VERIFYING)
        if (!verifyApk(entry, file)) {
            // NB-9：坏 APK 就地删除（含 .part 残留）—— 与 UpdateManager 对主壳
            // 更新的语义对齐；此前坏文件占着缓存位（可达数十 MB）且
            // cachedApkFile() 会继续把它喂给 UI 层解析。
            runCatching { file.delete() }
            runCatching { ResumePlanner.partFileOf(file).delete() }
            return Result.failure(
                IllegalStateException("SHA-256 校验失败 —— 拒绝安装（fail-closed）：${entry.id}")
            )
        }
        onPhase(ApkPipelinePhase.INSTALLING)
        // 网关为阻塞调用（等待系统安装结果广播）：切 IO 线程
        val installed = withContext(Dispatchers.IO) { installApk(file) }
        // fold 为 inline —— onSuccess 内可直接调挂起函数（版本账本同步）
        return installed.fold(
            onSuccess = {
                onPhase(ApkPipelinePhase.REGISTERING)
                // 系统安装成功 → 版本账本对齐索引版本（同 installDataPlugin
                // 语义，同步含落盘 IO）
                withContext(Dispatchers.IO) { installDataPlugin(entry) }
            },
            onFailure = { Result.failure(it) },
        )
    }

    /**
     * 最近一次下载的 APK 缓存文件（UI 侧解析包名 / 引导系统卸载 APK 本体用）。
     * 缓存被清理或从未下载 → null，调用方诚实降级。
     */
    fun cachedApkFile(entry: PluginIndexEntry): File? {
        val apk = entry.apk ?: return null
        val file = File(cacheDirFor(entry.id), safeFileNameOf(apk.url))
        return file.takeIf { it.exists() && it.length() > 0L }
    }

    // ── 数据插件补丁流水线（全真实：下载 → 校验 → 应用 → 回滚保护）────

    suspend fun downloadPatch(
        pluginId: String,
        patch: PluginPatch,
        onProgress: (readBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dest = File(cacheDirFor(pluginId), safeFileNameOf(patch.url))
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
        gcPatchBackups()
    }

    /**
     * 补丁备份区 GC（B-27）：PatchEngine 把备份建在 pluginsRoot 下的
     * `.abyss-patch-backup-<ts>/`（targetDir 外部）—— 失败回滚成功后与
     * 正常升级后都不清理，长期膨胀。策略：按目录名时间戳（而非 mtime，
     * 防复制/恢复改 mtime）保留最近 [keep] 份，其余删除。
     * 纯 JVM 可测（临时目录注入）。
     */
    fun gcPatchBackups(keep: Int = 3) {
        require(keep >= 0) { "keep 必须非负：$keep" }
        val dirs = pluginsRoot.listFiles { f ->
            f.isDirectory && (f.name.startsWith(com.abyss.tools.core.update.PATCH_BACKUP_PREFIX) ||
                f.name.startsWith(com.abyss.tools.core.update.PATCH_BACKUP_PREFIX_OLD))
        }.orEmpty()
        if (dirs.size <= keep) return
        // 前缀互异时名字整序 ≠ 时间序（.a- vs .p- 前缀先决）—— 剥前缀取时间戳排序
        fun ageOf(f: File): Long = when {
            f.name.startsWith(com.abyss.tools.core.update.PATCH_BACKUP_PREFIX) ->
                f.name.removePrefix(com.abyss.tools.core.update.PATCH_BACKUP_PREFIX).toLongOrNull() ?: 0L
            else ->
                f.name.removePrefix(com.abyss.tools.core.update.PATCH_BACKUP_PREFIX_OLD).toLongOrNull() ?: 0L
        }
        val sortedByAge = dirs.sortedByDescending(::ageOf)
        sortedByAge.drop(keep).forEach { dir ->
            runCatching { dir.deleteRecursively() }
        }
    }

    private fun cacheDirFor(pluginId: String): File {
        require(PluginContract.ID_PATTERN.matches(pluginId)) {
            "插件 id 非法（拒绝缓存目录操作）：$pluginId"
        }
        val root = pluginsRoot.canonicalFile
        val cacheRoot = File(pluginsRoot, "cache").canonicalFile
        val dir = File(cacheRoot, pluginId).canonicalFile
        check(dir.path.startsWith(cacheRoot.path + File.separator)) {
            "缓存目录越界（疑似路径穿越）：$pluginId → ${dir.path}"
        }
        dir.mkdirs()
        return dir
    }

    private fun PluginIndexEntry.toManifest(): PluginManifest = PluginManifest(
        id = id,
        name = name,
        version = version,
        minHost = minHost,
        minApp = minApp,
        category = category,
        summary = summary,
        icon = icon,
        permissions = permissions,
        shizuku = shizuku,
        entry = null,
        repo = repo,
        patchChannel = patchChannel,
        roadmap = roadmap,
        // ── hubVersion 0.4.0 增量透传（DC-100 §4）：本地 manifest 与索引
        //    同构 —— 本地依赖账本（卸载级联提示）与能力发现消费这些字段。
        provides = provides,
        depends = depends,
        capabilities = capabilities,
        assetChannel = assetChannel,
    )
}

