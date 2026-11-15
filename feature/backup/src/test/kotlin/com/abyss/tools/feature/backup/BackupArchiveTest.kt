package com.abyss.tools.feature.backup

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 主壳配置备份包引擎测试（纯 JVM）—— 打包 / 恢复 roundtrip 与
 * fail-closed 拒绝语义（穿越 / 炸弹 / 坏 manifest）。
 */
class BackupArchiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val engine = BackupRestoreEngine()

    private fun makeZip(vararg entries: Pair<String, ByteArray>): File {
        val zip = tmp.newFile("backup-${System.nanoTime()}.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            for ((name, content) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(content)
                out.closeEntry()
            }
        }
        return zip
    }

    private fun manifestJson(files: List<String>, schema: String = BackupContract.SCHEMA): ByteArray =
        """
        {"schema": "$schema", "createdAt": "2026-11-15T00:00:00Z", "appVersion": "0.8.0",
         "files": [${files.joinToString(",") { "\"$it\"" }}]}
        """.trimIndent().toByteArray()

    private fun blueprintJson(id: String): ByteArray =
        """{"id": "$id", "name": "$id", "steps": []}""".toByteArray()

    // ── roundtrip ─────────────────────────────────────────────────────

    @Test
    fun `打包到恢复 roundtrip —— 蓝图回写 归档项不回放`() {
        val sourceDir = tmp.newFolder("source")
        val bp = sourceDir.resolve("my-task.json").apply { writeBytes(blueprintJson("my-task")) }
        val probe = sourceDir.resolve("glass-probe.txt").apply { writeText("session-start|vibrancy|blur") }

        val dest = tmp.root.resolve("out/abyss-backup.zip")
        val report = BackupArchiveBuilder()
            .add("blueprints/my-task.json", bp)
            .add("glass-probe.txt", probe)
            .build(dest, appVersion = "0.8.0")

        assertEquals(listOf("blueprints/my-task.json", "glass-probe.txt"), report.entries)
        assertTrue(report.sizeBytes > 0)

        val blueprintsDir = tmp.newFolder("blueprints-target")
        val restore = engine.restore(dest, blueprintsDir)

        assertEquals(listOf("blueprints/my-task.json"), restore.restored)
        assertEquals(listOf("glass-probe.txt"), restore.archivedOnly)
        assertEquals(
            blueprintJson("my-task").decodeToString(),
            blueprintsDir.resolve("my-task.json").readText(),
        )
        // 归档项绝不回放（取证是现场记录）
        assertFalse(blueprintsDir.resolve("glass-probe.txt").exists())
    }

    @Test
    fun `两阶段恢复 —— 解包阶段零触碰，落位原子`() {
        // 目标目录已有同名蓝图：恢复覆盖之（用户显式选择了备份包）
        val blueprintsDir = tmp.newFolder("bp-target")
        blueprintsDir.resolve("my-task.json").writeText("""{"id": "my-task", "old": true}""")

        val source = tmp.newFile("bp.json").apply { writeBytes(blueprintJson("my-task")) }
        val zip = BackupArchiveBuilder()
            .add("blueprints/my-task.json", source)
            .build(tmp.root.resolve("pkg.zip"), appVersion = "0.8.0")

        engine.restore(zip, blueprintsDir)

        assertEquals(
            blueprintJson("my-task").decodeToString(),
            blueprintsDir.resolve("my-task.json").readText(),
        )
        // 无 .restore-part 残留
        assertFalse(blueprintsDir.resolve("my-task.json.restore-part").exists())
    }

    // ── fail-closed 拒绝语义 ──────────────────────────────────────────

    @Test
    fun `无 manifest 整包拒绝`() {
        val zip = makeZip("blueprints/evil.json" to blueprintJson("evil"))
        val result = runCatching { engine.restore(zip, tmp.newFolder("t1")) }
        assertTrue(result.exceptionOrNull() is BackupRejectedException)
    }

    @Test
    fun `schema 不符整包拒绝`() {
        val zip = makeZip(
            BackupContract.MANIFEST_ENTRY to manifestJson(emptyList(), schema = "abyss-backup-v0"),
            "blueprints/x.json" to blueprintJson("x"),
        )
        val result = runCatching { engine.restore(zip, tmp.newFolder("t2")) }
        assertTrue(result.exceptionOrNull() is BackupRejectedException)
    }

    @Test
    fun `路径穿越整包拒绝 —— 零触碰`() {
        val zip = makeZip(
            BackupContract.MANIFEST_ENTRY to manifestJson(listOf("../evil.json")),
            "../evil.json" to "evil".toByteArray(),
        )
        val target = tmp.newFolder("t3")
        val result = runCatching { engine.restore(zip, target) }
        assertTrue(result.exceptionOrNull() is BackupRejectedException)
        assertEquals("目标目录分毫未动", 0, target.listFiles()?.size ?: 0)
    }

    @Test
    fun `反斜杠与绝对路径整包拒绝`() {
        for (bad in listOf("\\evil.json", "/abs/evil.json", "a//b.json", "a/../b.json")) {
            val zip = makeZip(
                BackupContract.MANIFEST_ENTRY to manifestJson(listOf(bad)),
                bad to "x".toByteArray(),
            )
            val result = runCatching { engine.restore(zip, tmp.newFolder("t-${bad.hashCode()}")) }
            assertTrue("路径「$bad」必须拒绝", result.exceptionOrNull() is BackupRejectedException)
        }
    }

    @Test
    fun `单文件超限 —— zip bomb 整包拒绝`() {
        val big = ByteArray((BackupContract.MAX_ENTRY_BYTES + 1L).toInt())
        val zip = makeZip(
            BackupContract.MANIFEST_ENTRY to manifestJson(listOf("blueprints/big.json")),
            "blueprints/big.json" to big,
        )
        val result = runCatching { engine.restore(zip, tmp.newFolder("t4")) }
        assertTrue(result.exceptionOrNull() is BackupRejectedException)
    }

    @Test
    fun `打包侧路径校验 —— 穿越与重复路径拒绝`() {
        val source = tmp.newFile("a.json").apply { writeText("{}") }
        val result = runCatching {
            BackupArchiveBuilder().add("../evil.json", source)
        }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)

        val builder = BackupArchiveBuilder().add("blueprints/a.json", source)
        val dup = runCatching { builder.add("blueprints/a.json", source) }
        assertTrue(dup.exceptionOrNull() is IllegalArgumentException)
    }
}
