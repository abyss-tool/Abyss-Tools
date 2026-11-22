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
 *    通过系统安装器安装，宿主经 PackageManager 发现，经 AIDL 双向桥交互。
 *  - **数据插件**：manifest + 数据资产目录（任务蓝图 / 技能 / 配置），
 *    免安装、走 abyss-patch-v1 数据补丁通道热更新。
 *
 * HOST_API v2（ADR-100 D-5 · ARC-102 §2 —— 新增「依赖解析与能力绑定」）：
 *  - v1 → v2 为**只增不减**的增量：`minHost=1` 插件在 v2 宿主照常加载；
 *    `minHost=2` 插件在 v1 宿主 fail-closed 拒绝并提示升级（PLG-001 FR-001）；
 *  - 新增 [ACTION_PLUGIN] / [ACTION_LINUX_RUNTIME] 两个服务动作串与
 *    [META_CAPABILITIES] 能力声明键（承载跨插件契约的宿主中介）；
 *  - `PluginManifest` 增 `provides / depends / capabilities / assetChannel`
 *    四个可选字段（v1 解析器 `ignoreUnknownKeys = true` 天然兼容）。
 *  - 兼容期：宿主 v0.10 同时接受 v1/v2 插件，持续 ≥2 个宿主版本；期间
 *    `depends` 字段在 v1 宿主被忽略，但索引层标注「需新版宿主」。
 *  - ABI 破坏性变更必须再次提升 HOST_API 并让旧插件 fail-closed，禁止
 *    原地改方法签名（SDK-001 FR-001/005）。
 */
object PluginContract {

    /** 宿主插件 API 版本（v1：manifest 发现 + 补丁通道；v2：+ 依赖解析与能力绑定）。 */
    const val HOST_API_VERSION: Int = 2

    /** 插件 manifest 文件名（插件安装目录与插件仓库根的入口文件）。 */
    const val MANIFEST_FILE: String = "plugin.json"

    /** 数据插件安装根目录名（filesDir 之下）。 */
    const val PLUGINS_DIR: String = "plugins"

    /** 插件补丁通道固定读取点（插件仓库 main 分支）。 */
    const val PATCHES_INDEX: String = "patches/index.json"

    /** 插件 id 合法形态：小写字母数字 + 连字符（`abyss-plugin-terminal`）。 */
    val ID_PATTERN: Regex = Regex("^[a-z0-9]+(-[a-z0-9]+)+$")

    /** manifest 中宿主入口声明键（APK 插件 meta-data）。 */
    const val META_HOST_ENTRY: String = "com.abyss.tools.plugin.HOST_ENTRY"

    /** manifest 中 UI 入口声明键（APK 插件自有 Activity）。 */
    const val META_UI_ENTRIES: String = "com.abyss.tools.plugin.UI_ENTRIES"

    // ── HOST_API v2 增量（ADR-100 D-5 / ARC-102 §2）─────────────────────

    /** manifest 中能力声明键（v2 新增：PluginBroker 能力发现的事实源）。 */
    const val META_CAPABILITIES: String = "com.abyss.tools.plugin.CAPABILITIES"

    /** 通用插件服务动作串（AIDL 双向桥：IAbyssPluginHost 挂载点）。 */
    const val ACTION_PLUGIN: String = "com.abyss.tools.plugin.action.PLUGIN"

    /** Linux 运行时服务动作串（abyss-plugin-linux 的 LinuxRuntimeService 发现）。 */
    const val ACTION_LINUX_RUNTIME: String = "com.abyss.tools.plugin.action.LINUX_RUNTIME"

    /** 当前组织内唯一注册的能力 id（abyss-plugin-linux 提供）。 */
    const val CAPABILITY_LINUX_RUNTIME_V1: String = "linux-runtime-v1"

    /** 能力 id 合法形态：`^[a-z0-9-]+-v[0-9]+$`（主版本变化必须新开 id）。 */
    val CAPABILITY_PATTERN: Regex = Regex("^[a-z0-9-]+-v[0-9]+$")

    /** SessionGrant 有效期上限（ARC-102 FR-005；能力描述可收紧不可放宽）。 */
    const val GRANT_TTL_MAX_SEC: Int = 300
}
