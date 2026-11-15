package com.abyss.tools.core.common

/**
 * 工具目录（导航页的数据底座）。
 *
 * 承载形态三分法（v0.8.0 大内置化重构定型）：
 *  - [ToolStatus.READY]      壳内已可用（当前为插件中心入口）
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
 * 插件永远是导航页的第一个分类 —— 这是深渊工具「主壳轻、能力重」的
 * 产品结构表达。
 */
enum class ToolCategory(
    val id: String,
    val title: String,
    val description: String,
) {
    PLUGINS("plugins", "插件", "三个引擎级插件构成的重型能力生态：应用构建器 / 病毒拦截 / 虚拟机 —— 小功能内置，引擎级能力插件化"),
    SYSTEM("system", "系统工具", "基于 Shizuku 的免 Root 系统管控：冻结 / 隐藏 / 提取 / 授权总管 / 系统控制"),
    TERMINAL("terminal", "终端与脚本", "内置 Shizuku Shell 终端与脚本实验室：编辑 → 执行 → 历史一站直达"),
    DEV("dev", "开发与构建", "手机端 APK 构建：Alpine + PRoot 底座 + 三条构建路径（引擎级大插件承载）"),
    SANDBOX("sandbox", "沙箱与多开", "多用户隔离空间：免 Root 双开 / 独立数据域；整机虚拟机属插件能力"),
    DISGUISE("disguise", "伪装与隐私", "伪装四件套（v0.3 内置）：机型 / 电量 / 信号 / 定位，Shizuku 免 Root 实时生效；隐私守卫路线图中"),
    MEDIA("media", "媒体工具", "MediaExtractor/MediaMuxer 级音视频处理：音频提取 / 去音轨 / 无重编码裁剪"),
    AUTOMATION("automation", "自动化", "任务蓝图 JSON 编排：步骤 / 重试 / 通知一站式"),
    DESKTOP("desktop", "桌面美化", "组件银河四成员：黑洞时钟 / 引力电池 / 星轨日历 / 深渊引力仪，灵动岛悬浮球与动态壁纸"),
    NETWORK("network", "网络守护", "netstats 应用维度流量审计（内置）；VpnService 深度包审计路线图中"),
    DATA("data", "数据与备份", "APK 资料库 / 配置与插件包备份 / 带防护的恢复（内置）"),
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
        // ── 插件（第一分类，入口即插件中心）────────────────────────────
        ToolDef(
            id = "plugin-hub",
            title = "插件中心",
            summary = "三个引擎级插件仓库：应用构建器 / 病毒拦截 / 虚拟机，浏览 / 安装 / 补丁更新一站式。",
            category = ToolCategory.PLUGINS,
            status = ToolStatus.READY,
            iconKey = "extension",
        ),

        // ── 开发与构建（引擎级插件：构建底座）──────────────────────────
        ToolDef(
            id = "app-builder",
            title = "应用构建器",
            summary = "手机端 APK 构建器：Alpine + PRoot 底座，Anpyra / 内置工具链 / " +
                "标准 Gradle 三路径自由选择，支持独立 Shizuku 授权（引擎级大插件）。",
            category = ToolCategory.DEV,
            status = ToolStatus.PLUGIN,
            pluginId = "abyss-plugin-app-builder",
            iconKey = "construction",
        ),

        // ── 系统工具 ───────────────────────────────────────────────────
        ToolDef(
            id = ID_APP_MANAGER,
            title = "应用管理器",
            summary = "应用冻结 / 隐藏 / 强制停止 / 提取安装包 / 图标伪装（pm hide / disable-user）。",
            category = ToolCategory.SYSTEM,
            status = ToolStatus.BUILT_IN,
            iconKey = "apps",
        ),
        ToolDef(
            id = ID_SYS_CONTROL,
            title = "系统控制中心",
            summary = "Shizuku 授权深度利用：显示 / 电源 / 授权总管 / 隐私与管控四域 —— " +
                "深色·亮度·DPI·分辨率·动画·Doze 直改，任意应用 AppOps 与运行时权限改写，" +
                "权限审计 / 敏感操作记录 / Doze 白名单 / 流量策略。",
            category = ToolCategory.SYSTEM,
            status = ToolStatus.BUILT_IN,
            iconKey = "tune",
        ),
        ToolDef(
            id = ID_SHIZUKU_KIT,
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
            id = "virus-guard",
            title = "病毒拦截",
            summary = "安装事件拦截 + 病毒特征引擎（ClamAV 级签名 + VirusTotal 云查询），" +
                "引擎级能力由大插件承载。",
            category = ToolCategory.SYSTEM,
            status = ToolStatus.PLUGIN,
            pluginId = "abyss-plugin-virus-guard",
            iconKey = "bug_report",
        ),

        // ── 终端与脚本（v0.8.0 内置化：Shizuku Shell 真实执行）──────────
        ToolDef(
            id = ID_TERMINAL,
            title = "内置终端",
            summary = "adb shell 上下文的真实终端（Shizuku 免 Root）：指令历史 / 快捷命令 / " +
                "字号分档；本地 PTY 引擎（Termux 血统）属后续内置化路线。",
            category = ToolCategory.TERMINAL,
            status = ToolStatus.BUILT_IN,
            iconKey = "terminal",
        ),
        ToolDef(
            id = ID_SCRIPT_LAB,
            title = "脚本实验室",
            summary = "Shell / Python 脚本编辑 → 保存 → 经 Shizuku 执行；Python 运行时探测" +
                "（系统自带则可跑），完整引擎随内置化路线推进。",
            category = ToolCategory.TERMINAL,
            status = ToolStatus.BUILT_IN,
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
            id = ID_TASK_BLUEPRINT,
            title = "任务蓝图",
            summary = "JSON 任务编排：shell / python / ui_toast 步骤链 + 成功判定 + 双模式调度（间隔循环 / 每日定时 TimePicker）。",
            category = ToolCategory.TERMINAL,
            status = ToolStatus.BUILT_IN,
            iconKey = "account_tree",
        ),

        // ── 沙箱与多开（v0.8.0 内置化：多用户隔离引擎）──────────────────
        ToolDef(
            id = ID_SANDBOX_SPACE,
            title = "隔离空间",
            summary = "Android 原生多用户引擎（pm create-user / install --user）：免 Root " +
                "双开 + 独立数据域，Shizuku 直达；Hook 级沙箱属插件路线。",
            category = ToolCategory.SANDBOX,
            status = ToolStatus.BUILT_IN,
            iconKey = "layers",
        ),
        ToolDef(
            id = ID_DUAL_OPEN,
            title = "应用双开",
            summary = "把已装应用注册进隔离空间并行运行第二实例：数据天然隔离 / 独立通知。",
            category = ToolCategory.SANDBOX,
            status = ToolStatus.BUILT_IN,
            iconKey = "copy",
        ),
        ToolDef(
            id = "virtual-machine",
            title = "虚拟机",
            summary = "QEMU 级完整虚拟机：ARM/x86 系统镜像运行（Alpine / Windows / DOS），" +
                "VNC 显示，引擎级能力由大插件承载。",
            category = ToolCategory.SANDBOX,
            status = ToolStatus.PLUGIN,
            pluginId = "abyss-plugin-virtual-machine",
            iconKey = "dns",
        ),

        // ── 伪装与隐私（v0.3：四件套内置，Shizuku 免 Root）────────────
        ToolDef(
            id = ID_BATTERY_DISGUISE,
            title = "电量伪装",
            summary = "dumpsys battery 引擎：等级 / 充电状态 / 拔电一键伪装，全系统生效、重启自恢复。",
            category = ToolCategory.DISGUISE,
            status = ToolStatus.BUILT_IN,
            iconKey = "battery",
        ),
        ToolDef(
            id = ID_LOCATION_DISGUISE,
            title = "定位伪装",
            summary = "Fake GPS 同款官方通道：模拟位置源 + 三源注入 + 微漂移，全系统免 Root。",
            category = ToolCategory.DISGUISE,
            status = ToolStatus.BUILT_IN,
            iconKey = "location",
        ),
        ToolDef(
            id = ID_SIGNAL_DISGUISE,
            title = "信号伪装",
            summary = "状态栏显示伪装（5G-A/5G/4G + 信号格 + Wi-Fi 覆盖层，无卡可用）+ 飞行模式/数据/Wi-Fi 真实开关。",
            category = ToolCategory.DISGUISE,
            status = ToolStatus.BUILT_IN,
            iconKey = "signal",
        ),
        ToolDef(
            id = ID_MODEL_DISGUISE,
            title = "机型伪装",
            summary = "真机信息一览 + 十款旗舰档案库 + 系统设备名改写 + 档案导出（沙箱 Hook 消费）。",
            category = ToolCategory.DISGUISE,
            status = ToolStatus.BUILT_IN,
            iconKey = "smartphone",
        ),
        ToolDef(
            id = "privacy-guard",
            title = "隐私守卫",
            summary = "FLAG_SECURE 防截屏 / 敏感权限审计 / 异常行为监控。",
            category = ToolCategory.DISGUISE,
            status = ToolStatus.FRAMEWORK,
            iconKey = "visibility_off",
        ),

        // ── 媒体工具（v0.8.0 内置化：MediaMuxer 无重编码引擎）──────────
        ToolDef(
            id = ID_MEDIA_LAB,
            title = "媒体实验室",
            summary = "MediaExtractor / MediaMuxer 无重编码处理：音频提取（AAC→M4A）/ " +
                "去除音轨 / 关键帧对齐裁剪；FFmpeg 级转码属后续路线。",
            category = ToolCategory.MEDIA,
            status = ToolStatus.BUILT_IN,
            iconKey = "movie",
        ),
        ToolDef(
            id = ID_QR_CRAFT,
            title = "二维码工坊",
            summary = "ZXing 生成 / 解析 / 网页转二维码，一键分享。",
            category = ToolCategory.MEDIA,
            status = ToolStatus.BUILT_IN,
            iconKey = "qr",
        ),

        // ── 自动化 ────────────────────────────────────────────────────
        ToolDef(
            id = ID_AUTOMATION,
            title = "自动化编排",
            summary = "任务蓝图调度引擎：定时任务 / 触发器 / 步骤重试。",
            category = ToolCategory.AUTOMATION,
            status = ToolStatus.BUILT_IN,
            iconKey = "bolt",
        ),

        // ── 桌面美化（v0.9.0 引力场：组件银河四成员）───────────────
        ToolDef(
            id = ID_WIDGET_STUDIO,
            title = "组件工坊",
            summary = "组件银河四成员（v0.9.0）：黑洞动态时钟（2×2/4×2，事件视界 + " +
                "吸积盘整分重绘）/ 引力电池（吸积环电量刻度 + 充电喷流）/ 星轨日历" +
                "（行星公转一年）/ 深渊引力仪（内存·存储双环）—— 同一深空引擎，" +
                "统一主题与整分节奏；含灵动岛 / 悬浮球 / 动态壁纸入口。",
            category = ToolCategory.DESKTOP,
            status = ToolStatus.BUILT_IN,
            iconKey = "widgets",
        ),
        ToolDef(
            id = ID_DYNAMIC_ISLAND,
            title = "灵动岛与悬浮球",
            summary = "WindowManager 悬浮件：拖拽 / 吸附 / 状态岛动画。",
            category = ToolCategory.DESKTOP,
            status = ToolStatus.BUILT_IN,
            iconKey = "swap",
        ),
        ToolDef(
            id = ID_LIVE_WALLPAPER,
            title = "动态壁纸",
            summary = "WallpaperService 引擎：液态玻璃同源视觉的壁纸工坊。",
            category = ToolCategory.DESKTOP,
            status = ToolStatus.BUILT_IN,
            iconKey = "wallpaper",
        ),

        // ── 网络守护（v0.8.0 内置化：netstats 流量审计）────────────────
        ToolDef(
            id = ID_NET_AUDIT,
            title = "流量审计",
            summary = "dumpsys netstats 应用维度流量画像：总览 / 应用榜单 / 系统与应用过滤" +
                "（Shizuku 免 Root）；VpnService 深度包审计属插件路线。",
            category = ToolCategory.NETWORK,
            status = ToolStatus.BUILT_IN,
            iconKey = "lan",
        ),

        // ── 数据与备份（v0.8.0 内置化：APK 库 + zip 引擎）──────────────
        ToolDef(
            id = ID_BACKUP,
            title = "备份迁移",
            summary = "主壳配置包（任务蓝图 / 渲染探针 / 安全模式状态，zip 导入导出，" +
                "带路径穿越与 zip 炸弹防护）/ APK 资料库导出；应用私有数据抽取属插件能力（系统边界）。",
            category = ToolCategory.DATA,
            status = ToolStatus.BUILT_IN,
            iconKey = "backup",
        ),

        // ── 系统工具：临时时间修改（保留占位）──────────────────────────
        ToolDef(
            id = "time-shift",
            title = "临时时间修改",
            summary = "auto_time 关闭 + date 写入（高版本受限时走虚拟时钟）。",
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
