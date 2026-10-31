package com.abyss.tools.plugin.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
