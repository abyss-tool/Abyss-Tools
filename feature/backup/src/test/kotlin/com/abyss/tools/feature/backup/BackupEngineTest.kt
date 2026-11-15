package com.abyss.tools.feature.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份引擎纯 JVM 单测：ZipGuard 三道闸（条目名穿越 / 体量护栏 / 根白名单）
 * 与 manifest 纯编解码 roundtrip。
 *
 * 为什么不测引擎本体：BackupEngine 触 android.content.Context 与
 * org.json（Android 桩，单测调用即抛 "not mocked"）—— 按仓库纪律把
 * 可单测的判定逻辑全部抽在 ZipGuard / BackupManifestIo（零 Android
 * 依赖）；引擎侧对两者的接线（Android 侧集成）交由真机验证。
 *
 * 口径：全部 fail-closed —— 每条拒绝路径都必须给出人话错误（可断言），
 * 绝不静默放行。
 */
class BackupEngineTest {

    // ── ZipGuard 第一道闸：条目名（路径穿越 / 绝对路径 / 反斜杠）──────

    @Test
    fun `条目名拒绝路径穿越`() {
        assertNotNull(ZipGuard.entryNameError("../evil.json"))
        assertNotNull(ZipGuard.entryNameError("plugins/../../evil.json"))
        assertNotNull(ZipGuard.entryNameError("plugins/..\\evil.json"))
        // fail-closed 保守口径：文件名里出现 ".." 子串也拒绝（宁可误杀）
        assertNotNull(ZipGuard.entryNameError("plugins/my..file.json"))
    }

    @Test
    fun `条目名拒绝绝对路径与反斜杠形态`() {
        assertNotNull(ZipGuard.entryNameError("/abs/path.json"))
        assertNotNull(ZipGuard.entryNameError("\\server\\share\\evil.json"))
        assertNotNull(ZipGuard.entryNameError("plugins\\evil.json"))
    }

    @Test
    fun `条目名拒绝空名`() {
        assertNotNull(ZipGuard.entryNameError(""))
    }

    @Test
    fun `合法条目名放行`() {
        assertNull(ZipGuard.entryNameError("plugins/abyss-plugin-shell/plugin.json"))
        assertNull(ZipGuard.entryNameError("plugins/abyss-plugin-shell/dex/api.jar"))
        assertNull(ZipGuard.entryNameError("blueprints/night-schedule.json"))
        assertNull(ZipGuard.entryNameError("prefs/abyss_settings.xml"))
        assertNull(ZipGuard.entryNameError("manifest.json"))
    }

    // ── ZipGuard 第二道闸：zip 炸弹体量护栏 ──────────────────────────

    @Test
    fun `体量护栏在上限处放行越界即拒绝`() {
        assertNull(ZipGuard.totalBytesError(ZipGuard.MAX_TOTAL_UNCOMPRESSED_BYTES))
        assertNotNull(ZipGuard.totalBytesError(ZipGuard.MAX_TOTAL_UNCOMPRESSED_BYTES + 1))
        assertNotNull(ZipGuard.totalBytesError(Long.MAX_VALUE))
    }

    @Test
    fun `体量估算信声明尺寸头撒谎走压缩比启发`() {
        // 声明尺寸 > 0：优先信任真值
        assertEquals(1024L, ZipGuard.estimateEntryUncompressedBytes(1024, 100))
        assertEquals(1024L, ZipGuard.estimateEntryUncompressedBytes(1024, 40_000))
        // 声明缺失 / 撒谎（<=0）：compressed × 20 启发
        assertEquals(2000L, ZipGuard.estimateEntryUncompressedBytes(0, 100))
        assertEquals(2000L, ZipGuard.estimateEntryUncompressedBytes(-5, 100))
        assertEquals(0L, ZipGuard.estimateEntryUncompressedBytes(0, 0))
    }

    @Test
    fun `体量护栏拦截撒谎头的累计超限`() {
        // 单个条目声明 0 / 压缩 1 MB → 估 20 MB 不足为惧；
        // 四个这种条目即 80 MB > 64 MB → 累计护栏必须点名拒绝
        val perEntry = ZipGuard.estimateEntryUncompressedBytes(0, 1024 * 1024)
        assertEquals(20L * 1024 * 1024, perEntry)
        assertNotNull(ZipGuard.totalBytesError(perEntry * 4))
        assertNull(ZipGuard.totalBytesError(perEntry * 3))
    }

    // ── ZipGuard 第三道闸：根白名单 ──────────────────────────────────

    @Test
    fun `根提取与白名单全放行`() {
        assertEquals("plugins", ZipGuard.rootOf("plugins/a/b/plugin.json"))
        assertEquals("blueprints", ZipGuard.rootOf("blueprints/x.json"))
        assertEquals("prefs", ZipGuard.rootOf("prefs/abyss_settings.xml"))
        assertEquals("manifest.json", ZipGuard.rootOf("manifest.json"))
        assertNull(
            ZipGuard.unexpectedRootError(
                listOf("manifest.json", "plugins", "blueprints", "prefs"),
            ),
        )
    }

    @Test
    fun `未知根被点名拒绝`() {
        val error = ZipGuard.unexpectedRootError(listOf("plugins", "evil", "another-evil"))
        assertNotNull(error)
        assertTrue(error!!.contains("evil"))
    }

    @Test
    fun `RestorePreview 根聚合口径`() {
        // 与引擎 inspectZip 同一口径：条目名 → 顶层根去重 → 白名单判定
        val names = listOf(
            "manifest.json",
            "plugins/a/plugin.json",
            "plugins/b/plugin.json",
            "blueprints/x.json",
            "prefs/abyss_settings.xml",
        )
        val roots = names.map { ZipGuard.rootOf(it) }.distinct().sorted()
        assertEquals(listOf("blueprints", "manifest.json", "plugins", "prefs"), roots)
        assertNull(ZipGuard.unexpectedRootError(roots))
    }

    // ── BackupManifestIo：纯编解码 roundtrip 与 fail-closed ───────────

    @Test
    fun `manifest roundtrip 五字段一致`() {
        val manifest = BackupEngine.BackupManifest(
            schema = BackupEngine.SCHEMA,
            appVersion = "0.8.0",
            createdAt = 1730000000000L,
            pluginCount = 3,
            settingsIncluded = true,
        )
        val parsed = BackupManifestIo.parseManifestJson(
            BackupManifestIo.buildManifestJson(manifest),
        ).getOrThrow()
        assertEquals(manifest, parsed)
    }

    @Test
    fun `manifest 模板字段顺序即契约`() {
        val manifest = BackupEngine.BackupManifest(
            schema = BackupEngine.SCHEMA,
            appVersion = "0.8.0",
            createdAt = 1730000000000L,
            pluginCount = 0,
            settingsIncluded = false,
        )
        val json = BackupManifestIo.buildManifestJson(manifest)
        assertTrue(json.contains("\"schema\": \"abyss-backup-v1\""))
        assertTrue(json.contains("\"appVersion\": \"0.8.0\""))
        assertTrue(json.contains("\"createdAt\": 1730000000000"))
        assertTrue(json.contains("\"pluginCount\": 0"))
        assertTrue(json.contains("\"settingsIncluded\": false"))
    }

    @Test
    fun `manifest 拒绝坏 schema`() {
        val evil = BackupEngine.BackupManifest(
            schema = "evil-schema",
            appVersion = "0.8.0",
            createdAt = 1730000000000L,
            pluginCount = 1,
            settingsIncluded = false,
        )
        val result = BackupManifestIo.parseManifestJson(
            BackupManifestIo.buildManifestJson(evil),
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("schema") == true)
    }

    @Test
    fun `manifest 拒绝缺字段`() {
        val broken = """
            {
              "schema": "abyss-backup-v1",
              "appVersion": "0.8.0"
            }
        """.trimIndent()
        val result = BackupManifestIo.parseManifestJson(broken)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("createdAt") == true)
    }

    @Test
    fun `manifest 拒绝非数字 createdAt 与非布尔 settingsIncluded`() {
        val badCreatedAt = """
            {"schema": "abyss-backup-v1", "appVersion": "0.8.0",
             "createdAt": "yesterday", "pluginCount": 1, "settingsIncluded": true}
        """.trimIndent()
        val result1 = BackupManifestIo.parseManifestJson(badCreatedAt)
        assertTrue(result1.isFailure)

        val badBoolean = """
            {"schema": "abyss-backup-v1", "appVersion": "0.8.0",
             "createdAt": 1730000000000, "pluginCount": 1, "settingsIncluded": "maybe"}
        """.trimIndent()
        val result2 = BackupManifestIo.parseManifestJson(badBoolean)
        assertTrue(result2.isFailure)
        assertTrue(result2.exceptionOrNull()?.message?.contains("settingsIncluded") == true)
    }

    @Test
    fun `manifest 拒绝非正 createdAt 与负 pluginCount`() {
        val zeroCreatedAt = """
            {"schema": "abyss-backup-v1", "appVersion": "0.8.0",
             "createdAt": 0, "pluginCount": 1, "settingsIncluded": true}
        """.trimIndent()
        val result1 = BackupManifestIo.parseManifestJson(zeroCreatedAt)
        assertTrue(result1.isFailure)

        val negativeCount = """
            {"schema": "abyss-backup-v1", "appVersion": "0.8.0",
             "createdAt": 1730000000000, "pluginCount": -2, "settingsIncluded": true}
        """.trimIndent()
        val result2 = BackupManifestIo.parseManifestJson(negativeCount)
        assertTrue(result2.isFailure)
    }

    @Test
    fun `manifest 拒绝坏 JSON 整体`() {
        val garbage = "this-is-not-json-at-all"
        assertTrue(BackupManifestIo.parseManifestJson(garbage).isFailure)
    }

    @Test
    fun `manifest 字符串转义 roundtrip（引号与反斜杠）`() {
        val manifest = BackupEngine.BackupManifest(
            schema = BackupEngine.SCHEMA,
            appVersion = "0.8.0-\"nightly\\\\01\"",
            createdAt = 1730000000001L,
            pluginCount = 2,
            settingsIncluded = false,
        )
        val json = BackupManifestIo.buildManifestJson(manifest)
        // 模板确实产出了转义（不是把裸引号塞进 JSON）
        assertTrue(json.contains("\\\""))
        assertTrue(json.contains("\\\\"))
        assertEquals(manifest, BackupManifestIo.parseManifestJson(json).getOrThrow())
    }

    @Test
    fun `manifest 拒绝不认识的转义序列`() {
        val badEscape = """
            {"schema": "abyss-backup-v1", "appVersion": "v\n0_8_0",
             "createdAt": 1730000000000, "pluginCount": 1, "settingsIncluded": true}
        """.trimIndent()
        val result = BackupManifestIo.parseManifestJson(badEscape)
        assertTrue(result.isFailure)
        assertFalse(result.isSuccess)
    }

    @Test
    fun `manifest 解析容忍空白差异（org_json 紧凑形态同构可读）`() {
        // Android 侧 org.json toString() 无空格 —— 纯 JVM 解析口径必须读得懂它
        val compact = """
            {"schema":"abyss-backup-v1","appVersion":"0.8.0",
             "createdAt":1730000000000,"pluginCount":7,"settingsIncluded":true}
        """.trimIndent()
        val parsed = BackupManifestIo.parseManifestJson(compact).getOrThrow()
        assertEquals("0.8.0", parsed.appVersion)
        assertEquals(7, parsed.pluginCount)
        assertEquals(1730000000000L, parsed.createdAt)
        assertTrue(parsed.settingsIncluded)
    }
}
