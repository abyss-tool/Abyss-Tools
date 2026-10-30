package com.abyss.tools.core.plugin

import com.abyss.tools.core.common.SemVer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 插件中心（Ultra-Guru/Abyss-Plugins）索引数据契约。
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
)

object PluginParsers {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseIndex(raw: String): PluginIndex = json.decodeFromString(raw)

    fun parsePatchChannel(raw: String): PluginPatchChannel = json.decodeFromString(raw)
}
