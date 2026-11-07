package com.abyss.tools.core.update

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 极简 HTTP 传输抽象：检查更新 / 拉索引 / 下载补丁共用一条通道。
 *
 * 用接口隔离 OkHttp 的原因：单元测试注入 FakeTransport（零网络、零 mock
 * 框架），CI 上不依赖任何外部服务的可用性。
 */
interface HttpTransport {

    /** 同步 GET 文本（调用方负责切 IO 线程）。 */
    @Throws(IOException::class)
    fun get(url: String): String

    /** 流式下载到文件，onProgress(readBytes, totalBytes)（total 为 -1 时未知）。 */
    @Throws(IOException::class)
    fun download(
        url: String,
        dest: File,
        onProgress: (readBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    )
}

/**
 * 拉取域白名单（fail-closed，评审 #2）：深渊工具的全部远端资产只来自
 * GitHub 官方域 —— 清单（raw）、Release 页（github.com）与 Release 资产
 * 302 落点（objects / release-assets）。白名单外（含重定向后的最终落点）
 * 一律拒绝：即便索引 / 清单被篡改，也拉不走任意域的载荷。
 */
object FetchHostPolicy {

    private val ALLOWED_HOSTS = setOf(
        "raw.githubusercontent.com",
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    /** 校验 URL（https 强制 + host 白名单），非法即抛人话异常。 */
    @Throws(IOException::class)
    fun check(url: String): String {
        val parsed = runCatching { URI(url) }.getOrNull()
            ?: throw IOException("非法 URL（无法解析）：$url")
        val scheme = parsed.scheme?.lowercase()
        val host = parsed.host?.lowercase()
        if (scheme != "https") {
            throw IOException("只允许 https 通道（当前 $scheme）：$url")
        }
        if (host == null || host !in ALLOWED_HOSTS) {
            throw IOException("拉取域不在白名单（$host）：$url —— 疑似通道被篡改，已拒绝")
        }
        return url
    }
}

/** 生产实现：OkHttp 单客户端，超时对齐移动网络现实 + 域白名单 + 体量上限。 */
class OkHttpTransport : HttpTransport {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun get(url: String): String {
        FetchHostPolicy.check(url)
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} <- $url")
            // 重定向落点复检（github.com 资产 302 → githubusercontent 域，合法；
            // 被导去任意第三方域则在此拦截）
            FetchHostPolicy.check(response.request.url.toString())
            val body = response.body ?: throw IOException("空响应体 <- $url")
            val bytes = ByteArrayOutputStream().use { buffer ->
                body.byteStream().use { input ->
                    val chunk = ByteArray(8 * 1024)
                    var read: Int
                    while (input.read(chunk).also { read = it } != -1) {
                        buffer.write(chunk, 0, read)
                        if (buffer.size() > MAX_TEXT_BYTES) {
                            throw IOException("响应体超过 ${MAX_TEXT_BYTES / 1024} KB 上限 <- $url")
                        }
                    }
                }
                buffer.toByteArray()
            }
            return bytes.decodeToString()
        }
    }

    override fun download(
        url: String,
        dest: File,
        onProgress: (readBytes: Long, totalBytes: Long) -> Unit,
    ) {
        FetchHostPolicy.check(url)
        dest.parentFile?.mkdirs()
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} <- $url")
            FetchHostPolicy.check(response.request.url.toString())
            val body = response.body ?: throw IOException("空响应体 <- $url")
            val total = body.contentLength()
            if (total > MAX_DOWNLOAD_BYTES) {
                throw IOException("资产声明体量 ${total} B 超过 ${MAX_DOWNLOAD_BYTES / 1024 / 1024} MB 上限 <- $url")
            }
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read: Int
                    var accumulated = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        accumulated += read
                        if (accumulated > MAX_DOWNLOAD_BYTES) {
                            throw IOException("下载体量超过 ${MAX_DOWNLOAD_BYTES / 1024 / 1024} MB 上限 <- $url")
                        }
                        onProgress(accumulated, total)
                    }
                    output.flush()
                }
            }
        }
    }

    private companion object {
        /** 清单 / 索引类文本上限（现有通道 JSON 均 < 64 KB，2 MB 冗余 30×）。 */
        const val MAX_TEXT_BYTES = 2 * 1024 * 1024

        /** APK / 补丁资产上限（release APK 实测 ~10-30 MB，512 MB 冗余防线）。 */
        const val MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024
    }
}
