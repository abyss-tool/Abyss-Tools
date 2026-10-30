package com.abyss.tools.core.common

/**
 * 深渊工具的发布与插件渠道常量（双仓库模式，对齐 Android-Guru-Agent 体系）。
 *
 * 仓库矩阵：
 *  - Ultra-Guru/Abyss-Tools            开发仓库（本仓库）：源码 + CI 门禁
 *  - Ultra-Guru/Abyss-Tools-Release    发布仓库：version.json / patches.json /
 *                                       changelog.json + GitHub Releases 资产
 *  - Ultra-Guru/Abyss-Plugins          插件中心：plugins.json 索引 + 插件规范
 *  - Ultra-Guru/abyss-plugin-*         十余个大插件仓库（每个插件独立仓库，
 *                                       独立 CI + 独立补丁通道）
 *
 * App 侧与插件侧只认这里的 URL —— 渠道迁移时改一处。
 */
object AbyssChannels {

    const val ORG: String = "Ultra-Guru"

    const val DEV_REPO: String = "Ultra-Guru/Abyss-Tools"
    const val RELEASE_REPO: String = "Ultra-Guru/Abyss-Tools-Release"
    const val PLUGIN_HUB_REPO: String = "Ultra-Guru/Abyss-Plugins"

    /** App 更新清单（发布仓 main 分支根，客户端检查更新的固定读取点）。 */
    const val VERSION_JSON: String =
        "https://raw.githubusercontent.com/Ultra-Guru/Abyss-Tools-Release/main/version.json"

    /** 补丁全量索引（跨版本链式增量底座）。 */
    const val PATCHES_JSON: String =
        "https://raw.githubusercontent.com/Ultra-Guru/Abyss-Tools-Release/main/patches.json"

    /** 更新日志（版本叙事，设置页 / 更新弹层消费）。 */
    const val CHANGELOG_JSON: String =
        "https://raw.githubusercontent.com/Ultra-Guru/Abyss-Tools-Release/main/changelog.json"

    /** 插件中心索引（插件列表的唯一事实源，由插件仓库群聚合生成）。 */
    const val PLUGIN_INDEX_JSON: String =
        "https://raw.githubusercontent.com/Ultra-Guru/Abyss-Plugins/main/plugins.json"

    /** GitHub 仓库页跳转（About 链接用）。 */
    fun repoUrl(repo: String): String = "https://github.com/$repo"

    /** 插件补丁通道（每个插件仓库 main 分支下的固定读取点）。 */
    fun pluginPatchChannel(pluginId: String): String =
        "https://raw.githubusercontent.com/$ORG/$pluginId/main/patches/index.json"
}
