package com.abyss.tools.plugin.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 插件 Shizuku 授权声明 —— 「插件内单独带有 Shizuku 授权」的契约载体。
 *
 * 背景：Shizuku 的授权机制是严格 per-app 的（绑定具体包名）。宿主获得
 * 授权后，运行在宿主进程内的代码可经宿主 Binder 通道间接使用；但**独立
 * APK 插件有自己的包名，必须由用户单独授权一次**，否则 Shizuku 服务端
 * 直接拒绝。声明本字段 = 插件详情页向用户明确「装完插件还要单独授权
 * Shizuku」的原因与用途，避免用户在未知情的情况下反复碰壁。
 *
 * HOST_API v2 收敛（ADR-100 D-1 / LIN-005）：引擎级插件按需声明 ——
 * `abyss-plugin-linux` 是全生态**唯一**持有方（PRoot/QEMU 引擎需要
 * uid 2000 shell 上下文）；纯编排型消费方（如应用构建器）**必须删除**
 * 本声明（CI 门禁「出现即失败」，APP-100 FR-001）。
 */
@Serializable
data class PluginShizukuDecl(
    /** 是否需要独立于宿主的 Shizuku 授权（独立 APK 插件按 per-app 授权）。 */
    val ownAuth: Boolean = false,
    /** 为什么需要（详情页向用户解释，人话）。 */
    val reason: String = "",
) {
    init {
        // 形状纪律（HUB-001 / LIN-005 FR-001）：声明即独立授权 + 人话理由。
        if (ownAuth) {
            require(reason.length >= 10) {
                "shizuku.reason 需 ≥10 字符向用户解释单独授权原因（LIN-005 FR-001）"
            }
        }
    }
}

/**
 * 能力提供声明（DC-100 §3 ProvideSpec —— `provides[]` 条目）。
 */
@Serializable
data class ProvideSpec(
    /** 能力 id（`^[a-z0-9-]+-v[0-9]+$`；主版本变化必须新开 id，禁止原地改语义）。 */
    val capability: String,
    /** 该能力自哪个插件版本起提供。 */
    val since: String = "",
    /** false = 预览能力（索引标注，消费方展示「实验性」）。 */
    val stable: Boolean = true,
) {
    init {
        require(PluginContract.CAPABILITY_PATTERN.matches(capability)) {
            "provides.capability 形态非法 → $capability（应为 ^[a-z0-9-]+-v[0-9]+\$）"
        }
    }
}

/**
 * 能力依赖声明（DC-100 §3 DependSpec —— `depends[]` 条目）。
 */
@Serializable
data class DependSpec(
    /** 被依赖插件 id（符合 `ID_PATTERN`，且等于被依赖仓库末段）。 */
    val pluginId: String,
    /** 消费的能力 id（必须命中被依赖插件 `provides[].capability`）。 */
    val capability: String,
    /** SemVer 区间（`>=0.2.0 <1.0.0`；统一用 SemVer 比较，禁止 split('.')）。 */
    val versionRange: String = "",
    /** 默认 false；false 时宿主必须 fail-closed（缺失/版本低一律拒）。 */
    val optional: Boolean = false,
    /** `optional=false` 时必填且 ≥10 字符，人话说明为何依赖。 */
    val reason: String = "",
) {
    init {
        require(PluginContract.ID_PATTERN.matches(pluginId)) {
            "depends[].pluginId 形态非法 → $pluginId"
        }
        require(PluginContract.CAPABILITY_PATTERN.matches(capability)) {
            "depends[].capability 形态非法 → $capability"
        }
        if (!optional) {
            require(reason.length >= 10) {
                "depends[$pluginId].optional=false 时 reason 必填且 ≥10 字符（DC-100 §3）"
            }
        }
    }

    companion object {
        /** 常用区间形态锚点（DependencyResolver 消费 `[">=", "<"]` 二元组）。 */
        val RANGE_OPS: Set<String> = setOf(">=", ">", "<=", "<", "==")
    }
}

/**
 * 插件 manifest（plugin.json v2）—— 插件包与插件仓库共用的自描述文件。
 *
 * 插件仓库根的 plugin.json 与安装目录内的 plugin.json 同构：仓库侧由
 * 插件 CI 校验（tools/validate_plugin.py），宿主侧由本解析器消费。
 *
 * HOST_API v2 增量（DC-100 §2 —— 四个可选字段，全部向后兼容）：
 *  - [provides]：本插件对外提供的能力（`provides[].capability` 唯一）；
 *  - [depends]：消费的能力与插件（宿主 PluginBroker 做依赖解析）；
 *  - [capabilities]：能力细节描述（域 / 特性 / 架构 / 限额 —— 透传
 *    JsonObject，语义由能力提供方的 `:runtime-api` 契约定义，宿主不
 *    重复建模）；
 *  - [assetChannel]：`assets/index.json` 大资产通道 URL（过 ChannelPolicy
 *    域白名单与重定向复检，与 abyss-patch-v1 严格分离）。
 */
@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val minHost: Int = 1,
    val minApp: String = "0.1.0",
    val category: String = "tools",
    val summary: String = "",
    val icon: String? = null,
    val permissions: List<String> = emptyList(),
    /** Shizuku 授权声明（null = 不涉及；引擎级授权持有方必填）。 */
    val shizuku: PluginShizukuDecl? = null,
    val entry: String? = null,
    val repo: String = "",
    val patchChannel: String? = null,
    val roadmap: List<String> = emptyList(),
    // ── HOST_API v2 可选字段（v1 解析器 ignoreUnknownKeys 天然兼容）─────
    /** 对外提供的能力（引擎级插件必填；消费方为空）。 */
    val provides: List<ProvideSpec> = emptyList(),
    /** 消费的能力与插件（瘦客户端必填；`optional=false` 一律 fail-closed）。 */
    val depends: List<DependSpec> = emptyList(),
    /** 能力细节描述（透传能力提供方契约 JSON，宿主不重复建模）。 */
    val capabilities: JsonObject? = null,
    /** abyss-asset-v1 大资产通道 URL（null = 无大资产）。 */
    val assetChannel: String? = null,
) {
    /** 快捷判定：是否为 `linux-runtime-v1` 的提供方（当前唯一引擎级能力）。 */
    val providesLinuxRuntime: Boolean
        get() = provides.any { it.capability == PluginContract.CAPABILITY_LINUX_RUNTIME_V1 }

    /** 快捷判定：是否依赖 `linux-runtime-v1`（瘦客户端消费方）。 */
    val dependsOnLinuxRuntime: Boolean
        get() = depends.any { it.capability == PluginContract.CAPABILITY_LINUX_RUNTIME_V1 }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = true
        }

        fun parse(raw: String): PluginManifest = json.decodeFromString(raw)

        /** 序列化回 plugin.json 形态（installDataPlugin 落盘 / 插件仓生成用）。 */
        fun serialize(manifest: PluginManifest): String =
            json.encodeToString(PluginManifest.serializer(), manifest)
    }
}

/**
 * 进程内插件接口（数据插件 / 宿主内嵌插件的本期占位契约）。
 *
 * v1 框架期只保证 manifest 与生命周期钩子的形状稳定；APK 插件的
 * 跨进程桥（AIDL + 宿主服务发现）在 v0.2 发布流水线接入时再扩展。
 */
interface AbyssPlugin {
    val manifest: PluginManifest

    /** 宿主注册完成（manifest 校验通过）后回调。 */
    fun onInstalled() {}

    /** 用户启用插件时回调。 */
    fun onEnabled() {}

    /** 用户停用 / 宿主卸载插件时回调。 */
    fun onDisabled() {}
}
