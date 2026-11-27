package com.abyss.tools.core.common

/**
 * 深渊工具的发布与插件渠道常量（双仓库模式，对齐 Android-Guru-Agent 体系）。
 *
 * 仓库矩阵：
 *  - abyss-tool/Abyss-Tools            开发仓库（本仓库）：源码 + CI 门禁
 *  - abyss-tool/Abyss-Tools-Release    发布仓库：version.json / patches.json /
 *                                       changelog.json + GitHub Releases 资产
 *  - abyss-tool/Abyss-Plugins          插件中心：plugins.json 索引 + 插件规范
 *  - abyss-tool/abyss-plugin-*         三个引擎级大插件仓库（应用构建器 / 病毒拦截 /
 *                                       虚拟机 —— v0.8.0 起分界收紧：只有自带完整
 *                                       引擎的能力才独立成仓；历史上的十三个轻量
 *                                       插件仓已归档，能力全部内置主壳）
 *
 * App 侧与插件侧只认这里的 URL —— 渠道迁移时改一处。
 */
object AbyssChannels {

    const val ORG: String = "abyss-tool"

    const val DEV_REPO: String = "abyss-tool/Abyss-Tools"
    const val RELEASE_REPO: String = "abyss-tool/Abyss-Tools-Release"
    const val PLUGIN_HUB_REPO: String = "abyss-tool/Abyss-Plugins"

    /** App 更新清单（发布仓 main 分支根，客户端检查更新的固定读取点）。 */
    const val VERSION_JSON: String =
        "https://raw.githubusercontent.com/abyss-tool/Abyss-Tools-Release/main/version.json"

    /** 补丁全量索引（跨版本链式增量底座）。 */
    const val PATCHES_JSON: String =
        "https://raw.githubusercontent.com/abyss-tool/Abyss-Tools-Release/main/patches.json"

    /** 更新日志（版本叙事，设置页 / 更新弹层消费）。 */
    const val CHANGELOG_JSON: String =
        "https://raw.githubusercontent.com/abyss-tool/Abyss-Tools-Release/main/changelog.json"

    /** 插件中心索引（插件列表的唯一事实源，由插件仓库群聚合生成）。 */
    const val PLUGIN_INDEX_JSON: String =
        "https://raw.githubusercontent.com/abyss-tool/Abyss-Plugins/main/plugins.json"

    /** GitHub 仓库页跳转（About 链接用）。 */
    fun repoUrl(repo: String): String = "https://github.com/$repo"

    /** 插件补丁通道（每个插件仓库 main 分支下的固定读取点）。 */
    fun pluginPatchChannel(pluginId: String): String =
        "https://raw.githubusercontent.com/$ORG/$pluginId/main/patches/index.json"

    /**
     * 深渊agent 通道清单（FEAT-037）：姊妹项目 Ultra-Guru/Android-Guru-Agent
     * 的发布仓固定读取点 —— schema 与本仓 version.json 同族（versionName /
     * versionCode / tag / download.{arm64,universal}{url,sizeBytes,sha256}）。
     * 插件中心「深渊agent」置顶卡消费；下载资产经 MirrorRouter 镜像链。
     */
    const val AGENT_VERSION_JSON: String =
        "https://raw.githubusercontent.com/Ultra-Guru/Android-Guru-Agent-Release/main/version.json"

    /**
     * 深渊agent APK 资产 URL 的唯一合法前缀（B-06 ② 仓库归属硬校验）：
     * 清单内的 download.url 只允许指向该发布仓自身的 releases/download/
     * 路径 —— 旧语义只做域级白名单（github.com 任意仓库路径均放行），
     * 清单与镜像同时被劫持时可诱导安装任意仓库的 APK。
     */
    const val AGENT_ASSET_URL_PREFIX: String =
        "https://github.com/Ultra-Guru/Android-Guru-Agent-Release/releases/download/"
}
