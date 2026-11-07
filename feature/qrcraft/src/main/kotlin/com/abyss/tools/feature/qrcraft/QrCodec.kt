package com.abyss.tools.feature.qrcraft

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** 解码结果：文本 + 条码格式名（zxing BarcodeFormat.name，如 QR_CODE / CODE_128 / EAN_13）。 */
data class DecodedContent(
    val text: String,
    val format: String,
)

/**
 * 二维码 / 条码编解码（纯 Kotlin + zxing core，零 Android 依赖 —— JVM 可单测）。
 *
 * 编码：QRCodeWriter 以「自然模块尺寸」产出 BitMatrix（1 模块 = 1 bit，
 * zxing 3.5.3 的 renderResult 对 width=0/height=0 请求会取 QR 本征尺寸，
 * multiple 恒为 1），UI 层用 Canvas / 最近邻缩放自行放大 —— 不经过中间 Bitmap。
 *
 * 解码：调用方（Android 侧）把 Bitmap 压平成 ARGB IntArray 像素，
 * 经 [pixelsToLuminanceSource] → HybridBinarizer → MultiFormatReader 识别。
 * 支持格式：QR_CODE / CODE_128 / EAN_13 / UPC_A / ITF。
 */
object QrCodec {

    /** zxing 同款默认静区（模块数）。 */
    const val DEFAULT_MARGIN = 4

    /** 静区上限：防手滑传怪值产出不可扫的图。 */
    const val MAX_MARGIN = 16

    /** 0 = 按 QR 本征尺寸渲染（见类注释），宽高语义由调用方决定。 */
    private const val NATURAL_SIZE = 0

    /** QR 版本 1 = 21 模块，版本 40 = 177 模块（静区另计）。 */
    private const val MIN_QR_MODULES = 21
    private const val MAX_QR_MODULES = 177

    /** 扫码格式白名单（任务规格：QR + Code128 + EAN13 + UPC-A + ITF，TRY_HARDER）。 */
    private val DECODE_HINTS: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(
            BarcodeFormat.QR_CODE,
            BarcodeFormat.CODE_128,
            BarcodeFormat.EAN_13,
            BarcodeFormat.UPC_A,
            BarcodeFormat.ITF,
        ),
    )

    /**
     * 编码为自然尺寸 BitMatrix（含静区）。
     *
     * fail-closed：空内容 / 静区越界 / 内容超出容量（WriterException）
     * 一律返回 Result.failure（中文人话消息优先），绝不静默产出坏码。
     */
    fun encode(
        content: String,
        errorCorrection: ErrorCorrectionLevel = ErrorCorrectionLevel.M,
        margin: Int = DEFAULT_MARGIN,
    ): Result<BitMatrix> {
        if (content.isEmpty()) {
            return Result.failure(IllegalArgumentException("内容不能为空"))
        }
        if (margin !in 0..MAX_MARGIN) {
            return Result.failure(IllegalArgumentException("静区宽度需在 0～$MAX_MARGIN 模块之间"))
        }
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to errorCorrection,
            EncodeHintType.MARGIN to margin,
            // 关键决策：zxing 默认按 ISO-8859-1 落字节，中文会被替换成 '?'，
            // 编出来的码扫出来就是乱码 —— 必须显式钉 UTF-8。
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        return runCatching {
            QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, NATURAL_SIZE, NATURAL_SIZE, hints)
        }.mapCatching { matrix ->
            // 防御：万一未来 zxing 语义变化导致矩阵被放大（multiple > 1），
            // 直接拒绝渲染错码（fail-closed），而不是画出一个坏图。
            check(matrix.width == matrix.height) {
                "编码结果不是方阵（${matrix.width}x${matrix.height}）"
            }
            check(matrix.width in (MIN_QR_MODULES + 2 * margin)..(MAX_QR_MODULES + 2 * margin)) {
                "编码结果尺寸异常（${matrix.width}），不是自然模块尺寸，拒绝渲染"
            }
            matrix
        }
    }

    /** ARGB 像素 → zxing 灰度源（Android 的 Bitmap.getPixels / 测试程序化像素同构）。 */
    fun pixelsToLuminanceSource(pixels: IntArray, width: Int, height: Int): RGBLuminanceSource =
        RGBLuminanceSource(width, height, pixels)

    /**
     * 从 ARGB 像素矩阵解码（QR + Code 128 + EAN-13 + UPC-A + ITF，TRY_HARDER）。
     * 找不到码 → failure（人话：图里没有码 / 太暗 / 太模糊）。
     */
    fun decodePixels(pixels: IntArray, width: Int, height: Int): Result<DecodedContent> {
        if (width <= 0 || height <= 0) {
            return Result.failure(IllegalArgumentException("像素尺寸非法：${width}x$height"))
        }
        if (pixels.size != width * height) {
            return Result.failure(
                IllegalArgumentException("像素数组长度与尺寸不符（需要 ${width * height}，实际 ${pixels.size}）"),
            )
        }
        val reader = MultiFormatReader()
        try {
            reader.setHints(DECODE_HINTS)
            val source = pixelsToLuminanceSource(pixels, width, height)
            val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            return Result.success(DecodedContent(text = result.text, format = result.barcodeFormat.name))
        } catch (e: NotFoundException) {
            return Result.failure(
                IllegalStateException("图中未识别出二维码或条形码 —— 可能太暗 / 太模糊，或图片里没有码", e),
            )
        } catch (e: Exception) {
            // 个别畸形图片会触发 zxing 内部罕见异常（越界等）：同样 failure 封口，不 crash
            return Result.failure(e)
        } finally {
            reader.reset()
        }
    }

    /** 内容是否「疑似 URL」（http/https 开头）—— 用于扫描结果给「打开链接」动作。 */
    fun isProbablyUrl(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
    }
}
