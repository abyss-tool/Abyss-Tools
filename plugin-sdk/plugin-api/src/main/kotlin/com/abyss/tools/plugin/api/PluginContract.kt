package com.abyss.tools.plugin.api

/**
 * 插件契约（宿主 ↔ 插件共享常量）—— 单一事实源，防止两侧硬拷贝漂移。
 *
 * 版本演进纪律：HOST_API_VERSION 只增不减；插件用 [PluginManifest.minHost]
 * 声明其所需的最低宿主 API 版本，宿主在注册前比对，不满足则拒绝加载
 * 并日志引导（fail-closed）。
 *
 * 插件形态（框架期即定型）：
 *  - **APK 插件**：独立仓库构建的完整 APK（abyss-plugin-* 仓库群），
 *    通过系统安装器安装，宿主经 PackageManager 发现（v0.2 接入 AIDL 双向桥）。
 *  - **数据插件**：manifest + 数据资产目录（任务蓝图 / 技能 / 配置），
 *    免安装、走 abyss-patch-v1 数据补丁通道热更新。
 */
object PluginContract {

    /** 宿主插件 API 版本（v1：manifest 发现 + 补丁通道）。 */
    const val HOST_API_VERSION: Int = 1

    /** 插件 manifest 文件名（插件安装目录与插件仓库根的入口文件）。 */
    const val MANIFEST_FILE: String = "plugin.json"

    /** 数据插件安装根目录名（filesDir 之下）。 */
    const val PLUGINS_DIR: String = "plugins"

    /** 插件补丁通道固定读取点（插件仓库 main 分支）。 */
    const val PATCHES_INDEX: String = "patches/index.json"

    /** 插件 id 合法形态：小写字母数字 + 连字符（`abyss-plugin-terminal`）。 */
    val ID_PATTERN: Regex = Regex("^[a-z0-9]+(-[a-z0-9]+)+$")

    /** manifest 中宿主入口声明键（APK 插件 meta-data，v0.2 消费）。 */
    const val META_HOST_ENTRY: String = "com.abyss.tools.plugin.HOST_ENTRY"

    /** manifest 中 UI 入口声明键（APK 插件自有 Activity，v0.2 消费）。 */
    const val META_UI_ENTRIES: String = "com.abyss.tools.plugin.UI_ENTRIES"
}
