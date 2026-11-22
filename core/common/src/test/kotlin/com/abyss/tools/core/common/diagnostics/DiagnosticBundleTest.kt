package com.abyss.tools.core.common.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** N-EXP-003 一键诊断包（脱敏 fail-closed：白名单外一律遮蔽）。 */
class DiagnosticBundleTest {

    private val allowed = setOf("com.abyss.tools", "com.abyss.plugin.builder")

    @Test
    fun `non-whitelisted package names are redacted`() {
        val out = DiagnosticBundle.redact(
            "插件 com.third.party.app 安装失败;宿主 com.abyss.tools 正常",
            allowed,
        )
        assertFalse(out.contains("com.third.party.app"))
        assertTrue(out.contains(DiagnosticBundle.REDACTED))
        assertTrue(out.contains("com.abyss.tools"))
    }

    @Test
    fun `redaction is case-insensitive on whitelist`() {
        val out = DiagnosticBundle.redact("host COM.ABYSS.TOOLS ok; com.EVIL.App hidden", allowed)
        assertTrue(out.contains("COM.ABYSS.TOOLS"))
        assertFalse(out.contains("com.EVIL.App"))
    }

    @Test
    fun `bundle contains device sections errors and redacted log tail`() {
        val bundle = DiagnosticBundle.build(
            sections = listOf(
                "设备" to "ROM=ColorOS; SDK=36; ABI=arm64-v8a; installer=com.android.packageinstaller",
                "权限层" to "Shizuku=running(uid 2000); 授权=已授权",
            ),
            logTail = listOf(
                "12:00:01 [清单签名校验] version.json 签名验过",
                "12:00:02 [插件安装] com.suspicious.pkg 被拒",
            ),
            errorSummary = mapOf("清单签名校验" to 2, "网络" to 1),
            allowedPackages = allowed,
        )
        assertTrue(bundle.startsWith("abyss-diag-bundle-v1"))
        assertTrue(bundle.contains("## 设备"))
        assertTrue(bundle.contains("arm64-v8a"))
        assertFalse(bundle.contains("com.android.packageinstaller"))
        assertFalse(bundle.contains("com.suspicious.pkg"))
        assertTrue(bundle.contains("«pkg-redacted»"))
        assertTrue(bundle.contains("清单签名校验 × 2"))
    }

    @Test
    fun `empty errors renders placeholder`() {
        val bundle = DiagnosticBundle.build(emptyList(), emptyList(), emptyMap(), allowed)
        assertTrue(bundle.contains("(无错误记录)"))
    }
}
