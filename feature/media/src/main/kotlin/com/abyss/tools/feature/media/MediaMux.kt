package com.abyss.tools.feature.media

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec.BufferInfo
import android.media.MediaExtractor
// 同名规避：包内已有纯模型对象 MediaFormat（格式化），android.media.MediaFormat
// 用别名引入 —— 显式 import 优先于同包声明，但别名让两处语义零歧义。
import android.media.MediaFormat as AndroidMediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume

/**
 * 媒体引擎：MediaExtractor 拆封装 → MediaMuxer 重封装，全程零转码零质量损失
 * （样本与编码参数原样搬运，连 MediaFormat 都不重造）。
 *
 * 引擎纪律：
 *  - 重活全在 Dispatchers.IO；双通道取消 —— 协程取消（ensureActive，页面离开时
 *    rememberCoroutineScope 注销生效）+ 用户取消（[cancel] 的 @Volatile 标志，
 *    循环逐样本检查）。出口一律 Result + 中文人话错误，不留半成品临时文件；
 *  - 发布走 saveOutput 双分支：API 29+ MediaStore（RELATIVE_PATH + IS_PENDING），
 *    API 26–28 公共目录直写 + MediaScanner 登记（同 QrImageIo 语义，写权限由
 *    manifest 声明 maxSdkVersion=28 + UI 层运行时申请）；
 *  - MediaMuxer.release() / MediaExtractor.release() 永远在 finally，且各自
 *    runCatching 包裹（release 自身可能抛，不能盖掉主错误）；
 *  - 已知边界（诚实声明而非缺陷伪装）：仅 AAC 可进 M4A；裁剪起点只能对齐关键帧；
 *    转码 / 批量流水线属 FFmpeg 级插件能力（路线图）。
 */
object MediaMux {

    /** 进度语义：已复制样本时间戳 / 目标时长，钳在 0..1（时间轴进度，非样本计数）。 */
    data class Progress(val fraction: Float)

    /** 用户主动取消（普通 IllegalStateException —— 与协程取消区分开，Result 正常折叠）。 */
    class CancelledException : IllegalStateException("已取消")

    /** 输出公共子目录（API 29+ 的 RELATIVE_PATH 段 / API 26–28 的公共目录名共用）。 */
    private const val OUTPUT_DIR_NAME = "AbyssTools"

    /** 复用的样本缓冲 1MB：单样本超限属极端 4K 码流，交由异常路径诚实上报。 */
    private const val SAMPLE_BUFFER_BYTES = 1 shl 20

    /** 进度回调粒度：至少前进 1% 才上报，避免逐样本回调打爆重组。 */
    private const val PROGRESS_STEP = 0.01f

    /** API 26–28 MediaScanner 桥的超时：扫描服务僵死时退回 file://（不无限挂起）。 */
    private const val SCAN_TIMEOUT_MS = 5_000L

    /** 默认输出名时间戳（带毫秒防连点重名覆盖）。 */
    private val NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS")

    /**
     * 一次性取消标志：下一次 remux 开始时会重置。当前模型为单任务串行
     * （UI 的「开始处理」在任务处理中不可再发），因此全局广播 == 精确命中；
     * 多任务并发流水线属插件路线，届时改为按任务句柄取消。
     */
    @Volatile
    private var cancelled = false

    /** 请求取消当前处理（对排队后尚未进入 IO 循环的任务同样生效）。 */
    fun cancel() {
        cancelled = true
    }

    /** 默认输出名："abyss_{op}_{timestamp}.{ext}"（时间戳含毫秒，重试不覆盖旧产物）。 */
    private fun defaultName(op: MediaOp, ext: String): String =
        "abyss_${op.name.lowercase()}_${LocalDateTime.now().format(NAME_FORMAT)}.$ext"

    // ── 探测 ───────────────────────────────────────────────────────────────

    /**
     * 源信息探测：MediaMetadataRetriever 取时长 / 分辨率 / 旋转 / 总码率，
     * 另以 MediaExtractor 逐轨读 MediaFormat 取音视频编码 MIME（retriever 不暴露
     * per-track mime —— 这是编码字段必须走第二遍的原因）。
     * 两遍打不开同一 Uri 即整体失败（fail-closed，不降级成「部分字段可用」）。
     */
    suspend fun probe(context: Context, uri: Uri): Result<MediaProbe> = withContext(Dispatchers.IO) {
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val width = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull()
                val height = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull()
                val rotation = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull()
                val bitrate = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toLongOrNull()
                val (videoMime, audioMime) = firstTrackMimes(context, uri)
                MediaProbe(durationMs, width, height, rotation, videoMime, audioMime, bitrate)
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    /** 首条视频轨 / 首条音频轨的 MIME（都没有 → null，UI 显示「未知 / 无音轨」）。 */
    private fun firstTrackMimes(context: Context, uri: Uri): Pair<String?, String?> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri)
            var videoMime: String? = null
            var audioMime: String? = null
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(AndroidMediaFormat.KEY_MIME) ?: continue
                if (videoMime == null && mime.startsWith("video/")) videoMime = mime
                if (audioMime == null && mime.startsWith("audio/")) audioMime = mime
            }
            return videoMime to audioMime
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * MediaExtractor 无 (Context, Uri) 重载（那是 MediaMetadataRetriever 的 API）——
     * 统一经 AssetFileDescriptor 打开，用后即关；打不开给人话错误。
     */
    private fun MediaExtractor.setDataSource(context: Context, uri: Uri) {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { afd ->
            setDataSource(afd.fileDescriptor)
        } ?: throw IOException("无法打开所选媒体：$uri")
    }

    // ── 三个公开操作 ───────────────────────────────────────────────────────

    /**
     * 提取音频 → M4A（Music/AbyssTools）。仅支持 AAC（mp4a 家族）：MediaMuxer 的
     * MPEG_4 输出只认 AAC 音轨，MP3 / Opus 等其他编码在这里被拒绝并明说 ——
     * 重采样进 M4A 属转码，是 FFmpeg 插件的能力边界，不冒充支持。
     */
    suspend fun extractAudio(
        context: Context,
        uri: Uri,
        destName: String = defaultName(MediaOp.EXTRACT_AUDIO, "m4a"),
        onProgress: (Float) -> Unit = {},
    ): Result<Uri> = remux(
        context = context,
        uri = uri,
        destName = destName,
        outMime = "audio/mp4",
        kind = MediaKind.AUDIO,
        selector = { mime -> mime.startsWith("audio/") },
        onlyFirstMatch = true,
        emptyError = "源文件没有音轨",
        trackCheck = { mime ->
            if (!isAacFamily(mime)) {
                throw IllegalStateException(
                    "音轨编码 非 AAC（$mime），无法封装进 M4A —— 请等待 FFmpeg 级插件能力",
                )
            }
        },
        seekToUs = null,
        untilUs = null,
        onProgress = onProgress,
    )

    /**
     * 去除音轨 → 无声视频（Movies/AbyssTools）：只选视频轨重封装，样本与参数原样，
     * 输出与源同画质；多视频轨源只保留首条（与「选视频轨」的单数语义一致）。
     */
    suspend fun stripAudio(
        context: Context,
        uri: Uri,
        destName: String = defaultName(MediaOp.STRIP_AUDIO, "mp4"),
        onProgress: (Float) -> Unit = {},
    ): Result<Uri> = remux(
        context = context,
        uri = uri,
        destName = destName,
        outMime = "video/mp4",
        kind = MediaKind.VIDEO,
        selector = { mime -> mime.startsWith("video/") },
        onlyFirstMatch = true,
        emptyError = "源文件没有视频轨，无法生成无声视频",
        trackCheck = {},
        seekToUs = null,
        untilUs = null,
        onProgress = onProgress,
    )

    /**
     * 片段裁剪 → Movies/AbyssTools（音视频轨全选，逐轨写入）。
     *
     * 关键帧对齐（诚实代价）：起点 seek 到 SEEK_TO_PREVIOUS_SYNC —— 无重编码下
     * 不能从非关键帧开始拷贝（否则头几帧花屏），因此实际起点会回退到前一关键帧，
     * 导出片段可能比所选区间略长；保留原始时间戳（避免重算 PTS 引入负值）。
     * 终点侧按样本时间戳硬切，区间外样本跳过不写（音视频交错，跳过后继续消费，
     * 直到各自轨道越过终点）。
     */
    suspend fun trim(
        context: Context,
        uri: Uri,
        destName: String = defaultName(MediaOp.TRIM, "mp4"),
        startMs: Long,
        endMs: Long,
        onProgress: (Float) -> Unit = {},
    ): Result<Uri> {
        if (startMs < 0L || endMs <= startMs) {
            return Result.failure(
                IllegalArgumentException("片段区间无效（起点 ${startMs}ms / 终点 ${endMs}ms，终点须大于起点）"),
            )
        }
        return remux(
            context = context,
            uri = uri,
            destName = destName,
            outMime = "video/mp4",
            kind = MediaKind.VIDEO,
            selector = { mime -> mime.startsWith("video/") || mime.startsWith("audio/") },
            onlyFirstMatch = false,
            emptyError = "源文件没有可复制的轨道",
            trackCheck = {},
            seekToUs = startMs * 1000L,
            untilUs = endMs * 1000L,
            onProgress = onProgress,
        )
    }

    // ── 重封装核心 ─────────────────────────────────────────────────────────

    /** 输出域：音频走 Music，视频走 Movies（MediaStore 集合与旧版公共目录共用该映射）。 */
    private enum class MediaKind(val publicDir: String) {
        AUDIO(Environment.DIRECTORY_MUSIC),
        VIDEO(Environment.DIRECTORY_MOVIES),
    }

    /**
     * 统一重封装管线：cacheDir/media-lab 下的临时文件先成型，成功才发布进公共
     * 媒体库 —— 失败/取消/异常一律删临时文件，绝不把半成品暴露给相册。
     */
    private suspend fun remux(
        context: Context,
        uri: Uri,
        destName: String,
        outMime: String,
        kind: MediaKind,
        selector: (String) -> Boolean,
        onlyFirstMatch: Boolean,
        emptyError: String,
        trackCheck: (String) -> Unit,
        seekToUs: Long?,
        untilUs: Long?,
        onProgress: (Float) -> Unit,
    ): Result<Uri> = withContext(Dispatchers.IO) {
        cancelled = false // 一次性取消信号只作用于本轮（见 cancel() 注释）
        val tempDir = File(context.cacheDir, "media-lab")
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            return@withContext Result.failure<Uri>(IOException("无法创建处理缓存目录"))
        }
        val tempFile = File(tempDir, destName)
        if (tempFile.exists()) tempFile.delete()
        val extractor = MediaExtractor()
        runCatching {
            try {
                extractor.setDataSource(context, uri)
                val muxer = MediaMuxer(
                    tempFile.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                )
                try {
                    val indexMap = selectTracks(extractor, muxer, selector, onlyFirstMatch, trackCheck)
                    if (indexMap.isEmpty()) throw IllegalStateException(emptyError)
                    val durationUs = selectedDurationUs(extractor, indexMap)
                    muxer.start()
                    if (seekToUs != null) {
                        extractor.seekTo(seekToUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    }
                    val progressBaseUs = seekToUs ?: 0L
                    val progressSpanUs = if (seekToUs != null && untilUs != null) {
                        untilUs - seekToUs
                    } else {
                        durationUs
                    }
                    val copied = copySamples(
                        extractor, muxer, indexMap,
                        untilUs, progressBaseUs, progressSpanUs, onProgress,
                    )
                    if (copied == 0) {
                        throw IllegalStateException("该区间内没有可复制的样本（片段过短或时间越界）")
                    }
                    muxer.stop()
                } finally {
                    runCatching { muxer.release() }
                }
                val outUri = saveOutput(context, tempFile, destName, outMime, kind)
                tempFile.delete()
                outUri
            } finally {
                runCatching { extractor.release() }
            }
        }.onFailure {
            // 临时文件在任何失败路径（含取消）下都清掉；saveOutput 自身已清理 MediaStore 占位行
            tempFile.delete()
        }
    }

    /**
     * 逐轨选入 muxer（保留 extractor 的原始 MediaFormat —— 零转码的关键：编码
     * 参数与 csd 原样过账）。onlyFirstMatch = true 时命中一条即不再选（提取音频 /
     * 去音轨的「首轨」语义）；trackCheck 在选入前做编码能力校验（AAC 门禁）。
     */
    private fun selectTracks(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        selector: (String) -> Boolean,
        onlyFirstMatch: Boolean,
        trackCheck: (String) -> Unit,
    ): MutableMap<Int, Int> {
        val indexMap = HashMap<Int, Int>()
        for (i in 0 until extractor.trackCount) {
            if (onlyFirstMatch && indexMap.isNotEmpty()) continue
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(AndroidMediaFormat.KEY_MIME) ?: continue
            if (selector(mime)) {
                trackCheck(mime)
                extractor.selectTrack(i)
                indexMap[i] = muxer.addTrack(format)
            }
        }
        return indexMap
    }

    /** 已选轨道的时长上限（微秒，取自各轨 MediaFormat.KEY_DURATION —— 进度分母）。 */
    private fun selectedDurationUs(extractor: MediaExtractor, indexMap: Map<Int, Int>): Long {
        var durationUs = 0L
        for (trackIndex in indexMap.keys) {
            val format = extractor.getTrackFormat(trackIndex)
            if (format.containsKey(AndroidMediaFormat.KEY_DURATION)) {
                durationUs = maxOf(durationUs, format.getLong(AndroidMediaFormat.KEY_DURATION))
            }
        }
        return durationUs
    }

    /**
     * 样本搬运主循环：1MB 复用缓冲 + 复用 BufferInfo，逐样本 writeSampleData。
     * 每轮先查用户取消标志与协程活性（双通道），直到 EOS / untilUs 越界。
     * 返回复制的样本数 —— 0 视为失败（muxer.stop() 对空输出会抛，我们提前给人话）。
     */
    private suspend fun copySamples(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        indexMap: Map<Int, Int>,
        untilUs: Long?,
        progressBaseUs: Long,
        progressSpanUs: Long,
        onProgress: (Float) -> Unit,
    ): Int {
        val buffer = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES)
        val info = BufferInfo()
        var copied = 0
        var lastReported: Progress? = null
        onProgress(0f)
        while (true) {
            if (cancelled) throw CancelledException()
            currentCoroutineContext().ensureActive()
            val trackIndex = extractor.sampleTrackIndex
            if (trackIndex < 0) break // EOS
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break // 读尽
            val pts = extractor.sampleTime
            if (untilUs != null && pts >= untilUs) {
                // 区间外样本跳过但不终止：音视频交错，另一轨可能仍有区间内样本
                extractor.advance()
                continue
            }
            if (size == 0) {
                extractor.advance()
                continue
            }
            info.offset = 0
            info.size = size
            info.presentationTimeUs = pts
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(indexMap.getValue(trackIndex), buffer, info)
            copied++
            if (progressSpanUs > 0) {
                val fraction = ((pts - progressBaseUs).coerceAtLeast(0L)).toFloat() /
                    progressSpanUs.toFloat()
                val progress = Progress(fraction.coerceIn(0f, 1f))
                if (progress.fraction - (lastReported?.fraction ?: -1f) >= PROGRESS_STEP) {
                    lastReported = progress
                    onProgress(progress.fraction)
                }
            }
            extractor.advance()
        }
        onProgress(1f)
        return copied
    }

    // ── 发布（双分支）─────────────────────────────────────────────────────

    /**
     * 成品发布：API 29+ 走 MediaStore（RELATIVE_PATH = Music|Movies/AbyssTools，
     * IS_PENDING 先占位后转正）；API 26–28 公共目录直写（RELATIVE_PATH 不存在），
     * 写完经 MediaScanner 换取 content:// Uri —— file:// 直接分享在 targetSdk 24+
     * 会触发 FileUriExposedException，扫描失败才退回 file://（调用方已 runCatching 兜底）。
     */
    private suspend fun saveOutput(
        context: Context,
        tempFile: File,
        displayName: String,
        mime: String,
        kind: MediaKind,
    ): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        saveViaMediaStore(context, tempFile, displayName, mime, kind)
    } else {
        saveLegacy(context, tempFile, displayName, mime, kind)
    }

    private fun saveViaMediaStore(
        context: Context,
        tempFile: File,
        displayName: String,
        mime: String,
        kind: MediaKind,
    ): Uri {
        val collection = if (kind == MediaKind.AUDIO) {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, kind.publicDir + "/" + OUTPUT_DIR_NAME)
            // 先占位再转正：避免拷贝期间的半成品被相册/播放器看到
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("系统媒体库拒绝了写入请求（MediaStore insert 失败）")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                tempFile.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("无法打开媒体输出流")
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) } // 失败清占位行，不留残迹
            throw e
        }
        return uri
    }

    /** API 26–28：公共目录直写（需 WRITE_EXTERNAL_STORAGE，UI 层已申请）。 */
    @Suppress("DEPRECATION")
    private suspend fun saveLegacy(
        context: Context,
        tempFile: File,
        displayName: String,
        mime: String,
        kind: MediaKind,
    ): Uri {
        val dir = File(Environment.getExternalStoragePublicDirectory(kind.publicDir), OUTPUT_DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("无法创建输出目录：${dir.absolutePath}")
        }
        val target = File(dir, displayName)
        if (target.exists()) target.delete()
        try {
            tempFile.copyTo(target)
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        val scanned = withTimeoutOrNull(SCAN_TIMEOUT_MS) { scanForContentUri(context, target, mime) }
        return scanned ?: Uri.fromFile(target)
    }

    /** MediaScanner 桥：把公共目录文件登记进媒体库，回换可授权的 content:// Uri。 */
    private suspend fun scanForContentUri(context: Context, file: File, mime: String): Uri? =
        suspendCancellableCoroutine { continuation ->
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime)) { _, uri ->
                if (continuation.isActive) continuation.resume(uri)
            }
        }

    // ── 输出配套动作 ───────────────────────────────────────────────────────

    /** 查输出 Uri 的显示名（任务行的「输出 xxx.m4a」），file:// 与 content:// 双支持。 */
    fun displayName(context: Context, uri: Uri): String = runCatching {
        when (uri.scheme?.lowercase()) {
            "file" -> File(uri.path ?: "").name
            else -> {
                context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (index >= 0) cursor.getString(index) else null
                        } else {
                            null
                        }
                    }
                    ?: uri.lastPathSegment
            }
        }
    }.getOrDefault("未知") ?: "未知"

    /**
     * ACTION_SEND 分享：EXTRA_STREAM + clipData + FLAG_GRANT_READ_URI_PERMISSION 三件套
     * （只给 EXTRA_STREAM 不自动授权，接收方进程会读不到）。
     */
    fun launchShare(context: Context, uri: Uri, mime: String): Result<Unit> = runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("abyss-media", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(Intent.createChooser(send, "分享媒体文件"))
        } catch (e: ActivityNotFoundException) {
            throw IllegalStateException("没有可用的分享组件", e)
        }
    }

    /** ACTION_VIEW 打开（播放器 / 音频应用）。 */
    fun launchOpen(context: Context, uri: Uri, mime: String): Result<Unit> = runCatching {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(view)
        } catch (e: ActivityNotFoundException) {
            throw IllegalStateException("未找到能播放 $mime 的应用", e)
        }
    }

    /** AAC 家族判定：MPEG_4 容器能直封的只有 AAC（"audio/mp4a-latm"，含 "audio/mp4"）。 */
    private fun isAacFamily(mime: String): Boolean =
        mime == AndroidMediaFormat.MIMETYPE_AUDIO_AAC || mime == "audio/mp4"
}
