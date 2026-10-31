package com.abyss.tools.core.update

import kotlinx.serialization.Serializable

/**
 * 发布仓（Ultra-Guru/Abyss-Tools-Release）changelog.json 数据契约。
 *
 * 更新日志是更新通道的「叙事层」：version.json 只回答「有没有新版本」，
 * changelog.json 回答「这个版本带来了什么」。设置页「查看更新日志」与
 * 更新弹层的版本说明均消费本契约（markdown 字段供富文本渲染，notes 为
 * 结构化要点列表 —— 两者内容一致，渲染端按能力选择）。
 */

@Serializable
data class ChangelogEntry(
    val versionName: String,
    val versionCode: Int = 0,
    val tag: String? = null,
    val date: String? = null,
    val title: String? = null,
    val notes: List<String> = emptyList(),
    val markdown: String? = null,
    val releaseUrl: String? = null,
) {
    val resolvedTag: String get() = tag ?: "v$versionName"
}

@Serializable
data class ChangelogManifest(
    val schema: String = "abyss-changelog-v1",
    val generatedAt: String? = null,
    val latest: ChangelogEntry? = null,
    val history: List<ChangelogEntry> = emptyList(),
) {
    /** 全量条目（latest 置顶，供 UI 一次性渲染）。 */
    val entries: List<ChangelogEntry>
        get() = listOfNotNull(latest) + history
}
