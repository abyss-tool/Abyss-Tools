package com.abyss.tools.core.common.virus

/**
 * 待扫描对象（N-VIR-002，M1）。
 *
 * 输入契约遵循规格 §4：管线不做任何 IO，所有字段由调用方准备好后传入。
 * 这保证判定逻辑 100% 纯 JVM 可单测，不依赖 Android Context/File/网络。
 */
data class ScanSubject(
    /** 待扫描 APK/文件路径，仅用于审计日志展示，不参与判定。 */
    val apkPath: String,
    /** SHA-256 摘要，64 字符小写十六进制；哈希黑名单主要匹配键。 */
    val sha256: String,
    /** 包名（Android APK）；普通文件可为空字符串。 */
    val packageName: String,
    /** 签名证书 SHA-256 摘要（v2/v3 signer 优先），未签可为空。 */
    val signerSha256: String,
    /** 文件大小字节；用于本地启发式规则阈值判定。 */
    val fileSizeBytes: Long = 0L,
    /** 安装来源（"install-session"/"broadcast"/"manual"），供审计。 */
    val installSource: String = "unknown",
) {
    init {
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }) {
            "sha256 必须为 64 字符小写十六进制：$sha256"
        }
        require(signerSha256.isEmpty() ||
            (signerSha256.length == 64 && signerSha256.all { it in '0'..'9' || it in 'a'..'f' })) {
            "signerSha256 非空时必须为 64 字符小写十六进制：$signerSha256"
        }
        require(fileSizeBytes >= 0) { "fileSizeBytes 必须非负" }
    }
}
