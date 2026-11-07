package com.abyss.tools.core.update

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZipPatchApplierTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val applier = ZipPatchApplier()

    private fun makePatch(vararg entries: Pair<String, String>): File {
        val zip = tmp.newFile("patch-${System.nanoTime()}.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            for ((name, content) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return zip
    }

    private fun manifest(target: String = "abyss-plugin-terminal", filesJson: String): String =
        """
        {
          "schema": "abyss-patch-v1",
          "target": "$target",
          "fromVersion": "0.1.0",
          "toVersion": "0.1.1",
          "files": $filesJson
        }
        """.trimIndent()

    @Test
    fun `replace add delete 全动作应用`() {
        val dir = tmp.newFolder("plugin-home").apply {
            resolve("plugin.json").writeText("""{"id":"abyss-plugin-terminal","version":"0.1.0"}""")
            resolve("legacy").mkdirs()
            resolve("legacy/old.json").writeText("{}")
        }
        val patch = makePatch(
            "patch-manifest.json" to manifest(
                filesJson = """
                [
                  {"path": "plugin.json", "action": "replace"},
                  {"path": "skills/new.json", "action": "add"},
                  {"path": "legacy/old.json", "action": "delete"}
                ]
                """.trimIndent()
            ),
            "plugin.json" to """{"id":"abyss-plugin-terminal","version":"0.1.1"}""",
            "skills/new.json" to """{"skill":"hello"}""",
        )

        val report = applier.apply(patch, dir)

        assertEquals("0.1.1", report.toVersion)
        assertEquals(listOf("plugin.json"), report.replaced)
        assertEquals(listOf("skills/new.json"), report.added)
        assertEquals(listOf("legacy/old.json"), report.deleted)
        assertTrue(dir.resolve("plugin.json").readText().contains("0.1.1"))
        assertTrue(dir.resolve("skills/new.json").exists())
        assertFalse(dir.resolve("legacy/old.json").exists())
    }

    @Test
    fun `无 manifest 时整包拒绝`() {
        val dir = tmp.newFolder("no-manifest")
        val patch = makePatch("plugin.json" to "{}")
        try {
            applier.apply(patch, dir)
            throw AssertionError("应当 PatchRejectedException")
        } catch (e: PatchRejectedException) {
            assertTrue(e.message!!.contains("patch-manifest.json"))
        }
    }

    @Test
    fun `路径穿越整包拒绝且目标目录不被触碰`() {
        val dir = tmp.newFolder("escape").apply {
            resolve("safe.txt").writeText("original")
        }
        val patch = makePatch(
            "patch-manifest.json" to manifest(
                filesJson = """[{"path": "../evil.txt", "action": "add"}]"""
            ),
            "../evil.txt" to "pwned",
        )
        try {
            applier.apply(patch, dir)
            throw AssertionError("应当 PatchRejectedException")
        } catch (e: PatchRejectedException) {
            assertTrue(e.message!!.contains("穿越"))
        }
        assertEquals("original", dir.resolve("safe.txt").readText())
        assertFalse(tmp.root.resolve("evil.txt").exists())
    }

    @Test
    fun `载荷缺失整包拒绝`() {
        val dir = tmp.newFolder("missing-payload")
        val patch = makePatch(
            "patch-manifest.json" to manifest(
                filesJson = """[{"path": "plugin.json", "action": "replace"}]"""
            ),
            // 故意不带 plugin.json 载荷
        )
        try {
            applier.apply(patch, dir)
            throw AssertionError("应当 PatchRejectedException")
        } catch (e: PatchRejectedException) {
            assertTrue(e.message!!.contains("载荷"))
        }
    }

    @Test
    fun `写失败自动回滚`() {
        val dir = tmp.newFolder("rollback").apply {
            resolve("a.txt").writeText("AAA")
            resolve("blocker").mkdirs() // 同名目录挡路 → 第二步写入必失败
        }
        val patch = makePatch(
            "patch-manifest.json" to manifest(
                filesJson = """
                [
                  {"path": "a.txt", "action": "replace"},
                  {"path": "blocker", "action": "replace"}
                ]
                """.trimIndent()
            ),
            "a.txt" to "REPLACED",
            "blocker" to "X",
        )
        try {
            applier.apply(patch, dir)
            throw AssertionError("应当 IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("回滚"))
        }
        assertEquals("AAA", dir.resolve("a.txt").readText())
    }

    @Test
    fun `manifest 未列 files 时按包内载荷默认 replace`() {
        val dir = tmp.newFolder("implicit")
        val patch = makePatch(
            "patch-manifest.json" to manifest(filesJson = "[]"),
            "auto.txt" to "implicit-replace",
        )
        val report = applier.apply(patch, dir)
        assertEquals(listOf("auto.txt"), report.added)
        assertEquals("implicit-replace", dir.resolve("auto.txt").readText())
    }
}

class UpdateCheckerTest {

    private val current = com.abyss.tools.core.common.SemVer.parse("0.1.0")!!

    private fun transportOf(resource: String): HttpTransport {
        val raw = javaClass.classLoader!!.getResourceAsStream(resource)!!
            .readBytes().decodeToString()
        return object : HttpTransport {
            override fun get(url: String): String = raw
            override fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
                dest.writeBytes(raw.toByteArray())
            }
        }
    }

    @Test
    fun `通道同版本 → UpToDate`() = runTest {
        val checker = UpdateChecker(transportOf("channel/version-up-to-date.json"), "https://x/version.json")
        val result = checker.check(current)
        assertTrue(result is UpdateCheckResult.UpToDate)
    }

    @Test
    fun `补丁 fromTag 命中 → 增量可用`() = runTest {
        val checker = UpdateChecker(transportOf("channel/version-available-patch.json"), "https://x/version.json")
        val result = checker.check(current) as UpdateCheckResult.Available
        assertTrue(result.patchApplicable)
        assertEquals("v0.1.0", result.patch!!.fromTag)
        assertEquals("0.2.0", result.manifest.versionName)
        assertEquals("v0.2.0", result.manifest.resolvedTag)
    }

    @Test
    fun `补丁 fromTag 未命中 → 整包`() = runTest {
        val checker = UpdateChecker(transportOf("channel/version-available-full.json"), "https://x/version.json")
        val result = checker.check(current) as UpdateCheckResult.Available
        assertFalse(result.patchApplicable)
        assertEquals(null, result.patch)
        assertTrue(result.manifest.download != null)
    }

    @Test
    fun `网络失败 → Failed 不抛异常`() = runTest {
        val broken = object : HttpTransport {
            override fun get(url: String): String = throw IOException("offline")
            override fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) =
                throw IOException("offline")
        }
        val result = UpdateChecker(broken, "https://x/version.json").check(current)
        assertTrue(result is UpdateCheckResult.Failed)
    }

    @Test
    fun `坏 JSON → Failed`() = runTest {
        val broken = object : HttpTransport {
            override fun get(url: String): String = "not-json"
            override fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {}
        }
        val result = UpdateChecker(broken, "https://x/version.json").check(current)
        assertTrue(result is UpdateCheckResult.Failed)
    }
}

class DigestsTest {

    @Test
    fun `sha256 已知向量`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Digests.sha256("abc".toByteArray())
        )
    }

    @Test
    fun `verify 摘要缺失即拒绝（fail-closed）`() {
        val f = kotlin.io.path.createTempFile("digest", ".txt").toFile()
        f.writeText("data")
        // v0.6.0 起：通道未声明校验值 = 拒绝（不再「框架期兼容」放行）
        assertFalse(Digests.verify(f, null))
        assertFalse(Digests.verify(f, "  "))
        assertFalse(Digests.verify(f, "deadbeef"))
        assertFalse(Digests.verify(f, ""))
        assertTrue(Digests.verify(f, Digests.sha256(f)))
        assertTrue(Digests.verify(f, Digests.sha256(f).uppercase()))
        f.delete()
    }
}

class FetchHostPolicyTest {

    @Test
    fun `官方四域放行`() {
        listOf(
            "https://raw.githubusercontent.com/abyss-tool/Abyss-Tools-Release/main/version.json",
            "https://github.com/abyss-tool/Abyss-Tools-Release/releases/download/v0.6.0/abyss-tools-v0.6.0.apk",
            "https://objects.githubusercontent.com/some/asset",
            "https://release-assets.githubusercontent.com/some/asset",
        ).forEach { url ->
            assertEquals(url, FetchHostPolicy.check(url))
        }
    }

    @Test
    fun `白名单外域名拒绝（含仿冒域）`() {
        listOf(
            "https://evil.example.com/version.json",
            // 仿冒域：白名单是精确 host，不是后缀匹配
            "https://evil.raw.githubusercontent.com.evil.com/version.json",
            "https://raw.githubusercontent.com.evil.com/version.json",
            "http://raw.githubusercontent.com/main/version.json", // http 拒绝
            "not a url",
            "",
        ).forEach { url ->
            try {
                FetchHostPolicy.check(url)
                throw AssertionError("白名单外 URL 未被拒绝：$url")
            } catch (expected: IOException) {
                // fail-closed
            }
        }
    }
}

class ChangelogTest {

    @Test
    fun `解析 changelog latest 与 history 合成全量条目`() {
        val raw = javaClass.classLoader!!
            .getResourceAsStream("channel/changelog.json")!!.readBytes().decodeToString()
        val manifest = UpdateParsers.parseChangelogManifest(raw)

        assertEquals("abyss-changelog-v1", manifest.schema)
        assertEquals("0.2.0", manifest.latest!!.versionName)
        assertEquals(1, manifest.history.size)
        // latest 置顶的全量条目序
        assertEquals(listOf("0.2.0", "0.1.0"), manifest.entries.map { it.versionName })
        assertEquals(3, manifest.latest.notes.size)
        assertEquals("v0.2.0", manifest.latest.resolvedTag)
        // history 条目缺省 tag 时按 v{version} 推导
        assertEquals("v0.1.0", manifest.history.first().resolvedTag)
    }

    @Test
    fun `空 changelog 容忍`() {
        val manifest = UpdateParsers.parseChangelogManifest("{}")
        assertEquals(0, manifest.entries.size)
        assertEquals(null, manifest.latest)
    }

    @Test
    fun `UpdateManager fetchChangelog 走配置通道`() = runTest {
        val raw = javaClass.classLoader!!
            .getResourceAsStream("channel/changelog.json")!!.readBytes().decodeToString()
        val transport = object : HttpTransport {
            override fun get(url: String): String = raw
            override fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
                dest.writeBytes(raw.toByteArray())
            }
        }
        val cache = kotlin.io.path.createTempDirectory("updates").toFile()

        // 未配置通道 → 人话失败
        val unconfigured = UpdateManager(transport, "https://x/version.json", cache)
        assertTrue(unconfigured.fetchChangelog().isFailure)
        assertTrue(
            unconfigured.fetchChangelog().exceptionOrNull()!!.message!!.contains("未配置")
        )

        // 配置通道 → 拉取并解析
        val configured = UpdateManager(
            transport, "https://x/version.json", cache,
            changelogJsonUrl = "https://x/changelog.json",
        )
        val result = configured.fetchChangelog().getOrThrow()
        assertEquals("0.2.0", result.latest!!.versionName)
        cache.deleteRecursively()
    }

    @Test
    fun `installApk 未装配网关时人话失败`() = runTest {
        val cache = kotlin.io.path.createTempDirectory("updates").toFile()
        val manager = UpdateManager(
            object : HttpTransport {
                override fun get(url: String): String = "{}"
                override fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {}
            },
            "https://x/version.json", cache,
        )
        val apk = File(cache, "x.apk").apply { writeBytes(byteArrayOf(1)) }
        val result = manager.installApk(apk)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("未装配"))
        cache.deleteRecursively()
    }

    @Test
    fun `installApk 经网关直达`() = runTest {
        val cache = kotlin.io.path.createTempDirectory("updates").toFile()
        val gateway = object : ApkInstallGateway {
            override fun install(apk: File): Result<Unit> =
                Result.failure(IllegalStateException("用户取消"))
        }
        val manager = UpdateManager(
            object : HttpTransport {
                override fun get(url: String): String = "{}"
                override fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {}
            },
            "https://x/version.json", cache,
            apkGateway = gateway,
        )
        val apk = File(cache, "x.apk").apply { writeBytes(byteArrayOf(1)) }
        assertEquals("用户取消", manager.installApk(apk).exceptionOrNull()!!.message)
        cache.deleteRecursively()
    }
}
