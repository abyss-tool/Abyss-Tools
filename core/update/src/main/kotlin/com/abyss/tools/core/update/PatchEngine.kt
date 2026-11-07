package com.abyss.tools.core.update

import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 数据层补丁引擎（abyss-patch-v1）。
 *
 * 适用范围：**数据资产**的增量更新 —— 插件 manifest / 技能目录 / 配置
 * 等可整文件替换的层。APK 二进制增量（vcdiff 合成 + 覆盖安装）走发布仓
 * 发布流水线，v0.2 接入，与本引擎分属不同信任层。
 *
 * 补丁包格式（zip）：
 * ```
 * patch-manifest.json          # 必须存在于包根
 * <相对路径>                    # 载荷，与 manifest.files[].path 对应
 * ```
 *
 * manifest schema（abyss-patch-v1）：
 * ```json
 * {
 *   "schema": "abyss-patch-v1",
 *   "target": "abyss-plugin-terminal",
 *   "fromVersion": "0.1.0",
 *   "toVersion": "0.1.1",
 *   "files": [
 *     { "path": "plugin.json", "action": "replace" },
 *     { "path": "skills/new.json", "action": "add" },
 *     { "path": "legacy/config.json", "action": "delete" }
 *   ]
 * }
 * ```
 *
 * 安全纪律（fail-closed）：
 *  - 无 manifest / schema 不对 / target 为空 → 拒绝整个补丁；
 *  - 路径穿越（`../`、绝对路径）→ 拒绝整个补丁；
 *  - 任意一步写失败 → 从备份回滚全部已改文件，目标目录恢复原状。
 */

@Serializable
data class PatchFileAction(
    val path: String,
    val action: String = "replace",
) {
    val normalized: String get() = action.lowercase()
}

@Serializable
data class PatchManifest(
    val schema: String = "abyss-patch-v1",
    val target: String = "",
    val fromVersion: String? = null,
    val toVersion: String = "",
    val files: List<PatchFileAction> = emptyList(),
)

data class PatchReport(
    val target: String,
    val toVersion: String,
    val replaced: List<String>,
    val added: List<String>,
    val deleted: List<String>,
    val backupDir: File,
)

interface PatchApplier {
    /** @throws PatchRejectedException 补丁非法（格式 / 路径穿越 / schema 不符）。 */
    @Throws(IOException::class)
    fun apply(patchFile: File, targetDir: File): PatchReport
}

class PatchRejectedException(message: String) : IOException(message)

class ZipPatchApplier : PatchApplier {

    override fun apply(patchFile: File, targetDir: File): PatchReport {
        val entries = readZipEntries(patchFile)
        val manifestRaw = entries[MANIFEST_ENTRY]
            ?: throw PatchRejectedException("补丁包缺少 $MANIFEST_ENTRY（fail-closed 拒绝）")
        val manifest = try {
            json.decodeFromString<PatchManifest>(manifestRaw.decodeToString())
        } catch (t: Throwable) {
            throw PatchRejectedException("patch-manifest.json 解析失败: ${t.message}")
        }
        if (manifest.schema != SCHEMA) {
            throw PatchRejectedException("不支持的补丁 schema：${manifest.schema}（期望 $SCHEMA）")
        }
        if (manifest.target.isBlank() || manifest.toVersion.isBlank()) {
            throw PatchRejectedException("patch-manifest.json 缺少 target / toVersion")
        }

        // ── 路径安全与载荷完整性预检：任何非法 → 整包拒绝（零触碰）────
        val actions = manifest.files.ifEmpty {
            // manifest 未列 files 时：载荷里的每个非 manifest 条目默认 replace
            entries.keys
                .filter { it != MANIFEST_ENTRY }
                .map { PatchFileAction(path = it, action = ACTION_REPLACE) }
        }
        val targetCanonical = targetDir.canonicalFile
        for (action in actions) {
            // 备份区不可触碰（防补丁篡改回滚材料 / 老版本残留备份）
            if (action.path.startsWith(BACKUP_PREFIX_OLD) || action.path.startsWith(BACKUP_PREFIX)) {
                throw PatchRejectedException("补丁路径进入备份区（禁止）：${action.path}")
            }
            val file = File(targetDir, action.path)
            val canonical = file.canonicalFile
            if (canonical != targetCanonical && !canonical.path.startsWith(targetCanonical.path + File.separator)) {
                throw PatchRejectedException("非法补丁路径（疑似穿越）：${action.path}")
            }
            if (action.normalized != ACTION_DELETE && entries[action.path] == null) {
                throw PatchRejectedException("补丁包缺少载荷文件：${action.path}")
            }
        }

        // ── 备份 → 应用 → 失败回滚 ───────────────────────────────────
        // 备份区在 targetDir **外部**（评审 #9：原置于内部时补丁条目可碰到
        // 备份材料）；父目录不可写时如实失败，不降级回内部。
        val backupDir = File(targetDir.parentFile, "$BACKUP_PREFIX${System.currentTimeMillis()}")
        val replaced = mutableListOf<String>()
        val added = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val touched = mutableListOf<Pair<File, File?>>() // (目标文件, 备份文件|null=原本不存在)

        try {
            backupDir.mkdirs()
            for (action in actions) {
                val dest = File(targetDir, action.path)
                when (action.normalized) {
                    ACTION_DELETE -> {
                        if (dest.exists()) {
                            val backup = backup(backupDir, action.path, dest)
                            touched += dest to backup
                            if (dest.delete()) deleted += action.path
                        }
                    }
                    ACTION_REPLACE, ACTION_ADD -> {
                        val payload = entries[action.path]
                            ?: throw PatchRejectedException("补丁包缺少载荷文件：${action.path}")
                        val backup = if (dest.exists()) backup(backupDir, action.path, dest) else null
                        touched += dest to backup
                        dest.parentFile?.mkdirs()
                        dest.writeBytes(payload)
                        if (backup == null) added += action.path else replaced += action.path
                    }
                    else -> throw PatchRejectedException("未知 action：${action.action}")
                }
            }
        } catch (t: Throwable) {
            // 回滚：恢复备份 / 删除新增
            for ((dest, backup) in touched.reversed()) {
                runCatching {
                    if (backup != null && backup.exists()) {
                        dest.parentFile?.mkdirs()
                        backup.copyTo(dest, overwrite = true)
                        dest.setLastModified(backup.lastModified())
                    } else if (dest.exists()) {
                        dest.delete()
                    }
                }
            }
            runCatching { backupDir.deleteRecursively() }
            throw IOException("补丁应用失败已回滚：${t.message}", t)
        }

        if (replaced.isEmpty() && added.isEmpty() && deleted.isEmpty()) {
            runCatching { backupDir.deleteRecursively() }
        }
        return PatchReport(manifest.target, manifest.toVersion, replaced, added, deleted, backupDir)
    }

    private fun backup(backupDir: File, path: String, source: File): File {
        val backupFile = File(backupDir, path)
        backupFile.parentFile?.mkdirs()
        source.copyTo(backupFile, overwrite = true)
        return backupFile
    }

    /** 解包带三重上限（评审 #9：zip bomb 防护 —— 条目数 / 单文件 / 总量）。 */
    private fun readZipEntries(patchFile: File): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        var totalBytes = 0L
        ZipInputStream(patchFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                if (entries.size >= MAX_ENTRIES) {
                    throw PatchRejectedException("补丁条目数超过上限 $MAX_ENTRIES（疑似 zip bomb，整包拒绝）")
                }
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var entryBytes = 0L
                while (true) {
                    val read = zip.read(chunk)
                    if (read == -1) break
                    entryBytes += read
                    if (entryBytes > MAX_ENTRY_BYTES) {
                        throw PatchRejectedException(
                            "补丁单文件解压超过 ${MAX_ENTRY_BYTES / 1024 / 1024} MB 上限：${entry.name}",
                        )
                    }
                    buffer.write(chunk, 0, read)
                }
                totalBytes += entryBytes
                if (totalBytes > MAX_TOTAL_BYTES) {
                    throw PatchRejectedException("补丁总解压量超过 ${MAX_TOTAL_BYTES / 1024 / 1024} MB 上限（疑似 zip bomb，整包拒绝）")
                }
                entries[entry.name] = buffer.toByteArray()
                zip.closeEntry()
            }
        }
        return entries
    }

    private companion object {
        const val SCHEMA = "abyss-patch-v1"
        const val MANIFEST_ENTRY = "patch-manifest.json"
        const val ACTION_REPLACE = "replace"
        const val ACTION_ADD = "add"
        const val ACTION_DELETE = "delete"
        const val BACKUP_PREFIX = ".abyss-patch-backup-"
        const val BACKUP_PREFIX_OLD = ".patch-backup-"

        /** zip bomb 防线：数据层补丁的真实体量是 KB 级，三重上限极端冗余。 */
        const val MAX_ENTRIES = 512
        const val MAX_ENTRY_BYTES = 8L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

        private val json = Json { ignoreUnknownKeys = true }
    }
}
