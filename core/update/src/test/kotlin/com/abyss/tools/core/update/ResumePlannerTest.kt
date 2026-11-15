package com.abyss.tools.core.update

import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 断点续传决策表（纯函数 —— 覆盖更新下载健壮性的核心语义）。
 */
class ResumePlannerTest {

    @Test
    fun `无残留 —— 全量重下`() {
        val plan = ResumePlanner.plan(partLength = 0L, httpCode = 200)
        assertEquals(ResumePlan.Mode.RESTART, plan.mode)
        assertEquals(0L, plan.resumeFrom)
    }

    @Test
    fun `有残留 + 206 —— 从断点续传`() {
        val plan = ResumePlanner.plan(partLength = 12345L, httpCode = 206)
        assertEquals(ResumePlan.Mode.APPEND, plan.mode)
        assertEquals(12345L, plan.resumeFrom)
    }

    @Test
    fun `有残留 + 200 全量回 —— 截断重下（镜像不支持 Range）`() {
        val plan = ResumePlanner.plan(partLength = 12345L, httpCode = 200)
        assertEquals(ResumePlan.Mode.RESTART, plan.mode)
        assertEquals(0L, plan.resumeFrom)
    }

    @Test
    fun `有残留 + 416 —— part 越界或损坏，重下`() {
        val plan = ResumePlanner.plan(partLength = 9_999_999L, httpCode = 416)
        assertEquals(ResumePlan.Mode.RESTART, plan.mode)
    }

    @Test
    fun `part 文件与最终文件同目录同分区`() {
        val dest = File("/data/cache", "abyss-0.7.8.apk")
        val part = ResumePlanner.partFileOf(dest)
        assertEquals(dest.parentFile, part.parentFile)
        assertTrue(part.name.startsWith(dest.name))
        assertTrue(part.name.endsWith(ResumePlanner.PART_SUFFIX))
    }
}

/**
 * downloadVerifiedApk / installApk 清理语义（覆盖更新管线的缓存纪律）。
 */
class DownloadVerifiedApkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val payload = "abyss-apk-payload".toByteArray()

    private class FakeTransport(private val payload: ByteArray) : HttpTransport {
        var downloadCalls = 0
        override fun get(url: String): String = throw IOException("unused")
        override fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
            downloadCalls++
            dest.writeBytes(payload)
        }
    }

    private class FakeGateway : ApkInstallGateway {
        var lastFile: File? = null
        var lastExpectation: InstallExpectation? = null
        var succeed = true

        override fun install(apk: File): Result<Unit> = install(apk, null)

        override fun install(apk: File, expectation: InstallExpectation?): Result<Unit> {
            lastFile = apk
            lastExpectation = expectation
            return if (succeed) Result.success(Unit) else Result.failure(IOException("模拟安装失败"))
        }
    }

    private fun manager(transport: HttpTransport, gateway: FakeGateway? = null): UpdateManager =
        UpdateManager(
            transport = transport,
            versionJsonUrl = "https://raw.githubusercontent.com/x/version.json",
            cacheDir = tmp.root.resolve("cache"),
            apkGateway = gateway,
        )

    private val sha = Digests.sha256(payload)
    private val info = DownloadInfo(url = "https://github.com/x/releases/abyss-0.7.8.apk", sizeBytes = payload.size.toLong(), sha256 = sha)

    @Test
    fun `下载 + 校验通过 —— 返回文件`() = runTest {
        val gateway = FakeGateway()
        val m = manager(FakeTransport(payload), gateway)
        val file = m.downloadVerifiedApk(info).getOrThrow()
        assertTrue(file.exists())
        assertEquals(sha, Digests.sha256(file))
    }

    @Test
    fun `校验失败即弃缓存 —— 坏文件与 part 都不留`() = runTest {
        val bad = DownloadInfo(url = info.url, sha256 = "deadbeef".repeat(8))
        val m = manager(FakeTransport(payload))
        val result = m.downloadVerifiedApk(bad)
        assertTrue(result.isFailure)
        val dest = tmp.root.resolve("cache").resolve("abyss-0.7.8.apk")
        assertFalse("坏文件必须删除", dest.exists())
        assertFalse("part 残留必须删除", ResumePlanner.partFileOf(dest).exists())
    }

    @Test
    fun `缓存命中 —— 已有校验通过的文件秒回，不重付流量`() = runTest {
        val transport = FakeTransport(payload)
        val cacheDir = tmp.root.resolve("cache").apply { mkdirs() }
        val dest = cacheDir.resolve("abyss-0.7.8.apk").apply { writeBytes(payload) }
        val m = manager(transport)
        val file = m.downloadVerifiedApk(info).getOrThrow()
        assertEquals(0, transport.downloadCalls)
        assertEquals(dest.canonicalPath, file.canonicalPath)
    }

    @Test
    fun `期望门禁透传网关 —— versionCode 原样下发`() = runTest {
        val transport = FakeTransport(payload)
        val gateway = FakeGateway()
        val m = manager(transport, gateway)
        val file = m.downloadVerifiedApk(info).getOrThrow()
        val result = m.installApk(file, InstallExpectation(expectedVersionCode = 16L))
        assertTrue(result.isSuccess)
        assertEquals(16L, gateway.lastExpectation?.expectedVersionCode)
        assertNotNull(gateway.lastFile)
    }

    @Test
    fun `安装成功即删缓存 —— 失败保留供重试`() = runTest {
        val transport = FakeTransport(payload)
        val gateway = FakeGateway()
        val m = manager(transport, gateway)
        val file = m.downloadVerifiedApk(info).getOrThrow()

        gateway.succeed = false
        m.installApk(file, InstallExpectation(expectedVersionCode = 16L))
        assertTrue("失败保留缓存（重试时秒回）", file.exists())

        gateway.succeed = true
        m.installApk(file, InstallExpectation(expectedVersionCode = 16L))
        assertFalse("成功即释放缓存", file.exists())
    }
}
