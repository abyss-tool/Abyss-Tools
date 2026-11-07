package com.abyss.tools.feature.disguise

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.IBinder
import com.abyss.tools.core.shell.BatteryCommands
import com.abyss.tools.core.shell.BatteryStatus
import com.abyss.tools.core.shell.ShellGateway
import com.abyss.tools.core.shell.ShizukuShellGateway
import kotlin.concurrent.thread

/**
 * 电量伪装保持守护（前台服务）。
 *
 * 为什么需要它：部分定制 ROM 的电量管理服务会周期性把真实电量回写进
 * battery 服务，导致 dumpsys 伪装值被冲掉（读数跳回）。本守护每
 * [REASSERT_INTERVAL_MS] 重申一次伪装三连（level / status / unplug），
 * 保证「系统显示的就是伪装值」—— 用户看到的 / 全部 App 读到的恒定。
 *
 * 生命周期：START_NOT_STICKY（用户关掉的就是关掉的）。停止即静默，
 * 不额外 reset（伪装值留在系统里，直到用户点「恢复真实电量」或重启）。
 */
class BatteryGuardService : Service() {

    private val gateway: ShellGateway = ShizukuShellGateway()

    @Volatile
    private var loopRunning = false

    private var loopThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!loopRunning) {
            loopRunning = true
            isGuarding = true
            lastError = null
            loopThread = thread(isDaemon = true, name = "abyss-battery-guard") {
                while (loopRunning) {
                    reassertOnce()
                    runCatching { Thread.sleep(REASSERT_INTERVAL_MS) }
                        .onFailure { loopRunning = false }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        loopRunning = false
        loopThread?.interrupt()
        isGuarding = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 重申伪装三连（level / status / unplug，逐条独立失败计数）。 */
    private fun reassertOnce() {
        val level = guardLevel
        val status = guardStatus
        val unplug = guardUnplug
        val commands = buildList {
            add(BatteryCommands.setLevel(level))
            add(BatteryCommands.setStatus(status))
            if (unplug) add(BatteryCommands.unplug())
        }
        var failed = 0
        var lastMessage = ""
        for (command in commands) {
            val result = kotlinx.coroutines.runBlocking { gateway.exec(command) }
            result
                .onSuccess { r ->
                    if (!r.isSuccess) {
                        failed++
                        lastMessage = r.stderr.ifBlank { r.stdout }.trim()
                    }
                }
                .onFailure { e ->
                    failed++
                    lastMessage = e.message ?: e.javaClass.simpleName
                }
        }
        if (failed == commands.size) {
            // 全军覆没才报错并停守护（部分失败可能只是 unplug 幂等拒绝）
            lastError = "电量保持中断：$lastMessage"
            loopRunning = false
            isGuarding = false
            stopSelf()
        } else {
            reassertCount++
        }
    }

    // ── 前台通知 ──────────────────────────────────────────────────────

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "电量伪装保持",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "电量伪装守护运行状态（低打扰：无声音无横幅）"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, BatteryGuardService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentTitle("电量伪装保持中")
            .setContentText("系统显示恒为 $guardLevel% · ${guardStatus.label} · 每 ${REASSERT_INTERVAL_MS / 1000}s 重申")
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_battery),
                    "停止保持",
                    stopIntent,
                ).build(),
            )
            .build()
    }

    companion object {

        private const val CHANNEL_ID = "abyss_battery_guard"
        private const val NOTIFICATION_ID = 4202
        private const val ACTION_STOP = "com.abyss.tools.action.STOP_BATTERY_GUARD"

        /** 重申周期：20s —— 足够压过常见 ROM 的电量回写周期，功耗忽略不计。 */
        private const val REASSERT_INTERVAL_MS = 20_000L

        // ── 进程级运行态（UI 侧读取，volatile 直读）────────────────────

        @Volatile
        var isGuarding: Boolean = false
            private set

        @Volatile
        var lastError: String? = null
            private set

        @Volatile
        var reassertCount: Int = 0
            private set

        @Volatile
        var guardLevel: Int = 50

        @Volatile
        var guardStatus: BatteryStatus = BatteryStatus.DISCHARGING

        @Volatile
        var guardUnplug: Boolean = true

        fun start(context: Context, level: Int, status: BatteryStatus, unplug: Boolean) {
            guardLevel = level.coerceIn(0, 100)
            guardStatus = status
            guardUnplug = unplug
            context.startForegroundService(
                Intent(context, BatteryGuardService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BatteryGuardService::class.java))
        }
    }
}
