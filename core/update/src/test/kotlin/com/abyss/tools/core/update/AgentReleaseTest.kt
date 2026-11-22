package com.abyss.tools.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 深渊agent 通道清单解析测试（纯 JVM，FEAT-037 AC-003）。
 *
 * 夹具按真实通道 schema 构造（Ultra-Guru/Android-Guru-Agent-Release
 * version.json：download 为 ABI 变体 map）。
 */
class AgentReleaseTest {

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
          "signingCertSha256": "abc",
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
            {"versionName":"1.5.0","versionCode":1,"download":{"universal":{"url":"https://github.com/x/y.apk","sha256":"ab"}}}
        """.trimIndent()
        val m = AgentReleaseParsers.parseAgentReleaseManifest(raw)
        assertEquals("universal", m.defaultVariantKey())
        assertEquals("https://github.com/x/y.apk", m.assetOf(null)?.url)
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
            {"versionName":"1.5.0","versionCode":1,"download":{"arm64":{"url":"https://github.com/x/y.apk"}}}
        """.trimIndent()
        try {
            AgentReleaseParsers.parseAgentReleaseManifest(raw)
            fail("缺 sha256 应当拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sha256"))
        }
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
            AgentReleaseParsers.parseAgentReleaseManifest("""{"versionCode":1,"download":{"arm64":{"url":"u","sha256":"s"}}}""")
            fail("缺 versionName 应当拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("versionName"))
        }
    }
}
