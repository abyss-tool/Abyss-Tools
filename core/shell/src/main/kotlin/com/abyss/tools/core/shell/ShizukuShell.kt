package com.abyss.tools.core.shell

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.FileInputStream
import kotlin.concurrent.thread

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
 *
 * API 包名注意：Maven 坐标是 dev.rikka.shizuku:api，但类包名是
 * rikka.shizuku（Shizuku 上游历史命名），AIDL 服务接口在
 * moe.shizuku.server（dev.rikka.shizuku:aidl 传递依赖）。
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

    /** 拉起 Shizuku 的授权弹窗（Shizuku API 形态：requestPermission(requestCode)）。 */
    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
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
 * 通道：Shizuku binder → IShizukuService.newProcess（Shizuku 官方 AIDL，
 * 与 Shizuku 内置终端同源）→ IRemoteProcess 的 stdin/stdout/stderr
 * ParcelFileDescriptor。
 *
 * 管道纪律（v0.6.0 起，评审 #7 —— 原顺序读 + 无上限 + 假超时的重写）：
 *  - stdout / stderr 各起一条读取线程**并发消费**（先读 stdout 再读
 *    stderr 的顺序形态在大输出命令上会管道死锁：stderr 写满 ~64 KB 后
 *    子进程阻塞，父进程还在等 stdout EOF，互等）；
 *  - 每条流 [maxStreamBytes] 字节上限（截断并如实上报 truncated，
 *    绝不无界读入内存）；
 *  - 看门狗线程在 [timeoutMs] 后强杀进程 —— process.waitFor() 由此获得
 *    真实上界（协程取消打不断的阻塞 IO，靠 destroy 兑底）；
 *  - 读取线程 join 有界：流被孙进程继承等极端场景下放弃该流（守护
 *    线程不阻进程退出），结果如实标注。
 */
class ShizukuShellGateway(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val maxStreamBytes: Int = MAX_STREAM_BYTES,
) : ShellGateway {

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
                val binder = Shizuku.getBinder()
                    ?: throw ShizukuUnavailableException("Shizuku binder 不可用（服务刚断开，稍后重试）")
                val service = IShizukuService.Stub.asInterface(binder)
                val process: IRemoteProcess =
                    service.newProcess(arrayOf("sh", "-c", command), null, null)
                try {
                    runWithGuards(process)
                } finally {
                    runCatching { process.destroy() }
                }
            }
        }

    /** 并发读双流 + 看门狗超时 + 有界 join。 */
    private fun runWithGuards(process: IRemoteProcess): ShellResult {
        val stdoutReader = CappedFdReader(process.inputStream, "abyss-sh-stdout")
        val stderrReader = CappedFdReader(process.errorStream, "abyss-sh-stderr")
        stdoutReader.start()
        stderrReader.start()

        // 看门狗：超时强杀（对已完成进程的 destroy 是无害 no-op）
        val watchdog = thread(isDaemon = true, name = "abyss-sh-watchdog") {
            runCatching { Thread.sleep(timeoutMs) }
                .onFailure { return@thread } // 正常完成被打断：不杀
            runCatching { process.destroy() }
        }

        val exitCode: Int
        try {
            exitCode = process.waitFor()
        } finally {
            watchdog.interrupt()
        }

        // 进程退出 → 流应 EOF；孙进程继承管道等极端场景给有界 join
        val stdout = stdoutReader.await()
        val stderr = stderrReader.await()
        return ShellResult(
            exitCode = exitCode,
            stdout = stdout.text,
            stderr = stderr.text,
            truncated = stdout.truncated || stderr.truncated,
        )
    }

    /** 单流读取线程（守护线程 + 有界 join 语义；描述符确定性归还）。 */
    private inner class CappedFdReader(
        pfd: ParcelFileDescriptor?,
        name: String,
    ) : Thread(name) {

        private val latch = java.util.concurrent.CountDownLatch(1)
        private val pfdRef = pfd

        @Volatile
        private var result: CappedText = CappedText("", false)

        override fun run() {
            // 本地快照：属性智能转换不跨闭包（编译器保守），local val 零歧义
            val pfd = pfdRef
            result = if (pfd == null) {
                CappedText("", false)
            } else {
                runCatching {
                    FileInputStream(pfd.fileDescriptor).use { input ->
                        StreamGuards.readTextCapped(input, maxStreamBytes)
                    }
                }.getOrDefault(CappedText("", false))
            }
            runCatching { pfd?.close() }
            latch.countDown()
        }

        /** 有界等待（读取卡死时放弃该流，返回空文本 —— 结果仍会标注）。 */
        fun await(): CappedText {
            latch.await(STREAM_JOIN_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            return result
        }
    }

    companion object {
        /** 命令硬超时：伪装命令 / 蓝图步骤的兜底上界（30s）。 */
        const val DEFAULT_TIMEOUT_MS: Long = 30_000

        /** 单流上限：1 MB（dumpsys package 全量 ~ 数百 KB，截断即提示）。 */
        const val MAX_STREAM_BYTES: Int = 1024 * 1024

        /** 读取线程 join 上界（进程已退出后流的收尾时间）。 */
        private const val STREAM_JOIN_MS: Long = 3_000
    }
}
