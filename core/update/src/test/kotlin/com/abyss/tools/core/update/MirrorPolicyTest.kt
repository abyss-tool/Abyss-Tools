package com.abyss.tools.core.update

import com.abyss.tools.core.common.MIRROR_AUTO
import com.abyss.tools.core.common.MirrorCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FetchHostPolicy 镜像扩展 + MirroredTransport 回退语义单测（v0.7.0）。
 */
class MirrorPolicyTest {

    private val releaseUrl =
        "https://github.com/abyss-tool/Abyss-Tools-Release/releases/download/v0.3.0/abyss-tools-v0.3.0.apk"
    private val rawUrl =
        "https://raw.githubusercontent.com/abyss-tool/Abyss-Plugins/main/plugins.json"

    // ── FetchHostPolicy：GitHub 域照旧 ──────────────────────────────

    @Test
    fun `GitHub 白名单域照旧放行`() {
        assertEquals(releaseUrl, FetchHostPolicy.check(releaseUrl))
        assertEquals(rawUrl, FetchHostPolicy.check(rawUrl))
    }

    @Test
    fun `白名单外域依旧拒绝（fail-closed 不因镜像扩展松动）`() {
        assertThrows(java.io.IOException::class.java) {
            FetchHostPolicy.check("https://evil.example.com/payload.bin")
        }
        assertThrows(java.io.IOException::class.java) {
            FetchHostPolicy.check("http://github.com/not-https")
        }
    }

    // ── FetchHostPolicy：镜像域新语义 ───────────────────────────────

    @Test
    fun `注册镜像 URL 放行（PREFIX 内嵌合法 GitHub URL）`() {
        val mirror = MirrorCatalog.byId("ghfast")!!
        assertEquals(releaseUrl, FetchHostPolicy.check(MirrorCatalog.rewrite(mirror, releaseUrl)))
    }

    @Test
    fun `镜像域内嵌非法 URL 拒绝（防借镜像域外传载荷）`() {
        // https://ghfast.top/https://evil.example.com/... —— 镜像域合法但内层非白名单
        assertThrows(java.io.IOException::class.java) {
            FetchHostPolicy.check("https://ghfast.top/https://evil.example.com/payload.bin")
        }
        // 镜像域裸路径（无法还原完整内层 URL）
        assertThrows(java.io.IOException::class.java) {
            FetchHostPolicy.check("https://ghfast.top/relative/path")
        }
    }

    @Test
    fun `HOST_SWAP 镜像 URL 放行并可还原校验`() {
        val mirror = MirrorCatalog.byId("raw-gitmirror")!!
        assertEquals(rawUrl, FetchHostPolicy.check(MirrorCatalog.rewrite(mirror, rawUrl)))
    }

    // ── MirroredTransport：回退与直通语义 ───────────────────────────

    /** 记录调用序列的假底座：按 URL 决定成败。 */
    private class ScriptedTransport(
        private val failUrls: Set<String>,
        private val okBody: String = "payload",
    ) : HttpTransport {
        val attempts = mutableListOf<String>()

        override fun get(url: String): String {
            attempts += url
            if (url in failUrls) throw java.io.IOException("HTTP 503 <- $url")
            return okBody
        }

        override fun download(
            url: String,
            dest: File,
            onProgress: (Long, Long) -> Unit,
        ) {
            attempts += url
            if (url in failUrls) throw java.io.IOException("HTTP 503 <- $url")
            dest.writeText(okBody)
        }
    }

    @Test
    fun `首选镜像失败自动回退下一镜像直到成功`() {
        val mirror = MirrorCatalog.byId("ghfast")!!
        val router = MirrorRouter().apply { preferenceProvider = { mirror.id } }
        val base = ScriptedTransport(
            failUrls = setOf(
                MirrorCatalog.rewrite(mirror, releaseUrl), // 锁定镜像挂
            ),
        )
        val transport = MirroredTransport(base, router)

        val body = transport.get(releaseUrl)

        assertEquals("payload", body)
        // 尝试序列：锁定镜像 → 直连（手动锁定的回退链恒含 DIRECT）
        assertEquals(
            listOf(MirrorCatalog.rewrite(mirror, releaseUrl), releaseUrl),
            base.attempts,
        )
    }

    @Test
    fun `手动锁定的回退链 = 锁定镜像 + DIRECT`() {
        val mirror = MirrorCatalog.byId("ghfast")!!
        val router = MirrorRouter().apply { preferenceProvider = { mirror.id } }
        val chain = router.orderedMirrors(releaseUrl, lazyProbe = false)
        assertEquals(listOf(mirror, MirrorCatalog.DIRECT), chain)
    }

    @Test
    fun `自动模式无探测缓存时按目录序全链尝试`() {
        val router = MirrorRouter().apply { preferenceProvider = { MIRROR_AUTO } }
        val base = ScriptedTransport(failUrls = emptySet())
        val transport = MirroredTransport(base, router)

        transport.get(rawUrl)

        // 自动模式：orderedMirrors 首选目录序第一个（lazyProbe=false 不探测）
        val expectedFirst = MirrorCatalog.forAsset(
            com.abyss.tools.core.common.MirrorAssetKind.RAW,
        ).first()
        assertEquals(MirrorCatalog.rewrite(expectedFirst, rawUrl), base.attempts.first())
    }

    @Test
    fun `全链失败如实抛出最后异常（不吞错）`() {
        val router = MirrorRouter().apply {
            preferenceProvider = { MirrorCatalog.byId("ghfast")!!.id }
        }
        val mirror = MirrorCatalog.byId("ghfast")!!
        val base = ScriptedTransport(failUrls = setOf(MirrorCatalog.rewrite(mirror, rawUrl), rawUrl))
        val transport = MirroredTransport(base, router)

        val ex = assertThrows(java.io.IOException::class.java) {
            transport.get(rawUrl)
        }
        assertTrue(ex.message!!.contains("503"))
        assertEquals(2, base.attempts.size) // 两级都试过
    }

    @Test
    fun `非 GitHub 域直通底座（不经镜像逻辑）`() {
        val router = MirrorRouter()
        val base = ScriptedTransport(failUrls = emptySet())
        val transport = MirroredTransport(base, router)

        transport.get("https://example.com/local-only.txt")

        assertEquals(listOf("https://example.com/local-only.txt"), base.attempts)
    }

    @Test
    fun `探测缓存驱动自动模式延迟排序`() {
        val router = MirrorRouter().apply {
            preferenceProvider = { MIRROR_AUTO }
            // ghproxy-cn 最快，ghfast 不可达，direct 慢
            probeCache = mapOf(
                "ghproxy-cn" to 120L,
                "ghproxy-net" to 900L,
                "ghfast" to null,
                "gh-proxy-com" to null,
                "direct" to 2500L,
                "raw-gitmirror" to 300L,
            )
        }
        val chain = router.orderedMirrors(releaseUrl, lazyProbe = false)
        // RELEASE 形态：可达者延迟升序在前，不可达殿后
        assertEquals("ghproxy-cn", chain[0].id)
        assertEquals("ghproxy-net", chain[1].id)
        assertEquals("direct", chain[2].id)
        assertTrue(chain.last().id != "direct") // direct 不是最后 = 有不可达镜像殿后
        assertTrue(chain.indexOfFirst { it.id == "ghfast" } > 2)
    }
}
