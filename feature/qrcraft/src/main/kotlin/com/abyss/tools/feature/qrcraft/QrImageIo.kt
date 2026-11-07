package com.abyss.tools.feature.qrcraft

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.google.zxing.common.BitMatrix
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Android 侧图片 IO（Bitmap / MediaStore / FileProvider 全落在这里，屏幕层零位图细节）。
 *
 * 保存路径双分支：
 *  - API 29+：MediaStore.RELATIVE_PATH = Pictures/QRCraft（分区存储官方通道，免权限）；
 *  - API 26–28：MediaStore.RelativePath 尚不存在 → 公共图片目录直接 File 写
 *    （targetSdk 28 无 scoped storage 强制，需 WRITE_EXTERNAL_STORAGE 运行时权限，
 *    由 UI 层负责申请；app manifest 声明建议 maxSdkVersion="28"）。
 */
object QrImageIo {

    /** 解码最长边上限：两遍 decode + inSampleSize 降采样防 OOM。 */
    private const val MAX_DECODE_EDGE = 2048

    /** 相册子目录名（MediaStore 相对路径 / 旧版公共目录共用）。 */
    private const val GALLERY_DIR_NAME = "QRCraft"

    private val FILE_NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun newDisplayName(): String = "qrcraft-" + LocalDateTime.now().format(FILE_NAME_FORMAT) + ".png"

    // ── 解码：相册图片 → 降采样 Bitmap → 像素 → zxing ────────────────────

    /**
     * 图片字节数组 → 解码结果。两遍 decode：先 inJustDecodeBounds 取尺寸，
     * 算 2 的幂 inSampleSize 把最长边压到 ≤ 2048，再真解码（大图防 OOM）。
     */
    fun decodeImageBytes(bytes: ByteArray): Result<DecodedContent> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return Result.failure(IllegalArgumentException("图片无法解码 —— 格式不支持或内容损坏"))
        }
        var sample = 1
        var longestEdge = maxOf(bounds.outWidth, bounds.outHeight)
        while (longestEdge / 2 >= MAX_DECODE_EDGE) {
            sample *= 2
            longestEdge /= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return Result.failure(IllegalArgumentException("图片无法解码 —— 格式不支持或内容损坏"))
        try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            return QrCodec.decodePixels(pixels, width, height)
        } finally {
            bitmap.recycle()
        }
    }

    // ── 编码产物：BitMatrix → Bitmap ──────────────────────────────────────

    /**
     * 自然尺寸矩阵 → 目标尺寸位图（最近邻逐像素映射，1 模块 = N px 整数倍，
     * 无插值灰边 —— 双线性滤波会糊掉模块边缘，降低可扫性）。
     */
    fun bitMatrixToScaledBitmap(
        matrix: BitMatrix,
        targetSize: Int,
        foregroundArgb: Int,
        backgroundArgb: Int,
    ): Bitmap {
        val src = matrix.width
        val pixels = IntArray(targetSize * targetSize)
        for (y in 0 until targetSize) {
            val sy = (y.toLong() * src / targetSize).toInt().coerceAtMost(src - 1)
            for (x in 0 until targetSize) {
                val sx = (x.toLong() * src / targetSize).toInt().coerceAtMost(src - 1)
                pixels[y * targetSize + x] = if (matrix.get(sx, sy)) foregroundArgb else backgroundArgb
            }
        }
        return Bitmap.createBitmap(pixels, targetSize, targetSize, Bitmap.Config.ARGB_8888)
    }

    // ── 保存 / 分享 ────────────────────────────────────────────────────────

    /** 生成 PNG 并存入相册（API 分支见类注释），成功返回内容 Uri。 */
    fun savePng(
        context: Context,
        matrix: BitMatrix,
        sizePx: Int,
        foregroundArgb: Int,
        backgroundArgb: Int,
        displayName: String,
    ): Result<Uri> = runCatching {
        val bitmap = bitMatrixToScaledBitmap(matrix, sizePx, foregroundArgb, backgroundArgb)
        try {
            saveBitmapPng(context, bitmap, displayName)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 生成 PNG 写入分享缓存（cacheDir/qr-craft/share/），
     * 供 FileProvider（authority = packageName.fileprovider）授权分享。
     */
    fun writeShareCache(
        context: Context,
        matrix: BitMatrix,
        sizePx: Int,
        foregroundArgb: Int,
        backgroundArgb: Int,
        displayName: String,
    ): Result<File> = runCatching {
        val dir = File(context.cacheDir, "qr-craft/share")
        if (!dir.exists() && !dir.mkdirs()) throw IOException("无法创建分享缓存目录")
        val file = File(dir, displayName)
        val bitmap = bitMatrixToScaledBitmap(matrix, sizePx, foregroundArgb, backgroundArgb)
        try {
            compressPng(bitmap, FileOutputStream(file))
        } finally {
            bitmap.recycle()
        }
        file
    }

    /** ACTION_SEND image/png 分享（FileProvider 未接线时 failure，不 crash）。 */
    fun launchShare(context: Context, file: File): Result<Unit> = runCatching {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "分享二维码"))
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private fun compressPng(bitmap: Bitmap, out: FileOutputStream) {
        out.use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw IOException("PNG 压缩失败")
            }
        }
    }

    private fun saveBitmapPng(context: Context, bitmap: Bitmap, displayName: String): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + GALLERY_DIR_NAME)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("系统相册拒绝了写入请求（MediaStore insert 失败）")
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw IOException("PNG 压缩失败")
                }
            } ?: throw IOException("无法打开图片输出流")
            return uri
        }
        return saveBitmapPngLegacy(context, bitmap, displayName)
    }

    /** API 26–28：公共图片目录直接写（需 WRITE_EXTERNAL_STORAGE，UI 层已申请）。 */
    @Suppress("DEPRECATION")
    private fun saveBitmapPngLegacy(context: Context, bitmap: Bitmap, displayName: String): Uri {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            GALLERY_DIR_NAME,
        )
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("无法创建相册目录：${dir.absolutePath}")
        }
        val file = File(dir, displayName)
        compressPng(bitmap, FileOutputStream(file))
        // 直接写文件不经 MediaStore，手动扫进媒体库（相册立即可见）
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
        return Uri.fromFile(file)
    }
}
