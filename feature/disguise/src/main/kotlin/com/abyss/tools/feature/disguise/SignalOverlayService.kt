package com.abyss.tools.feature.disguise

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Paint
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 状态栏信号显示伪装服务（前台服务 + TYPE_APPLICATION_OVERLAY 覆盖层）。
 *
 * 为什么是覆盖层而不是改 SystemUI：状态栏信号图标由 SystemUI 进程渲染，
 * 免 Root 场景下唯一全局可见的伪造通道 = 在状态栏区域叠加一层不可触摸
 * 的透明窗口（截图 / 屏幕共享与真实屏幕同视图 —— 这正是「给别人看」
 * 场景的语义）。授权经 Shizuku `appops SYSTEM_ALERT_WINDOW`（与系统
 * 设置页开关等价的 adb 官方通道），或用户手动在设置页开启。
 *
 * 诚实边界（UI 同步展示）：
 *  - 锁屏 / 安全屏与下拉快捷设置面板头部不显示覆盖层（系统对 overlay
 *    窗口的原生约束，无解于免 Root 层）；
 *  - App 代码读 TelephonyManager / WifiManager 拿到的仍是真实值 ——
 *    那属于 LSPatch / 沙箱双开插件的 Hook 领域；
 *  - 覆盖层只画图标，不拦截任何触摸（FLAG_NOT_TOUCHABLE）。
 *
 * 生命周期：START_STICKY（进程被回收后系统拉起，规格从 prefs 恢复）；
 * 停止即移除窗口、状态栏恢复真实显示（「恢复」语义零残留）。
 */
class SignalOverlayService : Service() {

    private var overlayView: SignalOverlayView? = null
    private var windowHeight = 0

    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // 旋转 / 分屏后窗口宽度变化：重建布局参数（窗口本体不重挂）
            overlayView?.let { view ->
                runCatching {
                    val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    wm.updateViewLayout(view, buildLayoutParams())
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // FGS 时序纪律：startForeground 先于一切（Android 12+ 后台启动窗口）
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        registerReceiver(configReceiver, IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        attachOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(configReceiver) }
        detachOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── 覆盖层装配 ──────────────────────────────────────────────────

    private fun attachOverlay() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(this)) {
            lastError = "缺少悬浮窗授权（SYSTEM_ALERT_WINDOW）—— 请先在信号伪装页授权"
            stopSelf()
            return
        }
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = SignalOverlayView(this)
        try {
            wm.addView(view, buildLayoutParams())
        } catch (t: Throwable) {
            lastError = "覆盖层添加失败：${t.message ?: t.javaClass.simpleName}"
            stopSelf()
            return
        }
        overlayView = view
        activeView = view
        isRunning = true
        lastError = null
    }

    private fun detachOverlay() {
        overlayView?.let { view ->
            runCatching {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
            }
        }
        overlayView = null
        activeView = null
        isRunning = false
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        windowHeight = statusBarHeightPx()
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            windowHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WINDOW_FLAGS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
    }

    private fun statusBarHeightPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) return resources.getDimensionPixelSize(id)
        // 兜底：24dp × 密度（系统资源不可得时的常规状态栏高度）
        return (24f * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    }

    // ── 覆盖层视图（onDraw 直读 volatile 规格 → 配置变更即时生效）─────

    private class SignalOverlayView(context: Context) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            SignalStatusArt.draw(
                canvas = canvas,
                width = width.toFloat(),
                height = height.toFloat(),
                rawSpec = spec,
                nightMode = night,
                density = resources.displayMetrics.density,
                paint = paint,
            )
        }
    }

    // ── 前台通知 ──────────────────────────────────────────────────────

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "信号显示伪装",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "状态栏信号覆盖层运行状态（低打扰：无声音无横幅）"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, SignalOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_signal)
            .setContentTitle("状态栏信号显示伪装中")
            .setContentText("${spec.summary} · 停止即恢复真实显示")
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat_signal),
                    "停止并恢复",
                    stopIntent,
                ).build(),
            )
            .build()
    }

    companion object {

        private const val CHANNEL_ID = "abyss_signal_overlay"
        private const val NOTIFICATION_ID = 4203
        private const val ACTION_STOP = "com.abyss.tools.action.STOP_SIGNAL_OVERLAY"

        private const val WINDOW_FLAGS = (
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )

        // ── 进程级运行态（UI 侧读取 / 即时更新，volatile 直读）──────────

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var lastError: String? = null
            private set

        /** 当前生效规格（View.onDraw 直读 —— 更新即重绘）。 */
        @Volatile
        var spec: SignalSpoofSpec = SignalSpoofSpec()

        @Volatile
        private var activeView: View? = null

        fun start(context: Context, newSpec: SignalSpoofSpec) {
            spec = newSpec.clamped()
            context.startForegroundService(Intent(context, SignalOverlayService::class.java))
        }

        /** 运行中热更新规格（零窗口重挂，postInvalidate 即重绘）。 */
        fun update(newSpec: SignalSpoofSpec) {
            spec = newSpec.clamped()
            activeView?.postInvalidate()
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SignalOverlayService::class.java))
        }
    }
}
