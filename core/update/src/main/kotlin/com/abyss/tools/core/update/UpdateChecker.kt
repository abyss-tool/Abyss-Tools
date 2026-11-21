package com.abyss.tools.core.update

import com.abyss.tools.core.common.SemVer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 检查更新结果（密封：UI 直接 when 消费，不出现魔法字符串）。
 */
sealed interface UpdateCheckResult {

    /** 当前已是通道最新。 */
    data class UpToDate(val latest: SemVer) : UpdateCheckResult

    /**
     * 有新版本。
     * @param patchApplicable 当前版本恰为补丁 fromTag → 可走增量
     * @param patch           可用的增量补丁（null = 需整包下载）
     */
    data class Available(
        val manifest: VersionManifest,
        val latest: SemVer,
        val patchApplicable: Boolean,
        val patch: PatchInfo?,
    ) : UpdateCheckResult

    data class Failed(val error: Throwable) : UpdateCheckResult
}

/**
 * 更新检查器：读发布仓 version.json，与本地版本比对。
 *
 * v0.10（N-SEC-001）：支持强制 detached 签名校验，传入 [signedTransport]
 * 时走 `.minisig` 验签路径，fail-closed；否则保留旧行为（过渡期间兼容）。
 *
 * 决策表：
 *  - latest <= current            → UpToDate
 *  - latest > current 且 patch 命中 → Available(增量)
 *  - latest > current 其余情形     → Available(整包 / 通道未发布资产)
 *  - 网络 / JSON / 版本号非法      → Failed（fail-closed，绝不猜测）
 */
class UpdateChecker(
    private val transport: HttpTransport,
    private val versionJsonUrl: String,
    private val patchesJsonUrl: String? = null,
    private val signedTransport: SignedHttpTransport? = null,
) {

    /** 便捷构造：验签模式（M1 默认），传输层通过 [SignedHttpTransport] 内部 delegate 复用。 */
    constructor(
        signed: SignedHttpTransport,
        versionJsonUrl: String,
        patchesJsonUrl: String? = null,
    ) : this(
        transport = signed.delegateTransport(),
        versionJsonUrl = versionJsonUrl,
        patchesJsonUrl = patchesJsonUrl,
        signedTransport = signed,
    )

    suspend fun check(
        current: SemVer,
        currentTag: String = current.toTag(),
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val raw = fetchManifest(versionJsonUrl)
            val manifest = UpdateParsers.parseVersionManifest(raw)
            val latest = manifest.version
                ?: return@withContext UpdateCheckResult.Failed(
                    IllegalArgumentException("version.json 版本号非法：${manifest.versionName}")
                )
            if (latest <= current) {
                UpdateCheckResult.UpToDate(latest)
            } else {
                val patch = manifest.patch
                val applicable = patch != null && patch.fromTag == currentTag
                UpdateCheckResult.Available(
                    manifest = manifest,
                    latest = latest,
                    patchApplicable = applicable,
                    patch = if (applicable) patch else null,
                )
            }
        } catch (t: Throwable) {
            UpdateCheckResult.Failed(t)
        }
    }

    /** 拉取 patches.json（如有）并验签。 */
    suspend fun fetchPatches(): PatchesManifest? = withContext(Dispatchers.IO) {
        val url = patchesJsonUrl ?: return@withContext null
        runCatching {
            val raw = fetchManifest(url)
            UpdateParsers.parsePatchesManifest(raw)
        }.getOrNull()
    }

    private fun fetchManifest(url: String): String {
        // 验签路径优先；未配置 signedTransport 则走旧路径（过渡兼容期，
        // M1 发版后删除 fallback，强制验签）。
        return if (signedTransport != null) {
            signedTransport.getSigned(url)
        } else {
            transport.get(url)
        }
    }
}
