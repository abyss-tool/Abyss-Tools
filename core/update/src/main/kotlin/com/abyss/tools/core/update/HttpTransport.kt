package com.abyss.tools.core.update

import java.io.File
import java.io.IOException
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

/** 生产实现：OkHttp 单客户端，超时对齐移动网络现实。 */
class OkHttpTransport : HttpTransport {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun get(url: String): String {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} <- $url")
            return response.body?.string() ?: throw IOException("空响应体 <- $url")
        }
    }

    override fun download(
        url: String,
        dest: File,
        onProgress: (readBytes: Long, totalBytes: Long) -> Unit,
    ) {
        dest.parentFile?.mkdirs()
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} <- $url")
            val body = response.body ?: throw IOException("空响应体 <- $url")
            val total = body.contentLength()
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read: Int
                    var accumulated = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        accumulated += read
                        onProgress(accumulated, total)
                    }
                    output.flush()
                }
            }
        }
    }
}
