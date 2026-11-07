package com.abyss.tools.core.update

import com.abyss.tools.core.common.SemVer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 发布仓（abyss-tool/Abyss-Tools-Release）更新通道数据契约。
 *
 * 与 Android-Guru-Agent-Release 的 version.json / patches.json 字段语义
 * 对齐（versionName / versionCode / tag / download / patch），在其上
 * 增加 `schema` 字段做前向兼容锚点；客户端解析一律 ignoreUnknownKeys ——
 * 通道加字段不炸旧客户端。
 */

@Serializable
data class DownloadInfo(
    val url: String,
    val sizeBytes: Long = 0,
    val sha256: String? = null,
)

@Serializable
data class PatchInfo(
    val url: String,
    val fromTag: String,
    val toTag: String? = null,
    val sizeBytes: Long = 0,
    val sha256: String? = null,
)

@Serializable
data class VersionManifest(
    val schema: String = "abyss-version-v1",
    val versionName: String,
    val versionCode: Int = 0,
    val tag: String? = null,
    val publishedAt: String? = null,
    val notes: String? = null,
    val channel: String? = null,
    val download: DownloadInfo? = null,
    val patch: PatchInfo? = null,
    val commitSha: String? = null,
) {
    val resolvedTag: String get() = tag ?: "v$versionName"
    val version: SemVer? get() = SemVer.parse(versionName)
}

@Serializable
data class PatchesManifest(
    val schema: String = "abyss-patches-v1",
    val generatedAt: String? = null,
    val latestTag: String? = null,
    val patches: List<PatchInfo> = emptyList(),
)

object UpdateParsers {
    // 严格 JSON（v0.6.0 起去掉 isLenient）：坏清单直接解析失败进 Failed，
    // 绝不把畸形值猜成合法态；ignoreUnknownKeys 保留做前向兼容。
    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun parseVersionManifest(raw: String): VersionManifest = json.decodeFromString(raw)

    fun parsePatchesManifest(raw: String): PatchesManifest = json.decodeFromString(raw)

    fun parseChangelogManifest(raw: String): ChangelogManifest = json.decodeFromString(raw)
}
