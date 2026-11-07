package com.abyss.tools.core.plugin

import com.abyss.tools.plugin.api.PluginContract
import java.net.URI

/**
 * 插件通道运行时白名单（fail-closed，评审 #6）。
 *
 * 校验夹具（tools/validate_manifests.py）只防「手写漂移」，防不了
 * 「线上索引被篡改」—— 本策略在**运行时**对拉到的每条索引 / 补丁
 * 条目做同一套校验：不合规条目整条拒收（不展示、不可装、不可拉补丁），
 * 绝不「拉到什么用什么」。
 *
 * 规则（与夹具校验同构，双份事实源靠 CI 一致性测试粘合）：
 *  - id 匹配 [PluginContract.ID_PATTERN]；
 *  - repo 归属 abyss-tool 组织的 abyss-plugin-* 仓库，且末段 = 插件 id；
 *  - patchChannel（如声明）必须是 raw.githubusercontent.com 的
 *    abyss-tool/<repo>/main/patches/index.json 形态；
 *  - APK 资产（如发布）url 必须落在 GitHub 官方域，且 sha256 非空
 *    （摘要缺失 = 永远过不了校验 = 直接拒收，尽早暴露）；
 *  - 补丁条目 url 同样限 GitHub 官方域 + sha256 必填。
 */
object ChannelPolicy {

    private const val ORG = "abyss-tool"

    private val REPO_PATTERN = Regex("^abyss-tool/abyss-plugin-[a-z0-9]+(-[a-z0-9]+)*$")
    private val PATCH_CHANNEL_PATTERN = Regex(
        "^https://raw\\.githubusercontent\\.com/abyss-tool/abyss-plugin-[a-z0-9-]+/main/patches/index\\.json$",
    )
    private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")

    /** GitHub 官方资产域（与 core:update 的 FetchHostPolicy 同集合，模块间不互相依赖）。 */
    private val ASSET_HOSTS = setOf(
        "raw.githubusercontent.com",
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    /** 索引条目校验：null = 合规；否则为人话拒收理由。 */
    fun validateEntry(entry: PluginIndexEntry): String? {
        if (!PluginContract.ID_PATTERN.matches(entry.id)) {
            return "插件 id 形态非法：${entry.id}"
        }
        if (!REPO_PATTERN.matches(entry.repo)) {
            return "仓库不归属 $ORG 插件矩阵：${entry.repo}"
        }
        if (!entry.repo.endsWith(entry.id)) {
            return "仓库末段与插件 id 不符：${entry.repo} vs ${entry.id}"
        }
        entry.patchChannel?.let { channel ->
            if (!PATCH_CHANNEL_PATTERN.matches(channel)) {
                return "补丁通道形态非法：$channel"
            }
            if (!channel.contains("/$ORG/${entry.id}/")) {
                return "补丁通道不属于该插件仓库：$channel"
            }
        }
        entry.apk?.let { apk ->
            assetUrlProblem(apk.url)?.let { return it }
            if (apk.sha256.isNullOrBlank() || !SHA256_PATTERN.matches(apk.sha256)) {
                return "APK 资产缺少合法 sha256（fail-closed 拒收）：${entry.id}"
            }
        }
        return null
    }

    /** 补丁条目校验：null = 合规；否则为人话拒收理由。 */
    fun validatePatch(patch: PluginPatch): String? {
        assetUrlProblem(patch.url)?.let { return it }
        if (patch.sha256.isNullOrBlank() || !SHA256_PATTERN.matches(patch.sha256)) {
            return "补丁缺少合法 sha256（fail-closed 拒收）：${patch.from} → ${patch.to}"
        }
        return null
    }

    /** 资产 URL：https + GitHub 官方域（重定向落点由 FetchHostPolicy 在传输层复检）。 */
    private fun assetUrlProblem(url: String): String? {
        val parsed = runCatching { URI(url) }.getOrNull()
            ?: return "资产 URL 无法解析：$url"
        if (!parsed.scheme.equals("https", ignoreCase = true)) {
            return "资产 URL 非 https：$url"
        }
        val host = parsed.host?.lowercase()
        if (host == null || host !in ASSET_HOSTS) {
            return "资产域不在白名单（$host）：$url"
        }
        return null
    }
}

/** 运行时校验拒收的条目（索引 / 补丁通道共用，UI 展示拒收原因用）。 */
@kotlinx.serialization.Serializable
data class RejectedPluginEntry(
    val id: String,
    val reason: String,
)
