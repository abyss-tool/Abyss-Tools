package com.abyss.tools.feature.island

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewTreeLifecycleOwner
import androidx.lifecycle.viewmodel.ViewTreeViewModelStoreOwner
import androidx.savedstate.ViewTreeSavedStateRegistryOwner
import com.abyss.tools.core.designsystem.theme.AbyssTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 灵动岛前台服务：WindowManager 悬浮球（TYPE_APPLICATION_OVERLAY）+
 * 点击展开快捷面板 + 真实电量 / 分钟级时间数据源。
 *
 * 窗口拓扑（两个独立 overlay 窗口，共享同一 [ServiceLifecycleOwner]）：
 *  - 球：WRAP_CONTENT，FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_NO_LIMITS；
 *    球本体纯展示 —— 全部手势（拖拽 / 吸边 / 点击判定）在 View 层
 *    OnTouchListener 消费（返回 true），Compose 内不做可点击元素，互不抢事件；
 *  - 面板：300dp 定宽，FLAG_NOT_FOCUSABLE（无焦点窗口仍能收到触摸，面板内
 *    按钮可点）| FLAG_LAYOUT_NO_LIMITS | FLAG_WATCH_OUTSIDE_TOUCH（点外部即收起）。
 *
 * 数据源（全部真实读数，UI 经 collectAsState 订阅）：
 *  - 电量：ACTION_BATTERY_CHANGED 受保护的系统粘性广播 —— 注册即返回当前快照，
 *    level/scale/status 均取自 BatteryManager.EXTRA_*；
 *  - 时间：分钟级 tick，对齐到下一整分钟（比固定 60s 循环不漂移）；
 *  - 外观：SharedPreferences("island_prefs") 同源 —— 功能页写、Service 监听
 *    OnSharedPreferenceChangeListener 实时重绘。
 */
class FloatingBallService : Service() {

    // ── 窗口与桥接宿主 ────────────────────────────────────────────────────
    private lateinit var windowManager: WindowManager
    private var ballView: ComposeView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panelView: ComposeView? = null
    private var lifecycleOwner: ServiceLifecycleOwner? = null

    // ── 数据源（Compose 经 collectAsState 订阅）──────────────────────────
    // 注：Service 构造时 baseContext 尚未 attach（字段初始化器里不能做
    // SharedPreferences IO）—— prefsFlow 以哨兵值起步，onCreate 里读真值。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefsFlow = MutableStateFlow(IslandPrefs.DEFAULT)
    private val batteryFlow = MutableStateFlow(BatteryInfo.unknown())
    private val clockFlow = MutableStateFlow("--:--")

    /** 功能页 Slider / 色板写入 → 同源 SharedPreferences → 这里实时重绘球。 */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        prefsFlow.value = IslandPrefs.read(this)
    }

    /** 电量广播（粘性：注册返回值即当前快照，见 registerBattery）。 */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let { batteryFlow.value = BatteryInfo.from(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefsFlow.value = IslandPrefs.read(this)
        startIslandNotification()
        registerBattery()
        startClock()
        islandPrefs().registerOnSharedPreferenceChangeListener(prefsListener)
        addBallOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // 粘性：进程被回收后系统尝试重建服务（悬浮球场景下重建合理；
        // 重建走 onCreate 全新窗口，无状态可恢复 —— 与桥接层「空恢复」取舍一致）
        return START_STICKY
    }

    // ── 前台通知（IMPORTANCE_LOW 无声；点开回主应用）──────────────────────
    private fun startIslandNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "灵动岛悬浮球",
            NotificationManager.IMPORTANCE_LOW, // 低优先级：无声、不弹横幅
        ).apply {
            description = "悬浮球与快捷面板的常驻通知（前台服务的系统要求）"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)

        // 点开通知 → 拉起宿主 launcher Intent（Service 上下文启动 Activity 不强制
        // NEW_TASK，但显式声明意图更稳）
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

        startForeground(
            NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_island_notification)
                .setContentTitle("悬浮球运行中")
                .setContentText("拖动移动 · 点击展开面板")
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build(),
        )
    }

    // ── 悬浮球窗口 ────────────────────────────────────────────────────────
    @SuppressLint("ClickableViewAccessibility")
    private fun addBallOverlay() {
        // 时序（关键）：先起宿主生命周期（create→start→resume，Recomposer 到
        // RESUMED 才驱动首帧），再注入 ViewTree 三 Owner，最后 addView。
        val owner = ServiceLifecycleOwner()
        owner.start()
        lifecycleOwner = owner

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val bounds = screenBounds()
            val ballPx = currentBallPx()
            // 初始位置：屏幕右缘内缩 16dp、屏高 40% 处
            x = bounds.width() - ballPx - dp(16)
            y = (bounds.height() * 0.4f).toInt()
        }

        val view = ComposeView(this).apply {
            ViewTreeLifecycleOwner.set(this, owner)
            ViewTreeViewModelStoreOwner.set(this, owner)
            ViewTreeSavedStateRegistryOwner.set(this, owner)
            setContent {
                AbyssTheme {
                    val prefs by prefsFlow.collectAsState()
                    val battery by batteryFlow.collectAsState()
                    FloatingBall(prefs = prefs, battery = battery)
                }
            }
        }
        view.setOnTouchListener(ballGesture(view, params))

        val attached = runCatching { windowManager.addView(view, params) }
        if (attached.isFailure) {
            // 典型场景：授权在运行中被系统收回 —— 诚实退出而不是带病运行
            Toast.makeText(this, "悬浮窗权限不可用，悬浮球已停止", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        ballView = view
        ballParams = params
    }

    /**
     * 球体手势（全部在 View 层消费 —— 球是纯展示，Compose 内零可点击元素，
     * 不存在事件双抢）：
     *  - 拖动：位移超过 8dp 阈值进入拖拽，实时 updateViewLayout；
     *  - 松手：吸边（球心在左半屏贴左缘 0，否则贴右缘），纵坐标夹在屏内防丢球；
     *  - 点击：位移 < 8dp 且时长 < 250ms → 展开 / 收起面板。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun ballGesture(
        view: View,
        params: WindowManager.LayoutParams,
    ): View.OnTouchListener {
        val clickSlopPx = dp(8).toFloat()
        val clickTimeoutMs = 250L
        var downRawX = 0f
        var downRawY = 0f
        var downAt = 0L
        var originX = 0
        var originY = 0
        var dragged = false

        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downAt = SystemClock.elapsedRealtime()
                    originX = params.x
                    originY = params.y
                    dragged = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragged && (abs(dx) > clickSlopPx || abs(dy) > clickSlopPx)) {
                        dragged = true
                        hidePanel() // 球开始移动，面板锚点失效 → 先收起
                    }
                    if (dragged) {
                        params.x = originX + dx.roundToInt()
                        params.y = originY + dy.roundToInt()
                        updateViewLayoutSafe(view, params)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragged && SystemClock.elapsedRealtime() - downAt < clickTimeoutMs) {
                        // 点击（位移 < 8dp 且时长 < 250ms）→ 切换面板
                        togglePanel()
                    } else if (dragged) {
                        // 拖拽后松手 → 吸边；未拖动的长按 → 原地不动
                        snapToEdge(view, params)
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (dragged) snapToEdge(view, params)
                    true
                }

                else -> false
            }
        }
    }

    /** 松手吸边：球心在左半屏 → 贴左缘；否则贴右缘；纵坐标夹在屏内。 */
    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val bounds = screenBounds()
        val ballPx = currentBallPx(view)
        params.x = if (params.x + ballPx / 2 < bounds.width() / 2) {
            0
        } else {
            bounds.width() - ballPx
        }
        params.y = params.y.coerceIn(0, (bounds.height() - ballPx).coerceAtLeast(0))
        updateViewLayoutSafe(view, params)
    }

    // ── 快捷面板窗口 ──────────────────────────────────────────────────────
    private fun togglePanel() {
        if (panelView != null) hidePanel() else showPanel()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showPanel() {
        val owner = lifecycleOwner ?: return
        val anchorParams = ballParams ?: return
        val bounds = screenBounds()
        val ballPx = currentBallPx(ballView)
        val panelWidthPx = dp(PANEL_WIDTH_DP)

        val params = WindowManager.LayoutParams(
            panelWidthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // FLAG_NOT_FOCUSABLE：不抢键盘焦点，但 overlay 无焦点窗口仍收触摸
            // —— 面板内 Compose 按钮可正常点击；WATCH_OUTSIDE_TOUCH：点面板外
            // 一次 ACTION_OUTSIDE → 自动收起（系统弹层的标准做法）。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 锚定：球正下方、与球水平居中对齐；超出屏宽 / 屏底时夹回
            // （球贴底时面板翻到球上方，按面板估高 200dp 处理）
            x = (anchorParams.x + ballPx / 2 - panelWidthPx / 2)
                .coerceIn(dp(10), (bounds.width() - panelWidthPx - dp(10)).coerceAtLeast(dp(10)))
            val belowTop = anchorParams.y + ballPx + dp(12)
            val maxTop = bounds.height() - dp(PANEL_ESTIMATED_HEIGHT_DP)
            y = if (belowTop <= maxTop) {
                belowTop
            } else {
                (anchorParams.y - dp(PANEL_ESTIMATED_HEIGHT_DP) - dp(12))
                    .coerceAtLeast(dp(10))
            }
        }

        val view = ComposeView(this).apply {
            // 与球共享同一 ServiceLifecycleOwner（宿主代表「本服务的窗口存续期」，
            // 面板 removeView 只拆 composition，不动宿主 —— 见桥接层注释）
            ViewTreeLifecycleOwner.set(this, owner)
            ViewTreeViewModelStoreOwner.set(this, owner)
            ViewTreeSavedStateRegistryOwner.set(this, owner)
            setContent {
                AbyssTheme {
                    val timeText by clockFlow.collectAsState()
                    val battery by batteryFlow.collectAsState()
                    IslandPanel(
                        timeText = timeText,
                        battery = battery,
                        onOpenApp = { openMainApp() },
                        onCollapse = { hidePanel() },
                    )
                }
            }
        }
        // ACTION_OUTSIDE → 收起；其余事件返回 false 透传给 Compose（按钮才有得点）
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                hidePanel()
                true
            } else {
                false
            }
        }

        runCatching { windowManager.addView(view, params) }.onFailure { return }
        panelView = view
    }

    private fun hidePanel() {
        val view = panelView ?: return
        runCatching { windowManager.removeView(view) }
        panelView = null
    }

    /** 打开深渊工具：收起面板 + 拉起宿主 launcher Intent（Service 侧必须 NEW_TASK）。 */
    private fun openMainApp() {
        hidePanel()
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(launch) }
    }

    // ── 数据源：电量（真实广播）+ 时间（分钟对齐 tick）───────────────────
    private fun registerBattery() {
        // ACTION_BATTERY_CHANGED 是受保护的系统粘性广播：registerReceiver 的
        // 返回值就是最近一次广播（当前电量快照）—— 读数从第一帧起就是真实值
        val snapshot = registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        snapshot?.let { batteryFlow.value = BatteryInfo.from(it) }
    }

    private fun startClock() {
        scope.launch {
            while (isActive) {
                clockFlow.value = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val now = System.currentTimeMillis()
                val nextMinute = now / MILLIS_PER_MINUTE * MILLIS_PER_MINUTE + MILLIS_PER_MINUTE
                delay(nextMinute - now)
            }
        }
    }

    // ── 工具 ──────────────────────────────────────────────────────────────
    private fun updateViewLayoutSafe(view: View, params: WindowManager.LayoutParams) {
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /** 球直径（px）：优先用已测量的视图宽（大小实时可调），否则按当前配置折算。 */
    private fun currentBallPx(view: View? = null): Int {
        view?.width?.takeIf { it > 0 }?.let { return it }
        return prefsFlow.value.ballSizePx(resources.displayMetrics.density)
    }

    /** 真实屏幕范围（overlay 坐标空间）：R+ 用 WindowMetrics，旧版本 getRealSize。 */
    private fun screenBounds(): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            Point().also { windowManager.defaultDisplay.getRealSize(it) }
                .let { Rect(0, 0, it.x, it.y) }
        }

    private fun islandPrefs(): SharedPreferences =
        getSharedPreferences(IslandPrefs.PREFS_NAME, Context.MODE_PRIVATE)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    // ── 清理（与建立严格相反的顺序）───────────────────────────────────────
    override fun onDestroy() {
        // 1) 先拆两个 overlay 窗口（removeView 触发 composition 释放 —— 此刻
        //    宿主生命周期必须仍在 RESUMED，顺序不可反）；
        hidePanel()
        ballView?.let { view -> runCatching { windowManager.removeView(view) } }
        ballView = null
        ballParams = null
        // 2) 注销广播 / 偏好监听、取消协程（时钟 tick 停止）；
        runCatching { unregisterReceiver(batteryReceiver) }
        islandPrefs().unregisterOnSharedPreferenceChangeListener(prefsListener)
        scope.cancel()
        // 3) 最后销毁 ServiceLifecycleOwner（RESUMED → DESTROYED + 清 ViewModelStore）。
        lifecycleOwner?.destroy()
        lifecycleOwner = null
        running = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "abyss_island"
        private const val NOTIFICATION_ID = 6673
        private const val PANEL_WIDTH_DP = 300
        private const val PANEL_ESTIMATED_HEIGHT_DP = 200
        private const val MILLIS_PER_MINUTE = 60_000L

        /**
         * 进程内运行标记（同进程功能页的开关状态来源；比已废弃的
         * ActivityManager.getRunningServices 可靠 —— 同 app 进程内自证状态）。
         */
        @Volatile
        internal var running = false
            private set

        /** 开：前台服务方式启动（宿主已声明 FOREGROUND_SERVICE 权限）。 */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, FloatingBallService::class.java),
            )
        }

        /** 关：stopService → onDestroy 全量清理窗口。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBallService::class.java))
        }
    }
}
