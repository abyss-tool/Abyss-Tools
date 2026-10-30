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
 * 决策表：
 *  - latest <= current            → UpToDate
 *  - latest > current 且 patch 命中 → Available(增量)
 *  - latest > current 其余情形     → Available(整包 / 通道未发布资产)
 *  - 网络 / JSON / 版本号非法      → Failed（fail-closed，绝不猜测）
 */
class UpdateChecker(
    private val transport: HttpTransport,
    private val versionJsonUrl: String,
) {

    suspend fun check(
        current: SemVer,
        currentTag: String = current.toTag(),
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val raw = transport.get(versionJsonUrl)
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
}
