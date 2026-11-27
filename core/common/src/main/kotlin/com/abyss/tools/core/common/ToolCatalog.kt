package com.abyss.tools.core.common

/**
 * 工具目录（导航页的数据底座）。
 *
 * 承载形态三分法（v0.8.0 大内置化重构定型）：
 *  - [ToolStatus.READY]      壳内已可用（词汇位保留；插件中心入口已于 v0.9.13
 *                            升级为底部导航「插件」独立 tab，见 FEAT-034）
 *  - [ToolStatus.BUILT_IN]   主壳内置功能 —— v0.8.0 起承载形态大迁移：除
 *                            应用构建器外的全部原重型插件（终端 / 脚本 /
 *                            沙箱 / 媒体 / 网络 / 备份）全部内置化落地真实
 *                            功能页（Shizuku Shell 终端 / 脚本实验室 /
 *                            多用户隔离空间 / MediaMuxer 媒体实验室 /
 *                            netstats 流量审计 / 备份迁移），外加 v0.8.0
 *                            新增的系统控制中心（授权总管 AppOps + pm
 *                            grant/revoke）与组件工坊（黑洞时钟桌面组件）
 *  - [ToolStatus.PLUGIN]     由大插件承载 —— v0.8.0 起只保留「引擎级」重
 *                            型能力：应用构建器（旗舰）+ 病毒拦截（新）+
 *                            虚拟机（新）。曾经的六仓（终端 / Python /
 *                            沙箱 / 媒体 / 网络 / 备份）已归档
 *  - [ToolStatus.FRAMEWORK]  框架占位（路线图内排期，当前不可用）
 *
 * 分界纪律（v0.8.0 收紧）：**只有「自带完整引擎」的能力才做成插件**——
 * 构建底座（Alpine+PRoot）、病毒特征引擎（ClamAV 级）、整机虚拟机
 * （QEMU 级）；其余一律内置，避免插件中心碎片化。
 *
 * 插件承载形态分界（v0.9.13 起，FEAT-034）：**引擎级插件能力由底部导航
 * 「插件」独立 tab 承载**；工具目录只登记主壳内置 / 插件承载的工具行，
 * 不再为插件中心保留分类与入口行。
 *
 * UI-001（功能文案精简）：ToolCategory.description 与 ToolDef.summary
 * 装饰字段删除 —— 目录数据只保留 id / title / status / pluginId /
 * iconKey；状态语义经 [ToolStatus.label] 供无障碍朗读。
 */
enum class ToolCategory(
    val id: String,
    val title: String,
) {
    SYSTEM("system", "系统工具"),
    TERMINAL("terminal", "终端与脚本"),
    DEV("dev", "开发与构建"),
    SANDBOX("sandbox", "沙箱与多开"),
    DISGUISE("disguise", "伪装与隐私"),
    MEDIA("media", "媒体工具"),
    AUTOMATION("automation", "自动化"),
    DESKTOP("desktop", "桌面美化"),
    NETWORK("network", "网络守护"),
    DATA("data", "数据与备份"),
}

enum class ToolStatus(val label: String) {
    READY("可用"),
    BUILT_IN("内置"),
    PLUGIN("插件承载"),
    FRAMEWORK("路线图中"),
}

data class ToolDef(
    val id: String,
    val title: String,
    val category: ToolCategory,
    val status: ToolStatus,
    /** 插件承载形态对应的插件 id（status == PLUGIN 时必填）。 */
    val pluginId: String? = null,
    /** designsystem 侧图标键（AbyssIcons.icon(key) 映射）。 */
    val iconKey: String,
)

object ToolCatalog {

    /** 伪装四件套的稳定 id（AbyssRoot 路由消费，避免散落字符串）。 */
    const val ID_BATTERY_DISGUISE = "battery-disguise"
    const val ID_MODEL_DISGUISE = "model-disguise"
    const val ID_SIGNAL_DISGUISE = "signal-disguise"
    const val ID_LOCATION_DISGUISE = "location-disguise"

    /** 内置功能页的稳定 id。 */
    const val ID_APP_MANAGER = "app-manager"
    const val ID_SHIZUKU_KIT = "shizuku-kit"
    const val ID_QR_CRAFT = "qr-craft"
    const val ID_TASK_BLUEPRINT = "task-blueprint"
    const val ID_AUTOMATION = "automation"
    const val ID_DYNAMIC_ISLAND = "dynamic-island"
    const val ID_LIVE_WALLPAPER = "live-wallpaper"

    /** v0.8.0 大内置化：原六插件 + 新能力的稳定 id。 */
    const val ID_TERMINAL = "terminal"
    const val ID_SCRIPT_LAB = "script-lab"
    const val ID_SANDBOX_SPACE = "sandbox-space"
    const val ID_DUAL_OPEN = "dual-open"
    const val ID_MEDIA_LAB = "media-lab"
    const val ID_NET_AUDIT = "network-audit"
    const val ID_BACKUP = "backup-master"
    const val ID_SYS_CONTROL = "system-control"
    const val ID_WIDGET_STUDIO = "widget-studio"

    val categories: List<ToolCategory> = ToolCategory.entries.toList()

    val tools: List<ToolDef> = listOf(
        // ── 开发与构建（引擎级插件：构建底座）──────────────────────────
        ToolDef(
            id = "app-builder",
            title = "应用构建器",
            category = ToolCategory.DEV,
            status = ToolStatus.PLUGIN,
            pluginId = "abyss-plugin-app-builder",
            iconKey = "construction",
        ),
        ToolDef(
            id = "linux-runtime",
            title = "Linux 运行环境",
            category = ToolCategory.DEV,
            status = ToolStatus.PLUGIN,
            pluginId = "abyss-plugin-linux",
            iconKey = "terminal",
        ),

        // ── 系统工具 ───────────────────────────────────────────────────
        ToolDef(
            id = ID_APP_MANAGER,
            title = "应用管理器",
            category = ToolCategory.SYSTEM,
            status = ToolStatus.BUILT_IN,
            iconKey = "apps",
        ),
        ToolDef(
            id = ID_SYS_CONTROL,
            title = "系统控制中心",
            category = ToolCategory.SYSTEM,
            status = ToolStatus.BUILT_IN,
            iconKey = "tune",
        ),
        ToolDef(
            id = ID_SHIZUKU_KIT,
            title = "Shizuku 工具箱",
            category = ToolCategory.SYSTEM,
            status = ToolStatus.BUILT_IN,
            iconKey = "security",
        ),
        ToolDef(
            id = "battery-doctor",
            title = "电池与性能",
            category = ToolCategory.SYSTEM,
            status = ToolStatus.FRAMEWORK,
            iconKey = "bolt",
        ),
        ToolDef(
            id = "virus-guard",
            title = "病毒拦截",
            category = ToolCategory.SYSTEM,
            status = ToolStatus.PLUGIN,
            pluginId = "abyss-plugin-virus-guard",
            iconKey = "bug_report",
        ),

        // ── 终端与脚本（v0.8.0 内置化：Shizuku Shell 真实执行）──────────
        ToolDef(
            id = ID_TERMINAL,
            title = "内置终端",
            category = ToolCategory.TERMINAL,
            status = ToolStatus.BUILT_IN,
            iconKey = "terminal",
        ),
        ToolDef(
            id = ID_SCRIPT_LAB,
            title = "脚本实验室",
            category = ToolCategory.TERMINAL,
            status = ToolStatus.BUILT_IN,
            iconKey = "code",
        ),
        ToolDef(
            id = "code-editor",
            title = "代码编辑器",
            category = ToolCategory.TERMINAL,
            status = ToolStatus.FRAMEWORK,
            iconKey = "edit",
        ),
        ToolDef(
            id = ID_TASK_BLUEPRINT,
            title = "任务蓝图",
            category = ToolCategory.TERMINAL,
            status = ToolStatus.BUILT_IN,
            iconKey = "account_tree",
        ),

        // ── 沙箱与多开（v0.8.0 内置化：多用户隔离引擎）──────────────────
        ToolDef(
            id = ID_SANDBOX_SPACE,
            title = "隔离空间",
            category = ToolCategory.SANDBOX,
            status = ToolStatus.BUILT_IN,
            iconKey = "layers",
        ),
        ToolDef(
            id = ID_DUAL_OPEN,
            title = "应用双开",
            category = ToolCategory.SANDBOX,
            status = ToolStatus.BUILT_IN,
            iconKey = "copy",
        ),
        // ── 虚拟机（已收编 · HUB-014 归档纪律）────────────────────────
        // ADR-100 D-4：abyss-plugin-virtual-machine 归档，QEMU 整机能力由
        // Linux 插件的 QEMU_VM 执行域承接（见 DEV 分类的「Linux 运行环境」）。
        // 归档能力不得复活为插件（SRS-004 FR-005）—— 此条目仅作历史索引。

        // ── 伪装与隐私（v0.3：四件套内置，Shizuku 免 Root）────────────
        ToolDef(
            id = ID_BATTERY_DISGUISE,
            title = "电量伪装",
            category = ToolCategory.DISGUISE,
            status = ToolStatus.BUILT_IN,
            iconKey = "battery",
        ),
        ToolDef(
            id = ID_LOCATION_DISGUISE,
            title = "定位伪装",
            category = ToolCategory.DISGUISE,
            status = ToolStatus.BUILT_IN,
            iconKey = "location",
        ),
        ToolDef(
            id = ID_SIGNAL_DISGUISE,
            title = "信号伪装",
            category = ToolCategory.DISGUISE,
            status = ToolStatus.BUILT_IN,
            iconKey = "signal",
        ),
        ToolDef(
            id = ID_MODEL_DISGUISE,
            title = "机型伪装",
            category = ToolCategory.DISGUISE,
            status = ToolStatus.BUILT_IN,
            iconKey = "smartphone",
        ),
        ToolDef(
            id = "privacy-guard",
            title = "隐私守卫",
            category = ToolCategory.DISGUISE,
            status = ToolStatus.FRAMEWORK,
            iconKey = "visibility_off",
        ),

        // ── 媒体工具（v0.8.0 内置化：MediaMuxer 无重编码引擎）──────────
        ToolDef(
            id = ID_MEDIA_LAB,
            title = "媒体实验室",
            category = ToolCategory.MEDIA,
            status = ToolStatus.BUILT_IN,
            iconKey = "movie",
        ),
        ToolDef(
            id = ID_QR_CRAFT,
            title = "二维码工坊",
            category = ToolCategory.MEDIA,
            status = ToolStatus.BUILT_IN,
            iconKey = "qr",
        ),

        // ── 自动化 ────────────────────────────────────────────────────
        ToolDef(
            id = ID_AUTOMATION,
            title = "自动化编排",
            category = ToolCategory.AUTOMATION,
            status = ToolStatus.BUILT_IN,
            iconKey = "bolt",
        ),

        // ── 桌面美化（v0.9.0 引力场：组件银河四成员）───────────────
        ToolDef(
            id = ID_WIDGET_STUDIO,
            title = "组件工坊",
            category = ToolCategory.DESKTOP,
            status = ToolStatus.BUILT_IN,
            iconKey = "widgets",
        ),
        ToolDef(
            id = ID_DYNAMIC_ISLAND,
            title = "灵动岛与悬浮球",
            category = ToolCategory.DESKTOP,
            status = ToolStatus.BUILT_IN,
            iconKey = "swap",
        ),
        ToolDef(
            id = ID_LIVE_WALLPAPER,
            title = "动态壁纸",
            category = ToolCategory.DESKTOP,
            status = ToolStatus.BUILT_IN,
            iconKey = "wallpaper",
        ),

        // ── 网络守护（v0.8.0 内置化：netstats 流量审计）────────────────
        ToolDef(
            id = ID_NET_AUDIT,
            title = "流量审计",
            category = ToolCategory.NETWORK,
            status = ToolStatus.BUILT_IN,
            iconKey = "lan",
        ),

        // ── 数据与备份（v0.8.0 内置化：APK 库 + zip 引擎）──────────────
        ToolDef(
            id = ID_BACKUP,
            title = "备份迁移",
            category = ToolCategory.DATA,
            status = ToolStatus.BUILT_IN,
            iconKey = "backup",
        ),

        // ── 系统工具：临时时间修改（保留占位）──────────────────────────
        ToolDef(
            id = "time-shift",
            title = "临时时间修改",
            category = ToolCategory.SYSTEM,
            status = ToolStatus.FRAMEWORK,
            iconKey = "schedule",
        ),
    )

    fun toolsOf(category: ToolCategory): List<ToolDef> =
        tools.filter { it.category == category }

    fun byId(id: String): ToolDef? = tools.firstOrNull { it.id == id }

    /** 插件承载形态的工具数（仪表盘统计用）。 */
    val pluginDrivenCount: Int get() = tools.count { it.status == ToolStatus.PLUGIN }

    /** 主壳内置形态的工具数（v0.8 大内置化后的一等公民）。 */
    val builtInCount: Int get() = tools.count { it.status == ToolStatus.BUILT_IN }
}
