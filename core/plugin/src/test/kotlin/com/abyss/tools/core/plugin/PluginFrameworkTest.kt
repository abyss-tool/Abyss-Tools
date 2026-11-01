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
    fun `解析 8 插件索引（内置化重构后）`() {
        val index = PluginParsers.parseIndex(resource("plugins-index.json"))
        assertEquals("abyss-plugin-index-v1", index.schema)
        assertEquals(8, index.plugins.size)
        // 内置化后索引只剩重型插件；终端引擎仍在列
        assertTrue(index.plugins.any { it.id == "abyss-plugin-terminal" && it.name == "终端引擎" })
        assertTrue(index.plugins.all { it.versionSemVer != null })
        assertTrue(index.plugins.all { it.repo.startsWith("abyss-tool/abyss-plugin-") })
        assertTrue(index.plugins.all { it.patchChannel != null })
        // id 与仓库末段一一对应（索引完整性的核心不变式）
        assertTrue(index.plugins.all { it.repo.endsWith(it.id) })
        // id 唯一
        assertEquals(index.plugins.size, index.plugins.map { it.id }.toSet().size)
        // 已内置化的六个小插件不再占据插件中心位次
        val retired = setOf(
            "abyss-plugin-qr-craft", "abyss-plugin-island", "abyss-plugin-wallpaper",
            "abyss-plugin-shizuku-kit", "abyss-plugin-app-manager", "abyss-plugin-automation",
        )
        assertTrue(index.plugins.none { it.id in retired })
    }

    @Test
    fun `解析应用构建器的独立 Shizuku 授权声明`() {
        val index = PluginParsers.parseIndex(resource("plugins-index.json"))
        val builder = index.plugins.first { it.id == "abyss-plugin-app-builder" }
        assertEquals("dev", builder.category)
        assertEquals("应用构建器", builder.name)
        val decl = builder.shizuku
        assertTrue(decl != null && decl.ownAuth)
        assertTrue(decl!!.reason.contains("Shizuku"))
        // 无声明插件解析为 null（字段缺省不炸）
        assertTrue(index.plugins.first { it.id == "abyss-plugin-terminal" }.shizuku == null)
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
        assertEquals(8, result.plugins.size)
    }

    // ── 安装器：未发布 APK fail-closed；数据补丁真实应用 ───────────────

    private val noGateway = object : ApkInstallGateway {
        override fun install(apk: File): Result<Unit> =
            Result.failure(IllegalStateException("框架占位"))
    }

    @Test
    fun `APK 未发布时下载给出人话错误`() = runTest {
        val root = tmp.newFolder("plugins")
        val installer = PluginInstaller(root, fakeTransport(), noGateway)
        val entry = PluginIndexEntry(
            id = "abyss-plugin-terminal", name = "终端引擎",
            repo = "abyss-tool/abyss-plugin-terminal", category = "terminal",
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

    // ── 数据层安装（installDataPlugin 真实闭环）────────────────────────

    private fun entry(
        id: String = "abyss-plugin-terminal",
        version: String = "0.1.0",
        minApp: String = "0.1.0",
    ) = PluginIndexEntry(
        id = id, name = "终端引擎",
        repo = "abyss-tool/$id", category = "terminal",
        version = version, minApp = minApp, summary = "Termux 血统终端",
        icon = "🖥", permissions = listOf("INTERNET"),
        patchChannel = "https://raw.githubusercontent.com/abyss-tool/$id/main/patches/index.json",
        roadmap = listOf("v0.2 伪终端内核"),
    )

    @Test
    fun `数据层安装 - manifest 校验后原子落盘并进版本账本`() {
        val root = tmp.newFolder("plugins-data")
        val installer = PluginInstaller(root, fakeTransport(), noGateway)

        val result = installer.installDataPlugin(entry())
        val manifest = result.getOrThrow()

        assertEquals("abyss-plugin-terminal", manifest.id)
        assertTrue(installer.isInstalled("abyss-plugin-terminal"))
        assertEquals(1, installer.installed().size)
        // 版本查询可用
        assertEquals("0.1.0", installer.installedVersion("abyss-plugin-terminal").toString())
        // 落盘内容可再解析（序列化 → 反序列化回环）
        val onDisk = installer.installedManifest("abyss-plugin-terminal")!!
        assertEquals(manifest, onDisk)
        assertEquals(listOf("INTERNET"), onDisk.permissions)
        // staging 残留不应存在
        assertTrue(!installer.pluginDir("abyss-plugin-terminal").resolve("plugin.json.staging").exists())
    }

    @Test
    fun `数据层安装 - 重复安装覆盖升级且无残留`() {
        val root = tmp.newFolder("plugins-reinstall")
        val installer = PluginInstaller(root, fakeTransport(), noGateway)

        installer.installDataPlugin(entry()).getOrThrow()
        val upgraded = installer.installDataPlugin(entry(version = "0.2.0")).getOrThrow()

        assertEquals("0.2.0", upgraded.version)
        assertEquals("0.2.0", installer.installedVersion("abyss-plugin-terminal").toString())
        // 仍是唯一登记（目录不重复）
        assertEquals(1, installer.installed().size)
    }

    @Test
    fun `数据层安装 - 非法 id 与坏版本号 fail-closed`() {
        val root = tmp.newFolder("plugins-bad")
        val installer = PluginInstaller(root, fakeTransport(), noGateway)

        // id 形态非法（无连字符 → 不匹配 ID_PATTERN）
        assertTrue(installer.installDataPlugin(entry(id = "Terminal")).isFailure)
        // 版本号非法
        assertTrue(installer.installDataPlugin(entry(version = "0.1")).isFailure)
        assertTrue(installer.installDataPlugin(entry(version = "abc")).isFailure)
        // 宿主下限高于当前 AppInfo.VERSION_NAME
        assertTrue(installer.installDataPlugin(entry(minApp = "99.0.0")).isFailure)
        // 全部失败后不应产生任何登记
        assertTrue(installer.installed().isEmpty())
    }

    @Test
    fun `updatablePatch - from 命中本机版本时取 to 最大的补丁`() {
        val root = tmp.newFolder("plugins-patch-select")
        val installer = PluginInstaller(root, fakeTransport(), noGateway)

        // 未安装 → 永远无可应用补丁
        val emptyChannel = PluginPatchChannel(
            plugin = "abyss-plugin-terminal", latest = "0.1.2",
            patches = listOf(
                PluginPatch(from = "0.1.0", to = "0.1.1", url = "https://x/p1.zip"),
                PluginPatch(from = "0.1.0", to = "0.1.2", url = "https://x/p2.zip"),
            ),
        )
        assertEquals(null, installer.updatablePatch("abyss-plugin-terminal", emptyChannel))

        // 安装 0.1.0 后 → 命中 from=0.1.0 的两条中 to 最大的 0.1.2
        installer.installDataPlugin(entry()).getOrThrow()
        val selected = installer.updatablePatch("abyss-plugin-terminal", emptyChannel)!!
        assertEquals("0.1.2", selected.to)
        assertEquals("https://x/p2.zip", selected.url)

        // 补丁应用后本机为 0.1.2 → 无命中（链已走到头）
        installer.pluginDir("abyss-plugin-terminal").resolve("plugin.json").writeText(
            """{"id":"abyss-plugin-terminal","name":"终端引擎","version":"0.1.2","category":"terminal"}"""
        )
        assertEquals(null, installer.updatablePatch("abyss-plugin-terminal", emptyChannel))
    }

    @Test
    fun `数据层安装到补丁应用的完整链路`() = runTest {
        val root = tmp.newFolder("plugins-chain")
        val installer = PluginInstaller(root, fakeTransport(), noGateway)

        // 1) 数据层安装 0.1.0
        installer.installDataPlugin(entry()).getOrThrow()
        assertEquals("0.1.0", installer.installedVersion("abyss-plugin-terminal").toString())

        // 2) 补丁包：replace plugin.json（升 0.1.1）+ add skills/hello.json
        val patchZip = tmp.newFile("chain-patch.zip")
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
                """{"id":"abyss-plugin-terminal","name":"终端引擎","version":"0.1.1","category":"terminal","summary":"Termux 血统终端"}""".toByteArray()
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("skills/hello.json"))
            zip.write("""{"skill":"hello"}""".toByteArray())
            zip.closeEntry()
        }

        // 3) 应用 → 版本账本随 plugin.json 升级
        val report = installer.applyDataPatch("abyss-plugin-terminal", patchZip)
        assertEquals("0.1.1", report.toVersion)
        assertEquals("0.1.1", installer.installedVersion("abyss-plugin-terminal").toString())
        assertTrue(installer.pluginDir("abyss-plugin-terminal").resolve("skills/hello.json").exists())
    }

    private fun fakeTransport() = object : com.abyss.tools.core.update.HttpTransport {
        override fun get(url: String): String = "{}"
        override fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
            dest.writeBytes(byteArrayOf(1))
        }
    }
}
