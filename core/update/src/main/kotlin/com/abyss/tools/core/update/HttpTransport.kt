package com.abyss.tools.core.update

import com.abyss.tools.core.common.MirrorCatalog
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

    /**
     * 同步 GET 原始字节（N-SEC-001 签名校验使用，避免字符串往返带来的
     * 换行/编码归一化）。默认实现走 [get] 再转 UTF-8 字节；生产实现
     * [OkHttpTransport.getBytes] 直接返回网络字节流。
     */
    @Throws(IOException::class)
    fun getBytes(url: String): ByteArray = get(url).toByteArray(Charsets.UTF_8)

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
 *
 * v0.7.0 镜像扩展（不放松信任锚，只扩展通路）：
 *  - [MirrorCatalog.MIRROR_HOSTS] 内的注册镜像域放行，但 PREFIX 形态
 *    必须内嵌合法的 GitHub 白名单 URL（递归校验内层）；
 *  - 镜像只承担「通路」角色：一切资产仍强制 SHA-256 校验（下载管线
 *    fail-closed），镜像被劫持最多表现为下载失败 / 校验不过，不可能
 *    植入被篡改的载荷。
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
        if (host == null) {
            throw IOException("URL 缺少 host：$url")
        }
        if (host in ALLOWED_HOSTS) {
            return url
        }
        if (host in MirrorCatalog.MIRROR_HOSTS) {
            // 镜像域：必须能还原出合法的 GitHub 白名单 URL（PREFIX 内层递归校验）
            val original = MirrorCatalog.originalUrlOf(url)
                ?: throw IOException("镜像 URL 结构非法（无法还原原始 GitHub URL）：$url")
            return check(original)
        }
        throw IOException("拉取域不在白名单（$host）：$url —— 疑似通道被篡改，已拒绝")
    }
}

/** 生产实现：OkHttp 单客户端，超时对齐移动网络现实 + 域白名单 + 体量上限。 */
class OkHttpTransport : HttpTransport {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun get(url: String): String {
        return getBytes(url).decodeToString()
    }

    /** 原始字节读取（供签名校验用，避免字符串往返导致换行/编码归一化）。 */
    override fun getBytes(url: String): ByteArray {
        FetchHostPolicy.check(url)
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} <- $url")
            FetchHostPolicy.check(response.request.url.toString())
            val body = response.body ?: throw IOException("空响应体 <- $url")
            return ByteArrayOutputStream().use { buffer ->
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
        }
    }

    /**
     * 流式下载（v0.7.8 重写）：`.part` 暂存 + HTTP Range 断点续传 +
     * 成功后原子改名收尾。
     *
     *  - 落盘永远写 [ResumePlanner.partFileOf]，成功才 renameTo(dest) ——
     *    中断的下载绝不会以「完整文件」的假象留在缓存里被误用；
     *  - 有残留时带 Range 头重试，服务端命中（206）则续传，回全量（200）
     *    则截断重下 —— 决策见 [ResumePlanner]（只认 206，歧义一律重下）；
     *  - 中途异常：part 原样保留 —— 下一次调用自动从断点续传。
     */
    override fun download(
        url: String,
        dest: File,
        onProgress: (readBytes: Long, totalBytes: Long) -> Unit,
    ) {
        FetchHostPolicy.check(url)
        dest.parentFile?.mkdirs()
        val part = ResumePlanner.partFileOf(dest)
        val existing = if (part.exists()) part.length() else 0L

        val request = Request.Builder().url(url).apply {
            if (existing > 0) header("Range", "bytes=$existing-")
        }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} <- $url")
            // 重定向落点复检（同 get：302 去往任意第三方域在此拦截）
            FetchHostPolicy.check(response.request.url.toString())
            val body = response.body ?: throw IOException("空响应体 <- $url")
            val plan = ResumePlanner.plan(existing, response.code)
            // RESTART 模式（服务端回全量 / 无残留）：截断已有 part 文件，防旧数据污染新下载
            if (plan.mode == ResumePlan.Mode.RESTART && part.exists()) {
                part.writeBytes(ByteArray(0))
            }
            val contentLength = body.contentLength()
            val total = if (contentLength >= 0) contentLength + plan.resumeFrom else -1L
            if (contentLength >= 0 && total > MAX_DOWNLOAD_BYTES) {
                throw IOException("资产声明体量 ${total} B 超过 ${MAX_DOWNLOAD_BYTES / 1024 / 1024} MB 上限 <- $url")
            }
            val append = plan.mode == ResumePlan.Mode.APPEND
            body.byteStream().use { input ->
                java.io.FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read: Int
                    var accumulated = plan.resumeFrom
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
            // 原子收尾：同目录同分区 rename 保证不出现半份 APK 冒充完整文件
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
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
