package com.abyss.tools.core.plugin

import com.abyss.tools.core.common.SemVer
import com.abyss.tools.plugin.api.PluginShizukuDecl
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 插件中心（abyss-tool/Abyss-Plugins）索引数据契约。
 *
 * plugins.json 由插件仓库群聚合生成 —— 每个 abyss-plugin-* 仓库的
 * plugin.json 汇成一条索引项；App 的插件中心页只消费该索引（单一
 * 事实源），不逐仓爬取。
 */

@Serializable
data class PluginApkInfo(
    val url: String,
    val sizeBytes: Long = 0,
    val sha256: String? = null,
)

@Serializable
data class PluginIndexEntry(
    val id: String,
    val name: String,
    val repo: String,
    val category: String,
    val version: String,
    val minApp: String = "0.1.0",
    val summary: String = "",
    val icon: String? = null,
    val permissions: List<String> = emptyList(),
    /** Shizuku 独立授权声明（null = 不涉及；见 PluginShizukuDecl）。 */
    val shizuku: PluginShizukuDecl? = null,
    /** APK 下载资产（null = 尚未发布，框架预览期常态）。 */
    val apk: PluginApkInfo? = null,
    /** 该插件的补丁通道（缺省按约定推导）。 */
    val patchChannel: String? = null,
    val roadmap: List<String> = emptyList(),
) {
    val versionSemVer: SemVer? get() = SemVer.parse(version)
    val repoUrl: String get() = "https://github.com/$repo"
}

@Serializable
data class PluginIndex(
    val schema: String = "abyss-plugin-index-v1",
    val generatedAt: String? = null,
    val hubVersion: String? = null,
    val plugins: List<PluginIndexEntry> = emptyList(),
    /** 运行时校验拒收的条目（ChannelPolicy fail-closed：不合规即剔除，不展示不可装）。 */
    val rejected: List<RejectedPluginEntry> = emptyList(),
)

/**
 * 插件补丁通道（各插件仓库 patches/index.json）。
 */
@Serializable
data class PluginPatch(
    val from: String,
    val to: String,
    val url: String,
    val sha256: String? = null,
    val sizeBytes: Long = 0,
    val notes: String? = null,
)

@Serializable
data class PluginPatchChannel(
    val schema: String = "abyss-plugin-patches-v1",
    val plugin: String,
    val latest: String,
    val patches: List<PluginPatch> = emptyList(),
    /** 运行时校验拒收的补丁条目（缺摘要 / 域外 URL —— 永不可应用，尽早剔除）。 */
    val rejectedPatches: List<RejectedPluginEntry> = emptyList(),
)

object PluginParsers {
    // 严格 JSON（v0.6.0 起去掉 isLenient，评审 #6）：坏索引直接解析失败，
    // 绝不把畸形值猜成合法态；ignoreUnknownKeys 保留做 schema 前向兼容。
    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun parseIndex(raw: String): PluginIndex = json.decodeFromString(raw)

    fun parsePatchChannel(raw: String): PluginPatchChannel = json.decodeFromString(raw)
}
