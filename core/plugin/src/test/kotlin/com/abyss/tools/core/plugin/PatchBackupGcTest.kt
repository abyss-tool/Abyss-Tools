package com.abyss.tools.core.plugin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * B-27 回归：补丁备份区 GC（`.abyss-patch-backup-*` / 旧前缀 `.patch-backup-*`）。
 *
 * PatchEngine 把备份目录建在 pluginsRoot（targetDir 外部），失败回滚后
 * 与正常升级后均无清理 —— 长期膨胀。GC 策略：按目录名时间戳（前缀+ts，
 * 名字序 = 时间序）保留最近 N 份。
 */
class PatchBackupGcTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fakeTransport(): com.abyss.tools.core.update.HttpTransport =
        object : com.abyss.tools.core.update.HttpTransport {
            override fun get(url: String): String = throw java.io.IOException("fake")
            override fun download(
                url: String,
                dest: File,
                onProgress: (readBytes: Long, totalBytes: Long) -> Unit,
            ) {
                throw java.io.IOException("fake")
            }
        }

    private val noGateway = object : com.abyss.tools.core.update.ApkInstallGateway {
        override fun install(apk: File): Result<Unit> = Result.failure(IllegalStateException("fake"))
    }

    private fun mkBackup(root: File, prefix: String, ts: String, marker: String = "x") {
        val d = File(root, "$prefix$ts")
        d.mkdirs()
        d.resolve("old.txt").writeText(marker)
    }

    @Test
    fun `gc keeps newest N patch backup dirs and deletes the rest`() {
        val root = tmp.newFolder()
        val installer = PluginInstaller(root, fakeTransport(), noGateway)
        (1..5).forEach { i -> mkBackup(root, ".abyss-patch-backup-", "100000000000$i", "backup$i") }
        // 干扰项：普通目录与插件目录不受 GC 影响
        File(root, "abyss-plugin-terminal").mkdirs()
        File(root, "unrelated-dir").mkdirs()

        installer.gcPatchBackups(keep = 3)

        val remaining = root.listFiles { f -> f.name.startsWith(".abyss-patch-backup-") }!!
            .map { it.name.removePrefix(".abyss-patch-backup-") }.sorted()
        assertEquals(listOf("1000000000003", "1000000000004", "1000000000005"), remaining)
        assertTrue(File(root, "abyss-plugin-terminal").exists())
        assertTrue(File(root, "unrelated-dir").exists())
    }

    @Test
    fun `gc recognizes old prefix patch-backup dirs`() {
        val root = tmp.newFolder()
        val installer = PluginInstaller(root, fakeTransport(), noGateway)
        mkBackup(root, ".patch-backup-", "1")
        mkBackup(root, ".patch-backup-", "2")
        mkBackup(root, ".abyss-patch-backup-", "3")
        installer.gcPatchBackups(keep = 1)
        // 全量排序下最新 1 份保留（新前缀），旧前缀两份清理
        assertTrue(File(root, ".abyss-patch-backup-3").exists())
        assertFalse(File(root, ".patch-backup-1").exists())
        assertFalse(File(root, ".patch-backup-2").exists())
    }

    @Test
    fun `clearCache runs gc as well`() {
        val root = tmp.newFolder()
        val installer = PluginInstaller(root, fakeTransport(), noGateway)
        File(root, "cache").mkdirs()
        (1..5).forEach { i -> mkBackup(root, ".abyss-patch-backup-", "100000000000$i") }
        installer.clearCache()
        assertFalse(File(root, "cache").exists())
        assertEquals(3, root.listFiles { f -> f.name.startsWith(".abyss-patch-backup-") }!!.size)
    }
}
