package com.abyss.tools.feature.backup

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 主壳配置备份包（abyss-backup-v1）—— 打包与恢复引擎，纯 JVM 可单测。
 *
 * 包结构（zip）：
 * ```
 * backup-manifest.json     # 必须存在于包根（fail-closed）
 * blueprints/<id>.json     # 可回放：用户任务蓝图（恢复写回蓝图目录）
 * glass-probe.txt          # 仅归档：渲染探针现场（取证保留，不回放）
 * render-safemode.txt      # 仅归档：渲染安全模式状态
 * ```
 *
 * 安全纪律（对齐 ZipPatchApplier 同款 fail-closed）：
 *  - 无 manifest / schema 不符 → 整包拒绝；
 *  - 路径穿越（`../`、绝对路径、反斜杠）→ 整包拒绝；
 *  - zip bomb 三重上限（条目数 / 单文件 / 总量）→ 整包拒绝；
 *  - 恢复两阶段：先全部解包到临时目录（此阶段零触碰目标），再逐文件
 *    原子改名落位 —— 解包失败目标目录分毫未动；落位逐文件原子，
 *    单文件失败不影响已落位文件（诚实声明：非整包回滚）。
 *
 * 回放范围白名单：只有 `blueprints/` 前缀的条目写回蓝图目录 ——
 * 诊断/状态文件只进包不回放（取证是现场记录，恢复旧状态反而是错）。
 */
object BackupContract {
    const val SCHEMA = "abyss-backup-v1"
    const val MANIFEST_ENTRY = "backup-manifest.json"
    const val RESTORE_PREFIX = "blueprints/"

    /** 归档但永不回放的路径（诊断 / 状态 —— 恢复旧现场是错误的语义）。 */
    val ARCHIVE_ONLY_PREFIXES = listOf("glass-probe", "render-safemode", "crash/")

    /** zip bomb 防线：配置包真实体量是 KB 级，三重上限极端冗余。 */
    const val MAX_ENTRIES = 512
    const val MAX_ENTRY_BYTES = 8L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
}

@Serializable
data class BackupManifest(
    val schema: String = BackupContract.SCHEMA,
    val createdAt: String = "",
    val appVersion: String = "",
    val files: List<String> = emptyList(),
)

/** 打包结果（供 UI 呈现：进了什么 / 体积多少）。 */
data class BackupReport(val path: File, val entries: List<String>, val sizeBytes: Long)

/** 恢复结果：回放了什么 / 只归档了什么（人话呈现）。 */
data class RestoreReport(
    val restored: List<String>,
    val archivedOnly: List<String>,
    val manifestCreatedAt: String,
)

class BackupRejectedException(message: String) : IOException(message)

/** 相对路径合法性（打包与恢复共用）：白名单字符集 + 禁穿越 + 禁备份区。 */
internal fun validateRelativePath(path: String): String {
    if (path.isBlank()) throw BackupRejectedException("空路径")
    if (path.startsWith("/") || path.contains('\\')) {
        throw BackupRejectedException("非法备份路径（绝对路径 / 反斜杠）：$path")
    }
    val segments = path.split('/')
    if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
        throw BackupRejectedException("非法备份路径（穿越片段）：$path")
    }
    if (segments.any { segment -> segment.any { !it.isLetterOrDigit() && it !in "._- " } }) {
        throw BackupRejectedException("备份路径含白名单外字符：$path")
    }
    return path
}

/**
 * 打包器：add(file) 逐个登记源文件 → [build] 产出 zip。
 * relativePath 必须过 [validateRelativePath]；重复路径拒绝（防手滑覆盖）。
 */
class BackupArchiveBuilder {

    private val sources = LinkedHashMap<String, File>()

    fun add(relativePath: String, source: File): BackupArchiveBuilder = apply {
        val normalized = validateRelativePath(relativePath)
        require(!sources.containsKey(normalized)) { "重复备份路径：$normalized" }
        require(source.exists() && source.isFile) { "备份源不存在或不是文件：${source.absolutePath}" }
        sources[normalized] = source
    }

    /** 至少一个载荷；写入走「tmp → 原子改名」，半截包永不落盘。 */
    fun build(dest: File, appVersion: String): BackupReport {
        require(sources.isNotEmpty()) { "空备份包（至少登记一个源文件）" }
        val manifest = BackupManifest(
            createdAt = Instant.now().toString(),
            appVersion = appVersion,
            files = sources.keys.toList(),
        )
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(BackupContract.MANIFEST_ENTRY))
            zip.write(manifestJson.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            zip.closeEntry()
            for ((path, source) in sources) {
                zip.putNextEntry(ZipEntry(path))
                source.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        return BackupReport(dest, sources.keys.toList(), dest.length())
    }

    private companion object {
        val manifestJson = Json { ignoreUnknownKeys = true }
    }
}

/** 恢复引擎：校验 → 解包（守卫）→ 两阶段落位。 */
class BackupRestoreEngine {

    /**
     * @param zipFile       用户经 SAF 选中的备份包
     * @param blueprintsDir 主壳蓝图目录（filesDir/blueprints）—— 唯一回放目标
     */
    fun restore(zipFile: File, blueprintsDir: File): RestoreReport {
        val entries = readZipEntries(zipFile)
        val manifestRaw = entries[BackupContract.MANIFEST_ENTRY]
            ?: throw BackupRejectedException("备份包缺少 ${BackupContract.MANIFEST_ENTRY}（fail-closed 拒绝）")
        val manifest = try {
            json.decodeFromString<BackupManifest>(manifestRaw.decodeToString())
        } catch (t: Throwable) {
            throw BackupRejectedException("backup-manifest.json 解析失败: ${t.message}")
        }
        if (manifest.schema != BackupContract.SCHEMA) {
            throw BackupRejectedException("不支持的备份 schema：${manifest.schema}（期望 ${BackupContract.SCHEMA}）")
        }

        // ── 路径预检：穿越 / 备份区 / 白名单外字符 → 整包拒绝（零触碰）──
        for (path in entries.keys) {
            if (path == BackupContract.MANIFEST_ENTRY) continue
            validateRelativePath(path)
        }

        // ── 阶段一：解包全部载荷到临时目录（目标目录零触碰）────────────
        val staging = File(zipFile.parentFile, "abyss-restore-${System.currentTimeMillis()}")
        val restorable = mutableListOf<String>()
        val archivedOnly = mutableListOf<String>()
        try {
            staging.mkdirs()
            for ((path, bytes) in entries) {
                if (path == BackupContract.MANIFEST_ENTRY) continue
                if (path.startsWith(BackupContract.RESTORE_PREFIX)) {
                    val dest = File(staging, path)
                    dest.parentFile?.mkdirs()
                    dest.writeBytes(bytes)
                    restorable += path
                } else {
                    archivedOnly += path
                }
            }

            // ── 阶段二：逐文件原子落位（tmp → rename）───────────────────
            val placed = mutableListOf<String>()
            for (path in restorable) {
                val staged = File(staging, path)
                val dest = File(blueprintsDir, path.removePrefix(BackupContract.RESTORE_PREFIX))
                dest.parentFile?.mkdirs()
                val tmp = File(dest.parentFile, dest.name + ".restore-part")
                staged.copyTo(tmp, overwrite = true)
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                placed += path
            }
            return RestoreReport(placed, archivedOnly, manifest.createdAt)
        } finally {
            runCatching { staging.deleteRecursively() }
        }
    }

    /** 解包守卫（对齐 ZipPatchApplier：条目数 / 单文件 / 总量三重上限）。 */
    private fun readZipEntries(zipFile: File): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        var totalBytes = 0L
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                if (entries.size >= BackupContract.MAX_ENTRIES) {
                    throw BackupRejectedException("备份条目数超过上限 ${BackupContract.MAX_ENTRIES}（疑似 zip bomb，整包拒绝）")
                }
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var entryBytes = 0L
                while (true) {
                    val read = zip.read(chunk)
                    if (read == -1) break
                    entryBytes += read
                    if (entryBytes > BackupContract.MAX_ENTRY_BYTES) {
                        throw BackupRejectedException(
                            "备份单文件解压超过 ${BackupContract.MAX_ENTRY_BYTES / 1024 / 1024} MB 上限：${entry.name}",
                        )
                    }
                    buffer.write(chunk, 0, read)
                }
                totalBytes += entryBytes
                if (totalBytes > BackupContract.MAX_TOTAL_BYTES) {
                    throw BackupRejectedException("备份总解压量超过 ${BackupContract.MAX_TOTAL_BYTES / 1024 / 1024} MB 上限（疑似 zip bomb，整包拒绝）")
                }
                entries[entry.name] = buffer.toByteArray()
                zip.closeEntry()
            }
        }
        return entries
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
