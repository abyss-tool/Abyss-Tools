package com.abyss.tools.core.shell

import android.content.Context

/**
 * Shizuku 兼容变体注册表（v0.8.3）。
 *
 * 背景：Shizuku 的客户端 SDK（dev.rikka.shizuku:api）通过本应用内的
 * ShizukuProvider 接收服务端广播下发的 binder —— **服务端由谁实现与客户端
 * 无关**。因此官方 Shizuku 之外的一系列兼容实现（Shevery / Shizuku+ /
 * Sui / Shizuku-Next 等）都能为深渊工具提供同一条 adb shell 通道：
 *  - 官方 Shizuku / Shizuku-Next / Shevery 的服务端 stub 共用同一包名
 *    `moe.shizuku.privileged.api`（互相为替换安装关系）；
 *  - Shevery / Shizuku+ 另有独立的管理器 App（各自的 applicationId）；
 *  - Sui 是 Root 集成形态（Magisk 模块），无常规启动入口，需在进程早期
 *    调 [ShizukuMonitor.ensureInit] 中的 `Sui.init` 挂载 binder。
 *
 * 客户端要做的只有两件事：
 *  1. 「打开 Shizuku」入口按注册表逐个探测（而不是只认官方包名）；
 *  2. UI 如实展示「本机装了哪些变体」，让用户打开自己那一个。
 */
enum class ShizukuVariant(
    val displayName: String,
    val packageName: String,
    val detail: String,
) {
    /** 官方原版（服务端包名也是 Shevery / Shizuku-Next 等 fork 的共用 stub 包名）。 */
    SHIZUKU(
        displayName = "Shizuku",
        packageName = "moe.shizuku.privileged.api",
        detail = "官方原版 · 无线调试 / 连接电脑 / Root 激活",
    ),

    /** Shevery：现代化分支（M3 界面 / 兼容增强 / Dhizuku 支持）。 */
    SHEVERY(
        displayName = "Shevery",
        packageName = "com.hamondev.shevery",
        detail = "现代化分支 · M3 界面 / 兼容性增强 / Dhizuku",
    ),

    /** Shizuku+：Root / ADB / Dhizuku 统一 + OneUI 适配。 */
    SHIZUKU_PLUS(
        displayName = "Shizuku+",
        packageName = "af.shizuku.plus.api",
        detail = "Root / ADB / Dhizuku 统一 · OneUI 适配",
    ),

    /** Sui：Rikka 出品的 Root 集成形态（Magisk 模块，无常规启动入口）。 */
    SUI(
        displayName = "Sui",
        packageName = "rikka.sui",
        detail = "Root 集成形态 · Magisk 模块（自动接线，无需手动打开）",
    ),
    ;

    /** 是否已安装（targetSdk 28 下包可见性不受限；queries 仍显式声明全表）。 */
    @Suppress("DEPRECATION") // getPackageInfo(String, Int) 全版本可用；Flags 形态 API 33+ 才存在
    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getLaunchIntentForPackage(packageName) != null ||
            context.packageManager.getPackageInfo(packageName, 0) != null
    }.getOrDefault(false)
}

/** 变体探测的纯 UI 辅助（一次性快照，不缓存 —— 安装态随时会变）。 */
object ShizukuVariants {

    /** 本机已安装的全部变体（注册表序：官方优先）。 */
    fun installed(context: Context): List<ShizukuVariant> =
        ShizukuVariant.entries.filter { it.isInstalled(context) }

    /** 任一变体是否已安装（「打开 Shizuku」按钮的形态依据）。 */
    fun anyInstalled(context: Context): Boolean = installed(context).isNotEmpty()

    /** 官方下载页（全平台）：无任何变体时的兜底跳转目标。 */
    const val DOWNLOAD_URL = "https://shizuku.rikka.app/"
}
