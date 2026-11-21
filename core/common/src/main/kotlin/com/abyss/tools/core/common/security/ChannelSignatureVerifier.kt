package com.abyss.tools.core.common.security

import com.abyss.tools.core.common.crypto.MinisignVerifier

/**
 * 通道清单 detached 签名验证器（N-SEC-001）。
 *
 * 设计：
 *  - 深渊工具客户端内置 Abyss 官方 Ed25519 信任公钥（minisign 格式文本，常量）。
 *  - 客户端拉取 version.json / patches.json / plugins.json 时**必须同步拉取
 *    同名 `.minisig` 文件**，先验签再解析 JSON；验签失败整页 Error（fail-closed）。
 *  - 签名验证不通过 → 一律抛 [SignatureVerificationException]，由上层映射
 *    到 UiState.Error，人话提示"清单签名校验失败，通道可能被劫持"。
 *
 * 双公钥过渡期：[TRUSTED_KEYS] 可配置多把公钥，任意一把验过即合法。
 * 签名密钥轮换时，先把新公钥加入 TRUSTED_KEYS，在旧公钥废弃前给一个版本的
 * 兼容窗口；旧公钥对应的 minisig 继续有效，直到轮换完成。
 *
 * 私钥永远只存 GitHub Actions secrets（RELEASE_SIGNING_KEY），不进入仓库；
 * 发布流水线 `sign_manifests.py` 用 minisign CLI 对三份清单生成 detached 签名。
 */
object ChannelSignatureVerifier {

    /**
     * 深渊官方签名信任公钥集（minisign 公钥块文本，带注释行）。
     *
     * v0.10.0 首签公钥：`Abyss-Tools-Release-M1`，Ed25519。
     * 发布后**严禁修改**；密钥轮换走双公钥过渡期（新增、不替换）。
     *
     * 占位公钥已由深渊组织维护者在 GitHub Actions secrets 中用
     * `minisign -G` 生成；这里留入开发阶段专用的测试密钥对中的公钥，
     * CI 门禁会校验发布仓真实公钥与此处一致，任何不一致都会导致
     * `validate_manifests.py` 失败。
     *
     * 开发测试公钥（仅用于本地单测与开发阶段，发布流水线用真实私钥重签后
     * 替换公钥常量）：
     * ```
     * minisign -G -p abyss-dev.pub -s abyss-dev.key
     * ```
     */
    val TRUSTED_KEYS: List<String> = listOf(
        // 开发期首签公钥（M1, v0.10）；密钥对由 minisign 兼容方式生成，
        // 私钥仅存 GitHub Actions secrets (RELEASE_SIGNING_KEY)，永不入库。
        // 轮换走双公钥过渡期：新增、不替换，给存量版本至少一个版本窗口。
        """
        untrusted comment: Abyss-Tools dev signing public key (M1, v0.10)
        RWSWn6+V/umBvAHNeSekC6UjX5Tg0me06vgXgEPeJ88ulrP82fR94AYZ
        """.trimIndent(),
    )

    /**
     * 对任意清单（version.json / patches.json / plugins.json / patches/index.json）
     * 执行 detached 签名验证。
     *
     * @param manifestBytes  原始清单字节（必须与签名原文逐字节一致，禁止 JSON 归一化）
     * @param minisigText    `.minisig` 文件文本（minisign 格式，含注释行与 base64 段）
     * @throws SignatureVerificationException 任意解析/验签失败
     */
    fun verifyOrThrow(manifestBytes: ByteArray, minisigText: String) {
        if (manifestBytes.isEmpty()) {
            throw SignatureVerificationException("清单为空，拒绝解析")
        }
        if (minisigText.isBlank()) {
            throw SignatureVerificationException(
                "清单缺少 detached 签名（.minisig 文件），通道可能被劫持或镜像未同步"
            )
        }
        val errors = mutableListOf<String>()
        for ((idx, pkText) in TRUSTED_KEYS.withIndex()) {
            val ok = runCatching { MinisignVerifier.verify(pkText, minisigText, manifestBytes) }
                .getOrDefault(false)
            if (ok) return
            errors.add("key#$idx 验签失败")
        }
        throw SignatureVerificationException(
            "清单签名校验全部失败（fail-closed）：${errors.joinToString("; ")}。" +
                "通道可能被劫持，请切换网络或等待官方发布修复"
        )
    }

    /**
     * 验签诊断结果：用于诊断中心展示。
     * @param verified 验签是否通过
     * @param keyCount 尝试过的公钥数
     */
    data class VerifyDiagnostic(
        val manifestName: String,
        val verified: Boolean,
        val keyCount: Int,
        val error: String? = null,
    )

    fun verifyDiagnostic(
        manifestName: String,
        manifestBytes: ByteArray,
        minisigText: String,
    ): VerifyDiagnostic = try {
        verifyOrThrow(manifestBytes, minisigText)
        VerifyDiagnostic(manifestName, true, TRUSTED_KEYS.size)
    } catch (e: SignatureVerificationException) {
        VerifyDiagnostic(manifestName, false, TRUSTED_KEYS.size, e.message)
    }
}

/**
 * 通道清单 detached 签名校验失败异常。
 *
 * 设计为人话可直接展示给用户的异常类型：message 本身即为可展示文案，
 * 不含堆栈 / 内部坐标。
 */
class SignatureVerificationException(override val message: String) : SecurityException(message)
