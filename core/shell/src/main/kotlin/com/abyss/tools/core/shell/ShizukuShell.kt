package com.abyss.tools.core.shell

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.rikka.shizuku.Shizuku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shizuku 运行态（新手引导 / 仪表盘徽章 / 各伪装页共用一份数据源）。
 *
 * NotRunning 同时覆盖「未安装」与「未启动」—— 对用户而言动作一致
 * （去启动 Shizuku），区分两者的成本（跨进程探测）不值得。
 */
sealed interface ShizukuStatus {
    val binderAlive: Boolean

    /** binder 不在：未安装或未启动。 */
    data object NotRunning : ShizukuStatus {
        override val binderAlive: Boolean get() = false
    }

    /** binder 在。authorized = 是否已授权本应用。 */
    data class Running(
        val version: Int?,
        val authorized: Boolean,
    ) : ShizukuStatus {
        override val binderAlive: Boolean get() = true
    }
}

/**
 * Shizuku 状态监控：统一挂 binder 生死 + 授权结果监听，向 UI 扇出快照。
 * 单例（手动 DI 语义同 ServiceRegistry 其余成员），监听生命周期由
 * Compose DisposableEffect 管理（见 app 模块 rememberShizukuStatus）。
 */
object ShizukuMonitor {

    private val lock = Any()
    private val listeners = mutableSetOf<(ShizukuStatus) -> Unit>()
    private var hooksRegistered = false

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { refresh() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    /** 当前快照（binder 不在时安全降级 NotRunning，绝不抛）。 */
    fun current(): ShizukuStatus = runCatching {
        if (!Shizuku.pingBinder()) {
            ShizukuStatus.NotRunning
        } else {
            val authorized = runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            ShizukuStatus.Running(
                version = runCatching { Shizuku.getVersion() }.getOrNull(),
                authorized = authorized,
            )
        }
    }.getOrDefault(ShizukuStatus.NotRunning)

    fun addListener(listener: (ShizukuStatus) -> Unit) {
        synchronized(lock) {
            listeners.add(listener)
            ensureHooks()
        }
        listener(current())
    }

    fun removeListener(listener: (ShizukuStatus) -> Unit) {
        synchronized(lock) {
            listeners.remove(listener)
        }
    }

    /** 拉起 Shizuku 的授权弹窗（需要前台 Activity）。 */
    fun requestPermission(activity: Activity) {
        runCatching { Shizuku.requestPermission(activity, REQUEST_CODE) }
    }

    /** 跳去 Shizuku App（未启动 / 配置无线调试时用）。 */
    fun shizukuAppIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)

    /** 重算并扇出（binder / 授权事件回调线程不定，统一走这里）。 */
    fun refresh() {
        val snapshot = current()
        val snapshotListeners: List<(ShizukuStatus) -> Unit>
        synchronized(lock) {
            snapshotListeners = listeners.toList()
        }
        snapshotListeners.forEach { it(snapshot) }
    }

    /** 首个监听者到来时挂系统钩子（一次性）。 */
    private fun ensureHooks() {
        if (hooksRegistered) return
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            Shizuku.addRequestPermissionResultListener(permissionResult)
            hooksRegistered = true
        }
    }

    private const val REQUEST_CODE = 4200
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
}

/**
 * 真实网关：命令在 Shizuku 的 adb shell（uid 2000）上下文执行。
 *
 * 输出量约束：伪装四件套的命令输出全部是小体量（dumpsys battery /
 * settings get / appops get），顺序读 stdout → stderr 不会撑爆管道
 * 缓冲；不适用于大输出命令（那是终端引擎插件的领域）。
 */
class ShizukuShellGateway : ShellGateway {

    override suspend fun exec(command: String): Result<ShellResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!Shizuku.pingBinder()) {
                    throw ShizukuUnavailableException(
                        "Shizuku 未运行 —— 请先启动 Shizuku（无线调试或连接电脑激活），再回来重试",
                    )
                }
                if (runCatching { Shizuku.checkSelfPermission() }.getOrDefault(-1) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    throw ShizukuUnavailableException(
                        "Shizuku 已运行但未授权深渊工具 —— 请在 Shizuku 的「授权管理」中允许",
                    )
                }
                val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
                try {
                    val stdout = process.inputStream.readBytes().decodeToString()
                    val stderr = process.errorStream.readBytes().decodeToString()
                    ShellResult(
                        exitCode = process.waitFor(),
                        stdout = stdout,
                        stderr = stderr,
                    )
                } finally {
                    process.destroy()
                }
            }
        }
}
