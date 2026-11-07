package com.abyss.tools.feature.qrcraft

import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编解码纯逻辑：encode → 像素 → decode 往返 / URL 检测 / 坏输入失败封闭。
 * 全部跑在纯 JVM 上（zxing core 零 Android 依赖，RGBLuminanceSource 直接吃 IntArray）。
 */
class QrCodecTest {

    private val fgArgb = 0xFF141414.toInt() // 墨黑
    private val bgArgb = 0xFFFFFFFF.toInt() // 纸白

    /** BitMatrix → ARGB 像素阵（每模块放大 scale 倍，模拟真实截图尺寸）。 */
    private fun matrixToPixels(matrix: BitMatrix, scale: Int = 4): Triple<IntArray, Int, Int> {
        val width = matrix.width * scale
        val height = matrix.height * scale
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (matrix.get(x / scale, y / scale)) fgArgb else bgArgb
            }
        }
        return Triple(pixels, width, height)
    }

    // ── 往返：encode → 像素 → decode ──────────────────────────────────────

    @Test
    fun `编码转像素再解码往返 含中文 URL`() {
        val content = "https://abyss.tools/测试"
        val matrix = QrCodec.encode(content, ErrorCorrectionLevel.M).getOrThrow()

        // 自然尺寸断言：含默认静区的方阵
        assertTrue(matrix.width == matrix.height)
        assertTrue(matrix.width > 21)

        val (pixels, width, height) = matrixToPixels(matrix)
        val decoded = QrCodec.decodePixels(pixels, width, height).getOrThrow()
        assertEquals(content, decoded.text)
        assertEquals("QR_CODE", decoded.format)
    }

    @Test
    fun `四级纠错全部可往返`() {
        val content = "深渊工具 QR 工坊 — L/M/Q/H 纠错级别"
        val levels = listOf(
            ErrorCorrectionLevel.L,
            ErrorCorrectionLevel.M,
            ErrorCorrectionLevel.Q,
            ErrorCorrectionLevel.H,
        )
        for (level in levels) {
            val matrix = QrCodec.encode(content, level).getOrThrow()
            val (pixels, width, height) = matrixToPixels(matrix)
            val decoded = QrCodec.decodePixels(pixels, width, height).getOrThrow()
            assertEquals("纠错级别 $level 往返失败", content, decoded.text)
        }
    }

    @Test
    fun `纯白图片解码失败`() {
        // 没有码 → NotFoundException → Result.failure（fail-closed，不 crash）
        val result = QrCodec.decodePixels(IntArray(64) { bgArgb }, 8, 8)
        assertTrue(result.isFailure)
    }

    // ── URL 检测 ───────────────────────────────────────────────────────────

    @Test
    fun `URL 检测`() {
        assertTrue(QrCodec.isProbablyUrl("https://abyss.tools"))
        assertTrue(QrCodec.isProbablyUrl("http://abyss.tools/path?q=1"))
        assertTrue(QrCodec.isProbablyUrl("  https://abyss.tools  "))
        assertTrue(QrCodec.isProbablyUrl("HTTP://EXAMPLE.COM"))
        assertFalse(QrCodec.isProbablyUrl("abyss.tools"))
        assertFalse(QrCodec.isProbablyUrl("ftp://abyss.tools"))
        assertFalse(QrCodec.isProbablyUrl("查看 https://abyss.tools"))
        assertFalse(QrCodec.isProbablyUrl(""))
    }

    // ── 坏输入失败分支 ─────────────────────────────────────────────────────

    @Test
    fun `空内容编码失败`() {
        // zxing 对空内容抛 IllegalArgumentException（非 WriterException）——
        // 编码前置校验先行拦截，统一折叠为 Result.failure
        val result = QrCodec.encode("", ErrorCorrectionLevel.M)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.isNotBlank() == true)
    }

    @Test
    fun `静区越界编码失败`() {
        assertTrue(QrCodec.encode("abyss", ErrorCorrectionLevel.M, margin = -1).isFailure)
        assertTrue(QrCodec.encode("abyss", ErrorCorrectionLevel.M, margin = 99).isFailure)
    }

    @Test
    fun `像素尺寸与数组不符解码失败`() {
        assertTrue(QrCodec.decodePixels(IntArray(4), 0, 4).isFailure)
        assertTrue(QrCodec.decodePixels(IntArray(5), 2, 2).isFailure)
    }

    @Test
    fun `静区宽度参与自然尺寸`() {
        val noMargin = QrCodec.encode("abyss", ErrorCorrectionLevel.L, margin = 0).getOrThrow()
        val withMargin = QrCodec.encode("abyss", ErrorCorrectionLevel.L, margin = 4).getOrThrow()
        // 同内容同级别下，静区两侧各加 margin 模块
        assertEquals(withMargin.width, noMargin.width + 2 * 4)
    }
}
