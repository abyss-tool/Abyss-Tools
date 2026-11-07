package com.abyss.tools.install

import android.content.pm.Signature
import java.security.MessageDigest

/**
 * 发布者签名指纹（fail-closed 硬校验常量）。
 *
 * v0.5.0 起 meng411722 身份签名，指纹恒定 —— 宿主自更新（包名
 * com.abyss.tools 的覆盖安装）在「与在装版本同签名」之上额外做
 * **发布者指纹硬校验**：待装 APK 的签名证书 SHA-256 必须命中本常量。
 * 双保险语义：同签名防「伪装成任意第三方包」，指纹锚点防「历史泄露
 * 签名之外的新来源」被误认为可信（两者都过才放行）。
 *
 * 轮换流程（如未来需要）见 docs/SECURITY.md —— 需同步更新本常量并
 * 引导存量用户卸载重装。
 */
object PublisherSignatures {

    /** 期望的发布签名证书 SHA-256 指纹（冒号分隔大写十六进制）。 */
    const val PINNED_SHA256: String =
        "76:03:78:21:44:88:D6:20:79:78:BA:1E:37:E6:DC:82:AB:C2:DE:72:C9:F6:C6:7D:9B:44:95:15:8A:99:B7:69"

    fun matches(fingerprint: String): Boolean =
        fingerprint.equals(PINNED_SHA256, ignoreCase = true)

    /** 签名证书 → SHA-256 指纹（与 apksigner verify --print-certs 同形态）。 */
    fun sha256Fingerprint(signature: Signature): String =
        MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString(":") { "%02X".format(it) }
}
