package com.abyss.tools.core.plugin

import com.abyss.tools.core.common.SemVer
import com.abyss.tools.plugin.api.DependSpec
import com.abyss.tools.plugin.api.PluginShizukuDecl
import com.abyss.tools.plugin.api.ProvideSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 插件中心（abyss-tool/Abyss-Plugins）索引数据契约。
 *
 * plugins.json 由插件仓库群聚合生成 —— 每个 abyss-plugin-* 仓库的
 * plugin.json 汇成一条索引项；App 的插件中心页只消费该索引（单一
 * 事实源），不逐仓爬取。
 *
 * hubVersion 0.4.0 增量（DC-100 §4）：条目透传 v2 manifest 字段
 * `minHost / provides / depends / capabilities / assetChannel`，由
 * `sync_index.py` 从各仓 plugin.json 聚合（不手工维护）。
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
    /** 该插件要求的最低宿主插件 API 版本（0.4.0 起由 manifest 透传；缺省 1）。 */
    val minHost: Int = 1,
    val minApp: String = "0.1.0",
    val summary: String = "",
    val icon: String? = null,
    val permissions: List<String> = emptyList(),
    /**
     * N-EXP-006 结构化权限声明（v0.10 新增，可选；缺失时 UI 回退到
     * permissions: List<String> 旧字段做简单展示）。
     */
    val declaredPermissions: List<PluginPermission> = emptyList(),
    /** Shizuku 独立授权声明（null = 不涉及；见 PluginShizukuDecl）。 */
    val shizuku: PluginShizukuDecl? = null,
    // ── hubVersion 0.4.0 增量（DC-100 §4 —— sync_index.py 自动透传）─────
    /** 本插件对外提供的能力（引擎级插件必填）。 */
    val provides: List<ProvideSpec> = emptyList(),
    /** 消费的能力与插件（瘦客户端必填；依赖解析的事实源）。 */
    val depends: List<DependSpec> = emptyList(),
    /** 能力细节描述（透传能力提供方契约 JSON，宿主不重复建模）。 */
    val capabilities: JsonObject? = null,
    /** abyss-asset-v1 大资产通道 URL（null = 无大资产）。 */
    val assetChannel: String? = null,
    /** APK 下载资产（null = 尚未发布，框架预览期常态）。 */
    val apk: PluginApkInfo? = null,
    /** 该插件的补丁通道（缺省按约定推导）。 */
    val patchChannel: String? = null,
    val roadmap: List<String> = emptyList(),
) {
    val versionSemVer: SemVer? get() = SemVer.parse(version)
    val repoUrl: String get() = "https://github.com/$repo"

    /** 快捷判定：linux-runtime-v1 提供方（宿主侧能力发现入口）。 */
    val providesLinuxRuntime: Boolean
        get() = provides.any { it.capability == "linux-runtime-v1" }

    /** 快捷判定：linux-runtime-v1 消费方（瘦客户端依赖声明）。 */
    val dependsOnLinuxRuntime: Boolean
        get() = depends.any { it.capability == "linux-runtime-v1" }

    /** 快捷判定：v2 插件（minHost=2，v1 宿主加载即 fail-closed）。 */
    val requiresHostApi2: Boolean get() = minHost >= 2
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
