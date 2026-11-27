package com.abyss.tools.core.update

import com.abyss.tools.core.common.diagnostics.DiagnosticsLog
import com.abyss.tools.core.common.security.ChannelSignatureVerifier
import com.abyss.tools.core.common.security.SignatureVerificationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 已验签通道 HTTP 传输装饰器（N-SEC-001）。
 *
 * 行为：拉取任意通道清单（JSON）时，自动并行拉取同名 `.minisig` 签名文件，
 * **先验签再返回原文**。签名校验失败直接抛
 * [SignatureVerificationException]（fail-closed），上层折叠为
 * UiState.Error 并人话展示。
 *
 * 设计原则：
 *  - 不改变 [HttpTransport] 的接口语义：对调用方而言 [getSigned] 仍是返回
 *    字符串，唯一区别是当通道签名缺失/不合法时直接 fail-closed；
 *  - `.minisig` URL 默认 = `url + ".minisig"`；特殊 URL 可通过
 *    [sigUrlOverride] 覆盖（例如插件补丁通道 `patches/index.json`
 *    对应 `patches/index.json.minisig`）；
 *  - 验签只在清单类资源启用（[getSigned]），二进制 APK/补丁包不做 minisign
 *    验签（APK 走 Android 安装器系统签名校验，补丁包走 SHA-256 摘要校验）。
 *
 * @param manifestName 用于诊断日志标识（"version.json" / "plugins.json" 等）
 */
class SignedHttpTransport(
    private val delegate: HttpTransport,
    private val manifestName: String = "manifest",
    private val sigUrlOverride: String? = null,
) {
    /** 暴露底层 delegate，供非清单场景（如 APK 下载）直接复用同一条 OkHttp 客户端。 */
    fun delegateTransport(): HttpTransport = delegate
    /**
     * 同步 GET 清单（调用方负责切 IO 线程）。
     * @throws IOException 网络层错误
     * @throws SignatureVerificationException 签名校验失败（fail-closed）
     */
    @Throws(IOException::class, SignatureVerificationException::class)
    fun getSigned(url: String): String {
        FetchHostPolicy.check(url)
        val sigUrl = sigUrlOverride ?: "$url.minisig"
        FetchHostPolicy.check(sigUrl)

        val manifestBytes = try {
            delegate.getBytes(url)
        } catch (e: IOException) {
            DiagnosticsLog.warn(
                DiagnosticsLog.Kind.NETWORK, manifestName,
                "清单拉取失败: ${e.message}"
            )
            throw e
        }
        val sigText = try {
            // B-04 收口：签名文件同样走原始字节路径（base64+注释为纯 ASCII，
            // decode 恒等；但保持「签名链路零字符串往返」不变量，杜绝
            // delegate 自定义 get() 语义时引入归一化）
            delegate.getBytes(sigUrl).decodeToString()
        } catch (e: IOException) {
            val msg = "无法拉取清单签名文件（$sigUrl）：${e.message}"
            DiagnosticsLog.error(
                DiagnosticsLog.Kind.SIGNATURE_FAILURE, manifestName,
                "缺失 .minisig：${e.message}"
            )
            throw SignatureVerificationException(
                "$msg。镜像可能尚未同步，请稍后重试或切换网络"
            )
        }
        val diag = ChannelSignatureVerifier.verifyDiagnostic(manifestName, manifestBytes, sigText)
        if (!diag.verified) {
            DiagnosticsLog.error(
                DiagnosticsLog.Kind.SIGNATURE_FAILURE, manifestName,
                diag.error ?: "签名校验失败（无细节）"
            )
            throw SignatureVerificationException(diag.error ?: "清单签名校验失败")
        }
        return manifestBytes.toString(Charsets.UTF_8)
    }

    /**
     * 协程版：在 IO dispatcher 上执行 [getSigned]，异常封装为 [Result]。
     */
    suspend fun getSignedResult(url: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching { getSigned(url) }
        }
}

/**
 * 在现有 [UpdateChecker]/[com.abyss.tools.core.plugin.PluginRepository] 上
 * 最小侵入地加上验签：构造方法重载接收 [SignedHttpTransport]，拉取时用
 * [SignedHttpTransport.getSigned] 替代 raw [HttpTransport.get]。
 */
fun HttpTransport.signed(
    manifestName: String = "manifest",
    sigUrlOverride: String? = null,
): SignedHttpTransport = SignedHttpTransport(this, manifestName, sigUrlOverride)
