package com.abyss.tools.feature.backup

import android.content.Context
import com.abyss.tools.core.common.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 备份迁移引擎（v0.8.0 内置化）。
 *
 * 三件事（诚实边界）：
 *  1. 主壳配置与插件清单包导出：filesDir/plugins 目录全量（plugin.json 清单 +
 *     已装数据）、filesDir/blueprints 目录全量、shared_prefs/abyss_settings.xml
 *     原样字节 + 根级 manifest.json，打成 zip；
 *  2. 恢复：先全量校验（ZipGuard 三道闸：条目名 / 体量 / 根白名单），
 *     解包进 filesDir/restore_staging 暂存区，全部成功才合并进 live 目录；
 *  3. 应用私有数据（/data/data/&lt;pkg&gt; 的数据库等）的完整抽取与跨设备
 *     迁移属插件能力 —— 无 Root 不可达，这是系统边界而非工程取舍，
 *     本引擎不做也不假装能做。
 *
 * fail-closed 纪律（本类是恢复入口，必须偏执）：
 *  - 任何不认识的 schema / 缺字段 / 坏 JSON / 路径穿越 / 未知根目录 /
 *    超体量 → 一律拒绝并给人话错误，绝不「尽力恢复」；
 *  - 恢复合并语义：**只覆盖同名文件，绝不删除本机现有文件**（未知文件
 *    全保留）——「合并」不是「替换」，删除用户的未知文件不是备份引擎
 *    该做的决定；一切如实写进 [RestoreReport.notes]；
 *  - 解包阶段全或无（暂存区与 live 目录隔离，解包失败 live 目录零改动）；
 *    合并阶段逐文件原子（先写同目录 tmp 再 rename —— 单文件级回滚边界：
 *    rename 前旧文件保持完整）。
 *
 * prefs 口径的诚实说明：打包的是 shared_prefs/abyss_settings.xml 的磁盘
 * 快照；SharedPreferences.apply() 异步落盘，打包瞬间未落盘的内存态不会
 * 在包里（引擎无法 flush 其它模块的 prefs —— 不装作能）。
 *
 * IO 纪律：重活（build / validate / restore）全部 suspend + 强制
 * [Dispatchers.IO]；[readManifest] 只读单个 manifest 条目（轻量，普通函数，
 * UI 侧请走 validateForRestore 这条 suspend 路径）。
 *
 * manifest.json 读写用 org.json（Android 平台内置，零新增依赖）；可单测
 * 的判定逻辑抽在 [ZipGuard] / [BackupManifestIo]（纯 JVM，见各自的 why）。
 */
object BackupEngine {

    /** manifest.json 的 schema 契约（fail-closed：不认识的一律拒绝）。 */
    const val SCHEMA = "abyss-backup-v1"

    /** zip 根级 manifest 条目名（与根白名单一致）。 */
    private const val MANIFEST_ENTRY = "manifest.json"

    /** 恢复暂存目录（filesDir 下，引擎私有 —— 半程恢复的遗留可安全全清）。 */
    private const val STAGING_DIR = "restore_staging"

    /** 主壳设置文件名（shared_prefs 真实落盘名）。 */
    private const val SETTINGS_FILE_NAME = "abyss_settings.xml"

    /** plugins 根下不入包的直属目录（补丁引擎临时解包区，可重建）。 */
    private const val PLUGINS_TRANSIENT_DIR = "cache"

    // ── 数据模型（纯 JVM，单测可直接构造）────────────────────────────

    /** 备份包根级 manifest 的五字段契约（schema 固定 [SCHEMA]）。 */
    data class BackupManifest(
        val schema: String,
        val appVersion: String,
        val createdAt: Long,
        val pluginCount: Int,
        val settingsIncluded: Boolean,
    )

    /** 恢复预校验结果（UI 确认弹窗的数据源 —— 先看清楚再动刀）。 */
    data class RestorePreview(
        val fileCount: Int,
        val totalBytes: Long,
        val manifest: BackupManifest,
        val roots: List<String>,
    )

    /** 恢复执行报告：restored = 合并进 live 目录的文件数；skipped = 条目
     *  数中未作为数据落盘的项（目录条目 + manifest.json —— 后者已读取
     *  校验，不安装）；notes 逐条如实陈述合并边界。 */
    data class RestoreReport(
        val restoredFiles: Int,
        val skippedFiles: Int,
        val notes: List<String>,
    )

    // ── 导出：配置 + 插件清单包 ──────────────────────────────────────

    /**
     * 生成配置与插件备份包（zip）写到 [out]。
     *
     * 条目布局：plugins/&lt;相对路径&gt;、blueprints/&lt;相对路径&gt;、
     * prefs/abyss_settings.xml（存在才含）、manifest.json（org.json 写出：
     * schema / appVersion（AppInfo.VERSION_NAME）/ createdAt / pluginCount /
     * settingsIncluded）。plugins 直属 cache/ 不入包（临时解包区可重建）。
     */
    suspend fun buildConfigBackup(context: Context, out: File): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching { writeConfigBackup(context.applicationContext, out) }
        }

    private fun writeConfigBackup(context: Context, out: File): File {
        out.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("无法创建备份输出目录：${parent.absolutePath}")
            }
        }
        var pluginCount = 0
        var settingsIncluded = false
        ZipOutputStream(FileOutputStream(out).buffered()).use { zos ->
            // plugins/**：plugin.json 计数即 pluginCount（manifest 的诚实来源）
            val pluginsRoot = File(context.filesDir, "plugins")
            for (file in collectZipAssets(pluginsRoot)) {
                if (file.name == "plugin.json") pluginCount++
                putFileEntry(zos, "plugins/" + zipRelativeName(file, pluginsRoot), file)
            }
            // blueprints/**：全部文件（含 runs.json 执行历史 —— 属于「配置」面）
            val blueprintsRoot = File(context.filesDir, "blueprints")
            for (file in collectZipAssets(blueprintsRoot)) {
                putFileEntry(zos, "blueprints/" + zipRelativeName(file, blueprintsRoot), file)
            }
            // prefs：设置文件原样字节（raw copy，不做 XML 语义层 —— 解析是
            // 恢复端自己的事，字节级保真最诚实）
            val settings = settingsFile(context)
            if (settings.isFile) {
                putFileEntry(zos, "prefs/$SETTINGS_FILE_NAME", settings)
                settingsIncluded = true
            }
            // manifest.json 收尾（org.json：Android 内置，零依赖）
            val manifest = JSONObject()
                .put("schema", SCHEMA)
                .put("appVersion", AppInfo.VERSION_NAME)
                .put("createdAt", System.currentTimeMillis())
                .put("pluginCount", pluginCount)
                .put("settingsIncluded", settingsIncluded)
            zos.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zos.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return out
    }

    /** 收集 root 下入包文件；只裁 root 直属 transient 目录（cache/），深层的
     *  同名目录属于插件数据本体，保留。 */
    private fun collectZipAssets(root: File): List<File> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .onEnter { dir ->
                dir == root || dir.parentFile != root || dir.name != PLUGINS_TRANSIENT_DIR
            }
            .filter { it.isFile }
            .toList()
    }

    /** zip 条目名里的相对路径（相对分区根 plugins/ 或 blueprints/）：统一
     *  '/' 分隔（zip 规范），不带平台分隔符。 */
    private fun zipRelativeName(file: File, sectionRoot: File): String =
        file.relativeTo(sectionRoot).path.replace(File.separatorChar, '/')

    private fun putFileEntry(zos: ZipOutputStream, zipPath: String, file: File) {
        val entry = ZipEntry(zipPath).apply {
            setTime(file.lastModified())
            // 声明解压尺寸：恢复端 ZipGuard 的体量护栏优先信这个真值
            setSize(file.length())
        }
        zos.putNextEntry(entry)
        file.inputStream().use { input -> input.copyTo(zos) }
        zos.closeEntry()
    }

    /** shared_prefs 真实路径（不走 getSharedPreferences —— 要的是文件本体）。 */
    private fun settingsFile(context: Context): File {
        val dataDir = context.applicationInfo.dataDir
            ?: throw IOException("无法定位应用私有数据目录（applicationInfo.dataDir 为空）—— 备份中止，拒绝生成不完整包")
        return File(File(dataDir, "shared_prefs"), SETTINGS_FILE_NAME)
    }

    // ── 读取：manifest 单读（轻量，非 suspend —— UI 请走 validate 路径）──

    /**
     * 只解析 zip 的 manifest.json 条目（fail-closed：缺条目 / 坏 JSON /
     * schema 不符 / 字段缺失类型错误 → 人话错误）。
     */
    fun readManifest(zip: File): Result<BackupManifest> = runCatching {
        ZipFile(zip).use { zf ->
            val entry = zf.getEntry(MANIFEST_ENTRY)
                ?: throw IOException("备份包缺少 manifest.json —— 这不是深渊工具的备份包，拒绝")
            val json = zf.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            parseManifestJson(json)
        }
    }

    /** org.json 解析 + 五字段契约校验（Android 侧路径）。 */
    private fun parseManifestJson(json: String): BackupManifest {
        val obj = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw IOException("manifest.json 不是合法 JSON —— 备份包损坏或来源可疑，拒绝")
        }
        val schema = obj.optString("schema")
        if (schema != SCHEMA) {
            throw IOException("备份包 schema 不符：期望 $SCHEMA，实际「$schema」—— 版本不兼容，拒绝恢复")
        }
        try {
            val appVersion = obj.getString("appVersion")
            if (appVersion.isBlank()) {
                throw IOException("manifest 的 appVersion 为空 —— 备份包不诚实，拒绝")
            }
            return BackupManifest(
                schema = schema,
                appVersion = appVersion,
                createdAt = obj.getLong("createdAt"),
                pluginCount = obj.getInt("pluginCount"),
                settingsIncluded = obj.getBoolean("settingsIncluded"),
            )
        } catch (e: IOException) {
            throw e
        } catch (e: JSONException) {
            throw IOException("manifest.json 字段缺失或类型错误（需要 appVersion / createdAt / pluginCount / settingsIncluded）—— 拒绝恢复")
        }
    }

    // ── 恢复：预校验 → 暂存解包 → 合并 ──────────────────────────────

    /**
     * 恢复预校验：枚举 zip 条目过 ZipGuard 三道闸（条目名 / 体量护栏 /
     * 根白名单）并读 manifest。UI 拿 [RestorePreview] 给用户过目，
     * 确认后才走 [restore]。
     */
    suspend fun validateForRestore(zip: File): Result<RestorePreview> =
        withContext(Dispatchers.IO) {
            runCatching { inspectZip(zip) }
        }

    /** 单包全量体检（restore 前会复检 —— 预览通过≠现在还是同一个包）。 */
    private fun inspectZip(zip: File): RestorePreview {
        if (!zip.isFile) {
            throw IOException("备份包文件不存在或不可读：${zip.absolutePath}")
        }
        var fileCount = 0
        var totalBytes = 0L
        val roots = mutableSetOf<String>()
        ZipFile(zip).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                ZipGuard.entryNameError(entry.name)?.let { throw IOException(it) }
                roots.add(ZipGuard.rootOf(entry.name))
                if (entry.isDirectory) continue
                fileCount++
                // zip 炸弹护栏：声明尺寸优先；头撒谎（size<=0）→ 压缩比启发
                totalBytes += ZipGuard.estimateEntryUncompressedBytes(
                    entry.size,
                    entry.compressedSize,
                )
            }
        }
        ZipGuard.totalBytesError(totalBytes)?.let { throw IOException(it) }
        ZipGuard.unexpectedRootError(roots)?.let { throw IOException(it) }
        // manifest 必在（根白名单不豁免它）—— parseManifestJson 内置人话失败
        val manifest = readManifest(zip).getOrElse { throw it }
        return RestorePreview(
            fileCount = fileCount,
            totalBytes = totalBytes,
            manifest = manifest,
            roots = roots.sorted(),
        )
    }

    /**
     * 执行恢复（调用即视为用户已确认 —— UI 侧先弹 [RestorePreview]
     * 确认框，本方法不再二次询问，但报告里如实陈述做了什么）。
     *
     * 流程：复检 → 解包到 filesDir/restore_staging（每条目双重 zip-slip
     * 闸：名称校验 + canonical 包含性，任一越界整包拒绝并点名条目）→
     * 全部成功才合并（plugins / blueprints 逐文件原子覆盖，prefs 写
     * shared_prefs 真实路径，附「重启生效」说明）→ 清暂存区。
     */
    suspend fun restore(context: Context, zip: File): Result<RestoreReport> =
        withContext(Dispatchers.IO) {
            runCatching { restoreInternal(context.applicationContext, zip) }
        }

    private fun restoreInternal(context: Context, zip: File): RestoreReport {
        // 防御纵深：恢复执行前重新体检（选中到确认之间包可能被换掉）
        inspectZip(zip)

        val filesDir = context.filesDir
        val staging = File(filesDir, STAGING_DIR)
        // 暂存区是引擎私有（上一次半程恢复的遗留）—— 全清重建，绝不触碰 live 目录
        if (staging.exists()) staging.deleteRecursively()
        if (!staging.mkdirs()) {
            throw IOException("无法创建恢复暂存区：${staging.absolutePath}")
        }

        val notes = mutableListOf<String>()
        notes.add("合并策略：只覆盖同名文件，本机现有文件一律保留（未删除任何本地文件）")

        var restored = 0
        var skipped = 0
        var manifestConsumed = false
        try {
            var dirEntries = 0
            var actualTotal = 0L
            ZipFile(zip).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) {
                        dirEntries++
                        continue
                    }
                    ZipGuard.entryNameError(entry.name)?.let { throw IOException(it) }
                    val target = File(staging, entry.name)
                    ensureInsideStaging(target, staging, entry.name)
                    target.parentFile?.let { parent ->
                        if (!parent.exists() && !parent.mkdirs()) {
                            throw IOException("无法创建解包目录：${parent.absolutePath}")
                        }
                    }
                    zf.getInputStream(entry).use { input ->
                        FileOutputStream(target).use { out ->
                            // 头部撒谎对策：中央目录声明可伪造，实收字节直接对账
                            actualTotal += input.copyTo(out)
                            if (actualTotal > ZipGuard.MAX_TOTAL_UNCOMPRESSED_BYTES) {
                                throw IOException("解压实际总量超过 64 MB 上限（当前条目：${entry.name}）—— 疑似 zip 炸弹，中止")
                            }
                        }
                    }
                    if (entry.name == MANIFEST_ENTRY) manifestConsumed = true
                }
            }

            // ── 解包全量成功才到这 —— 此前 live 目录零改动 ─────────────
            restored += mergeTree(File(staging, "plugins"), File(filesDir, "plugins"), "plugins", notes)
            restored += mergeTree(File(staging, "blueprints"), File(filesDir, "blueprints"), "blueprints", notes)

            // prefs：写 shared_prefs 真实路径（UI 已确认 —— 引擎如实报告重启边界）
            val stagingPrefs = File(File(staging, "prefs"), SETTINGS_FILE_NAME)
            if (stagingPrefs.isFile) {
                val prefsDest = settingsFile(context)
                prefsDest.parentFile?.let { parent ->
                    if (!parent.exists() && !parent.mkdirs()) {
                        throw IOException("无法创建 shared_prefs 目录：${parent.absolutePath}")
                    }
                }
                copyAtomically(stagingPrefs, prefsDest)
                restored++
                notes.add("设置已写入 shared_prefs/$SETTINGS_FILE_NAME —— 重启 App 后生效（生效前旧设置仍在内存）")
            } else {
                notes.add("备份包不含设置文件 —— 未触碰现有设置")
            }

            // manifest.json 已读取校验，不作为数据落盘（计入跳过项 —— 如实）
            skipped = dirEntries + if (manifestConsumed) 1 else 0
            if (manifestConsumed) {
                notes.add("manifest.json 已校验并计入统计（不作为数据落盘）")
            }
        } finally {
            // 无论成败都清暂存区（引擎私有目录，清了不影响 live 数据）
            if (staging.exists()) staging.deleteRecursively()
        }
        notes.add("暂存区 restore_staging 已清理")
        return RestoreReport(restoredFiles = restored, skippedFiles = skipped, notes = notes)
    }

    /** 合并一棵树到 live 根：逐文件原子覆盖（同名盖、新增进、未知不动）。 */
    private fun mergeTree(sourceDir: File, targetRoot: File, label: String, notes: MutableList<String>): Int {
        if (!sourceDir.isDirectory) {
            notes.add("备份包不含 $label/ —— 跳过")
            return 0
        }
        var count = 0
        sourceDir.walkTopDown()
            .filter { it.isFile }
            .forEach { src ->
                val dest = File(targetRoot, src.relativeTo(sourceDir).path)
                dest.parentFile?.let { parent ->
                    if (!parent.exists() && !parent.mkdirs()) {
                        throw IOException("无法创建合并目录：${parent.absolutePath}")
                    }
                }
                copyAtomically(src, dest)
                count++
            }
        if (count == 0) {
            notes.add("备份包 $label/ 为空 —— 未合并任何文件")
        } else {
            notes.add("$label 已合并：$count 个文件（同名覆盖，未删除本机多余文件）")
        }
        return count
    }

    /**
     * 原子文件替换：先写同目录 .abyss-restore.tmp 再 rename —— 合并中途
     * 崩溃不会留半截文件，rename 前旧文件保持完整（单文件级回滚边界）。
     */
    private fun copyAtomically(src: File, dest: File) {
        val tmp = File(dest.parentFile, dest.name + ".abyss-restore.tmp")
        try {
            src.inputStream().use { input ->
                FileOutputStream(tmp).use { out -> input.copyTo(out) }
            }
            if (!tmp.renameTo(dest)) {
                // rename 失败（被占用等）→ 保守直拷兜底（仍覆盖目标）
                src.copyTo(dest, overwrite = true)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** zip-slip 第二道闸：canonical 包含性 —— 白名单之外的最后一道防线。 */
    private fun ensureInsideStaging(target: File, staging: File, entryName: String) {
        val stagingRoot = staging.canonicalPath + File.separator
        if (!target.canonicalPath.startsWith(stagingRoot)) {
            throw IOException(
                "zip 条目「$entryName」解包越界（目标 ${target.path} 落在暂存区之外）—— 路径穿越，中止整个恢复",
            )
        }
    }
}

/**
 * 恢复安全闸（纯 JVM，零 Android 依赖 —— 单测锚定的核心防线）。
 *
 * 三道闸：
 *  1. 条目名：绝对路径（"/..."）、含 ".."、含反斜杠（Windows 形态的
 *     穿越）、空名 → 拒绝。为什么按「包含 ..」而不是按路径段判：我们的
 *     schema 里文件名出现 ".." 本就不是合法资产名，宁可误杀（fail-closed）
 *     不可漏放 —— 恢复入口没有「宽松」的资格；
 *  2. 体量：累计解压总量超 [MAX_TOTAL_UNCOMPRESSED_BYTES]（zip 炸弹护栏）。
 *     单条目声明尺寸 >0 信声明；<=0（头撒谎 / 未写）按压缩比启发
 *     [COMPRESSION_RATIO_HEURISTIC] 倍估 —— 上限直接对累计值生效；
 *  3. 根白名单：zip 顶层只允许 [ALLOWED_ROOTS]（manifest.json / plugins /
 *     blueprints / prefs）—— 出现任何未知根（哪怕无害的 README）即整包
 *     拒绝：我们不认识的容器内容，凭什么放它落地。
 */
object ZipGuard {

    /** 解压总量上限（64 MB —— 配置与清单包的正常体量远低于此）。 */
    const val MAX_TOTAL_UNCOMPRESSED_BYTES: Long = 64L * 1024 * 1024

    /** 头撒谎 / 未声明尺寸时的压缩比启发倍数。 */
    const val COMPRESSION_RATIO_HEURISTIC: Long = 20L

    /** zip 顶层根白名单（与引擎备份布局一字不差）。 */
    val ALLOWED_ROOTS: Set<String> = setOf("manifest.json", "plugins", "blueprints", "prefs")

    /** 条目名校验：安全返回 null，否则人话错误（含条目名，可直接抛给用户）。 */
    fun entryNameError(entryName: String): String? = when {
        entryName.isEmpty() ->
            "zip 条目名为空 —— 拒绝"
        entryName.startsWith("/") ->
            "zip 条目使用绝对路径：「$entryName」—— 拒绝"
        entryName.contains("\\") ->
            "zip 条目名含反斜杠：「$entryName」—— 疑似 Windows 路径形态穿越，拒绝"
        entryName.contains("..") ->
            "zip 条目名含 \"..\"：「$entryName」—— 路径穿越，拒绝"
        else -> null
    }

    /** 单条目解压体量估算（声明尺寸优先，头撒谎走压缩比启发）。 */
    fun estimateEntryUncompressedBytes(declaredSize: Long, compressedSize: Long): Long =
        if (declaredSize > 0) {
            declaredSize
        } else {
            compressedSize.coerceAtLeast(0) * COMPRESSION_RATIO_HEURISTIC
        }

    /** 累计体量护栏：超限返回人话错误，否则 null。 */
    fun totalBytesError(totalBytes: Long): String? =
        if (totalBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
            "备份包解压总量约 ${totalBytes / (1024 * 1024)} MB，超过 ${MAX_TOTAL_UNCOMPRESSED_BYTES / (1024 * 1024)} MB 上限 —— 疑似 zip 炸弹，拒绝"
        } else {
            null
        }

    /** 条目的顶层根（"/" 前的首段；"manifest.json" 的根就是它自己）。 */
    fun rootOf(entryName: String): String = entryName.substringBefore('/')

    /** 根白名单校验：全部合法返回 null，否则点名未知根的人话错误。 */
    fun unexpectedRootError(roots: Collection<String>): String? {
        val unknown = roots.filter { it !in ALLOWED_ROOTS }.distinct().sorted()
        return if (unknown.isEmpty()) {
            null
        } else {
            "备份包包含未知根目录：${unknown.joinToString("、")}（只允许 manifest.json / plugins / blueprints / prefs）—— 拒绝"
        }
    }
}

/**
 * manifest.json 的纯 JVM 编解码（手写字符串模板 + 针对 5 个已知字段的正则）。
 *
 * 为什么手写而不用 org.json：org.json 在 Android 单测类路径上是空桩
 * （调用即抛 "not mocked"），而 zip 校验核心按仓库纪律必须可纯 JVM 单测；
 * schema 是我们自己的五字段契约（abyss-backup-v1，字段名 / 类型固定），
 * 手写模板 + 针对性正则即可钉死格式契约。Android 侧 [BackupEngine] 用
 * org.json 读写同一字段集 —— 两端口径由本类的 roundtrip 单测锚定不漂移。
 *
 * 已知边界（如实）：字符串转义只产出 / 只接受 \" 与 \\（我们的 schema 与
 * SemVer 版本号不会出现别的形态）；出现其它转义序列就地拒绝（fail-closed）。
 */
object BackupManifestIo {

    private val SCHEMA_RE = stringFieldRegex("schema")
    private val APP_VERSION_RE = stringFieldRegex("appVersion")
    private val CREATED_AT_RE = numberFieldRegex("createdAt")
    private val PLUGIN_COUNT_RE = numberFieldRegex("pluginCount")
    private val SETTINGS_INCLUDED_RE = booleanFieldRegex("settingsIncluded")

    /** 生成 manifest.json 文本（字段顺序固定 —— 模板即契约）。 */
    fun buildManifestJson(manifest: BackupEngine.BackupManifest): String =
        "{\n" +
            "  \"schema\": \"${jsonEscape(manifest.schema)}\",\n" +
            "  \"appVersion\": \"${jsonEscape(manifest.appVersion)}\",\n" +
            "  \"createdAt\": ${manifest.createdAt},\n" +
            "  \"pluginCount\": ${manifest.pluginCount},\n" +
            "  \"settingsIncluded\": ${manifest.settingsIncluded}\n" +
            "}\n"

    /** 解析 manifest.json（fail-closed：缺字段 / 坏 schema / 坏数值 → 人话错误）。 */
    fun parseManifestJson(json: String): Result<BackupEngine.BackupManifest> = runCatching {
        val schema = SCHEMA_RE.find(json)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("manifest 缺少 schema 字段 —— 拒绝")
        if (schema != BackupEngine.SCHEMA) {
            throw IllegalArgumentException(
                "manifest schema 不符：期望 ${BackupEngine.SCHEMA}，实际「${jsonEscape(schema)}」—— 拒绝",
            )
        }
        val appVersion = unescapeJsonString(
            APP_VERSION_RE.find(json)?.groupValues?.get(1)
                ?: throw IllegalArgumentException("manifest 缺少 appVersion 字段 —— 拒绝"),
        )
        if (appVersion.isBlank()) {
            throw IllegalArgumentException("manifest 的 appVersion 为空 —— 备份包不诚实，拒绝")
        }
        val createdAt = (CREATED_AT_RE.find(json)?.groupValues?.get(1) ?: "-1").toLong()
        if (createdAt <= 0) {
            throw IllegalArgumentException("manifest 的 createdAt 缺失或非正数（$createdAt）—— 拒绝")
        }
        val pluginCount = (PLUGIN_COUNT_RE.find(json)?.groupValues?.get(1) ?: "-1").toInt()
        if (pluginCount < 0) {
            throw IllegalArgumentException("manifest 的 pluginCount 缺失或为负（$pluginCount）—— 拒绝")
        }
        val settingsIncluded = SETTINGS_INCLUDED_RE.find(json)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("manifest 缺少 settingsIncluded 字段（只接受 true/false）—— 拒绝")
        BackupEngine.BackupManifest(
            schema = schema,
            appVersion = appVersion,
            createdAt = createdAt,
            pluginCount = pluginCount,
            settingsIncluded = settingsIncluded == "true",
        )
    }

    // ── 内部：模板与正则（一处定义，字段名不散落）────────────────────

    /** 匹配 "field": "value"（value 容忍 \" 与 \\ 转义，容忍冒号两侧空白）。 */
    private fun stringFieldRegex(field: String): Regex =
        Regex("\"$field\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")

    /** 匹配 "field": 123（容忍负号 —— 负值交由上层的语义校验点名拒绝）。 */
    private fun numberFieldRegex(field: String): Regex =
        Regex("\"$field\"\\s*:\\s*(-?\\d+)")

    /** 匹配 "field": true|false（其它值不匹配 → 上层按缺字段拒绝）。 */
    private fun booleanFieldRegex(field: String): Regex =
        Regex("\"$field\"\\s*:\\s*(true|false)")

    /** 只产出 \" 与 \\ 两类转义（模板口径，见类注释的已知边界）。 */
    private fun jsonEscape(raw: String): String =
        raw.replace("\\", "\\\\").replace("\"", "\\\"")

    /** 只接受 \" 与 \\；其它转义序列就地拒绝（fail-closed）。 */
    private fun unescapeJsonString(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    else -> throw IllegalArgumentException("manifest 字符串含不支持的转义（\\$next）—— 拒绝")
                }
                i += 2
            } else {
                out.append(c)
                i += 1
            }
        }
        return out.toString()
    }
}
