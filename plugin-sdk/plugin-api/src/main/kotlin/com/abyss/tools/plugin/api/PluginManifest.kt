package com.abyss.tools.plugin.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 插件 Shizuku 授权声明 —— 「插件内单独带有 Shizuku 授权」的契约载体。
 *
 * 背景：Shizuku 的授权机制是严格 per-app 的（绑定具体包名）。宿主获得
 * 授权后，运行在宿主进程内的代码可经宿主 Binder 通道间接使用；但**独立
 * APK 插件有自己的包名，必须由用户单独授权一次**，否则 Shizuku 服务端
 * 直接拒绝。声明本字段 = 插件详情页向用户明确「装完插件还要单独授权
 * Shizuku」的原因与用途，避免用户在未知情的情况下反复碰壁。
 */
@Serializable
data class PluginShizukuDecl(
    /** 是否需要独立于宿主的 Shizuku 授权（独立 APK 插件按 per-app 授权）。 */
    val ownAuth: Boolean = false,
    /** 为什么需要（详情页向用户解释，人话）。 */
    val reason: String = "",
)

/**
 * 插件 manifest（plugin.json）—— 插件包与插件仓库共用的自描述文件。
 *
 * 插件仓库根的 plugin.json 与安装目录内的 plugin.json 同构：仓库侧由
 * 插件 CI 校验（tools/validate_plugin.py），宿主侧由本解析器消费。
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
    /** Shizuku 授权声明（null = 不涉及；独立 APK 插件需要单独授权时必填）。 */
    val shizuku: PluginShizukuDecl? = null,
    val entry: String? = null,
    val repo: String = "",
    val patchChannel: String? = null,
    val roadmap: List<String> = emptyList(),
) {
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
