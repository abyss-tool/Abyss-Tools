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
    fun `verify 声明缺失时放行`() {
        val f = kotlin.io.path.createTempFile("digest", ".txt").toFile()
        f.writeText("data")
        assertTrue(Digests.verify(f, null))
        assertTrue(Digests.verify(f, "  "))
        assertFalse(Digests.verify(f, "deadbeef"))
        assertTrue(Digests.verify(f, Digests.sha256(f)))
        f.delete()
    }
}
