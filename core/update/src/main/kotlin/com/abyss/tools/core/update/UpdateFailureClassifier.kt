package com.abyss.tools.core.update

/**
 * N-UPD-003 · 更新失败原因
 *
 * 统一归类所有失败路径的原因，供 UI 展示人话提示 + 诊断导出。
 * 每个原因必须有：
 * - userMessage: 用户侧人话（不暴露堆栈/URL）
 * - recoverable: 用户是否可以自行恢复
 * - category: 分类标签（network / security / storage / install / parse / unknown）
 */
enum class UpdateFailureReason(
    val userMessage: String,
    val recoverable: Boolean,
    val category: String
) {
    NETWORK_UNREACHABLE("网络不可用，请检查 Wi-Fi/移动数据后重试", true, "network"),
    DNS_LOOKUP_FAILED("无法解析更新服务器地址，请切换网络后重试", true, "network"),
    CONNECTION_TIMEOUT("连接服务器超时，请稍后重试", true, "network"),
    SSL_HANDSHAKE_FAILED("安全连接失败，可能被中间人劫持或证书异常", false, "network"),
    HTTP_4XX_CLIENT("请求被服务器拒绝（%d）", false, "network"),
    HTTP_5XX_SERVER("服务器暂时不可用（%d），请稍后重试", true, "network"),
    HTTP_REDIRECT_TO_UNTRUSTED_HOST("服务器重定向到不可信域名，已拦截", false, "network"),
    MIRROR_ALL_UNREACHABLE("所有镜像均不可达，请稍后再试", true, "network"),

    SIGNATURE_MISSING("更新文件缺少数字签名，已拒绝安装", false, "security"),
    SIGNATURE_VERIFICATION_FAILED("签名校验失败，文件可能被篡改", false, "security"),
    CHECKSUM_MISMATCH("文件哈希校验不匹配，下载已损坏，请切换网络重试", true, "security"),
    PUBLISHER_KEY_MISMATCH("发布者公钥不匹配，已拒绝安装", false, "security"),

    STORAGE_INSUFFICIENT("存储空间不足，请清理后重试（至少需要 %s）", true, "storage"),
    PART_FILE_CORRUPTED("断点文件损坏，将重新下载", true, "storage"),
    WRITE_PERMISSION_DENIED("无写入权限，请检查存储授权", true, "storage"),

    INDEX_PARSE_FAILED("更新清单格式异常，请联系开发者", false, "parse"),
    MANIFEST_DRIFT("归档仓清单版本漂移，当前版本已无法识别该清单", false, "parse"),

    INSTALL_PARSE_APK_BAD("APK 文件损坏或格式不合法", true, "install"),
    INSTALL_SIGNATURE_MISMATCH("安装包签名与已装版本不一致，需卸载旧版", true, "install"),
    INSTALL_VERSION_DOWNGRADE("无法降级安装，当前已是更高版本", true, "install"),
    INSTALL_INSUFFICIENT_STORAGE("安装空间不足", true, "install"),
    INSTALL_USER_REJECTED("您取消了安装", true, "install"),
    INSTALL_ABORTED("安装被系统中止", true, "install"),
    INSTALL_BLOCKED("安装被系统拦截，请在系统设置中允许安装来源", true, "install"),

    PATCH_PARSE_FAILED("补丁文件格式异常", false, "install"),
    PATCH_APPLY_FAILED("补丁合成失败，请重新下载完整包", true, "install"),
    PATCH_VERIFY_FAILED("补丁合成后校验失败", true, "install"),

    UNKNOWN("更新失败：%s", true, "unknown")
}

data class UpdateFailure(
    val reason: UpdateFailureReason,
    val message: String,
    val code: Int? = null,
    val throwable: Throwable? = null
) {
    fun formatUserMessage(): String = when (reason) {
        UpdateFailureReason.HTTP_4XX_CLIENT,
        UpdateFailureReason.HTTP_5XX_SERVER -> reason.userMessage.format(code ?: 0)
        UpdateFailureReason.STORAGE_INSUFFICIENT -> reason.userMessage.format(message)
        UpdateFailureReason.UNKNOWN -> reason.userMessage.format(message)
        else -> reason.userMessage
    }
}

/**
 * N-UPD-003 · 失败分类器：把任意 throwable / HTTP 状态映射为 UpdateFailure
 */
object UpdateFailureClassifier {

    fun classify(throwable: Throwable, context: String = ""): UpdateFailure {
        val msg = throwable.message ?: context
        val cls = throwable.javaClass.simpleName
        return when {
            msg.contains("SignatureVerificationException", ignoreCase = true)
                || cls.contains("SignatureVerif") ->
                UpdateFailure(UpdateFailureReason.SIGNATURE_VERIFICATION_FAILED, msg)
            msg.contains("MissingSignature", ignoreCase = true) ->
                UpdateFailure(UpdateFailureReason.SIGNATURE_MISSING, msg)
            msg.contains("PublisherKeyMismatch", ignoreCase = true)
                || msg.contains("public key mismatch", ignoreCase = true) ->
                UpdateFailure(UpdateFailureReason.PUBLISHER_KEY_MISMATCH, msg)
            msg.contains("checksum mismatch", ignoreCase = true)
                || msg.contains("sha256 mismatch", ignoreCase = true)
                || msg.contains("digest mismatch", ignoreCase = true) ->
                UpdateFailure(UpdateFailureReason.CHECKSUM_MISMATCH, msg)
            msg.contains("dns", ignoreCase = true)
                || cls.contains("UnknownHost") // NB-10：类名才是可靠信号 —— 真实
                // UnknownHostException 的 message 形如「Unable to resolve host
                // "raw..."」不含 UnknownHost/dns 字样，此前恒漏分到 UNKNOWN。
                || msg.contains("UnknownHost", ignoreCase = true) ->
                UpdateFailure(UpdateFailureReason.DNS_LOOKUP_FAILED, msg)
            msg.contains("SSL", ignoreCase = true)
                || msg.contains("certpath", ignoreCase = true)
                || msg.contains("handshake", ignoreCase = true) ->
                UpdateFailure(UpdateFailureReason.SSL_HANDSHAKE_FAILED, msg)
            msg.contains("timeout", ignoreCase = true)
                || msg.contains("SocketTimeout", ignoreCase = true)
                || msg.contains("ConnectTimedOut", ignoreCase = true) ->
                UpdateFailure(UpdateFailureReason.CONNECTION_TIMEOUT, msg)
            msg.contains("unreachable", ignoreCase = true)
                || msg.contains("NoRouteToHost", ignoreCase = true)
                || msg.contains("NetworkUnreachable", ignoreCase = true) ->
                UpdateFailure(UpdateFailureReason.NETWORK_UNREACHABLE, msg)
            msg.contains("ENOSPC", ignoreCase = true)
                || msg.contains("no space left", ignoreCase = true) ->
                UpdateFailure(UpdateFailureReason.STORAGE_INSUFFICIENT, msg, throwable = throwable)
            msg.contains("EACCES", ignoreCase = true)
                || msg.contains("permission denied", ignoreCase = true) ->
                UpdateFailure(UpdateFailureReason.WRITE_PERMISSION_DENIED, msg)
            msg.contains("corrupt", ignoreCase = true) && msg.contains("part", ignoreCase = true) ->
                UpdateFailure(UpdateFailureReason.PART_FILE_CORRUPTED, msg)
            msg.contains("redirect", ignoreCase = true) && msg.contains("host", ignoreCase = true) ->
                UpdateFailure(UpdateFailureReason.HTTP_REDIRECT_TO_UNTRUSTED_HOST, msg)
            msg.contains("INSTALL_PARSE_FAILED_NOT_APK", ignoreCase = true)
                || msg.contains("bad apk", ignoreCase = true) ->
                UpdateFailure(UpdateFailureReason.INSTALL_PARSE_APK_BAD, msg)
            msg.contains("INSTALL_FAILED_VERSION_DOWNGRADE", ignoreCase = true) ->
                UpdateFailure(UpdateFailureReason.INSTALL_VERSION_DOWNGRADE, msg)
            msg.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", ignoreCase = true)
                || msg.contains("signatures do not match", ignoreCase = true) ->
                UpdateFailure(UpdateFailureReason.INSTALL_SIGNATURE_MISMATCH, msg)
            msg.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE", ignoreCase = true) ->
                UpdateFailure(UpdateFailureReason.INSTALL_INSUFFICIENT_STORAGE, msg)
            msg.contains("INSTALL_FAILED_USER_REJECTED", ignoreCase = true) ->
                UpdateFailure(UpdateFailureReason.INSTALL_USER_REJECTED, msg)
            msg.contains("INSTALL_FAILED_ABORTED", ignoreCase = true) ->
                UpdateFailure(UpdateFailureReason.INSTALL_ABORTED, msg)
            msg.contains("INSTALL_FAILED_BLOCKED", ignoreCase = true) ->
                UpdateFailure(UpdateFailureReason.INSTALL_BLOCKED, msg)
            msg.contains("manifest drift", ignoreCase = true) ->
                UpdateFailure(UpdateFailureReason.MANIFEST_DRIFT, msg)
            msg.contains("index parse", ignoreCase = true)
                || msg.contains("json", ignoreCase = true) && msg.contains("malformed", ignoreCase = true) ->
                UpdateFailure(UpdateFailureReason.INDEX_PARSE_FAILED, msg)
            msg.contains("patch", ignoreCase = true) && msg.contains("parse", ignoreCase = true) ->
                UpdateFailure(UpdateFailureReason.PATCH_PARSE_FAILED, msg)
            msg.contains("patch", ignoreCase = true) && msg.contains("apply", ignoreCase = true) ->
                UpdateFailure(UpdateFailureReason.PATCH_APPLY_FAILED, msg)
            msg.contains("patch", ignoreCase = true) && msg.contains("verify", ignoreCase = true) ->
                UpdateFailure(UpdateFailureReason.PATCH_VERIFY_FAILED, msg)
            else -> UpdateFailure(UpdateFailureReason.UNKNOWN, msg.ifBlank { cls }, throwable = throwable)
        }
    }

    fun classifyHttp(status: Int, host: String = ""): UpdateFailure? = when {
        status in 200..299 -> null
        status in 400..499 -> UpdateFailure(UpdateFailureReason.HTTP_4XX_CLIENT,
            "HTTP $status from $host", code = status)
        status in 500..599 -> UpdateFailure(UpdateFailureReason.HTTP_5XX_SERVER,
            "HTTP $status from $host", code = status)
        status == 0 -> UpdateFailure(UpdateFailureReason.NETWORK_UNREACHABLE, "no response")
        else -> UpdateFailure(UpdateFailureReason.UNKNOWN, "HTTP $status", code = status)
    }

    fun allMirrorsFailed(tried: Int): UpdateFailure =
        UpdateFailure(UpdateFailureReason.MIRROR_ALL_UNREACHABLE,
            "tried $tried mirrors, all failed", code = tried)
}
