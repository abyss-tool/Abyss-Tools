package com.abyss.tools.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 镜像目录单测（v0.7.0）：改写语义 / 形态判定 / 白名单推导 / 还原闭环。
 */
class MirrorCatalogTest {

    private val releaseUrl =
        "https://github.com/abyss-tool/Abyss-Tools-Release/releases/download/v0.3.0/abyss-tools-v0.3.0.apk"
    private val rawUrl =
        "https://raw.githubusercontent.com/abyss-tool/Abyss-Plugins/main/plugins.json"

    // ── 形态判定 ─────────────────────────────────────────────────────

    @Test
    fun `github release 资产判定为 RELEASE`() {
        assertEquals(MirrorAssetKind.RELEASE, MirrorCatalog.assetKindOf(releaseUrl))
    }

    @Test
    fun `raw 文本判定为 RAW`() {
        assertEquals(MirrorAssetKind.RAW, MirrorCatalog.assetKindOf(rawUrl))
    }

    @Test
    fun `非 GitHub 域判定为 null（不改写直通）`() {
        assertNull(MirrorCatalog.assetKindOf("https://example.com/file.bin"))
        assertNull(MirrorCatalog.assetKindOf("file:///local/path"))
        assertNull(MirrorCatalog.assetKindOf("http://github.com/insecure")) // http 非 https
    }

    // ── 改写语义 ─────────────────────────────────────────────────────

    @Test
    fun `PREFIX 镜像改写 = 前缀拼接原始 URL`() {
        val mirror = MirrorCatalog.byId("ghfast")!!
        assertEquals("https://ghfast.top/$releaseUrl", MirrorCatalog.rewrite(mirror, releaseUrl))
        assertEquals("https://ghfast.top/$rawUrl", MirrorCatalog.rewrite(mirror, rawUrl))
    }

    @Test
    fun `HOST_SWAP 镜像只改写 raw 域且仅替换 host`() {
        val mirror = MirrorCatalog.byId("raw-gitmirror")!!
        assertEquals(
            "https://raw.gitmirror.com/abyss-tool/Abyss-Plugins/main/plugins.json",
            MirrorCatalog.rewrite(mirror, rawUrl),
        )
        // HOST_SWAP 不服务 RELEASE 形态 → 原样返回
        assertEquals(releaseUrl, MirrorCatalog.rewrite(mirror, releaseUrl))
    }

    @Test
    fun `DIRECT 镜像恒原样返回`() {
        assertEquals(releaseUrl, MirrorCatalog.rewrite(MirrorCatalog.DIRECT, releaseUrl))
        assertEquals(rawUrl, MirrorCatalog.rewrite(MirrorCatalog.DIRECT, rawUrl))
    }

    @Test
    fun `非 GitHub 域不经任何镜像改写`() {
        val url = "https://example.com/file.bin"
        MirrorCatalog.all.forEach { mirror ->
            assertEquals(url, MirrorCatalog.rewrite(mirror, url))
        }
    }

    // ── id 解析 / forAsset ──────────────────────────────────────────

    @Test
    fun `byId 未知 id 与 auto 均回退 null（直连语义）`() {
        assertNull(MirrorCatalog.byId(null))
        assertNull(MirrorCatalog.byId(MIRROR_AUTO))
        assertNull(MirrorCatalog.byId("nonexistent-mirror"))
        assertNotNull(MirrorCatalog.byId("ghfast"))
    }

    @Test
    fun `forAsset RELEASE 不含纯 raw 镜像 RAW 含全部可服务镜像`() {
        val releaseMirrors = MirrorCatalog.forAsset(MirrorAssetKind.RELEASE)
        assertTrue(releaseMirrors.all { MirrorAssetKind.RELEASE in it.appliesTo })
        assertTrue(releaseMirrors.none { it.id == "raw-gitmirror" })

        val rawMirrors = MirrorCatalog.forAsset(MirrorAssetKind.RAW)
        assertTrue(rawMirrors.any { it.id == "raw-gitmirror" })
        assertTrue(rawMirrors.contains(MirrorCatalog.DIRECT)) // DIRECT 恒在列
    }

    // ── 还原闭环（FetchHostPolicy 联动的信任基础）────────────────────

    @Test
    fun `PREFIX 改写可无损还原原始 URL`() {
        val mirror = MirrorCatalog.byId("ghproxy-net")!!
        val rewritten = MirrorCatalog.rewrite(mirror, releaseUrl)
        assertEquals(releaseUrl, MirrorCatalog.originalUrlOf(rewritten))
    }

    @Test
    fun `HOST_SWAP 改写可还原原始 raw URL`() {
        val mirror = MirrorCatalog.byId("raw-gitmirror")!!
        val rewritten = MirrorCatalog.rewrite(mirror, rawUrl)
        assertEquals(rawUrl, MirrorCatalog.originalUrlOf(rewritten))
    }

    @Test
    fun `非镜像 URL 还原为 null`() {
        assertNull(MirrorCatalog.originalUrlOf(releaseUrl))
        assertNull(MirrorCatalog.originalUrlOf("https://evil.example.com/$releaseUrl"))
    }

    // ── 白名单推导 ──────────────────────────────────────────────────

    @Test
    fun `镜像域白名单 = 非 DIRECT 镜像 host 全集`() {
        val expected = MirrorCatalog.all
            .filter { it.strategy != MirrorStrategy.DIRECT }
            .map { it.host }
            .toSet()
        assertEquals(expected, MirrorCatalog.MIRROR_HOSTS)
        assertTrue("ghfast.top" in MirrorCatalog.MIRROR_HOSTS)
        assertTrue("github.com" !in MirrorCatalog.MIRROR_HOSTS) // 直连域不在镜像白名单
    }
}
