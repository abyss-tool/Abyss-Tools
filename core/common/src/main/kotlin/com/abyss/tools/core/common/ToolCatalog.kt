package com.abyss.tools.core.common

/**
 * 工具目录（导航页的数据底座）。
 *
 * 承载形态三分法（v0.2 起定型）：
 *  - [ToolStatus.READY]      壳内已可用（当前为插件中心入口）
 *  - [ToolStatus.BUILT_IN]   主壳内置功能 —— 轻量能力直接写进主仓库，
 *                            随版本更新获得，无需安装插件（v0.2 起：
 *                            应用管理 / Shizuku 面板 / 自动化 / 灵动岛 /
 *                            壁纸 / 二维码 / 任务蓝图）
 *  - [ToolStatus.PLUGIN]     由大插件承载（重型能力：独立仓库 / 独立 CI /
 *                            独立补丁通道，点击跳到对应插件详情页安装）
 *  - [ToolStatus.FRAMEWORK]  框架占位（路线图内排期，当前不可用）
 *
 * 分界纪律：**只有「重」的能力才做成插件**（原生工具链 / 独立进程 /
 * 大型框架 —— 终端 / Python / 沙箱 / 伪装 / 媒体 / 网络 / 备份 / 构建
 * 器）；轻量功能一律内置，避免插件中心的碎片化。
 *
 * 插件永远是导航页的第一个分类 —— 这是深渊工具「主壳轻、能力重」的
 * 产品结构表达。
 */
enum class ToolCategory(
    val id: String,
    val title: String,
    val description: String,
) {
    PLUGINS("plugins", "插件", "八个大插件仓库构成的重型能力生态：终端 / Python / 沙箱 / 伪装 / 媒体 / 网络 / 备份 / 构建器 —— 小功能内置，大功能插件化"),
    SYSTEM("system", "系统工具", "基于 Shizuku 的免 Root 系统管控：冻结 / 隐藏 / 提取 / 优化"),
    TERMINAL("terminal", "终端与脚本", "内置 Linux 终端环境与 IDE 级代码编辑，任务蓝图驱动执行"),
    DEV("dev", "开发与构建", "手机端 APK 构建器：Alpine + PRoot 底座 + 三条构建路径（大插件承载）"),
    SANDBOX("sandbox", "沙箱与多开", "VirtualApp 血统的虚拟空间：免安装运行 / 应用双开 / 隔离管控"),
    DISGUISE("disguise", "伪装与隐私", "机型 / 定位 / 电量 / 图标伪装与防截屏等隐私增强"),
    MEDIA("media", "媒体工具", "FFmpeg 级音视频处理：批量剪辑 / 提取 / 无痕导出"),
    AUTOMATION("automation", "自动化", "任务蓝图 JSON 编排：步骤 / 重试 / 通知一站式"),
    DESKTOP("desktop", "桌面美化", "灵动岛 / 悬浮球 / 动态壁纸与桌面组件"),
    NETWORK("network", "网络守护", "VpnService 流量审计与异常传输拦截"),
    DATA("data", "数据与备份", "应用数据备份 / 迁移 / 恢复工厂"),
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
    val summary: String,
    val category: ToolCategory,
    val status: ToolStatus,
    /** 插件承载形态对应的插件 id（status == PLUGIN 时必填）。 */
    val pluginId: String? = null,
    /** designsystem 侧图标键（AbyssIcons.icon(key) 映射）。 */
    val iconKey: String,
)

object ToolCatalog {

    val categories: List<ToolCategory> = ToolCategory.entries.toList()

    val tools: List<ToolDef> = listOf(
        // ── 插件（第一分类，入口即插件中心）────────────────────────────
        ToolDef(
            id = "plugin-hub",
            title = "插件中心",
            summary = "八个大插件仓库：浏览 / 安装 / 补丁更新，一站式重型能力生态入口。",
            category = ToolCategory.PLUGINS,
            status = ToolStatus.READY,
            iconKey = "extension",
        ),

        // ── 开发与构建 ─────────────────────────────────────────────
        ToolDef(
            id = "app-builder",
            title = "应用构建器",
            summary = "手机端 APK 构建器：Alpine + PRoot 底座，Anpyra / 内置工具链 / " +
                "标准 Gradle 三路径自由选择，支持独立 Shizuku 授权（大插件承载）。",
            category = ToolCategory.DEV,
            status = ToolStatus.PLUGIN,
            pluginId = "abyss-plugin-app-builder",
            iconKey = "construction",
        ),

        // ── 系统工具 ───────────────────────────────────────────────────
        ToolDef(
            id = "app-manager",
            title = "应用管理器",
            summary = "应用冻结 / 隐藏 / 强制停止 / 提取安装包 / 图标伪装（pm hide / disable-user）。",
            category = ToolCategory.SYSTEM,
            status = ToolStatus.BUILT_IN,
            iconKey = "apps",
        ),
        ToolDef(
            id = "shizuku-kit",
            title = "Shizuku 工具箱",
            summary = "免 Root 提权指令面板：appops / settings / dumpsys 的可视化封装。",
            category = ToolCategory.SYSTEM,
            status = ToolStatus.BUILT_IN,
            iconKey = "security",
        ),
        ToolDef(
            id = "battery-doctor",
            title = "电池与性能",
            summary = "耗电排行 / 后台收编 / 内存清理（dumpsys 数据图表化）。",
            category = ToolCategory.SYSTEM,
            status = ToolStatus.FRAMEWORK,
            iconKey = "bolt",
        ),
        ToolDef(
            id = "time-shift",
            title = "临时时间修改",
            summary = "auto_time 关闭 + date 写入（高版本受限时走虚拟时钟）。",
            category = ToolCategory.SYSTEM,
            status = ToolStatus.FRAMEWORK,
            iconKey = "schedule",
        ),

        // ── 终端与脚本 ─────────────────────────────────────────────────
        ToolDef(
            id = "terminal",
            title = "内置终端",
            summary = "Termux 血统的完整 Linux Shell：黑底白字 / 快捷命令键盘 / 前台服务保活。",
            category = ToolCategory.TERMINAL,
            status = ToolStatus.PLUGIN,
            pluginId = "abyss-plugin-terminal",
            iconKey = "terminal",
        ),
        ToolDef(
            id = "python-lab",
            title = "Python 游乐场",
            summary = "Chaquopy 引擎：numpy / opencv 脚本执行 + HTML 实时渲染预览。",
            category = ToolCategory.TERMINAL,
            status = ToolStatus.PLUGIN,
            pluginId = "abyss-plugin-python-lab",
            iconKey = "code",
        ),
        ToolDef(
            id = "code-editor",
            title = "代码编辑器",
            summary = "Sora Editor 级多语言高亮 / 补全 / Markdown 实时渲染工作区。",
            category = ToolCategory.TERMINAL,
            status = ToolStatus.FRAMEWORK,
            iconKey = "edit",
        ),
        ToolDef(
            id = "task-blueprint",
            title = "任务蓝图",
            summary = "JSON 任务编排：shell / python / ui_toast 步骤链 + 成功判定 + 进阶解锁。",
            category = ToolCategory.TERMINAL,
            status = ToolStatus.BUILT_IN,
            iconKey = "account_tree",
        ),

        // ── 沙箱与多开 ────────────────────────────────────────────────
        ToolDef(
            id = "sandbox-space",
            title = "虚拟空间",
            summary = "VirtualApp 血统沙箱：外部 APK 免安装直接运行 + API 接管。",
            category = ToolCategory.SANDBOX,
            status = ToolStatus.PLUGIN,
            pluginId = "abyss-plugin-sandbox",
            iconKey = "layers",
        ),
        ToolDef(
            id = "dual-open",
            title = "应用双开",
            summary = "沙箱内并行运行第二个实例：数据隔离 / 独立通知。",
            category = ToolCategory.SANDBOX,
            status = ToolStatus.PLUGIN,
            pluginId = "abyss-plugin-sandbox",
            iconKey = "copy",
        ),

        // ── 伪装与隐私 ────────────────────────────────────────────────
        ToolDef(
            id = "device-disguise",
            title = "设备伪装",
            summary = "机型 / 定位 / 电量 / 信号伪装（沙箱 Hook 层返回伪造数据）。",
            category = ToolCategory.DISGUISE,
            status = ToolStatus.PLUGIN,
            pluginId = "abyss-plugin-disguise",
            iconKey = "theater",
        ),
        ToolDef(
            id = "privacy-guard",
            title = "隐私守卫",
            summary = "FLAG_SECURE 防截屏 / 敏感权限审计 / 异常行为监控。",
            category = ToolCategory.DISGUISE,
            status = ToolStatus.FRAMEWORK,
            iconKey = "visibility_off",
        ),

        // ── 媒体工具 ──────────────────────────────────────────────────
        ToolDef(
            id = "media-lab",
            title = "媒体实验室",
            summary = "FFmpeg 批量剪辑 / 音频提取 / 无痕导出（Python 后台执行）。",
            category = ToolCategory.MEDIA,
            status = ToolStatus.PLUGIN,
            pluginId = "abyss-plugin-media-lab",
            iconKey = "movie",
        ),
        ToolDef(
            id = "qr-craft",
            title = "二维码工坊",
            summary = "ZXing 生成 / 解析 / 网页转二维码，一键分享。",
            category = ToolCategory.MEDIA,
            status = ToolStatus.BUILT_IN,
            iconKey = "qr",
        ),

        // ── 自动化 ────────────────────────────────────────────────────
        ToolDef(
            id = "automation",
            title = "自动化编排",
            summary = "任务蓝图调度引擎：定时任务 / 触发器 / 步骤重试。",
            category = ToolCategory.AUTOMATION,
            status = ToolStatus.BUILT_IN,
            iconKey = "bolt",
        ),

        // ── 桌面美化 ──────────────────────────────────────────────────
        ToolDef(
            id = "dynamic-island",
            title = "灵动岛与悬浮球",
            summary = "WindowManager 悬浮件：拖拽 / 吸附 / 状态岛动画。",
            category = ToolCategory.DESKTOP,
            status = ToolStatus.BUILT_IN,
            iconKey = "widgets",
        ),
        ToolDef(
            id = "live-wallpaper",
            title = "动态壁纸",
            summary = "WallpaperService 引擎：液态玻璃同源视觉的壁纸工坊。",
            category = ToolCategory.DESKTOP,
            status = ToolStatus.BUILT_IN,
            iconKey = "wallpaper",
        ),

        // ── 网络守护 ──────────────────────────────────────────────────
        ToolDef(
            id = "network-guard",
            title = "流量审计",
            summary = "VpnService 虚拟网卡：域名画像 / 异常传输拦截 / 恶意行为告警。",
            category = ToolCategory.NETWORK,
            status = ToolStatus.PLUGIN,
            pluginId = "abyss-plugin-network-guard",
            iconKey = "lan",
        ),

        // ── 数据与备份 ────────────────────────────────────────────────
        ToolDef(
            id = "backup-master",
            title = "备份迁移",
            summary = "应用数据备份 / 跨设备迁移 / 恢复工厂。",
            category = ToolCategory.DATA,
            status = ToolStatus.PLUGIN,
            pluginId = "abyss-plugin-backup",
            iconKey = "backup",
        ),
    )

    fun toolsOf(category: ToolCategory): List<ToolDef> =
        tools.filter { it.category == category }

    fun byId(id: String): ToolDef? = tools.firstOrNull { it.id == id }

    /** 插件承载形态的工具数（仪表盘统计用）。 */
    val pluginDrivenCount: Int get() = tools.count { it.status == ToolStatus.PLUGIN }

    /** 主壳内置形态的工具数（v0.2 内置化重构后的一等公民）。 */
    val builtInCount: Int get() = tools.count { it.status == ToolStatus.BUILT_IN }
}
