package com.abyss.tools.core.update

import java.io.File

/**
 * APK 安装网关 —— Android 侧（:app）实现的注入点。
 *
 * 放在 core:update 而非 core:plugin 的原因：这是「下载完成的 APK 如何进入
 * 系统」的通用问题 —— 宿主自更新与 APK 插件安装共用同一条 PackageInstaller
 * 通道（含同签名门禁）。core:plugin 经 api 依赖本模块直接复用；core:update
 * 保持纯 JVM（可单测），Android 依赖全部隔离在网关后面。
 *
 * 实现纪律：
 *  - [install] 为阻塞调用（等待系统安装结果广播），调用方必须切出主线程；
 *  - 已安装包的升级必须校验签名一致（换签名覆盖一律 fail-closed）；
 *  - 返回 kotlin.Result，失败信息为人话（不带堆栈）。
 */
interface ApkInstallGateway {

    /** 提交 APK 给系统安装器并等待最终结果。 */
    fun install(apk: File): Result<Unit>
}
