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

    /**
     * 带期望门禁的安装（v0.7.8：自更新专用）—— 通道清单声明与实际
     * APK 必须对得上号，不符即拒（fail-closed）。默认实现忽略门禁直接
     * 转发（插件安装等无清单期望的调用方零感知）；Android 实现覆写收紧。
     */
    fun install(apk: File, expectation: InstallExpectation): Result<Unit> = install(apk)
}

/**
 * 自更新期望（v0.7.8）：version.json 声明的版本号 —— 下载完成的 APK
 * 必须与之一致才允许提交安装。镜像错位 / 通道未滚动 / 资产指错版本等
 * 一类「清单与资产漂移」在进入系统安装器之前就地拦截，不再依赖系统
 * 返回难懂的 INSTALL_FAILED_VERSION_DOWNGRADE 之类错误码。
 */
data class InstallExpectation(
    /** 通道清单声明的 versionCode（下载的 APK 必须严格相等）。 */
    val expectedVersionCode: Long,
)
