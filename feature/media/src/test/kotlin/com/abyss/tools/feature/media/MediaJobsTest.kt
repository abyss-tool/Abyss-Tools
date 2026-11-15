package com.abyss.tools.feature.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MediaJobs 纯逻辑单测：全部跑在纯 JVM 上 —— 时长 / 字节 / 码率格式化的
 * 边界（0、负值钳制、毫秒与 KB/MB 档位切换）+ MediaJob 默认值与 copy 语义
 * （任务列表原地更新依赖 copy 只动目标字段的契约）。android.media 的
 * MediaMux 部分不进单测（真机肉眼验收）。
 */
class MediaJobsTest {

    // ── formatDuration ────────────────────────────────────────────────────

    @Test
    fun `formatDuration 零与负值钳为 0_00`() {
        assertEquals("0:00", MediaFormat.formatDuration(0L))
        assertEquals("0:00", MediaFormat.formatDuration(-1L))
        assertEquals("0:00", MediaFormat.formatDuration(-61_000L))
    }

    @Test
    fun `formatDuration 毫秒边界 整秒不带十分位`() {
        assertEquals("0:01", MediaFormat.formatDuration(1_000L))
        assertEquals("0:59", MediaFormat.formatDuration(59_000L))
        assertEquals("1:00", MediaFormat.formatDuration(60_000L))
        // 84_000ms = 1:24.0 —— 十分位为 0 时裁掉，不输出 "1:24.0"
        assertEquals("1:24", MediaFormat.formatDuration(84_000L))
        assertEquals("59:59", MediaFormat.formatDuration(3_599_000L))
        // 跨小时档切换：H:MM:SS，不带十分位
        assertEquals("1:00:00", MediaFormat.formatDuration(3_600_000L))
        assertEquals("1:02:03", MediaFormat.formatDuration(3_723_000L))
    }

    @Test
    fun `formatDuration 亚秒以十分位补显`() {
        assertEquals("0:00.5", MediaFormat.formatDuration(500L))
        assertEquals("0:00.9", MediaFormat.formatDuration(999L))
        assertEquals("0:59.9", MediaFormat.formatDuration(59_999L))
        assertEquals("1:23.7", MediaFormat.formatDuration(83_700L))
    }

    // ── formatBytes ────────────────────────────────────────────────────────

    @Test
    fun `formatBytes 零与负值钳为 0 B`() {
        assertEquals("0 B", MediaFormat.formatBytes(0L))
        assertEquals("0 B", MediaFormat.formatBytes(-1024L))
    }

    @Test
    fun `formatBytes 字节档整数显示`() {
        assertEquals("732 B", MediaFormat.formatBytes(732L))
        assertEquals("1023 B", MediaFormat.formatBytes(1023L))
    }

    @Test
    fun `formatBytes KB MB GB 档一位小数`() {
        assertEquals("1.0 KB", MediaFormat.formatBytes(1024L))
        assertEquals("1.5 KB", MediaFormat.formatBytes(1536L))
        assertEquals("10.0 MB", MediaFormat.formatBytes(10_485_760L))
        // 1024^3 = 1 GiB，MB 档判不到，直接落 GB
        assertEquals("1.0 GB", MediaFormat.formatBytes(1_073_741_824L))
    }

    @Test
    fun `formatBytes GB 封顶不进 TB`() {
        // 5 TiB → 不引入 TB 档，GB 直示大数（拿什么报什么）
        assertEquals("5120.0 GB", MediaFormat.formatBytes(5L * 1024 * 1024 * 1024 * 1024))
    }

    // ── formatBitrate ──────────────────────────────────────────────────────

    @Test
    fun `formatBitrate 零与负值钳为 0 kbps`() {
        assertEquals("0 kbps", MediaFormat.formatBitrate(0L))
        assertEquals("0 kbps", MediaFormat.formatBitrate(-1L))
    }

    @Test
    fun `formatBitrate 不足 1 Mbps 整数 kbps`() {
        assertEquals("128 kbps", MediaFormat.formatBitrate(128_000L))
        assertEquals("999 kbps", MediaFormat.formatBitrate(999_999L))
    }

    @Test
    fun `formatBitrate 达 1 Mbps 起一位小数`() {
        assertEquals("1.0 Mbps", MediaFormat.formatBitrate(1_000_000L))
        assertEquals("12.3 Mbps", MediaFormat.formatBitrate(12_345_678L))
        assertEquals("45.5 Mbps", MediaFormat.formatBitrate(45_500_000L))
    }

    // ── MediaJob 默认值与 copy 语义 ────────────────────────────────────────

    @Test
    fun `MediaJob 默认值 零进度 无输出 无错误`() {
        val job = MediaJob(
            id = 1L,
            op = MediaOp.TRIM,
            sourceName = "a.mp4",
            createdAt = 2L,
            status = MediaJob.JobStatus.QUEUED,
        )
        assertEquals(0f, job.progress, 1e-6f)
        assertNull(job.outputPath)
        assertNull(job.error)
    }

    @Test
    fun `MediaJob copy 只动目标字段 其余保持`() {
        val queued = MediaJob(
            id = 1L,
            op = MediaOp.EXTRACT_AUDIO,
            sourceName = "a.mp4",
            createdAt = 2L,
            status = MediaJob.JobStatus.QUEUED,
        )
        val running = queued.copy(status = MediaJob.JobStatus.RUNNING)
        // 只切状态：进度仍为默认 0f（引擎首条回调前不虚报进度）
        assertEquals(MediaJob.JobStatus.RUNNING, running.status)
        assertEquals(0f, running.progress, 1e-6f)
        assertNull(running.outputPath)
        assertEquals(queued.id, running.id)
        assertEquals(queued.op, running.op)

        val done = running.copy(status = MediaJob.JobStatus.DONE, progress = 1f, outputPath = "abyss.m4a")
        assertEquals(1f, done.progress, 1e-6f)
        assertEquals("abyss.m4a", done.outputPath)
        assertNull(done.error)
        assertNotEquals(running, done)
    }

    @Test
    fun `MediaOp 三操作标题为中文文案`() {
        assertEquals("提取音频", MediaOp.EXTRACT_AUDIO.title)
        assertEquals("去除音轨", MediaOp.STRIP_AUDIO.title)
        assertEquals("片段裁剪", MediaOp.TRIM.title)
    }
}
