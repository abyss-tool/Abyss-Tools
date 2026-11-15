package com.abyss.tools.feature.media

import java.util.Locale

/**
 * 媒体实验室的纯模型层：操作枚举 / 探测结果 / 任务记录 / 人读格式化。
 *
 * 为什么单独成文件且零 Android 依赖：格式化与任务状态语义要进 JVM 单测
 * （MediaJobsTest），android.media 的 MediaExtractor / MediaMuxer 全部隔离在
 * MediaMux —— 与 core:common「纯 JVM 可单测」同一纪律的模块内复刻。
 */

/** 无重编码三操作（title 即 UI 文案，单一事实源）。 */
enum class MediaOp(val title: String) {
    /** 把 AAC 音轨原样封装为 M4A（零转码；非 AAC 编码会被拒绝并给人话）。 */
    EXTRACT_AUDIO("提取音频"),

    /** 只保留视频轨，音轨整条丢弃（画质与编码参数不动）。 */
    STRIP_AUDIO("去除音轨"),

    /** 关键帧对齐的区间裁剪（起点回退到前一关键帧，详见 MediaMux.trim）。 */
    TRIM("片段裁剪"),
}

/**
 * 源信息探测结果。可空字段 = 探测不到（元数据缺失 / 音频-only 等），
 * UI 对 null 一律显示「未知 / 无」，绝不造数。
 */
data class MediaProbe(
    val durationMs: Long,
    val width: Int?,
    val height: Int?,
    val rotation: Int?,
    val videoMime: String?,
    val audioMime: String?,
    val bitrate: Long?,
)

/**
 * 一条处理任务。队列是内存态（页面生命周期即队列生命周期，v0.8.0 不做持久化 ——
 * 诚实边界：退出页面任务历史即清空）。progress 只在 RUNNING 期间有意义，
 * DONE 收口为 1f；outputPath 存「输出文件名」（分享/打开用的 Uri 由界面侧另行持有）。
 */
data class MediaJob(
    val id: Long,
    val op: MediaOp,
    val sourceName: String,
    val createdAt: Long,
    val status: JobStatus,
    val progress: Float = 0f,
    val outputPath: String? = null,
    val error: String? = null,
) {
    /** 任务生命周期：排队 → 处理 →（完成 / 失败 / 取消）。 */
    enum class JobStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }
}

/**
 * 人读格式化（时长 / 字节 / 码率 / 编码标签）—— 纯函数。
 *
 * 为什么显式 Locale.ROOT：媒体数值是「数据」而非「本地化文案」，固定点号小数
 * 让单测断言不随运行环境（土耳其等 locale 的小数逗号）漂移。
 */
object MediaFormat {

    /**
     * 毫秒 → "H:MM:SS" / "M:SS"。不足整秒时附十分位（如 "1:23.7"），整秒自动省略
     * 十分位（84_000ms → "1:24"，不输出难看的 "1:24.0"）；负值钳为 0 —— 滑杆拖越界
     * 时 UI 不至于显示 "-0:01"。
     * 达到 1 小时改走 H:MM:SS 且不带十分位（长视频的亚秒对阅读没有增量价值）。
     */
    fun formatDuration(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val totalSeconds = clamped / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        val tenths = ((clamped % 1000L) / 100L).toInt()
        return if (hours > 0L) {
            "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
        } else {
            val base = "%d:%02d".format(Locale.ROOT, minutes, seconds)
            if (tenths > 0) "$base.$tenths" else base
        }
    }

    /**
     * 字节 → B / KB / MB / GB。B 档整数（"732 B" —— 小数对纯字节数是噪音），
     * 其余一位小数（"1.5 KB" / "12.3 MB"）；负值钳为 0。
     * TB 档刻意不引入（媒体文件语境无意义），GB 封顶直示大数（5 TB → "5120.0 GB"），
     * 不静默换算成误导性的「5.0 TB」以外的什么 —— 拿到什么报什么。
     */
    fun formatBytes(bytes: Long): String {
        val clamped = bytes.coerceAtLeast(0L)
        if (clamped < 1024L) return "$clamped B"
        val kb = clamped / 1024.0
        if (kb < 1024.0) return "%.1f KB".format(Locale.ROOT, kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return "%.1f MB".format(Locale.ROOT, mb)
        return "%.1f GB".format(Locale.ROOT, mb / 1024.0)
    }

    /**
     * 码率 → "128 kbps" / "12.3 Mbps"。≥ 1 Mbps 一位小数，不足 1 Mbps 整数 kbps；
     * 0 与负值钳为 0 kbps —— 「未知」由 UI 层对 null 码率单独表达（"未知"），
     * 这里的 0 是真实的零，不替上层造数。
     */
    fun formatBitrate(bps: Long): String {
        val clamped = bps.coerceAtLeast(0L)
        return if (clamped >= 1_000_000L) {
            "%.1f Mbps".format(Locale.ROOT, clamped / 1_000_000.0)
        } else {
            "${clamped / 1000L} kbps"
        }
    }

    /**
     * MIME → 人读编码标签（"video/avc" → "AVC / H.264"）。
     * 收录的是本机探测器常见值；未收录的 MIME 原样透出（诚实优先于好看 ——
     * 用户宁可看到生串也不该看到猜错的编码名），null → "未知"。
     */
    fun codecLabel(mime: String?): String = when (mime) {
        null -> "未知"
        "video/avc" -> "AVC / H.264"
        "video/hevc" -> "HEVC / H.265"
        "video/av01" -> "AV1"
        "video/mp4v-es" -> "MPEG-4 Visual"
        "video/dolby-vision" -> "Dolby Vision"
        "video/vp9", "video/x-vnd.on2.vp9" -> "VP9"
        "audio/mp4a-latm" -> "AAC"
        "audio/mp4" -> "AAC (MP4)"
        "audio/mpeg" -> "MP3"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/flac" -> "FLAC"
        "audio/ac3" -> "AC-3"
        "audio/eac3" -> "E-AC-3"
        "audio/amr" -> "AMR"
        "audio/amr-wb" -> "AMR-WB"
        else -> mime
    }
}
