package com.abyss.tools.core.update

import com.abyss.tools.core.common.diagnostics.DiagnosticsLog
import java.io.IOException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/**
 * 更新链失败分类器（N-UPD-003，M1 P1）。
 *
 * 把网络 / 解析 / 签名 / 下载 / 校验 / 安装各阶段的异常映射为稳定的
 * 失败类别码，供诊断中心聚合展示与一键反馈；分类结果不向任何远端回传，
 * 仅本地记录。
 */
enum class UpdateFailureCategory(val label: String, val humanMessage: String) {
    NETWORK_UNREACHABLE("网络不可达", "无法连接到更新服务器，请检查网络后重试"),
    NETWORK_TIMEOUT("超时", "连接服务器超时，可能是网络波动或镜像未同步"),
    SSL_ERROR("证书错误", "TLS 证书校验失败，网络可能被劫持"),
    HTTP_ERROR("HTTP 错误", "服务器返回异常状态码"),
    HOST_BLOCKED("域白名单拦截", "更新通道被导到非白名单域，疑似被劫持"),
    SIGNATURE_INVALID("清单签名校验失败", "通道清单 detached 签名校验失败，已拒绝"),
    MANIFEST_PARSE("清单解析失败", "通道清单格式非法，可能为镜像未同步"),
    DIGEST_MISMATCH("摘要校验失败", "下载文件 SHA-256 摘要与清单不符，已拒绝安装"),
    PATCH_BROKEN("补丁应用失败", "增量补丁已损坏，自动回退整包下载"),
    INSTALL_BLOCKED("安装被系统拦截", "系统安装器拒绝（签名不一致/版本降级/空间不足）"),
    INSUFFICIENT_SPACE("空间不足", "设备存储空间不足，请清理后重试"),
    CANCELLED("用户取消", "用户取消了下载/安装"),
    UNKNOWN("未知错误", "发生未知错误，可在诊断中心查看详情");

    companion object {
        fun fromThrowable(t: Throwable?): UpdateFailureCategory = when (t) {
            is UnknownHostException -> NETWORK_UNREACHABLE
            is SocketTimeoutException -> NETWORK_TIMEOUT
            is SSLException -> SSL_ERROR
            is com.abyss.tools.core.common.security.SignatureVerificationException -> SIGNATURE_INVALID
            is java.net.ConnectException -> NETWORK_UNREACHABLE
            else -> {
                val msg = t?.message?.lowercase() ?: ""
                when {
                    "timeout" in msg -> NETWORK_TIMEOUT
                    "cert" in msg || "ssl" in msg -> SSL_ERROR
                    "白名单" in msg || "不在白名单" in msg -> HOST_BLOCKED
                    "http " in msg && t is IOException -> HTTP_ERROR
                    "sha256" in msg || "摘要" in msg || "digest" in msg -> DIGEST_MISMATCH
                    "补丁" in msg && "失败" in msg -> PATCH_BROKEN
                    "签名" in msg && ("不一致" in msg || "不符" in msg) -> INSTALL_BLOCKED
                    "空间" in msg || "space" in msg -> INSUFFICIENT_SPACE
                    "取消" in msg || "cancel" in msg -> CANCELLED
                    "非法" in msg || "parse" in msg || "json" in msg -> MANIFEST_PARSE
                    else -> UNKNOWN
                }
            }
        }
    }
}

/** 带分类的失败结果，便于 UI 层直接展示人话文案。 */
data class ClassifiedUpdateFailure(
    val category: UpdateFailureCategory,
    val cause: Throwable?,
    val detail: String? = cause?.message,
) {
    val humanMessage: String get() = category.humanMessage
}

fun Throwable.toClassifiedFailure(): ClassifiedUpdateFailure {
    val cat = UpdateFailureCategory.fromThrowable(this)
    DiagnosticsLog.error(DiagnosticsLog.Kind.UPDATE_CHECK, cat.label, message ?: "(无细节)")
    return ClassifiedUpdateFailure(cat, this)
}
