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
     * 当前首签公钥：`meng4117222`（Ed25519，v0.10.0 起正式化，已替换
     * 开发期占位钥 —— d3bcc82）。**发布后严禁修改**；密钥轮换走双公钥
     * 过渡期（列表新增、不替换，给存量版本至少一个版本窗口）。
     *
     * B-25 注释收口：原注释同时保留「正式首签钥，严禁修改」与「开发
     * 测试公钥，发布时替换」两段互相矛盾的语义 —— 密钥已正式化，开发期
     * 话术全部清除，杜绝后人误以为该常量仍待轮换替换。发布侧事实源：
     * `tools/publisher_pubkey.py` 从本常量提取公钥；Hub / 插件仓各
     * workflow 的 pubkey.txt 与此同源（同步副本，改动需同步 5 处，
     * 见 B-26 多副本治理项）。
     */
    val TRUSTED_KEYS: List<String> = listOf(
        // 正式首签公钥（meng4117222，v0.10）；密钥对由 minisign 兼容方式生成，
        // 私钥仅存 GitHub Actions secrets (RELEASE_SIGNING_KEY)，永不入库。
        // 轮换走双公钥过渡期：新增、不替换，给存量版本至少一个版本窗口。
        """
        untrusted comment: Abyss-Tools manifest signing public key (meng4117222)
        RWRx16ORC4gsdxs6DsnctDYagiRptPX1NgFcVpQNYqh/BXGhPAHebh3B
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
