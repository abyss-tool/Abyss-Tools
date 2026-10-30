package com.abyss.tools.core.plugin

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginFrameworkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── 索引 / 补丁通道解析 ────────────────────────────────────────────

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes().decodeToString()

    @Test
    fun `解析 13 插件索引`() {
        val index = PluginParsers.parseIndex(resource("plugins-index.json"))
        assertEquals("abyss-plugin-index-v1", index.schema)
        assertEquals(13, index.plugins.size)
        assertEquals("终端引擎", index.plugins.first().name)
        assertTrue(index.plugins.all { it.versionSemVer != null })
        assertTrue(index.plugins.all { it.repo.startsWith("Ultra-Guru/abyss-plugin-") })
        assertTrue(index.plugins.all { it.patchChannel != null })
        // id 与仓库末段一一对应（索引完整性的核心不变式）
        assertTrue(index.plugins.all { it.repo.endsWith(it.id) })
        // id 唯一
        assertEquals(index.plugins.size, index.plugins.map { it.id }.toSet().size)
    }

    @Test
    fun `解析空补丁通道`() {
        val channel = PluginParsers.parsePatchChannel(resource("plugin-patch-channel.json"))
        assertEquals("abyss-plugin-terminal", channel.plugin)
        assertEquals("0.1.0", channel.latest)
        assertTrue(channel.patches.isEmpty())
    }

    @Test
    fun `索引拉取走 FakeTransport`() = runTest {
        val raw = resource("plugins-index.json")
        val transport = object : com.abyss.tools.core.update.HttpTransport {
            override fun get(url: String): String = raw
            override fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
                dest.writeBytes(raw.toByteArray())
            }
        }
        val repo = PluginRepository(transport, "https://x/plugins.json")
        val result = repo.fetchIndex().getOrThrow()
        assertEquals(13, result.plugins.size)
    }

    // ── 安装器：未发布 APK fail-closed；数据补丁真实应用 ───────────────

    private val noGateway = object : ApkInstallGateway {
        override fun install(apk: File) = Result.failure(IllegalStateException("框架占位"))
    }

    @Test
    fun `APK 未发布时下载给出人话错误`() = runTest {
        val root = tmp.newFolder("plugins")
        val installer = PluginInstaller(root, fakeTransport(), noGateway)
        val entry = PluginIndexEntry(
            id = "abyss-plugin-terminal", name = "终端引擎",
            repo = "Ultra-Guru/abyss-plugin-terminal", category = "terminal",
            version = "0.1.0", summary = "", apk = null,
        )
        val result = installer.downloadApk(entry)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("尚未发布"))
    }

    @Test
    fun `数据补丁应用与安装登记`() = runTest {
        val root = tmp.newFolder("plugins")
        val installer = PluginInstaller(root, fakeTransport(), noGateway)

        // 先「安装」数据插件（模拟：目录 + plugin.json 落位）
        val dir = installer.pluginDir("abyss-plugin-terminal").apply { mkdirs() }
        dir.resolve("plugin.json").writeText(
            """{"id":"abyss-plugin-terminal","name":"终端引擎","version":"0.1.0","category":"terminal"}"""
        )
        assertTrue(installer.isInstalled("abyss-plugin-terminal"))
        assertEquals(1, installer.installed().size)
        assertEquals("终端引擎", installer.installed().first().name)

        // 构造数据补丁：replace plugin.json + add skills/hello.json
        val patchZip = tmp.newFile("data-patch.zip")
        ZipOutputStream(patchZip.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("patch-manifest.json"))
            zip.write(
                """
                {
                  "schema": "abyss-patch-v1",
                  "target": "abyss-plugin-terminal",
                  "fromVersion": "0.1.0",
                  "toVersion": "0.1.1",
                  "files": [
                    {"path": "plugin.json", "action": "replace"},
                    {"path": "skills/hello.json", "action": "add"}
                  ]
                }
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("plugin.json"))
            zip.write(
                """{"id":"abyss-plugin-terminal","name":"终端引擎","version":"0.1.1","category":"terminal"}"""
                    .toByteArray()
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("skills/hello.json"))
            zip.write("""{"skill":"hello"}""".toByteArray())
            zip.closeEntry()
        }

        val report = installer.applyDataPatch("abyss-plugin-terminal", patchZip)
        assertEquals("0.1.1", report.toVersion)
        assertEquals(1, report.replaced.size)
        assertEquals(1, report.added.size)
        // 安装登记表随之升级
        assertEquals("0.1.1", installer.installed().first().version)
        assertTrue(dir.resolve("skills/hello.json").exists())
    }

    @Test
    fun `卸载清目录`() {
        val root = tmp.newFolder("plugins-uninstall")
        val installer = PluginInstaller(root, fakeTransport(), noGateway)
        val dir = installer.pluginDir("abyss-plugin-qr-craft").apply { mkdirs() }
        dir.resolve("plugin.json").writeText("""{"id":"abyss-plugin-qr-craft"}""")
        assertTrue(installer.uninstall("abyss-plugin-qr-craft"))
        assertTrue(!installer.isInstalled("abyss-plugin-qr-craft"))
    }

    private fun fakeTransport() = object : com.abyss.tools.core.update.HttpTransport {
        override fun get(url: String): String = "{}"
        override fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
            dest.writeBytes(byteArrayOf(1))
        }
    }
}
