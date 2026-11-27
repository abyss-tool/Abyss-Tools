package com.abyss.tools.core.update

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 深渊agent 通道清单解析测试（纯 JVM，FEAT-037 AC-003）。
 *
 * 夹具按真实通道 schema 构造（Ultra-Guru/Android-Guru-Agent-Release
 * version.json：download 为 ABI 变体 map）。
 */
class AgentReleaseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fullManifest = """
        {
          "versionName": "1.5.84",
          "versionCode": 264,
          "tag": "v1.5.84",
          "publishedAt": "2026-11-22T06:53:37Z",
          "releasePage": "https://github.com/Ultra-Guru/Android-Guru-Agent-Release/releases/tag/v1.5.84",
          "download": {
            "arm64": {
              "url": "https://github.com/Ultra-Guru/Android-Guru-Agent-Release/releases/download/v1.5.84/ApexAgent-arm64.apk",
              "sizeBytes": 348346637,
              "sha256": "1e9ff07b626f8dc8cb5aa1c613fd9aa5a472ff5ff33155f3728d3028b8d16a5f"
            },
            "universal": {
              "url": "https://github.com/Ultra-Guru/Android-Guru-Agent-Release/releases/download/v1.5.84/ApexAgent-universal.apk",
              "sizeBytes": 963268098,
              "sha256": "fc55a6cf450720543d7180bbf804e280cf6167a66101b911f139da30b0a26027"
            }
          },
          "signingCertSha256": "bcb9dfdd9b2e197c90ab19fb80fa68fcbf9a9f180702d51b268069538303c913",
          "commitSha": "432d9651"
        }
    """.trimIndent()

    @Test
    fun `解析正常清单（双变体 + 未知字段忽略）`() {
        val m = AgentReleaseParsers.parseAgentReleaseManifest(fullManifest)
        assertEquals("1.5.84", m.versionName)
        assertEquals(264, m.versionCode)
        assertEquals("v1.5.84", m.resolvedTag)
        assertEquals(2, m.download.size)
        assertNull(m.assetOf("nonexistent")) // 未知变体 → 默认变体兜底？不：显式未知键走默认
        assertTrue(m.defaultVariantKey() == "arm64")
        assertEquals(348346637L, m.assetOf("arm64")?.sizeBytes)
        assertEquals(963268098L, m.assetOf("universal")?.sizeBytes)
    }

    @Test
    fun `仅 universal 变体时默认变体兜底 universal`() {
        val raw = """
            {"versionName":"1.5.0","versionCode":1,"signingCertSha256":"$CERT_SHA",
             "download":{"universal":{"url":"$URL_PREFIX/v1.5.0/agent-universal.apk","sha256":"${"a".repeat(64)}"}}}
        """.trimIndent()
        val m = AgentReleaseParsers.parseAgentReleaseManifest(raw)
        assertEquals("universal", m.defaultVariantKey())
        assertEquals("$URL_PREFIX/v1.5.0/agent-universal.apk", m.assetOf(null)?.url)
    }

    @Test
    fun `缺 download 字段 fail-closed 拒绝`() {
        try {
            AgentReleaseParsers.parseAgentReleaseManifest("""{"versionName":"1.5.0","versionCode":1}""")
            fail("缺 download 应当拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("download"))
        }
    }

    @Test
    fun `资产缺 sha256 fail-closed 拒绝（拒绝无校验下载）`() {
        val raw = """
            {"versionName":"1.5.0","versionCode":1,"signingCertSha256":"$CERT_SHA",
             "download":{"arm64":{"url":"$URL_PREFIX/v1.5.0/a.apk"}}}
        """.trimIndent()
        try {
            AgentReleaseParsers.parseAgentReleaseManifest(raw)
            fail("缺 sha256 应当拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sha256"))
        }
    }

    // ── B-06/B-18 回归：信任锚三件套 ─────────────────────────────────

    @Test
    fun `缺 signingCertSha256 拒绝（B-18 无指纹锚点不提供安装通路）`() {
        val raw = fullManifest.replace(""""signingCertSha256": "bcb9dfdd9b2e197c90ab19fb80fa68fcbf9a9f180702d51b268069538303c913",""", "")
        try {
            AgentReleaseParsers.parseAgentReleaseManifest(raw)
            fail("缺 signingCertSha256 应当拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("signingCertSha256"))
        }
    }

    @Test
    fun `signingCertSha256 形态非法拒绝（非 64 位小写十六进制）`() {
        val raw = fullManifest.replace(
            """"signingCertSha256": "bcb9dfdd9b2e197c90ab19fb80fa68fcbf9a9f180702d51b268069538303c913",""",
            """"signingCertSha256": "not-a-fingerprint",""")
        try {
            AgentReleaseParsers.parseAgentReleaseManifest(raw)
            fail("非法指纹形态应当拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("signingCertSha256"))
        }
    }

    @Test
    fun `资产 URL 指向其它仓库拒绝（B-06 仓库归属硬校验）`() {
        // 域合法（github.com）但仓库归属不符 —— 旧域级白名单会放行
        val raw = fullManifest.replace(
            "https://github.com/Ultra-Guru/Android-Guru-Agent-Release/releases/download/v1.5.84/ApexAgent-arm64.apk",
            "https://github.com/attacker/evil-repo/releases/download/v1.5.84/trojan.apk")
        try {
            AgentReleaseParsers.parseAgentReleaseManifest(raw)
            fail("跨仓库资产 URL 应当拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("releases/download"))
        }
    }

    companion object {
        private const val CERT_SHA = "bcb9dfdd9b2e197c90ab19fb80fa68fcbf9a9f180702d51b268069538303c913"
        private const val URL_PREFIX =
            "https://github.com/Ultra-Guru/Android-Guru-Agent-Release/releases/download"
    }

    @Test
    fun `坏 JSON 与缺 versionName 一律拒绝`() {
        try {
            AgentReleaseParsers.parseAgentReleaseManifest("not-json{")
            fail("坏 JSON 应当拒绝")
        } catch (expected: Exception) {
            // kotlinx.SerializationException 族 —— fail-closed 即可
        }
        try {
            AgentReleaseParsers.parseAgentReleaseManifest(
                """{"versionCode":1,"signingCertSha256":"$CERT_SHA","download":{"arm64":{"url":"$URL_PREFIX/v/a.apk","sha256":"s"}}}""")
            fail("缺 versionName 应当拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("versionName"))
        }
    }

    // ── Manager 层（B-06 ② / B-18）──────────────────────────────────

    /** 记录型网关：捕获期望门禁参数。 */
    private class RecordingGateway : ApkInstallGateway {
        var captured: InstallExpectation? = null
        override fun install(apk: File): Result<Unit> = install(apk, InstallExpectation(0))
        override fun install(apk: File, expectation: InstallExpectation): Result<Unit> {
            captured = expectation
            return Result.success(Unit)
        }
    }

    @Test
    fun `install 携带清单期望 versionCode 与证书指纹（B-18）`() {
        val gateway = RecordingGateway()
        val manager = AgentReleaseManager(
            transport = NoopTransport,
            versionJsonUrl = "https://raw.githubusercontent.com/Ultra-Guru/Android-Guru-Agent-Release/main/version.json",
            cacheDir = tmp.newFolder(),
            apkGateway = gateway,
        )
        val manifest = AgentReleaseParsers.parseAgentReleaseManifest(fullManifest)
        manager.install(File("agent.apk"), manifest).getOrThrow()
        val captured = gateway.captured!!
        assertEquals(264L, captured.expectedVersionCode)
        assertEquals(
            "bcb9dfdd9b2e197c90ab19fb80fa68fcbf9a9f180702d51b268069538303c913",
            captured.expectedSigningCertSha256,
        )
    }

    @Test
    fun `downloadVerified 拒绝跨仓库资产 URL 且不发起网络请求（B-06 ②）`() {
        // 解析层已拦（跨仓库 URL 过不了 parseAgentReleaseManifest）；
        // manager 层防御深度：直接构造的 DownloadInfo 同样在下载前被拒。
        val gateway = RecordingGateway()
        val manager = AgentReleaseManager(
            transport = ThrowingTransport,
            versionJsonUrl = "https://raw.githubusercontent.com/x/y/main/version.json",
            cacheDir = tmp.newFolder(),
            apkGateway = gateway,
        )
        val evil = DownloadInfo(
            url = "https://github.com/attacker/evil/releases/download/v1/t.apk",
            sizeBytes = 1L,
            sha256 = "a".repeat(64),
        )
        val e = assertThrows(IllegalArgumentException::class.java) {
            manager.downloadVerified(evil)
        }
        assertTrue(e.message!!.contains("releases/download"))
    }

    private object NoopTransport : HttpTransport {
        override fun get(url: String): String = ""
        override fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {}
    }

    private object ThrowingTransport : HttpTransport {
        override fun get(url: String): String = throw java.io.IOException("不应发起网络请求")
        override fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) =
            throw java.io.IOException("不应发起网络请求")
    }
}
